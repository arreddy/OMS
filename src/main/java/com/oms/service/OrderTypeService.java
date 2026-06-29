package com.oms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.domain.order.OrderType;
import com.oms.domain.workflow.StateType;
import com.oms.domain.workflow.TerminalOutcome;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
     * Extends an existing order type's schema (SPEC.md §3.3: "adding... new
     * custom fields means inserting/updating a row here — no DDL, no
     * deploy"). Either schema is optional so callers can update just one.
     * Doesn't touch any already-stored order.attributes — schema validation
     * only happens at write time, never retroactively (UI-SPEC.md §4.2).
     */
    @Transactional
    public OrderType updateSchema(String code, String attributeSchemaJson, String lineAttributeSchemaJson) {
        OrderType orderType = getByCode(code);
        if (attributeSchemaJson != null) {
            assertValidJson(attributeSchemaJson, "attribute_schema");
            orderType.setAttributeSchema(attributeSchemaJson);
        }
        if (lineAttributeSchemaJson != null) {
            assertValidJson(lineAttributeSchemaJson, "line_attribute_schema");
            orderType.setLineAttributeSchema(lineAttributeSchemaJson);
        }
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
        validateGraph(command);

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
            state.setCustomerVisible(spec.customerVisible());
            state.setCustomerFacingLabel(spec.customerFacingLabel());
            state.setTerminalOutcome(spec.terminalOutcome());
            state.setCanvasX(spec.canvasX());
            state.setCanvasY(spec.canvasY());
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

    /**
     * Backs the Workflow Designer's "Publish disabled until validation passes"
     * promise (UI spec §4.3) server-side, so a direct API call can't publish a
     * broken graph either: every state reachable from the initial state, every
     * non-terminal state has at least one outbound transition, and every
     * MANUAL state has both a TASK_APPROVED and a TASK_REJECTED transition.
     */
    private void validateGraph(PublishWorkflowCommand command) {
        List<StateSpec> states = command.states();
        List<TransitionSpec> transitions = command.transitions();
        Set<String> stateCodes = states.stream().map(StateSpec::code).collect(Collectors.toSet());

        long initialCount = states.stream().filter(StateSpec::initial).count();
        if (initialCount != 1) {
            throw new IllegalArgumentException("Workflow definition must have exactly one initial state, found " + initialCount);
        }
        String initialCode = states.stream().filter(StateSpec::initial).findFirst().orElseThrow().code();

        // Mirrors chk_terminal_outcome_consistency — catching this here gives a clean 400
        // instead of letting a mismatched payload fail as a raw DB constraint violation.
        List<String> terminalOutcomeMismatches = states.stream()
                .filter(s -> s.terminal() == (s.terminalOutcome() == null))
                .map(StateSpec::code)
                .toList();
        if (!terminalOutcomeMismatches.isEmpty()) {
            throw new IllegalArgumentException(
                    "terminal_outcome must be set if and only if the state is terminal: " + terminalOutcomeMismatches);
        }

        Map<String, List<TransitionSpec>> outboundByCode = transitions.stream()
                .collect(Collectors.groupingBy(TransitionSpec::fromStateCode));

        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        reachable.add(initialCode);
        queue.add(initialCode);
        while (!queue.isEmpty()) {
            for (TransitionSpec t : outboundByCode.getOrDefault(queue.poll(), List.of())) {
                if (reachable.add(t.toStateCode())) {
                    queue.add(t.toStateCode());
                }
            }
        }
        Set<String> unreachable = new HashSet<>(stateCodes);
        unreachable.removeAll(reachable);
        if (!unreachable.isEmpty()) {
            throw new IllegalArgumentException("States unreachable from initial state '" + initialCode + "': " + unreachable);
        }

        List<String> deadEnds = states.stream()
                .filter(s -> !s.terminal())
                .map(StateSpec::code)
                .filter(code -> outboundByCode.getOrDefault(code, List.of()).isEmpty())
                .toList();
        if (!deadEnds.isEmpty()) {
            throw new IllegalArgumentException("Non-terminal states with no outbound transition: " + deadEnds);
        }

        List<String> incompleteManualStates = states.stream()
                .filter(s -> s.stateType() == StateType.MANUAL)
                .map(StateSpec::code)
                .filter(code -> {
                    List<TransitionSpec> outbound = outboundByCode.getOrDefault(code, List.of());
                    boolean hasApprove = outbound.stream().anyMatch(t -> t.triggerType() == TriggerType.TASK_APPROVED);
                    boolean hasReject = outbound.stream().anyMatch(t -> t.triggerType() == TriggerType.TASK_REJECTED);
                    return !(hasApprove && hasReject);
                })
                .toList();
        if (!incompleteManualStates.isEmpty()) {
            throw new IllegalArgumentException(
                    "MANUAL states missing a TASK_APPROVED and/or TASK_REJECTED transition: " + incompleteManualStates);
        }
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

    public record StateSpec(String code, StateType stateType, boolean initial, boolean terminal,
                             String defaultAssigneeGroup, boolean customerVisible, String customerFacingLabel,
                             TerminalOutcome terminalOutcome, BigDecimal canvasX, BigDecimal canvasY) {
    }

    public record TransitionSpec(String fromStateCode, String toStateCode, int sequence, TriggerType triggerType,
                                  String triggerCode, String guardExpression, String sideEffect) {
    }

    public record PublishWorkflowCommand(String name, List<StateSpec> states, List<TransitionSpec> transitions) {
    }
}
