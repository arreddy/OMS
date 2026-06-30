package com.oms.domain.task;

import com.oms.domain.order.Order;
import com.oms.domain.workflow.WorkflowInstance;
import com.oms.domain.workflow.WorkflowState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.OffsetDateTime;
import java.util.UUID;

/** SPEC.md §5.1. Created automatically when a workflow_instance enters a MANUAL state. */
@Entity
@Table(name = "task")
@Getter
@Setter
@NoArgsConstructor
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "task_id")
    private UUID taskId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 64)
    private String tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_instance_id", nullable = false, updatable = false)
    private WorkflowInstance workflowInstance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id", nullable = false, updatable = false)
    private WorkflowState state;

    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "assignee_id", length = 100)
    private String assigneeId;

    @Column(name = "assignee_group", length = 100)
    private String assigneeGroup;

    @Column(name = "priority", nullable = false)
    private short priority;

    @Column(name = "sla_due_at")
    private OffsetDateTime slaDueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 10)
    private TaskDecision decision;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "decision_by", length = 100)
    private String decisionBy;

    /** Set on escalate — by a manual override (UI spec §2.4) or the SLA sweep job (SPEC.md §5.3 step 4). */
    @Column(name = "escalation_reason")
    private String escalationReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
