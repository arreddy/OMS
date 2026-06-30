package com.oms.tenant;

/**
 * Holds the current request's tenant id for the lifetime of the thread that
 * handles it. Populated by {@link TenantFilter} for HTTP requests; scheduled
 * jobs that run outside a request (SLA sweep, outbox publisher) must set it
 * explicitly via {@link #runAs} since there is no header to resolve it from.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runAs(String tenantId, Runnable action) {
        set(tenantId);
        try {
            action.run();
        } finally {
            clear();
        }
    }
}
