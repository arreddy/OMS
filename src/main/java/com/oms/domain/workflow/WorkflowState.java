package com.oms.domain.workflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** SPEC.md §4.2. */
@Entity
@Table(name = "workflow_state")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "state_id")
    private UUID stateId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_definition_id", nullable = false, updatable = false)
    private WorkflowDefinition workflowDefinition;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_type", nullable = false, length = 20)
    private StateType stateType;

    @Column(name = "is_initial", nullable = false)
    private boolean initial;

    @Column(name = "is_terminal", nullable = false)
    private boolean terminal;

    /** Default assignee_group for tasks created on entry to this state, when state_type = MANUAL. */
    @Column(name = "default_assignee_group", length = 100)
    private String defaultAssigneeGroup;
}
