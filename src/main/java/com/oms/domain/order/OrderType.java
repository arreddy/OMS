package com.oms.domain.order;

import com.oms.domain.workflow.WorkflowDefinition;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The extensibility registry (SPEC.md §3.3). order_type.workflow_definition_id
 * is the sole source of truth for "the active workflow version" of this order
 * type — see WorkflowDefinition for why there is no parallel is_active flag.
 */
@Entity
@Table(name = "order_type")
@Getter
@Setter
@NoArgsConstructor
public class OrderType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_type_id")
    private UUID orderTypeId;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attribute_schema", nullable = false, columnDefinition = "jsonb")
    private String attributeSchema;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "line_attribute_schema", nullable = false, columnDefinition = "jsonb")
    private String lineAttributeSchema;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_definition_id")
    private WorkflowDefinition workflowDefinition;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
