package com.nexus.backend.tenant;

/**
 * Implemented by every entity that owns a {@code company_id} column.
 *
 * <p>Being tenant-aware is what lets {@link TenantStampListener} stamp the
 * current company onto a new row without every call site remembering to do it.
 * The Phase 4 architecture test asserts that every mapped entity except the
 * documented exceptions implements this interface, so a new table cannot be
 * added to the model without deciding which company its rows belong to.
 */
public interface TenantAware {

    Long getCompanyId();

    void setCompanyId(Long companyId);
}
