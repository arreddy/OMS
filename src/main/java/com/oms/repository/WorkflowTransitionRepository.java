package com.oms.repository;

import com.oms.domain.workflow.WorkflowTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowTransitionRepository extends JpaRepository<WorkflowTransition, UUID> {

    List<WorkflowTransition> findByFromState_StateIdOrderBySequenceAsc(UUID fromStateId);

    List<WorkflowTransition> findByWorkflowDefinition_WorkflowDefinitionId(UUID workflowDefinitionId);
}
