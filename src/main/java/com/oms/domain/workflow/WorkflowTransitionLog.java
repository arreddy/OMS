package com.oms.domain.workflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.OffsetDateTime;
import java.util.UUID;

/** SPEC.md §4.5. Append-only audit trail. */
@Entity
@Table(name = "workflow_transition_log")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowTransitionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "log_id")
    private UUID logId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instance_id", nullable = false, updatable = false)
    private WorkflowInstance instance;

    @Column(name = "from_state_code", length = 50)
    private String fromStateCode;

    @Column(name = "to_state_code", nullable = false, length = 50)
    private String toStateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", length = 20)
    private TriggerType triggerType;

    @Column(name = "trigger_code", length = 50)
    private String triggerCode;

    @Column(name = "triggered_by", nullable = false, length = 100)
    private String triggeredBy;

    @Column(name = "comment")
    private String comment;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;
}
