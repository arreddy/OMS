package com.oms.web;

import com.oms.domain.order.Order;
import com.oms.domain.workflow.WorkflowDefinition;
import com.oms.domain.workflow.WorkflowInstance;
import com.oms.domain.workflow.WorkflowState;
import com.oms.exception.NotFoundException;
import com.oms.repository.WorkflowDefinitionRepository;
import com.oms.repository.WorkflowInstanceRepository;
import com.oms.repository.WorkflowStateRepository;
import com.oms.repository.WorkflowTransitionLogRepository;
import com.oms.repository.WorkflowTransitionRepository;
import com.oms.service.OrderService;
import com.oms.service.WorkflowEngineService;
import com.oms.web.dto.WorkflowDtos.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** SPEC.md §6 (Workflow). */
@RestController
public class WorkflowController {

    private final OrderService orderService;
    private final WorkflowEngineService workflowEngineService;
    private final WorkflowInstanceRepository workflowInstanceRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final WorkflowTransitionLogRepository workflowTransitionLogRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowStateRepository workflowStateRepository;

    public WorkflowController(OrderService orderService,
                               WorkflowEngineService workflowEngineService,
                               WorkflowInstanceRepository workflowInstanceRepository,
                               WorkflowTransitionRepository workflowTransitionRepository,
                               WorkflowTransitionLogRepository workflowTransitionLogRepository,
                               WorkflowDefinitionRepository workflowDefinitionRepository,
                               WorkflowStateRepository workflowStateRepository) {
        this.orderService = orderService;
        this.workflowEngineService = workflowEngineService;
        this.workflowInstanceRepository = workflowInstanceRepository;
        this.workflowTransitionRepository = workflowTransitionRepository;
        this.workflowTransitionLogRepository = workflowTransitionLogRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowStateRepository = workflowStateRepository;
    }

    @GetMapping("/orders/{id}/workflow")
    @Transactional(readOnly = true)
    public WorkflowInstanceResponse getWorkflow(@PathVariable UUID id) {
        return buildWorkflowResponse(id);
    }

    @PostMapping("/orders/{id}/workflow/transitions")
    @Transactional
    public WorkflowInstanceResponse fireTransition(@PathVariable UUID id, @RequestBody FireTransitionRequest request,
                                                    @RequestHeader(value = "X-User-Id", defaultValue = "system") String actor) {
        Order order = orderService.getOrder(id);
        workflowEngineService.fireTrigger(order, request.triggerType(), request.triggerCode(), actor, request.comment());
        return buildWorkflowResponse(id);
    }

    @GetMapping("/workflow-definitions/{id}")
    @Transactional(readOnly = true)
    public WorkflowDefinitionResponse getDefinition(@PathVariable UUID id) {
        WorkflowDefinition definition = workflowDefinitionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No workflow definition " + id));

        List<StateResponse> states = workflowStateRepository.findByWorkflowDefinition_WorkflowDefinitionId(id)
                .stream()
                .map(s -> new StateResponse(s.getStateId(), s.getCode(), s.getStateType(), s.isInitial(), s.isTerminal(), s.getDefaultAssigneeGroup()))
                .toList();
        List<TransitionResponse> transitions = workflowTransitionRepository.findByWorkflowDefinition_WorkflowDefinitionId(id)
                .stream()
                .map(t -> new TransitionResponse(t.getTransitionId(), t.getFromState().getCode(), t.getToState().getCode(),
                        t.getSequence(), t.getTriggerType(), t.getTriggerCode(), t.getGuardExpression(), t.getSideEffect()))
                .toList();

        return new WorkflowDefinitionResponse(definition.getWorkflowDefinitionId(), definition.getOrderTypeCode(),
                definition.getVersion(), definition.getName(), definition.getPublishedAt(), states, transitions);
    }

    private WorkflowInstanceResponse buildWorkflowResponse(UUID orderId) {
        WorkflowInstance instance = workflowInstanceRepository.findByOrder_OrderId(orderId)
                .orElseThrow(() -> new NotFoundException("No workflow instance for order " + orderId));
        WorkflowState current = instance.getCurrentState();

        List<TransitionOption> options = workflowTransitionRepository
                .findByFromState_StateIdOrderBySequenceAsc(current.getStateId())
                .stream()
                .map(t -> new TransitionOption(t.getTriggerType(), t.getTriggerCode(), t.getToState().getCode()))
                .toList();
        List<TransitionLogEntry> history = workflowTransitionLogRepository
                .findByInstance_InstanceIdOrderByOccurredAtAsc(instance.getInstanceId())
                .stream()
                .map(l -> new TransitionLogEntry(l.getFromStateCode(), l.getToStateCode(), l.getTriggerType(),
                        l.getTriggerCode(), l.getTriggeredBy(), l.getComment(), l.getOccurredAt()))
                .toList();

        return new WorkflowInstanceResponse(instance.getInstanceId(), orderId, current.getCode(), current.isTerminal(), options, history);
    }
}
