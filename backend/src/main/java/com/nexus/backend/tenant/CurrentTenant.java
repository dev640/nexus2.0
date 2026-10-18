package com.nexus.backend.tenant;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Reads the caller's company for services that must scope a query to it.
 *
 * <p>Exists so the "no company" case is decided once. An account with no company
 * is a SUPER_ADMIN: a global operator whose rows are nobody's, and for whom a
 * company-scoped listing has no correct answer. Answering it accidentally — by
 * running the query unscoped — is the exact mistake that would hand every
 * company's data to one account, so the answer is a refusal instead.
 */
@Component
public class CurrentTenant {

    /** The caller's company, or a 403 when the account belongs to none. */
    public Long require() {
        Long companyId = TenantContext.getTenantId();
        if (companyId == null) {
            throw new AccessDeniedException(
                "This account does not belong to a company, so it has no company-scoped view of this data.");
        }
        return companyId;
    }

    /** The caller's company, or null when the account belongs to none. */
    public Long orNull() {
        return TenantContext.getTenantId();
    }
}
