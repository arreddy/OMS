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
}
