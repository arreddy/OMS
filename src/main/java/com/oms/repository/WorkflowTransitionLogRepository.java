package com.oms.repository;

import com.oms.domain.workflow.WorkflowTransitionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionLogRepository extends JpaRepository<WorkflowTransitionLog, UUID> {

    List<WorkflowTransitionLog> findByInstance_InstanceIdOrderByOccurredAtAsc(UUID instanceId);
}
