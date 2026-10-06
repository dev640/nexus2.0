package com.nexus.backend.service;

import java.util.Optional;

/**
 * Outcome of one Copilot call: either an answer, or the reason there is none.
 *
 * <p>This replaces a bare {@code Optional<String>}, which made every failure
 * indistinguishable — the Copilot was "unavailable" whether the key was
 * missing, rejected or rate limited.
 *
 * @param text    the model's answer, or null when {@link #failure()} is set
 * @param failure why there is no answer, or null when {@code text} is set
 */
public record LlmResult(String text, LlmFailure failure) {

    public static LlmResult answered(String text) {
        return new LlmResult(text, null);
    }

    public static LlmResult failed(LlmFailure failure) {
        return new LlmResult(null, failure);
    }

    public boolean answered() {
        return text != null;
    }

    /** The answer, for call sites that only care whether one is present. */
    public Optional<String> textOrEmpty() {
        return Optional.ofNullable(text);
    }
}