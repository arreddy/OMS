package com.oms.tenant;

import com.oms.repository.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;

/**
 * Resolves the tenant for every request from X-Tenant-Id, mirroring the
 * existing trusted X-User-Id header pattern (no auth framework is wired up
 * here yet — see TECHNICAL.md). Runs first so every downstream filter,
 * controller, and Hibernate query sees TenantContext already populated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends GenericFilterBean {

    private final TenantRepository tenantRepository;

    public TenantFilter(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Tenant provisioning can't itself require an existing tenant — and
        // Tenant carries no @TenantId, so there's nothing here to scope.
        if (httpRequest.getServletPath().startsWith("/tenants")) {
            chain.doFilter(request, response);
            return;
        }

        String tenantId = httpRequest.getHeader("X-Tenant-Id");
        if (tenantId == null || tenantId.isBlank()) {
            httpResponse.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-Tenant-Id header is required");
            return;
        }
        if (tenantRepository.findByTenantIdAndActiveTrue(tenantId).isEmpty()) {
            httpResponse.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown tenant: " + tenantId);
            return;
        }

        try {
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
