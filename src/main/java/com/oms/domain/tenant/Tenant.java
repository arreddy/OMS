package com.oms.domain.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registry of known tenants. Not itself tenant-scoped. Used to validate
 * incoming X-Tenant-Id headers and to enumerate tenants for scheduled jobs
 * that run outside any request (TenantFilter, TaskService, DomainEventPublisher).
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
