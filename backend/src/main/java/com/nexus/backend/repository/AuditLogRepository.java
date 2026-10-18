package com.nexus.backend.repository;

import com.nexus.backend.domain.audit.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Reads for the audit trail. Writes go through
 * {@link com.nexus.backend.service.AuditService}, which fills in the actor and
 * the company so no caller has to remember them.
 *
 * <p>Both listings are ordered newest-first and page-bounded: the table only
 * grows, and an operator looking at it wants the recent end.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Everything one company did, newest first. */
    List<AuditLog> findByCompanyIdOrderByIdDesc(Long companyId, Pageable pageable);

    /** The global view, including actions that belong to no company. */
    List<AuditLog> findAllByOrderByIdDesc(Pageable pageable);
}
