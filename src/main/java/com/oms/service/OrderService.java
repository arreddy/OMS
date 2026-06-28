package com.oms.service;

import com.oms.domain.event.AggregateType;
import com.oms.domain.order.Order;
import com.oms.domain.order.OrderLine;
import com.oms.domain.order.OrderType;
import com.oms.exception.ConflictException;
import com.oms.exception.NotFoundException;
import com.oms.repository.OrderLineRepository;
import com.oms.repository.OrderRepository;
import com.oms.service.validation.JsonSchemaValidationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** SPEC.md §3.1, §3.2, §6 (Orders). */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderTypeService orderTypeService;
    private final WorkflowEngineService workflowEngineService;
    private final JsonSchemaValidationService schemaValidationService;
    private final EventOutboxService eventOutboxService;

    public OrderService(OrderRepository orderRepository,
                         OrderLineRepository orderLineRepository,
                         OrderTypeService orderTypeService,
                         WorkflowEngineService workflowEngineService,
                         JsonSchemaValidationService schemaValidationService,
                         EventOutboxService eventOutboxService) {
        this.orderRepository = orderRepository;
        this.orderLineRepository = orderLineRepository;
        this.orderTypeService = orderTypeService;
        this.workflowEngineService = workflowEngineService;
        this.schemaValidationService = schemaValidationService;
        this.eventOutboxService = eventOutboxService;
    }

    public Order getOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("No order with id " + orderId));
    }

    public Page<Order> listOrders(String status, String orderTypeCode, String customerRef, Pageable pageable) {
        Specification<Order> spec = Specification.allOf(
                OrderRepository.hasStatus(status),
                OrderRepository.hasOrderTypeCode(orderTypeCode),
                OrderRepository.hasCustomerRef(customerRef));
        return orderRepository.findAll(spec, pageable);
    }

    @Transactional
    public Order createOrder(CreateOrderCommand command, String actor) {
        OrderType orderType = orderTypeService.getByCode(command.orderTypeCode());
        if (!orderType.isActive()) {
            throw new IllegalArgumentException("Order type " + command.orderTypeCode() + " is not active");
        }
        String attributesJson = command.attributes() == null ? "{}" : command.attributes();
        schemaValidationService.validate(orderType.getAttributeSchema(), attributesJson);

        Order order = new Order();
        order.setOrderNumber(generateOrderNumber(command.orderTypeCode()));
        order.setOrderTypeCode(command.orderTypeCode());
        // Placeholder — overwritten by WorkflowEngineService#startInstance below, within
        // this same transaction, before anything else can observe it. order.status is
        // never written outside the workflow engine after this point (SPEC.md §8).
        order.setStatus("INITIALIZING");
        order.setCustomerRef(command.customerRef());
        order.setCurrency(command.currency());
        order.setTotalAmount(command.totalAmount() == null ? BigDecimal.ZERO : command.totalAmount());
        order.setAttributes(attributesJson);
        order.setCreatedBy(actor);
        order.setUpdatedBy(actor);
        order = orderRepository.save(order);

        if (command.lines() != null) {
            int lineNumber = 1;
            for (CreateLineCommand lineCommand : command.lines()) {
                OrderLine line = buildLine(order, orderType, lineNumber++, lineCommand);
                orderLineRepository.save(line);
            }
        }

        workflowEngineService.startInstance(order, orderType, actor);

        eventOutboxService.record("order.created", AggregateType.ORDER, order.getOrderId(),
                Map.of("orderId", order.getOrderId().toString(), "orderTypeCode", order.getOrderTypeCode(),
                        "occurredAt", OffsetDateTime.now().toString(), "triggeredBy", actor));

        return order;
    }

    @Transactional
    public Order updateOrder(UUID orderId, UpdateOrderCommand command, long expectedVersion, String actor) {
        Order order = getOrder(orderId);
        requireVersionMatch(order.getVersion(), expectedVersion, "order " + orderId);

        OrderType orderType = orderTypeService.getByCode(order.getOrderTypeCode());

        if (command.customerRef() != null) {
            order.setCustomerRef(command.customerRef());
        }
        if (command.currency() != null) {
            order.setCurrency(command.currency());
        }
        if (command.totalAmount() != null) {
            order.setTotalAmount(command.totalAmount());
        }
        if (command.attributes() != null) {
            schemaValidationService.validate(orderType.getAttributeSchema(), command.attributes());
            order.setAttributes(command.attributes());
        }
        order.setUpdatedBy(actor);
        // saveAndFlush: this order is already managed (re-fetched, not newly inserted),
        // so a plain save() defers the UPDATE — and the @Version bump — until commit;
        // the version returned in the response would otherwise be stale by one.
        order = orderRepository.saveAndFlush(order);

        eventOutboxService.record("order.updated", AggregateType.ORDER, order.getOrderId(),
                Map.of("orderId", order.getOrderId().toString(), "occurredAt", OffsetDateTime.now().toString(),
                        "triggeredBy", actor));
        return order;
    }

    @Transactional
    public OrderLine addLine(UUID orderId, CreateLineCommand command) {
        Order order = getOrder(orderId);
        OrderType orderType = orderTypeService.getByCode(order.getOrderTypeCode());
        int nextLineNumber = orderLineRepository.findTopByOrder_OrderIdOrderByLineNumberDesc(orderId)
                .map(l -> l.getLineNumber() + 1)
                .orElse(1);
        OrderLine line = buildLine(order, orderType, nextLineNumber, command);
        return orderLineRepository.save(line);
    }

    @Transactional
    public OrderLine updateLine(UUID orderId, UUID lineId, UpdateLineCommand command, long expectedVersion) {
        OrderLine line = orderLineRepository.findById(lineId)
                .orElseThrow(() -> new NotFoundException("No line " + lineId));
        if (!line.getOrder().getOrderId().equals(orderId)) {
            throw new NotFoundException("Line " + lineId + " does not belong to order " + orderId);
        }
        requireVersionMatch(line.getVersion(), expectedVersion, "order line " + lineId);

        OrderType orderType = orderTypeService.getByCode(line.getOrder().getOrderTypeCode());

        if (command.quantity() != null) {
            line.setQuantity(command.quantity());
        }
        if (command.unitPrice() != null) {
            line.setUnitPrice(command.unitPrice());
        }
        if (command.quantity() != null || command.unitPrice() != null) {
            line.setLineTotal(line.getQuantity().multiply(line.getUnitPrice()));
        }
        if (command.status() != null) {
            line.setStatus(command.status());
        }
        if (command.attributes() != null) {
            schemaValidationService.validate(orderType.getLineAttributeSchema(), command.attributes());
            line.setAttributes(command.attributes());
        }
        return orderLineRepository.saveAndFlush(line);
    }

    private OrderLine buildLine(Order order, OrderType orderType, int lineNumber, CreateLineCommand command) {
        String attributesJson = command.attributes() == null ? "{}" : command.attributes();
        schemaValidationService.validate(orderType.getLineAttributeSchema(), attributesJson);

        OrderLine line = new OrderLine();
        line.setOrder(order);
        line.setLineNumber(lineNumber);
        line.setItemRef(command.itemRef());
        line.setQuantity(command.quantity());
        line.setUnitPrice(command.unitPrice());
        line.setLineTotal(command.quantity().multiply(command.unitPrice()));
        line.setStatus("ACTIVE");
        line.setAttributes(attributesJson);
        return line;
    }

    private String generateOrderNumber(String orderTypeCode) {
        return orderTypeCode + "-" + orderRepository.nextOrderNumberSequence();
    }

    private void requireVersionMatch(long actual, long expected, String description) {
        if (actual != expected) {
            throw new ConflictException("Version mismatch on " + description + ": expected " + expected + " but was " + actual);
        }
    }

    public record CreateLineCommand(String itemRef, BigDecimal quantity, BigDecimal unitPrice, String attributes) {
    }

    public record CreateOrderCommand(String orderTypeCode, String customerRef, String currency,
                                      BigDecimal totalAmount, String attributes, java.util.List<CreateLineCommand> lines) {
    }

    public record UpdateOrderCommand(String customerRef, String currency, BigDecimal totalAmount, String attributes) {
    }

    public record UpdateLineCommand(BigDecimal quantity, BigDecimal unitPrice, String status, String attributes) {
    }
}
