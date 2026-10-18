package com.nexus.backend.tenant;

/**
 * Which company the current unit of work belongs to.
 *
 * <p>Held per thread rather than passed down every call: one request is one
 * person acting inside one company, so threading the id through every service
 * signature would add a parameter that is almost always the same value and can
 * be silently wrong at any call site that guessed it.
 *
 * <p>Two rules matter because this is a thread pool, not a per-request thread:
 * every entry point that sets a tenant must clear it in a {@code finally} (see
 * {@code JwtAuthenticationFilter}), and anything that hands work to another
 * thread must carry the tenant across explicitly with {@link TenantScope} — a
 * pooled thread keeps whatever the previous task left on it.
 *
 * <p>Deliberately a plain {@code ThreadLocal} and not an
 * {@code InheritableThreadLocal}: pooled threads are created once and reused, so
 * inheritance would copy the tenant of whichever request happened to create the
 * thread and then keep it forever.
 */
public final class TenantContext {
    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    /** Setting null clears the thread instead of storing a null entry. */
    public static void setTenantId(Long tenantId) {
        if (tenantId == null) {
            CURRENT_TENANT.remove();
            return;
        }
        CURRENT_TENANT.set(tenantId);
    }

    public static Long getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static boolean hasTenant() {
        return CURRENT_TENANT.get() != null;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    public static Long requireTenantId() {
        Long tenantId = getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}