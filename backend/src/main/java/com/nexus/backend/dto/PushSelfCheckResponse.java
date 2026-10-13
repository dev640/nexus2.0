package com.nexus.backend.dto;

import java.time.Instant;

/**
 * Everything needed to answer "is push working here?" about one account, in a
 * single call.
 *
 * <p>Exists because the alternative is a log hunt. When a phone does not buzz, the
 * server's own state — whether a key pair is configured, whether the two halves
 * belong together, whether the browser is registered at all, and what the push
 * service last said — is spread across a startup line, a database row and a warning
 * from a background thread. A deployment that answers these questions about itself
 * makes that a single request instead.
 *
 * <p>Scoped to the caller: the device count is their own, and the attempt is the last
 * one made on their behalf. Nothing here describes another account's devices, and
 * nothing here is key material — the fingerprint is a digest of the public half,
 * which the browser is handed in full anyway.
 *
 * @param pushEnabled                whether a usable key pair is configured at all
 * @param publicKeyFingerprint       twelve hex characters of the public key's SHA-256,
 *                                   or null when push is off. Compare it against the
 *                                   pair on disk to prove the deployed pair is the
 *                                   intended one
 * @param publicKeyMatchesPrivateKey true when the two halves are demonstrably one pair,
 *                                   false when they are demonstrably not, and null when
 *                                   it could not be checked. False means every push is
 *                                   being refused with a 403, whatever else looks fine
 * @param subject                    the contact address sent to push services. A value
 *                                   they consider malformed is itself a cause of 403s
 * @param ttlHours                   how long a push service holds an undelivered
 *                                   notification
 * @param devices                    how many browsers this account has registered. Zero
 *                                   means no push can be sent to it, however healthy
 *                                   everything else is
 * @param lastAttempt                how the most recent attempt for this account ended,
 *                                   or null if there has not been one since the process
 *                                   started
 */
public record PushSelfCheckResponse(
    boolean pushEnabled,
    String publicKeyFingerprint,
    Boolean publicKeyMatchesPrivateKey,
    String subject,
    long ttlHours,
    long devices,
    LastAttempt lastAttempt
) {

    /**
     * The most recent attempt, as it happened.
     *
     * @param at        when it was made
     * @param attempted how many of the account's browsers were tried
     * @param accepted  how many the push service took
     * @param outcome   {@code ACCEPTED}, {@code REFUSED}, {@code GONE},
     *                  {@code UNREACHABLE}, {@code NOT_SENT} or {@code UNSENDABLE}.
     *                  {@code GONE} is expected once per dead browser: the row is
     *                  deleted as it is reported
     * @param status    the HTTP status behind the outcome, or 0 when there was no HTTP
     *                  response at all — a refused connection, a timeout, or no request
     *                  to send
     */
    public record LastAttempt(
        Instant at,
        int attempted,
        int accepted,
        String outcome,
        int status
    ) {}
}
