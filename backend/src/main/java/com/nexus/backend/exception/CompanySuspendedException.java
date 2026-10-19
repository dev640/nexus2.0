package com.nexus.backend.exception;

/**
 * Raised when an account whose company is suspended tries to sign in or refresh
 * a token.
 *
 * <p>Refused here rather than merely recorded, because a suspended workspace is
 * one whose access has been withdrawn: issuing a token would hand its people a
 * credential that every subsequent request is refused anyway, and the honest
 * answer at the door is the same 403 the rest of their requests will get.
 *
 * <p>The HTTP path (an already-authenticated account) is refused by
 * {@code SuspendedCompanyFilter} instead — this exception covers the two
 * endpoints that run before any authentication exists, where there is no
 * principal to gate.
 */
public class CompanySuspendedException extends RuntimeException {

    /** Marker in the refusal body, matching the filter's so clients see one code. */
    public static final String CODE = "WORKSPACE_SUSPENDED";

    /** Shown as-is; it names who can lift it. */
    public static final String MESSAGE =
        "This workspace is suspended. Contact your administrator to restore access.";

    public CompanySuspendedException() {
        super(MESSAGE);
    }
}
