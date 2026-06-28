package com.oms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.order.OrderType;
import com.oms.domain.workflow.StateType;
import com.oms.domain.workflow.TriggerType;
import com.oms.domain.workflow.WorkflowDefinition;
import com.oms.domain.workflow.WorkflowState;
import com.oms.domain.workflow.WorkflowTransition;
import com.oms.exception.NotFoundException;
import com.oms.repository.OrderTypeRepository;
import com.oms.repository.WorkflowDefinitionRepository;
import com.oms.repository.WorkflowStateRepository;
import com.oms.repository.WorkflowTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SPEC.md §3.3 registry + §4.1/§6 workflow publishing. */
@Service
public class OrderTypeService {

    private final OrderTypeRepository orderTypeRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final WorkflowStateRepository workflowStateRepository;
    private final WorkflowTransitionRepository workflowTransitionRepository;
    private final ObjectMapper objectMapper;

    public OrderTypeService(OrderTypeRepository orderTypeRepository,
                             WorkflowDefinitionRepository workflowDefinitionRepository,
                             WorkflowStateRepository workflowStateRepository,
                             WorkflowTransitionRepository workflowTransitionRepository,
                             ObjectMapper objectMapper) {
        this.orderTypeRepository = orderTypeRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.workflowStateRepository = workflowStateRepository;
        this.workflowTransitionRepository = workflowTransitionRepository;
        this.objectMapper = objectMapper;
    }

    public OrderType getByCode(String code) {
        return orderTypeRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("No order type with code " + code));
    }

    public List<OrderType> listActive() {
        return orderTypeRepository.findAllByActiveTrue();
    }

    @Transactional
    public OrderType create(String code, String name, String attributeSchemaJson, String lineAttributeSchemaJson) {
        if (orderTypeRepository.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Order type " + code + " already exists");
        }
        assertValidJson(attributeSchemaJson, "attribute_schema");
        assertValidJson(lineAttributeSchemaJson, "line_attribute_schema");

        OrderType orderType = new OrderType();
        orderType.setCode(code);
        orderType.setName(name);
        orderType.setAttributeSchema(attributeSchemaJson);
        orderType.setLineAttributeSchema(lineAttributeSchemaJson);
        orderType.setActive(true);
        return orderTypeRepository.save(orderType);
    }

    /**
     * Publishes a new workflow_definition version and atomically repoints
     * order_type.workflow_definition_id at it (SPEC.md §4.1) — the single
     * source of truth for "the active version" of this order type. Existing
     * workflow_instance rows keep whatever workflow_definition_id they were
     * already pinned to.
     */
    @Transactional
    public WorkflowDefinition publishWorkflow(String orderTypeCode, PublishWorkflowCommand command) {
        OrderType orderType = getByCode(orderTypeCode);

        long initialCount = command.states().stream().filter(StateSpec::initial).count();
        if (initialCount != 1) {
            throw new IllegalArgumentException("Workflow definition must have exactly one initial state, found " + initialCount);
        }

        int nextVersion = workflowDefinitionRepository.findMaxVersion(orderTypeCode) + 1;
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setOrderTypeCode(orderTypeCode);
        definition.setVersion(nextVersion);
        definition.setName(command.name());
        definition.setPublishedAt(OffsetDateTime.now());
        definition = workflowDefinitionRepository.save(definition);

        Map<String, WorkflowState> statesByCode = new HashMap<>();
        for (StateSpec spec : command.states()) {
            WorkflowState state = new WorkflowState();
            state.setWorkflowDefinition(definition);
            state.setCode(spec.code());
            state.setStateType(spec.stateType());
            state.setInitial(spec.initial());
            state.setTerminal(spec.terminal());
            state.setDefaultAssigneeGroup(spec.defaultAssigneeGroup());
            statesByCode.put(spec.code(), workflowStateRepository.save(state));
        }

        for (TransitionSpec spec : command.transitions()) {
            WorkflowState from = requireState(statesByCode, spec.fromStateCode());
            WorkflowState to = requireState(statesByCode, spec.toStateCode());

            WorkflowTransition transition = new WorkflowTransition();
            transition.setWorkflowDefinition(definition);
            transition.setFromState(from);
            transition.setToState(to);
            transition.setSequence(spec.sequence());
            transition.setTriggerType(spec.triggerType());
            transition.setTriggerCode(spec.triggerCode());
            transition.setGuardExpression(spec.guardExpression());
            transition.setSideEffect(spec.sideEffect());
            workflowTransitionRepository.save(transition);
        }

        orderType.setWorkflowDefinition(definition);
        orderTypeRepository.save(orderType);

        return definition;
    }

    private WorkflowState requireState(Map<String, WorkflowState> statesByCode, String code) {
        WorkflowState state = statesByCode.get(code);
        if (state == null) {
            throw new IllegalArgumentException("Transition references unknown state code " + code);
        }
        return state;
    }

    private void assertValidJson(String json, String label) {
        try {
            objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(label + " is not valid JSON: " + e.getMessage());
        }
    }

    public record StateSpec(String code, StateType stateType, boolean initial, boolean terminal, String defaultAssigneeGroup) {
    }

    public record TransitionSpec(String fromStateCode, String toStateCode, int sequence, TriggerType triggerType,
                                  String triggerCode, String guardExpression, String sideEffect) {
    }

    public record PublishWorkflowCommand(String name, List<StateSpec> states, List<TransitionSpec> transitions) {
    }
}
