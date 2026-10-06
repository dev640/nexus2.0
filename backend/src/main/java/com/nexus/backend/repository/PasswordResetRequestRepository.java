package com.nexus.backend.repository;

import com.nexus.backend.domain.user.PasswordResetRequest;
import com.nexus.backend.domain.user.ResetRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PasswordResetRequestRepository extends JpaRepository<PasswordResetRequest, Long> {

    Optional<PasswordResetRequest> findFirstByUserIdAndStatus(Long userId, ResetRequestStatus status);

    /**
     * The admin queue. Fetch-joins user and resolver so the response DTO can be
     * built without a lazy-init failure after the transaction closes.
     */
    @Query("""
        SELECT r FROM PasswordResetRequest r
        JOIN FETCH r.user u
        LEFT JOIN FETCH r.resolvedBy
        WHERE r.status = :status
        ORDER BY r.requestedAt DESC
        """)
    List<PasswordResetRequest> findByStatusWithUser(@Param("status") ResetRequestStatus status);

    @Query("""
        SELECT r FROM PasswordResetRequest r
        JOIN FETCH r.user u
        LEFT JOIN FETCH r.resolvedBy
        ORDER BY r.requestedAt DESC
        """)
    List<PasswordResetRequest> findAllWithUser();
}