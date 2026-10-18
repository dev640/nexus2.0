package com.nexus.backend.tenant;

import jakarta.persistence.PrePersist;
import lombok.extern.slf4j.Slf4j;

/**
 * Stamps the current company onto a new {@link TenantAware} row.
 *
 * <p>Every tenant table has {@code company_id NOT NULL}, and the composite
 * foreign keys in V19 make a cross-company reference impossible to insert. The
 * stamp is therefore the one thing every write must get right, and the cost of
 * forgetting it at a single call site is an insert that fails on a constraint or
 * — worse on the nullable {@code users.company_id} — a row that belongs to
 * nobody. Doing it here means a caller only has to establish the tenant once per
 * request instead of remembering the column on every save.
 *
 * <p>An explicitly assigned company always wins: importing or migrating rows
 * under a different tenant than the caller's is a deliberate act, not an
 * accident. A row with neither an explicit company nor a tenant in scope is
 * refused rather than silently inserted, because there is no correct value to
 * guess and a NULL would be caught by the database anyway with a message that
 * names no entity.
 */
@Slf4j
public class TenantStampListener {

    @PrePersist
    public void stampCompanyOnCreate(Object entity) {
        if (!(entity instanceof TenantAware tenant)) {
            return;
        }
        if (tenant.getCompanyId() != null) {
            return;
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "Refusing to insert " + entity.getClass().getSimpleName()
                    + " with no company: no tenant is set in TenantContext and none was assigned explicitly.");
        }
        tenant.setCompanyId(tenantId);
    }
}
