package com.oms.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.order.OrderType;
import com.oms.domain.workflow.WorkflowDefinition;
import com.oms.service.OrderTypeService;
import com.oms.web.dto.OrderTypeDtos.ActiveWorkflowSummary;
import com.oms.web.dto.OrderTypeDtos.CreateOrderTypeRequest;
import com.oms.web.dto.OrderTypeDtos.OrderTypeResponse;
import com.oms.web.dto.OrderTypeDtos.OrderTypeSchemaResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** SPEC.md §6 (Order Types). */
@RestController
@RequestMapping("/order-types")
public class OrderTypeController {

    private final OrderTypeService orderTypeService;
    private final ObjectMapper objectMapper;

    public OrderTypeController(OrderTypeService orderTypeService, ObjectMapper objectMapper) {
        this.orderTypeService = orderTypeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OrderTypeResponse> list() {
        return orderTypeService.listActive().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{code}/schema")
    @Transactional(readOnly = true)
    public OrderTypeSchemaResponse schema(@PathVariable String code) {
        OrderType orderType = orderTypeService.getByCode(code);
        ActiveWorkflowSummary summary = null;
        WorkflowDefinition definition = orderType.getWorkflowDefinition();
        if (definition != null) {
            summary = new ActiveWorkflowSummary(definition.getWorkflowDefinitionId(), definition.getVersion(), definition.getName());
        }
        return new OrderTypeSchemaResponse(orderType.getCode(), readJson(orderType.getAttributeSchema()),
                readJson(orderType.getLineAttributeSchema()), summary);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderTypeResponse create(@RequestBody CreateOrderTypeRequest request) {
        OrderType orderType = orderTypeService.create(request.code(), request.name(),
                request.attributeSchema().toString(), request.lineAttributeSchema().toString());
        return toResponse(orderType);
    }

    @PutMapping("/{code}/workflow")
    public ResponseEntity<Void> publishWorkflow(@PathVariable String code,
                                                 @RequestBody OrderTypeService.PublishWorkflowCommand command) {
        WorkflowDefinition definition = orderTypeService.publishWorkflow(code, command);
        return ResponseEntity.created(
                        java.net.URI.create("/workflow-definitions/" + definition.getWorkflowDefinitionId()))
                .build();
    }

    private OrderTypeResponse toResponse(OrderType orderType) {
        WorkflowDefinition definition = orderType.getWorkflowDefinition();
        return new OrderTypeResponse(orderType.getOrderTypeId(), orderType.getCode(), orderType.getName(),
                readJson(orderType.getAttributeSchema()), readJson(orderType.getLineAttributeSchema()),
                definition == null ? null : definition.getWorkflowDefinitionId(), orderType.isActive());
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored JSON is invalid: " + e.getMessage(), e);
        }
    }
}
