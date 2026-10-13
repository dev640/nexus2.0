package com.nexus.backend.service.push;

import java.time.Instant;

/**
 * What happened the last time a push was attempted for one account.
 *
 * <p>Kept for diagnostics rather than for the record: a push service that refuses
 * every request leaves the only evidence in a log line a developer has to go and
 * find, and "the phone did not buzz" is otherwise indistinguishable from "the
 * server never tried". One of these per account turns that into a question the
 * application can answer about itself.
 *
 * <p>Deliberately not an audit log. It holds the most recent attempt only, in
 * memory, and a restart forgets it — which is the right lifetime for "did the last
 * thing I did work?".
 *
 * @param at        when the attempt was made
 * @param attempted how many of the account's registered browsers were tried
 * @param accepted  how many of them the push service took (2xx)
 * @param outcome   how it ended, in the terms an operator can act on
 * @param status    the HTTP status of the reported outcome, or 0 when there was no
 *                  HTTP response at all (a timeout, a refused connection) or no
 *                  request to send in the first place
 */
public record PushAttempt(
    Instant at,
    int attempted,
    int accepted,
    Outcome outcome,
    int status
) {

    /**
     * How an attempt ended.
     *
     * <p>These are the branches of {@link WebPushSender}, named so that a caller
     * reading one of them out of a JSON response does not need to know the status
     * codes by heart.
     */
    public enum Outcome {
        /** The push service took every copy. */
        ACCEPTED,
        /**
         * Answered, but not with success and not with "this browser is gone" —
         * a payload it will not take, a rate limit, or its own trouble. The
         * subscription is kept and the next notification tries again.
         */
        REFUSED,
        /**
         * 404 or 410: the browser is uninstalled or has replaced its subscription.
         * The row is deleted, so this can only describe the moment it went away.
         */
        GONE,
        /** No answer at all — DNS, TCP, TLS, or a timeout. */
        UNREACHABLE,
        /** The request could not be built, so nothing was sent. */
        NOT_SENT,
        /** The payload could not be serialized. An internal fault, not the push service's. */
        UNSENDABLE
    }
}
