package com.nexus.backend.domain.user;

/**
 * Lifecycle of a password reset request.
 *
 * <p>The generated password is never persisted — it is returned to the admin once
 * and then discarded — so a resolved row records only that it happened.
 */
public enum ResetRequestStatus {
    /** Filed by a user, waiting for an admin. */
    PENDING,
    /** An admin generated and handed over a new password. */
    RESOLVED,
    /** An admin closed it without issuing a password. */
    DISMISSED
}