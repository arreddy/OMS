package com.oms.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.order.Order;
import com.oms.domain.order.OrderLine;
import com.oms.repository.OrderLineRepository;
import com.oms.service.OrderService;
import com.oms.web.dto.OrderDtos.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SPEC.md §6 (Orders). */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderLineRepository orderLineRepository;
    private final ObjectMapper objectMapper;

    public OrderController(OrderService orderService, OrderLineRepository orderLineRepository, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.orderLineRepository = orderLineRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@RequestBody CreateOrderRequest request,
                                 @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        List<OrderService.CreateLineCommand> lines = request.lines() == null ? null : request.lines().stream()
                .map(l -> new OrderService.CreateLineCommand(l.itemRef(), l.quantity(), l.unitPrice(), jsonToString(l.attributes())))
                .toList();
        OrderService.CreateOrderCommand command = new OrderService.CreateOrderCommand(request.orderTypeCode(),
                request.customerRef(), request.currency(), request.totalAmount(), jsonToString(request.attributes()), lines);
        Order order = orderService.createOrder(command, actor);
        return toResponse(order);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return toResponse(orderService.getOrder(id));
    }

    @GetMapping
    public Page<OrderResponse> list(@RequestParam(required = false) List<String> status,
                                     @RequestParam(required = false) List<String> orderType,
                                     @RequestParam(required = false) String customerRef,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdFrom,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime createdTo,
                                     @RequestParam(required = false) Boolean hasOpenTask,
                                     Pageable pageable) {
        return orderService.listOrders(status, orderType, customerRef, createdFrom, createdTo, hasOpenTask, pageable)
                .map(this::toResponse);
    }

    @PatchMapping("/{id}")
    public OrderResponse update(@PathVariable UUID id, @RequestBody UpdateOrderRequest request,
                                 @RequestHeader("If-Match") long ifMatch,
                                 @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        OrderService.UpdateOrderCommand command = new OrderService.UpdateOrderCommand(request.customerRef(),
                request.currency(), request.totalAmount(), jsonToString(request.attributes()));
        return toResponse(orderService.updateOrder(id, command, ifMatch, actor));
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderLineResponse addLine(@PathVariable UUID id, @RequestBody CreateLineRequest request) {
        OrderService.CreateLineCommand command = new OrderService.CreateLineCommand(request.itemRef(),
                request.quantity(), request.unitPrice(), jsonToString(request.attributes()));
        return toLineResponse(orderService.addLine(id, command));
    }

    @PatchMapping("/{id}/lines/{lineId}")
    public OrderLineResponse updateLine(@PathVariable UUID id, @PathVariable UUID lineId,
                                         @RequestBody UpdateLineRequest request,
                                         @RequestHeader("If-Match") long ifMatch) {
        OrderService.UpdateLineCommand command = new OrderService.UpdateLineCommand(request.quantity(),
                request.unitPrice(), request.status(), jsonToString(request.attributes()));
        return toLineResponse(orderService.updateLine(id, lineId, command, ifMatch));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderLineResponse> lines = orderLineRepository.findByOrder_OrderIdOrderByLineNumberAsc(order.getOrderId())
                .stream().map(this::toLineResponse).toList();
        return new OrderResponse(order.getOrderId(), order.getOrderNumber(), order.getOrderTypeCode(), order.getStatus(),
                order.getCustomerRef(), order.getCurrency(), order.getTotalAmount(), readJson(order.getAttributes()),
                order.getVersion(), order.getCreatedAt(), order.getUpdatedAt(), lines);
    }

    private OrderLineResponse toLineResponse(OrderLine line) {
        return new OrderLineResponse(line.getLineId(), line.getLineNumber(), line.getItemRef(), line.getQuantity(),
                line.getUnitPrice(), line.getLineTotal(), line.getStatus(), readJson(line.getAttributes()), line.getVersion());
    }

    /**
     * Jackson deserializes an explicit JSON `null` for a JsonNode-typed field
     * into a NullNode instance, not a Java null reference — node == null only
     * catches an omitted field. Both must mean "no value given" here.
     */
    private String jsonToString(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.toString();
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored JSON is invalid: " + e.getMessage(), e);
        }
    }
}
