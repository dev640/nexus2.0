package com.nexus.backend.repository;

import com.nexus.backend.domain.push.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    /**
     * Every browser one account has registered, which is where a push fans out.
     * Scoped: an endpoint is always written for the caller, so it only ever matches
     * the caller's own devices, but listing by user id alone would hand that caller
     * every company's matching rows — a user id is just a number, and the same number
     * can of course appear in another company.
     */
    List<PushSubscription> findByUserIdAndCompanyId(Long userId, Long companyId);

    /**
     * The row a browser is re-registering, so re-subscribing updates rather than
     * adds. Scoped to the caller's company: an endpoint claimed by somebody else's
     * account is moved to this one, and the same endpoint under a different company
     * is still a different endpoint.
     */
    Optional<PushSubscription> findByEndpointAndCompanyId(String endpoint, Long companyId);

    long countByUserIdAndCompanyId(Long userId, Long companyId);

    /**
     * A user's subscriptions by endpoint, scoped to the caller's company. Used by
     * the unsubscribe path so the same endpoint string from another company is not
     * treated as this caller's.
     */
    Optional<PushSubscription> findByUserIdAndEndpointAndCompanyId(
        Long userId, String endpoint, Long companyId);

    /**
     * Rewritten to the caller's company, keeping the user and the endpoint. Used
     * when a browser re-files a subscription and the stored row currently belongs
     * to a different account under the same company.
     *
     * <p>Scoped to one company on purpose: moving an endpoint from another company's
     * account would silently attach a device to a different workspace.
     */
    @Modifying
    @Query("delete from PushSubscription p where p.id = :id and p.companyId = :companyId")
    void deleteByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);

    /**
     * A subscription that currently belongs to this user under a different company
     * than the caller. Used by the re-register path when a browser re-files its
     * subscription after moving across companies.
     */
    Optional<PushSubscription> findByUserIdAndEndpointAndCompanyIdNot(
        Long userId, String endpoint, Long companyId);

    /**
     * All subscriptions for the given users under the given company. Used by the
     * fan-out path when an account changes company and the caller wants to re-stamp
     * every device that still answers under this company.
     */
    List<PushSubscription> findByUserIdInAndCompanyId(List<Long> userIds, Long companyId);
}
