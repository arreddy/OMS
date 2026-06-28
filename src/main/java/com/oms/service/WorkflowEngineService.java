package com.oms.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.event.AggregateType;
import com.oms.domain.order.Order;
import com.oms.domain.order.OrderType;
import com.oms.domain.task.Task;
import com.oms.domain.task.TaskDecision;
import com.oms.domain.task.TaskStatus;
import com.oms.domain.workflow.*;
import com.oms.exception.ConflictException;
import com.oms.repository.*;
import com.oms.service.guard.GuardEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Source of truth for order lifecycle (SPEC.md §4). Depends only on
 * TaskRepository (not TaskService) so that TaskService -> WorkflowEngineService
 * stays a one-directional dependency: TaskService needs to call back into the
 * engine on approve/reject, and a two-way dependency between the two services
 * would create a circular bean graph.
 */
@Service
public class WorkflowEngineService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngineService.class);
    private static final int MAX_AUTO_PROGRESS_HOPS = 50;

    private final OrderRepository orderRepository;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final WorkflowTransitionLogRepository workflowTransitionLogRepository;
    private final TaskRepository taskRepository;
    private final EventOutboxService eventOutboxService;
    private final GuardEvaluator guardEvaluator;
    private final ObjectMapper objectMapper;

    @Value("${oms.task.default-priority:5}")
    private short defaultTaskPriority;

    @Value("${oms.task.default-sla-hours:24}")
    private long defaultSlaHours;

    public WorkflowEngineService(OrderRepository orderRepository,
                                  WorkflowInstanceRepository workflowInstanceRepository,
                                  WorkflowStateRepository workflowStateRepository,
                                  WorkflowTransitionRepository workflowTransitionRepository,
                                  WorkflowTransitionLogRepository workflowTransitionLogRepository,
                                  TaskRepository taskRepository,
                                  EventOutboxService eventOutboxService,
                                  GuardEvaluator guardEvaluator,
                                  ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.workflowTransitionRepository = workflowTransitionRepository;
        this.workflowTransitionLogRepository = workflowTransitionLogRepository;
        this.taskRepository = taskRepository;
        this.eventOutboxService = eventOutboxService;
        this.guardEvaluator = guardEvaluator;
        this.objectMapper = objectMapper;
    }

    /** Pins the order to order_type.workflow_definition_id and enters the initial state (SPEC.md §4.1, §4.4). */
    @Transactional
    public WorkflowInstance startInstance(Order order, OrderType orderType, String triggeredBy) {
        WorkflowDefinition definition = orderType.getWorkflowDefinition();
        if (definition == null) {
            throw new IllegalStateException("Order type " + orderType.getCode() + " has no published workflow");
        }
        WorkflowState initial = workflowStateRepository
                .findByWorkflowDefinition_WorkflowDefinitionIdAndIsInitialTrue(definition.getWorkflowDefinitionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow definition " + definition.getWorkflowDefinitionId() + " has no initial state"));

        WorkflowInstance instance = new WorkflowInstance();
        instance.setOrder(order);
        instance.setWorkflowDefinition(definition);
        instance.setCurrentState(initial);
        instance = workflowInstanceRepository.save(instance);

        order.setStatus(initial.getCode());
        orderRepository.save(order);

        logTransition(instance, null, initial.getCode(), null, null, triggeredBy, null);

        eventOutboxService.record("workflow.transitioned", AggregateType.WORKFLOW_INSTANCE, instance.getInstanceId(),
                Map.of("orderId", order.getOrderId().toString(), "orderTypeCode", order.getOrderTypeCode(),
                        "toState", initial.getCode(), "occurredAt", OffsetDateTime.now().toString(),
                        "triggeredBy", triggeredBy));

        if (initial.getStateType() == StateType.MANUAL) {
            createTask(instance, initial);
        }

        runAutoProgress(instance);
        return instance;
    }

    /** Fires an EVENT/API_ACTION/TIMER trigger against an order's current state (POST /orders/{id}/workflow/transitions). */
    @Transactional
    public void fireTrigger(Order order, TriggerType triggerType, String triggerCode, String triggeredBy, String comment) {
        WorkflowInstance instance = workflowInstanceRepository.findByOrder_OrderId(order.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order " + order.getOrderId() + " has no workflow instance"));
        WorkflowState current = instance.getCurrentState();
        if (current.isTerminal()) {
            throw new ConflictException("Order " + order.getOrderId() + " workflow has already completed (state " + current.getCode() + ")");
        }

        Map<String, Object> context = buildContext(order);
        WorkflowTransition match = workflowTransitionRepository.findByFromState_StateIdOrderBySequenceAsc(current.getStateId())
                .stream()
                .filter(t -> t.getTriggerType() == triggerType && Objects.equals(t.getTriggerCode(), triggerCode))
                .filter(t -> guardEvaluator.evaluate(t.getGuardExpression(), context))
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "No transition for trigger " + triggerType + "/" + triggerCode + " from state " + current.getCode()));

        applyTransition(instance, match, triggeredBy, comment);
        runAutoProgress(instance);
    }

    /** Fires the TASK_APPROVED/TASK_REJECTED transition matching a task decision (SPEC.md §5.3 step 3). */
    @Transactional
    public void fireTaskDecision(Task task, TaskDecision decision, String triggeredBy, String comment) {
        WorkflowInstance instance = task.getWorkflowInstance();
        TriggerType triggerType = decision == TaskDecision.APPROVE ? TriggerType.TASK_APPROVED : TriggerType.TASK_REJECTED;

        Map<String, Object> context = buildContext(instance.getOrder());
        WorkflowTransition match = workflowTransitionRepository.findByFromState_StateIdOrderBySequenceAsc(task.getState().getStateId())
                .stream()
                .filter(t -> t.getTriggerType() == triggerType)
                .filter(t -> guardEvaluator.evaluate(t.getGuardExpression(), context))
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "No " + triggerType + " transition configured from state " + task.getState().getCode()));

        applyTransition(instance, match, triggeredBy, comment);
        runAutoProgress(instance);
    }

    /**
     * Drives AUTOMATIC/WAIT states forward: repeatedly fires the first
     * trigger-code-less transition whose guard passes (SPEC.md §4.2), until
     * none match, the instance reaches a MANUAL state, or it terminates.
     * Capped defensively — SPEC.md flags loop/cycle detection as still open;
     * this just stops a misconfigured workflow from spinning forever.
     */
    private void runAutoProgress(WorkflowInstance instance) {
        for (int hops = 0; hops < MAX_AUTO_PROGRESS_HOPS; hops++) {
            WorkflowState current = instance.getCurrentState();
            if (current.isTerminal() || current.getStateType() == StateType.MANUAL) {
                return;
            }
            Map<String, Object> context = buildContext(instance.getOrder());
            Optional<WorkflowTransition> match = workflowTransitionRepository
                    .findByFromState_StateIdOrderBySequenceAsc(current.getStateId())
                    .stream()
                    .filter(t -> t.getTriggerCode() == null)
                    .filter(t -> guardEvaluator.evaluate(t.getGuardExpression(), context))
                    .findFirst();
            if (match.isEmpty()) {
                return;
            }
            applyTransition(instance, match.get(), "SYSTEM", null);
        }
        log.warn("Auto-progress hop limit ({}) reached for workflow_instance {} — check for a guard-only transition cycle",
                MAX_AUTO_PROGRESS_HOPS, instance.getInstanceId());
    }

    private void applyTransition(WorkflowInstance instance, WorkflowTransition transition, String triggeredBy, String comment) {
        WorkflowState from = instance.getCurrentState();
        WorkflowState to = transition.getToState();
        Order order = instance.getOrder();
        String fromStatus = order.getStatus();

        instance.setCurrentState(to);
        if (to.isTerminal()) {
            instance.setCompletedAt(OffsetDateTime.now());
        }
        workflowInstanceRepository.save(instance);

        order.setStatus(to.getCode());
        orderRepository.save(order);

        logTransition(instance, from.getCode(), to.getCode(), transition.getTriggerType(), transition.getTriggerCode(), triggeredBy, comment);

        if (transition.getSideEffect() != null) {
            // Side-effect action dispatch is an open extension point (SPEC.md §9 territory) —
            // no action registry exists yet, so this just logs the identifier.
            log.info("Transition side effect requested: {} (order {})", transition.getSideEffect(), order.getOrderId());
        }

        OffsetDateTime now = OffsetDateTime.now();
        eventOutboxService.record("workflow.transitioned", AggregateType.WORKFLOW_INSTANCE, instance.getInstanceId(),
                Map.of("orderId", order.getOrderId().toString(), "orderTypeCode", order.getOrderTypeCode(),
                        "fromState", from.getCode(), "toState", to.getCode(),
                        "occurredAt", now.toString(), "triggeredBy", triggeredBy));
        eventOutboxService.record("order.status_changed", AggregateType.ORDER, order.getOrderId(),
                Map.of("orderId", order.getOrderId().toString(), "fromStatus", fromStatus, "toStatus", to.getCode(),
                        "occurredAt", now.toString(), "triggeredBy", triggeredBy));

        if (to.getStateType() == StateType.MANUAL) {
            createTask(instance, to);
        }
    }

    private void logTransition(WorkflowInstance instance, String fromStateCode, String toStateCode,
                                TriggerType triggerType, String triggerCode, String triggeredBy, String comment) {
        WorkflowTransitionLog entry = new WorkflowTransitionLog();
        entry.setInstance(instance);
        entry.setFromStateCode(fromStateCode);
        entry.setToStateCode(toStateCode);
        entry.setTriggerType(triggerType);
        entry.setTriggerCode(triggerCode);
        entry.setTriggeredBy(triggeredBy);
        entry.setComment(comment);
        workflowTransitionLogRepository.save(entry);
    }

    private Task createTask(WorkflowInstance instance, WorkflowState state) {
        Task task = new Task();
        task.setOrder(instance.getOrder());
        task.setWorkflowInstance(instance);
        task.setState(state);
        task.setTaskType(state.getCode());
        task.setStatus(TaskStatus.UNASSIGNED);
        task.setAssigneeGroup(state.getDefaultAssigneeGroup());
        task.setPriority(defaultTaskPriority);
        task.setSlaDueAt(OffsetDateTime.now().plusHours(defaultSlaHours));
        task = taskRepository.save(task);

        eventOutboxService.record("task.created", AggregateType.TASK, task.getTaskId(),
                Map.of("taskId", task.getTaskId().toString(), "orderId", instance.getOrder().getOrderId().toString(),
                        "taskType", task.getTaskType(), "assigneeGroup", String.valueOf(task.getAssigneeGroup()),
                        "occurredAt", OffsetDateTime.now().toString()));
        return task;
    }

    /** Context guard_expression / JSON Logic rules are evaluated against: {"order": {...}}. */
    private Map<String, Object> buildContext(Order order) {
        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("orderId", order.getOrderId().toString());
        orderMap.put("orderNumber", order.getOrderNumber());
        orderMap.put("orderTypeCode", order.getOrderTypeCode());
        orderMap.put("status", order.getStatus());
        orderMap.put("customerRef", order.getCustomerRef());
        orderMap.put("currency", order.getCurrency());
        orderMap.put("totalAmount", order.getTotalAmount() == null ? 0d : order.getTotalAmount().doubleValue());
        orderMap.put("attributes", parseAttributes(order.getAttributes()));

        Map<String, Object> context = new HashMap<>();
        context.put("order", orderMap);
        return context;
    }

    private Map<String, Object> parseAttributes(String attributesJson) {
        try {
            String json = attributesJson == null || attributesJson.isBlank() ? "{}" : attributesJson;
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse order attributes as JSON for guard evaluation: {}", e.getMessage());
            return Map.of();
        }
    }
}
