package com.nexus.backend.service;

import java.util.Locale;

/**
 * The supported language-model providers, and everything that differs between
 * them: base URL, how the key is presented, the request path, and whether the
 * model travels in the URL or the body.
 *
 * <p>This is a registry rather than a pair of {@code if (gemini)} branches
 * because the differences used to be scattered across the client. Adding a
 * provider by hand meant remembering four separate places, and forgetting one
 * produced a client that built Gemini bodies but posted them to OpenAI.
 *
 * <p>{@link #exampleModel()} is documentation, not a default. There is
 * deliberately no fallback model: see {@link LlmClient}.
 */
public enum LlmProvider {

    OPENAI(
        "openai",
        "https://api.openai.com/v1",
        "gpt-4o-mini",
        "/chat/completions",
        Auth.BEARER
    ),
    GEMINI(
        "gemini",
        "https://generativelanguage.googleapis.com/v1beta",
        "gemini-2.5-flash",
        // Gemini carries the model in the path rather than the request body.
        null,
        Auth.API_KEY_HEADER
    );

    /** How the API key is presented. */
    enum Auth {
        /** {@code Authorization: Bearer <key>} — OpenAI and compatible APIs. */
        BEARER,
        /** {@code x-goog-api-key: <key>} — Google's generateContent API. */
        API_KEY_HEADER
    }

    private final String id;
    private final String defaultBaseUrl;
    private final String exampleModel;
    private final String completionPath;
    private final Auth auth;

    LlmProvider(String id, String defaultBaseUrl, String exampleModel, String completionPath, Auth auth) {
        this.id = id;
        this.defaultBaseUrl = defaultBaseUrl;
        this.exampleModel = exampleModel;
        this.completionPath = completionPath;
        this.auth = auth;
    }

    public String id() {
        return id;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** A currently-valid model id for this provider, shown in setup hints only. */
    public String exampleModel() {
        return exampleModel;
    }

    /** Request path, or null for providers that put the model in the URL. */
    public String completionPath() {
        return completionPath;
    }

    Auth auth() {
        return auth;
    }

    /**
     * The provider actually in use.
     *
     * <p>An explicit setting wins; otherwise the base URL is inspected, so
     * pointing only {@code NEXUS_LLM_BASE_URL} at Gemini is enough. Anything
     * unrecognised falls back to OpenAI.
     */
    public static LlmProvider resolve(String configuredProvider, String baseUrl) {
        if (configuredProvider != null && !configuredProvider.isBlank()) {
            String wanted = configuredProvider.trim().toLowerCase(Locale.ROOT);
            for (LlmProvider candidate : values()) {
                if (candidate.id.equals(wanted)) {
                    return candidate;
                }
            }
        }
        return baseUrl != null && baseUrl.contains("generativelanguage.googleapis.com")
            ? GEMINI
            : OPENAI;
    }

    /** Absolute request URL. The key is never part of it, so this is safe to log. */
    public String endpoint(String baseUrl, String model) {
        return completionPath == null
            ? baseUrl + "/models/" + model + ":generateContent"
            : baseUrl + completionPath;
    }
}