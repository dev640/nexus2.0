package com.nexus.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Optional language-model client.
 *
 * <p>Off unless {@code NEXUS_LLM_API_KEY} <em>and</em> {@code NEXUS_LLM_MODEL}
 * are both set. There is deliberately no default model: guessing an id is how
 * a deployment ends up calling a model that does not exist, failing on every
 * single request while presenting itself to the user as merely "unavailable".
 * A missing model is now a named, reportable condition instead.
 *
 * <p>Failures are classified into {@link LlmFailure} rather than collapsed into
 * an empty result, so the Copilot can tell an operator which setting is wrong.
 * The vendor's own error body is logged server-side and never returned.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmProvider provider;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final RestClient restClient;

    public LlmClient(
        @Value("${nexus.llm.provider:}") String provider,
        @Value("${nexus.llm.api-key:}") String apiKey,
        @Value("${nexus.llm.base-url:}") String baseUrl,
        @Value("${nexus.llm.model:}") String model
    ) {
        this.provider = LlmProvider.resolve(provider, baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
            ? this.provider.defaultBaseUrl()
            : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.restClient = buildRestClient();
    }

    /** True only when a request can actually be made. */
    public boolean isConfigured() {
        return configurationFailure() == null;
    }

    /**
     * Why the Copilot cannot run, or null when it can. Callers use this to
     * report a specific cause instead of a generic "unavailable".
     */
    public LlmFailure configurationFailure() {
        if (apiKey.isEmpty()) {
            return LlmFailure.NOT_CONFIGURED;
        }
        if (model.isEmpty()) {
            return LlmFailure.MODEL_NOT_CONFIGURED;
        }
        return null;
    }

    /** The provider actually in use, normalised. */
    public String provider() {
        return provider.id();
    }

    public LlmProvider providerType() {
        return provider;
    }

    /**
     * The endpoint in use, never including the key. Safe to log. A
     * not-yet-configured model shows as a placeholder rather than an empty path
     * segment.
     */
    public String endpoint() {
        return provider.endpoint(baseUrl, model.isEmpty() ? "<no model configured>" : model);
    }

    /** Ask the model. Never throws: failures come back as a classified result. */
    public LlmResult complete(String systemPrompt, String question) {
        LlmFailure configuration = configurationFailure();
        if (configuration != null) {
            log.debug("Copilot not called ({}): {}", provider.id(), configuration);
            return LlmResult.failed(configuration);
        }

        try {
            String body = provider == LlmProvider.GEMINI
                ? buildGeminiBody(systemPrompt, question)
                : buildOpenAiBody(systemPrompt, question);
            String response = post(body);
            if (response == null) {
                return LlmResult.failed(LlmFailure.MALFORMED_RESPONSE);
            }
            return extractText(response)
                .map(LlmResult::answered)
                .orElseGet(() -> LlmResult.failed(LlmFailure.MALFORMED_RESPONSE));
        } catch (Exception e) {
            LlmFailure failure = classify(e);
            // The vendor's body can echo the request, so it is logged, never returned.
            log.warn("Copilot request failed ({} {}): {}", provider.id(), endpoint(), failure, e);
            return LlmResult.failed(failure);
        }
    }

    private RestClient buildRestClient() {
        // Without explicit timeouts a hung upstream holds the request thread
        // indefinitely, so a slow provider becomes an unbounded resource leak.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        RestClient.Builder builder = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Content-Type", "application/json");

        switch (provider.auth()) {
            case BEARER -> builder.defaultHeader("Authorization", "Bearer " + apiKey);
            case API_KEY_HEADER -> builder.defaultHeader("x-goog-api-key", apiKey);
        }
        return builder.build();
    }

    private String post(String body) {
        var request = restClient.post();
        if (provider == LlmProvider.GEMINI) {
            // Passed as a pre-built URI so the colon in ":generateContent"
            // survives instead of being percent-encoded by URI template expansion.
            request.uri(URI.create(endpoint()));
        } else {
            request.uri(provider.completionPath());
        }
        return request.body(body).retrieve().body(String.class);
    }

    /**
     * Maps an exception onto the taxonomy. Only the HTTP status and a couple of
     * coarse signals are used; nothing from the vendor body reaches the client.
     */
    static LlmFailure classify(Exception e) {
        if (e instanceof HttpStatusCodeException http) {
            String body = http.getResponseBodyAsString() == null
                ? ""
                : http.getResponseBodyAsString().toLowerCase(Locale.ROOT);
            return switch (http.getStatusCode().value()) {
                case 400 -> LlmFailure.BAD_REQUEST;
                case 401, 403 -> LlmFailure.INVALID_API_KEY;
                case 404 -> LlmFailure.MODEL_NOT_FOUND;
                // Providers overload 429 for both "slow down" and "out of credit";
                // only the body separates them, and it is inspected here solely
                // to pick a label.
                case 429 -> body.contains("quota") || body.contains("billing")
                    ? LlmFailure.QUOTA_EXCEEDED
                    : LlmFailure.RATE_LIMITED;
                default -> LlmFailure.UNKNOWN;
            };
        }
        if (e instanceof ResourceAccessException) {
            Throwable cause = e.getCause();
            return cause instanceof SocketTimeoutException || cause instanceof InterruptedIOException
                ? LlmFailure.TIMEOUT
                : LlmFailure.NETWORK;
        }
        if (e instanceof JsonProcessingException) {
            return LlmFailure.MALFORMED_RESPONSE;
        }
        return LlmFailure.UNKNOWN;
    }

    // ---- Wire formats, separated from the HTTP call so they can be unit-tested ----

    String buildOpenAiBody(String systemPrompt, String question) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", question)
                ),
                "temperature", 0.2
        ));
    }

    String buildGeminiBody(String systemPrompt, String question) throws Exception {
        // Gemini takes the system prompt as a separate systemInstruction object and a
        // single user content entry, not a chat-style messages array.
        return objectMapper.writeValueAsString(Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", question)))
                ),
                "generationConfig", Map.of("temperature", 0.2)
        ));
    }

    Optional<String> extractText(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode content = provider == LlmProvider.GEMINI
                ? root.path("candidates").path(0).path("content").path("parts").path(0).path("text")
                : root.path("choices").path(0).path("message").path("content");
        return content.isMissingNode() || content.asText().isBlank()
                ? Optional.empty()
                : Optional.of(content.asText().trim());
    }
}