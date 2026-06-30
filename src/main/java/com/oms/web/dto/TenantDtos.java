package com.oms.web.dto;

public final class TenantDtos {

    private TenantDtos() {
    }

    public record CreateTenantRequest(String tenantId, String name) {
    }

    public record TenantResponse(String tenantId, String name, boolean active) {
    }
}
