package com.nexus.backend.service;

/**
 * Why a Copilot call did not produce an answer.
 *
 * <p>Every constant carries a {@link #safeReason()}: a short explanation that is
 * safe to hand to a browser. Vendor error bodies are deliberately not used for
 * it — they routinely echo the request, and can contain the API key, internal
 * model ids or upstream HTML. They are logged server-side instead.
 *
 * <p>The distinction matters because a Copilot that is misconfigured, whose key
 * was revoked, and one that is merely rate limited all fail identically from
 * the outside: the app quietly serves the grounded answer. Without a taxonomy
 * the only visible symptom is that the feature "does not work sometimes", and
 * nothing tells an operator which setting to change.
 *
 * <p>{@link #retryable()} marks the failures worth retrying on the next
 * question; the rest need a human to change configuration or billing.
 */
public enum LlmFailure {

    /** No API key is set, so the Copilot is off by design. */
    NOT_CONFIGURED(false, "Copilot is not configured"),
    /** A key is set but no model, so no request can be built. */
    MODEL_NOT_CONFIGURED(false, "Copilot has no model configured"),
    /** 401/403 — the key is wrong, revoked, or lacks access. */
    INVALID_API_KEY(false, "Copilot's API key was rejected"),
    /** 404 — the configured model id does not exist at this provider. */
    MODEL_NOT_FOUND(false, "The configured Copilot model does not exist"),
    /** 429 with a quota/billing signal — the account is out of credit. */
    QUOTA_EXCEEDED(false, "Copilot's usage quota is exhausted"),
    /** 429 without a quota signal — too many requests in the window. */
    RATE_LIMITED(true, "Copilot is being rate limited"),
    /** 400 — the provider refused the request shape. */
    BAD_REQUEST(false, "Copilot rejected the request"),
    TIMEOUT(true, "Copilot took too long to answer"),
    /** DNS failure, connection refused, TLS error. */
    NETWORK(true, "Copilot could not be reached"),
    /** A 2xx whose body could not be read as the expected shape. */
    MALFORMED_RESPONSE(true, "Copilot returned an unreadable response"),
    /** Anything not otherwise classified, including 5xx. */
    UNKNOWN(true, "Copilot is unavailable");

    private final boolean retryable;
    private final String safeReason;

    LlmFailure(boolean retryable, String safeReason) {
        this.retryable = retryable;
        this.safeReason = safeReason;
    }

    /** Whether retrying later could plausibly succeed without intervention. */
    public boolean retryable() {
        return retryable;
    }

    /** Operator- and user-safe explanation. Never contains vendor or internal detail. */
    public String safeReason() {
        return safeReason;
    }
}