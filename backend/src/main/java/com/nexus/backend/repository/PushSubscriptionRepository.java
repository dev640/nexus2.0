package com.nexus.backend.repository;

import com.nexus.backend.domain.push.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    /** Every browser one account has registered, which is where a push fans out. */
    List<PushSubscription> findByUserId(Long userId);

    /** The row a browser is re-registering, so re-subscribing updates rather than adds. */
    Optional<PushSubscription> findByEndpoint(String endpoint);

    long countByUserId(Long userId);
}
