package com.oms.web.dto;

import com.oms.domain.workflow.StateType;
import com.oms.domain.workflow.TriggerType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    /**
     * SPEC.md §6 describes this endpoint's body as just {trigger_code, payload},
     * but the underlying transition model (SPEC.md §4.3) keys matching on
     * (trigger_type, trigger_code) together, and this single endpoint is meant
     * to cover both EVENT and API_ACTION triggers (and TIMER, for completeness)
     * — so triggerType is included explicitly rather than inferred.
     */
    public record FireTransitionRequest(TriggerType triggerType, String triggerCode, String comment) {
    }

    public record TransitionOption(TriggerType triggerType, String triggerCode, String toStateCode) {
    }

    public record TransitionLogEntry(String fromStateCode, String toStateCode, TriggerType triggerType,
                                      String triggerCode, String triggeredBy, String comment, OffsetDateTime occurredAt) {
    }

    public record WorkflowInstanceResponse(UUID instanceId, UUID orderId, String currentState, boolean terminal,
                                            List<TransitionOption> validNextTransitions,
                                            List<TransitionLogEntry> history) {
    }

    public record StateResponse(UUID stateId, String code, StateType stateType, boolean initial, boolean terminal,
                                 String defaultAssigneeGroup) {
    }

    public record TransitionResponse(UUID transitionId, String fromStateCode, String toStateCode, int sequence,
                                      TriggerType triggerType, String triggerCode, String guardExpression,
                                      String sideEffect) {
    }

    public record WorkflowDefinitionResponse(UUID workflowDefinitionId, String orderTypeCode, int version,
                                              String name, OffsetDateTime publishedAt,
                                              List<StateResponse> states, List<TransitionResponse> transitions) {
    }
}
