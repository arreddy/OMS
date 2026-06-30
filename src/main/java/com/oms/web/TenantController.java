package com.oms.web;

import com.oms.domain.tenant.Tenant;
import com.oms.service.TenantService;
import com.oms.web.dto.TenantDtos.CreateTenantRequest;
import com.oms.web.dto.TenantDtos.TenantResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SPEC.md §10. Deliberately exempt from TenantFilter (see TenantFilter) —
 * creating a tenant can't itself require an existing X-Tenant-Id, and Tenant
 * carries no @TenantId since it isn't tenant-owned data.
 */
@RestController
@RequestMapping("/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public List<TenantResponse> list() {
        return tenantService.list().stream().map(this::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.create(request.tenantId(), request.name());
        return toResponse(tenant);
    }

    private TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(tenant.getTenantId(), tenant.getName(), tenant.isActive());
    }
}
