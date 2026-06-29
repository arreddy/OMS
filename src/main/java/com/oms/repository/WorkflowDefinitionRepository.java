package com.oms.repository;

import com.oms.domain.workflow.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    @Query("select coalesce(max(d.version), 0) from WorkflowDefinition d where d.orderTypeCode = :orderTypeCode")
    int findMaxVersion(String orderTypeCode);

    /** Backs the Designer's version history dropdown (UI spec §4.3). */
    List<WorkflowDefinition> findByOrderTypeCodeOrderByVersionDesc(String orderTypeCode);
}
