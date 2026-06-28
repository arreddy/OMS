package com.oms.domain.workflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SPEC.md §4.1. Immutable once published. There is no is_active flag here —
 * "the active version for an order type" is defined solely by
 * order_type.workflow_definition_id (OrderType#workflowDefinition), so there
 * is exactly one place that can disagree with itself.
 */
@Entity
@Table(name = "workflow_definition")
@Getter
@Setter
@NoArgsConstructor
public class WorkflowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "workflow_definition_id")
    private UUID workflowDefinitionId;

    @Column(name = "order_type_code", nullable = false, length = 50)
    private String orderTypeCode;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;
}
