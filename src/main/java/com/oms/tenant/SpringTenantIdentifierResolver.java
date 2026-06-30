package com.oms.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Wired into Hibernate via the "hibernate.tenant_identifier_resolver" property
 * (see TenantHibernateConfig). Backs every @TenantId-annotated entity's
 * automatic tenant filtering/population.
 */
@Component
public class SpringTenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    /**
     * Hibernate needs a resolvable tenant identifier to open any Session at
     * all — including ones opened outside a request or TenantContext.runAs
     * block, e.g. Spring Data's query-method validation at startup. This
     * sentinel matches no real tenant row, so any query that somehow runs
     * under it (a missed TenantFilter/runAs call) returns nothing rather
     * than leaking another tenant's data.
     */
    private static final String NO_TENANT_SENTINEL = "__no_tenant__";

    @Override
    public String resolveCurrentTenantIdentifier() {
        String tenantId = TenantContext.get();
        return tenantId != null ? tenantId : NO_TENANT_SENTINEL;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }
}
