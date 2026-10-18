package com.nexus.backend.repository;

import com.nexus.backend.domain.user.PasswordResetRequest;
import com.nexus.backend.domain.user.ResetRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, Long> {

    /**
     * The open request for one account, if there is one.
     *
     * <p>Not company-scoped, and it does not need to be: it is keyed by the
     * account, whose id is globally unique, so it can only ever reach that
     * person's own request.
     */
    Optional<PasswordResetRequest> findFirstByUserIdAndStatus(Long userId, ResetRequestStatus status);

    /** One request, only if it belongs to this company. Guards resolve/dismiss. */
    Optional<PasswordResetRequest> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * The admin queue for one company. Fetch-joins user and resolver so the
     * response DTO can be built without a lazy-init failure after the
     * transaction closes.
     */
    @Query("""
        SELECT r FROM PasswordResetRequest r
        JOIN FETCH r.user u
        LEFT JOIN FETCH r.resolvedBy
        WHERE r.status = :status AND r.companyId = :companyId
        ORDER BY r.requestedAt DESC
        """)
    List<PasswordResetRequest> findByStatusAndCompanyIdWithUser(
        @Param("status") ResetRequestStatus status, @Param("companyId") Long companyId);

    @Query("""
        SELECT r FROM PasswordResetRequest r
        JOIN FETCH r.user u
        LEFT JOIN FETCH r.resolvedBy
        WHERE r.companyId = :companyId
        ORDER BY r.requestedAt DESC
        """)
    List<PasswordResetRequest> findAllByCompanyIdWithUser(@Param("companyId") Long companyId);
}
