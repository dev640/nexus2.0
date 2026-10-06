package com.nexus.backend.service.ai;

/**
 * Failure raised by the AI engine, classified so callers can return a useful,
 * user-friendly message without leaking internal details.
 */
public class AiEngineException extends RuntimeException {

    public enum Kind {
        /** No API key configured. */
        NOT_CONFIGURED,
        /** The provider rejected the credentials. */
        AUTH,
        /** The provider is rate-limiting us. */
        RATE_LIMIT,
        /** The request timed out. */
        TIMEOUT,
        /** The provider is unreachable or returned a server error. */
        UNAVAILABLE,
        /** The request was rejected as invalid. */
        INVALID_REQUEST,
        /** Anything else. */
        UNKNOWN
    }

    private final Kind kind;

    public AiEngineException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AiEngineException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** A safe, user-facing message — never includes keys or provider payloads. */
    public String userMessage() {
        return switch (kind) {
            case NOT_CONFIGURED -> "The AI engine is not configured. Set OPENAI_API_KEY on the server to enable AI answers.";
            case AUTH -> "The AI service rejected the configured credentials. Ask an administrator to check OPENAI_API_KEY.";
            case RATE_LIMIT -> "The AI service is rate-limiting requests right now. Try again in a moment.";
            case TIMEOUT -> "The AI service took too long to respond. Try again.";
            case UNAVAILABLE -> "The AI service is temporarily unavailable. Try again shortly.";
            case INVALID_REQUEST -> "The AI service could not process this request.";
            case UNKNOWN -> "Something went wrong while generating the answer. Try again.";
        };
    }
}
