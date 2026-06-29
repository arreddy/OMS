package com.oms.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.order.OrderType;
import com.oms.domain.workflow.WorkflowDefinition;
import com.oms.domain.workflow.WorkflowState;
import com.oms.repository.WorkflowDefinitionRepository;
import com.oms.repository.WorkflowStateRepository;
import com.oms.service.OrderTypeService;
import com.oms.web.dto.OrderTypeDtos.ActiveWorkflowSummary;
import com.oms.web.dto.OrderTypeDtos.CreateOrderTypeRequest;
import com.oms.web.dto.OrderTypeDtos.OrderTypeResponse;
import com.oms.web.dto.OrderTypeDtos.OrderTypeSchemaResponse;
import com.oms.web.dto.OrderTypeDtos.StatusTaxonomyEntry;
import com.oms.web.dto.OrderTypeDtos.UpdateOrderTypeSchemaRequest;
import com.oms.web.dto.WorkflowDtos.WorkflowVersionSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** SPEC.md §6 (Order Types). */
@RestController
@RequestMapping("/order-types")
public class OrderTypeController {

    private final OrderTypeService orderTypeService;
    private final WorkflowStateRepository workflowStateRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final ObjectMapper objectMapper;

    public OrderTypeController(OrderTypeService orderTypeService, WorkflowStateRepository workflowStateRepository,
                                WorkflowDefinitionRepository workflowDefinitionRepository, ObjectMapper objectMapper) {
        this.orderTypeService = orderTypeService;
        this.workflowStateRepository = workflowStateRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
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
        if (isMissing(request.attributeSchema()) || isMissing(request.lineAttributeSchema())) {
            throw new IllegalArgumentException("attributeSchema and lineAttributeSchema are both required");
        }
        OrderType orderType = orderTypeService.create(request.code(), request.name(),
                request.attributeSchema().toString(), request.lineAttributeSchema().toString());
        return toResponse(orderType);
    }

    /** Extends an existing order type's schema without touching already-stored order.attributes (SPEC.md §3.3). */
    @PatchMapping("/{code}")
    public OrderTypeResponse updateSchema(@PathVariable String code, @RequestBody UpdateOrderTypeSchemaRequest request) {
        OrderType orderType = orderTypeService.updateSchema(code,
                isMissing(request.attributeSchema()) ? null : request.attributeSchema().toString(),
                isMissing(request.lineAttributeSchema()) ? null : request.lineAttributeSchema().toString());
        return toResponse(orderType);
    }

    /**
     * Jackson deserializes an explicit JSON `null` for a JsonNode-typed field
     * into a NullNode instance, not a Java null reference — node == null only
     * catches an omitted field. Both must mean "no value given" here.
     */
    private boolean isMissing(JsonNode node) {
        return node == null || node.isNull();
    }

    /**
     * Backs the Ops order list's status filter (UI spec §2.1), which needs to
     * group every in-use status code by badge category across order types —
     * order.status is a literal code, not a category, and that mapping lives
     * on workflow_state per order type, so there's no single column to filter
     * on directly. Dedupes by code, keeping the first category seen for it.
     */
    @GetMapping("/status-taxonomy")
    @Transactional(readOnly = true)
    public List<StatusTaxonomyEntry> statusTaxonomy() {
        List<UUID> definitionIds = orderTypeService.listActive().stream()
                .map(OrderType::getWorkflowDefinition)
                .filter(Objects::nonNull)
                .map(WorkflowDefinition::getWorkflowDefinitionId)
                .toList();
        if (definitionIds.isEmpty()) {
            return List.of();
        }
        var byCode = new LinkedHashMap<String, StatusTaxonomyEntry>();
        for (WorkflowState state : workflowStateRepository.findByWorkflowDefinition_WorkflowDefinitionIdIn(definitionIds)) {
            byCode.putIfAbsent(state.getCode(), new StatusTaxonomyEntry(state.getCode(), state.getBadgeCategory()));
        }
        return List.copyOf(byCode.values());
    }

    /** Backs the Designer's version history dropdown (UI spec §4.3). */
    @GetMapping("/{code}/workflow-versions")
    public List<WorkflowVersionSummary> workflowVersions(@PathVariable String code) {
        return workflowDefinitionRepository.findByOrderTypeCodeOrderByVersionDesc(code).stream()
                .map(d -> new WorkflowVersionSummary(d.getWorkflowDefinitionId(), d.getVersion(), d.getName(), d.getPublishedAt()))
                .toList();
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
