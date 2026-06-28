package com.oms.domain.workflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * SPEC.md §4.3. A null trigger_code means "evaluate this transition's guard
 * immediately on state entry, no external signal required" — see
 * WorkflowState (StateType) and WorkflowEngineService for how AUTOMATIC/WAIT
 * states use this. sequence resolves ties when more than one outbound
 * transition from the same state could be eligible at once: lower values are
 * evaluated first, and the first one whose trigger matches and guard passes wins.
 */
@Entity
@Table(name = "workflow_transition")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "transition_id")
    private UUID transitionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_definition_id", nullable = false, updatable = false)
    private WorkflowDefinition workflowDefinition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_state_id", nullable = false, updatable = false)
    private WorkflowState fromState;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_state_id", nullable = false, updatable = false)
    private WorkflowState toState;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private TriggerType triggerType;

    @Column(name = "trigger_code", length = 50)
    private String triggerCode;

    /** JSON Logic expression text, evaluated against {"order": {...}}. Null = always true. */
    @Column(name = "guard_expression")
    private String guardExpression;

    @Column(name = "side_effect", length = 100)
    private String sideEffect;
}
