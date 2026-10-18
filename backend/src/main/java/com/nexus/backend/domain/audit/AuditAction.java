package com.nexus.backend.domain.audit;

/**
 * The recorded action names, as a closed vocabulary rather than free-form
 * strings.
 *
 * <p>Audit rows are read back by operators and by reports; a typo in a string
 * literal at one call site would silently split one action into two that look
 * identical in a listing. Keeping the set here also makes it obvious which
 * actions are currently instrumented and which are still to come.
 */
public enum AuditAction {

    /** An admin created an account. */
    USER_CREATED,

    /** An admin deleted an account. */
    USER_DELETED,

    /** An admin changed an account's role. */
    USER_ROLE_CHANGED,

    /** A signed-in user replaced their own password. */
    PASSWORD_CHANGED,

    /** A locked-out user filed a password-reset request. */
    PASSWORD_RESET_REQUESTED,

    /** An admin issued a new password for a reset request. */
    PASSWORD_RESET_RESOLVED,

    /** An admin closed a reset request without issuing a password. */
    PASSWORD_RESET_DISMISSED
}
