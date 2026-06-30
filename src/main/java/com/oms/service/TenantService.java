package com.oms.service;

import com.oms.domain.tenant.Tenant;
import com.oms.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** SPEC.md §10, §9 — tenant registry (no API existed for this until now). */
@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public List<Tenant> list() {
        return tenantRepository.findAll();
    }

    @Transactional
    public Tenant create(String tenantId, String name) {
        if (tenantRepository.findById(tenantId).isPresent()) {
            throw new IllegalArgumentException("Tenant " + tenantId + " already exists");
        }
        Tenant tenant = new Tenant();
        tenant.setTenantId(tenantId);
        tenant.setName(name);
        tenant.setActive(true);
        return tenantRepository.save(tenant);
    }
}
