package com.oms.repository;

import com.oms.domain.workflow.WorkflowState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowStateRepository extends JpaRepository<WorkflowState, UUID> {

    Optional<WorkflowState> findByWorkflowDefinition_WorkflowDefinitionIdAndInitialTrue(UUID workflowDefinitionId);

    Optional<WorkflowState> findByWorkflowDefinition_WorkflowDefinitionIdAndCode(UUID workflowDefinitionId, String code);

    List<WorkflowState> findByWorkflowDefinition_WorkflowDefinitionId(UUID workflowDefinitionId);

    /** Backs GET /order-types/status-taxonomy — aggregates states across several active order types' workflows. */
    List<WorkflowState> findByWorkflowDefinition_WorkflowDefinitionIdIn(Collection<UUID> workflowDefinitionIds);
}
