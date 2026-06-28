package com.oms.repository;

import com.oms.domain.workflow.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowInstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

    Optional<WorkflowInstance> findByOrder_OrderId(UUID orderId);
}
