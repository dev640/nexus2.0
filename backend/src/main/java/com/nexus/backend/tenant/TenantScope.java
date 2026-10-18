package com.nexus.backend.tenant;

import java.util.function.Supplier;

/**
 * Carries the current tenant onto another thread for one unit of work.
 *
 * <p>The request that starts a streaming AI answer, a push fan-out or any other
 * background job is not the thread that finishes it. {@link TenantContext} is
 * per-thread, so work handed to a pool thread starts with no tenant at all (or,
 * worse, with the tenant of whatever request ran on that thread last). This is
 * how the tenant is carried across that boundary explicitly.
 *
 * <p>Whatever tenant the executing thread already had is restored afterwards, so
 * nesting scopes is safe and a pooled thread is never left holding a company
 * that is not its current task's.
 */
public final class TenantScope {

    private TenantScope() {}

    /** Runs {@code body} as {@code companyId}, restoring the previous tenant after. */
    public static void run(Long companyId, Runnable body) {
        Long previous = TenantContext.getTenantId();
        TenantContext.setTenantId(companyId);
        try {
            body.run();
        } finally {
            TenantContext.setTenantId(previous);
        }
    }

    /** Runs {@code body} as {@code companyId} and returns its result. */
    public static <T> T call(Long companyId, Supplier<T> body) {
        Long previous = TenantContext.getTenantId();
        TenantContext.setTenantId(companyId);
        try {
            return body.get();
        } finally {
            TenantContext.setTenantId(previous);
        }
    }
}
