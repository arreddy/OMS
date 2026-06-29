package com.oms.repository;

import com.oms.domain.task.Task;
import com.oms.domain.task.TaskStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    List<Task> findByWorkflowInstance_InstanceIdAndStatusIn(UUID workflowInstanceId, Collection<TaskStatus> statuses);

    List<Task> findByStatusInAndSlaDueAtBefore(Collection<TaskStatus> statuses, OffsetDateTime cutoff);

    static Specification<Task> hasStatus(TaskStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    static Specification<Task> hasAssigneeGroup(String assigneeGroup) {
        return (root, query, cb) -> assigneeGroup == null ? null : cb.equal(root.get("assigneeGroup"), assigneeGroup);
    }

    static Specification<Task> hasOrderTypeCode(String orderTypeCode) {
        return (root, query, cb) -> orderTypeCode == null ? null
                : cb.equal(root.get("order").get("orderTypeCode"), orderTypeCode);
    }

    /** Backs the "My tasks" toggle (UI spec §2.3) — caller passes the current user's ID. */
    static Specification<Task> hasAssigneeId(String assigneeId) {
        return (root, query, cb) -> assigneeId == null ? null : cb.equal(root.get("assigneeId"), assigneeId);
    }

    static Specification<Task> hasPriority(Short priority) {
        return (root, query, cb) -> priority == null ? null : cb.equal(root.get("priority"), priority);
    }

    /** Backs Order Detail's "awaiting task" card (UI spec §2.2) — finds the task(s) for one order. */
    static Specification<Task> hasOrderId(UUID orderId) {
        return (root, query, cb) -> orderId == null ? null : cb.equal(root.get("order").get("orderId"), orderId);
    }
}
