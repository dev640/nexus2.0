package com.nexus.backend.service.ai;

import java.util.List;
import java.util.function.Consumer;

/**
 * Server-side abstraction over the configured AI engine. The rest of Nexus
 * talks to this interface only, so swapping or upgrading the provider (OpenAI
 * today) never touches callers.
 *
 * Implementations must never expose the API key to the browser: every call
 * happens in a backend service or controller.
 */
public interface AiEngine {

    /** A chat message in the provider's role/content shape. */
    record Message(String role, String content) {
        public static Message system(String content) { return new Message("system", content); }
        public static Message user(String content) { return new Message("user", content); }
        public static Message assistant(String content) { return new Message("assistant", content); }
    }

    /** True when an API key is configured server-side. */
    boolean isConfigured();

    /** Model used for answers (empty string when not configured). */
    String answerModel();

    /** Model used for embeddings. */
    String embeddingModel();

    /**
     * Streams an answer token by token through {@code onDelta} and returns the
     * full accumulated text.
     *
     * @throws AiEngineException on timeout, rate limit, auth or provider errors
     */
    String streamAnswer(List<Message> messages, Consumer<String> onDelta);

    /**
     * Embeds a batch of texts. Order of the returned vectors matches the input.
     *
     * @throws AiEngineException when embeddings are unavailable
     */
    List<float[]> embed(List<String> texts);
}
