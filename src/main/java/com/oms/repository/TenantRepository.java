package com.oms.repository;

import com.oms.domain.tenant.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, String> {

    Optional<Tenant> findByTenantIdAndActiveTrue(String tenantId);

    List<Tenant> findAllByActiveTrue();
}
