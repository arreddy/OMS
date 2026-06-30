package com.oms.domain.workflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
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

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

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

    /** Drives the Customer Portal timeline (UI spec §3) — internal-only states stay off it. */
    @Column(name = "is_customer_visible", nullable = false)
    private boolean customerVisible;

    /** Plain-language status shown to customers, independent of `code` (UI spec §3, §4.2). */
    @Column(name = "customer_facing_label", length = 200)
    private String customerFacingLabel;

    /** Only set when terminal = true — see chk_terminal_outcome_consistency. */
    @Enumerated(EnumType.STRING)
    @Column(name = "terminal_outcome", length = 10)
    private TerminalOutcome terminalOutcome;

    /** Workflow Designer canvas position (UI spec §4.3) — purely a layout hint, no engine meaning. */
    @Column(name = "canvas_x", precision = 10, scale = 2)
    private BigDecimal canvasX;

    @Column(name = "canvas_y", precision = 10, scale = 2)
    private BigDecimal canvasY;

    /** Derived, not persisted — see BadgeCategory. */
    public BadgeCategory getBadgeCategory() {
        return BadgeCategory.of(stateType, terminal, terminalOutcome);
    }
}
