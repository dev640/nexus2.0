package com.nexus.backend.service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider selection, the required-model contract, and the failure taxonomy.
 *
 * <p>The two wire formats differ in both request and response shape, so these
 * tests pin the exact JSON we send and parse. The failure tests pin the
 * property that matters most: whatever the provider throws, what reaches the
 * browser is a fixed string and never the vendor's own error body.
 */
class LlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmClient client(String provider, String baseUrl, String model) {
        return new LlmClient(provider, "test-key", baseUrl, model);
    }

    // ---------- configuration ----------

    @Test
    void disabledWithoutApiKey() {
        assertThat(new LlmClient("", "", "", "gpt-4o-mini").isConfigured()).isFalse();
        assertThat(new LlmClient("gemini", "", "", "gemini-2.5-flash").isConfigured()).isFalse();
    }

    /**
     * The regression this whole change exists for: a key without a model used to
     * fall back to a hardcoded id, and a stale id fails on every request while
     * looking like a transient outage.
     */
    @Test
    void apiKeyWithoutModelIsNotConfiguredAndSaysSo() {
        LlmClient client = client("gemini", "", "");

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.configurationFailure()).isEqualTo(LlmFailure.MODEL_NOT_CONFIGURED);
    }

    @Test
    void missingApiKeyIsReportedSeparatelyFromAMissingModel() {
        assertThat(new LlmClient("", "", "", "").configurationFailure())
            .isEqualTo(LlmFailure.NOT_CONFIGURED);
    }

    @Test
    void requiresBothKeyAndModel() {
        assertThat(client("gemini", "", "gemini-2.5-flash").isConfigured()).isTrue();
        assertThat(client("gemini", "", "gemini-2.5-flash").configurationFailure()).isNull();
    }

    @Test
    void blankModelIsTreatedAsAbsent() {
        assertThat(client("openai", "", "   ").isConfigured()).isFalse();
    }

    // ---------- provider resolution ----------

    @Test
    void appliesProviderDefaultBaseUrl() {
        assertThat(client("", "", "gpt-4o-mini").endpoint())
            .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(client("gemini", "", "gemini-2.5-flash").endpoint())
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
    }

    @Test
    void infersGeminiFromBaseUrlWhenProviderOmitted() {
        assertThat(client("", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash").provider())
            .isEqualTo("gemini");
    }

    @Test
    void unknownProviderFallsBackToOpenAi() {
        assertThat(client("anthropic", "", "gpt-4o-mini").provider()).isEqualTo("openai");
    }

    @Test
    void providerIsCaseInsensitiveAndPadded() {
        assertThat(client("  GEMINI ", "", "gemini-2.5-flash").provider()).isEqualTo("gemini");
    }

    @Test
    void explicitModelIsUsedVerbatim() {
        assertThat(client("gemini", "", "gemini-3.1-pro-preview").endpoint())
            .contains("gemini-3.1-pro-preview");
    }

    @Test
    void endpointsNeverContainTheApiKey() {
        for (LlmProvider provider : LlmProvider.values()) {
            assertThat(provider.endpoint(provider.defaultBaseUrl(), "some-model"))
                .as("%s endpoint", provider.id())
                .doesNotContain("test-key");
        }
        assertThat(client("gemini", "", "gemini-2.5-flash").endpoint()).doesNotContain("test-key");
    }

    @Test
    void endpointNamesTheMissingModelRatherThanEmittingAnEmptyPathSegment() {
        assertThat(client("gemini", "", "").endpoint())
            .contains("<no model configured>");
    }

    /**
     * Pins the documented example model per provider. Model ids go stale, and
     * this makes updating one a deliberate edit rather than a silent drift.
     */
    @Test
    void documentedExampleModelsAreCurrentAndNotTheRemovedIds() {
        assertThat(LlmProvider.GEMINI.exampleModel()).isEqualTo("gemini-2.5-flash");
        assertThat(LlmProvider.OPENAI.exampleModel()).isEqualTo("gpt-4o-mini");
        for (LlmProvider provider : LlmProvider.values()) {
            assertThat(provider.exampleModel()).as("%s example model", provider.id()).isNotBlank();
        }
    }

    @Test
    void everyProviderResolvesToItselfAndHasACompleteDefinition() {
        for (LlmProvider provider : LlmProvider.values()) {
            assertThat(LlmProvider.resolve(provider.id(), "")).isEqualTo(provider);
            assertThat(provider.defaultBaseUrl()).startsWith("https://");
            assertThat(provider.auth()).isNotNull();
            // Exactly one of "model in path" and "model in body" must hold.
            assertThat(provider.completionPath() == null)
                .as("%s model placement", provider.id())
                .isEqualTo(provider == LlmProvider.GEMINI);
        }
    }

    // ---------- wire formats ----------

    @Test
    void buildsOpenAiChatCompletionBody() throws Exception {
        JsonNode body = MAPPER.readTree(client("openai", "", "gpt-4o-mini").buildOpenAiBody("FACTS: 7 tasks", "What blocks?"));

        assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("FACTS: 7 tasks");
        assertThat(body.path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("What blocks?");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        // OpenAI has no systemInstruction field.
        assertThat(body.has("systemInstruction")).isFalse();
    }

    @Test
    void buildsGeminiGenerateContentBody() throws Exception {
        JsonNode body = MAPPER.readTree(client("gemini", "", "gemini-2.5-flash").buildGeminiBody("FACTS: 7 tasks", "What blocks?"));

        assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asText())
                .isEqualTo("FACTS: 7 tasks");
        assertThat(body.path("contents")).hasSize(1);
        assertThat(body.path("contents").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("contents").path(0).path("parts").path(0).path("text").asText())
                .isEqualTo("What blocks?");
        assertThat(body.path("generationConfig").path("temperature").asDouble()).isEqualTo(0.2);
        // Gemini carries the model in the URL, not the body, and has no messages array.
        assertThat(body.has("model")).isFalse();
        assertThat(body.has("messages")).isFalse();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    void extractsTextFromOpenAiResponse() throws Exception {
        String json = """
            {"choices":[{"message":{"role":"assistant","content":"  Achal is overloaded.  "}}]}""";
        assertThat(client("openai", "", "gpt-4o-mini").extractText(json)).contains("Achal is overloaded.");
    }

    @Test
    void extractsTextFromGeminiResponse() throws Exception {
        String json = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":" Sprint 8 is 19% complete."}]},
             "finishReason":"STOP"}]}""";
        assertThat(client("gemini", "", "gemini-2.5-flash").extractText(json)).contains("Sprint 8 is 19% complete.");
    }

    @Test
    void returnsEmptyForBlankOrMissingText() throws Exception {
        assertThat(client("gemini", "", "gemini-2.5-flash").extractText("""
            {"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}""")).isEmpty();
        assertThat(client("openai", "", "gpt-4o-mini").extractText("""
            {"choices":[{"message":{"content":"   "}}]}""")).isEmpty();
        assertThat(client("openai", "", "gpt-4o-mini").extractText("{}")).isEmpty();
    }

    // ---------- results ----------

    @Test
    void unconfiguredClientReturnsTheNamedFailureAndMakesNoRequest() {
        LlmResult result = new LlmClient("gemini", "", "", "").complete("facts", "question");

        assertThat(result.answered()).isFalse();
        assertThat(result.failure()).isEqualTo(LlmFailure.NOT_CONFIGURED);
        assertThat(result.text()).isNull();
        assertThat(result.textOrEmpty()).isEmpty();
    }

    @Test
    void missingModelSurfacesAsModelNotConfiguredRatherThanAnHttpError() {
        LlmResult result = client("openai", "", "").complete("facts", "question");

        assertThat(result.failure()).isEqualTo(LlmFailure.MODEL_NOT_CONFIGURED);
        assertThat(result.failure().safeReason()).contains("model");
    }

    @Test
    void answeredResultCarriesNoFailure() {
        LlmResult result = LlmResult.answered("Sprint 8 is on track.");

        assertThat(result.answered()).isTrue();
        assertThat(result.failure()).isNull();
        assertThat(result.textOrEmpty()).contains("Sprint 8 is on track.");
    }

    // ---------- failure taxonomy ----------

    @Test
    void classifiesAuthenticationFailures() {
        assertThat(LlmClient.classify(new HttpClientErrorException(HttpStatus.UNAUTHORIZED)))
            .isEqualTo(LlmFailure.INVALID_API_KEY);
        assertThat(LlmClient.classify(new HttpClientErrorException(HttpStatus.FORBIDDEN)))
            .isEqualTo(LlmFailure.INVALID_API_KEY);
    }

    @Test
    void classifiesAnUnknownModelByStatus() {
        assertThat(LlmClient.classify(new HttpClientErrorException(HttpStatus.NOT_FOUND)))
            .isEqualTo(LlmFailure.MODEL_NOT_FOUND);
    }

    /**
     * 429 is overloaded by both providers for "slow down" and "you are out of
     * credit". Conflating them tells an operator to wait when they actually need
     * to add billing.
     */
    @Test
    void separatesRateLimitingFromAnExhaustedQuota() {
        assertThat(LlmClient.classify(status429("rate limit exceeded")))
            .isEqualTo(LlmFailure.RATE_LIMITED);
        assertThat(LlmClient.classify(status429("You exceeded your current quota, please check your plan and billing details")))
            .isEqualTo(LlmFailure.QUOTA_EXCEEDED);
    }

    @Test
    void classifiesBadRequestsAndServerErrors() {
        assertThat(LlmClient.classify(new HttpClientErrorException(HttpStatus.BAD_REQUEST)))
            .isEqualTo(LlmFailure.BAD_REQUEST);
        assertThat(LlmClient.classify(new HttpServerErrorException(HttpStatus.BAD_GATEWAY)))
            .isEqualTo(LlmFailure.UNKNOWN);
        assertThat(LlmClient.classify(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
            .isEqualTo(LlmFailure.UNKNOWN);
    }

    @Test
    void classifiesTimeoutsSeparatelyFromOtherNetworkFailures() {
        assertThat(LlmClient.classify(new ResourceAccessException("read timed out", new SocketTimeoutException("read timed out"))))
            .isEqualTo(LlmFailure.TIMEOUT);
        assertThat(LlmClient.classify(new ResourceAccessException("connection refused", new java.net.ConnectException())))
            .isEqualTo(LlmFailure.NETWORK);
    }

    @Test
    void classifiesUnreadableBodiesAndAnythingElse() {
        assertThat(LlmClient.classify(new JsonParseException("unexpected end", (com.fasterxml.jackson.core.JsonLocation) null)))
            .isEqualTo(LlmFailure.MALFORMED_RESPONSE);
        assertThat(LlmClient.classify(new IllegalStateException("boom")))
            .isEqualTo(LlmFailure.UNKNOWN);
    }

    /**
     * The property that makes the taxonomy safe to return: no reason string is
     * built from vendor output, so an upstream that echoes the key or returns
     * HTML cannot leak through to a browser.
     */
    @Test
    void noSafeReasonContainsVendorTextOrInternalDetail() {
        for (LlmFailure failure : LlmFailure.values()) {
            assertThat(failure.safeReason()).as("%s reason", failure).isNotBlank();
            assertThat(failure.safeReason()).doesNotContain("sk-");
            assertThat(failure.safeReason()).doesNotContain("java.lang");
            assertThat(failure.safeReason()).doesNotContain("<html>");
            assertThat(failure.safeReason()).doesNotContain("com.nexus");
        }
    }

    /**
     * The whole point of the taxonomy: the conditions an operator must tell
     * apart have to read differently in the UI.
     */
    @Test
    void theDiagnosableConditionsHaveDistinctReasons() {
        Set<String> reasons = EnumSet.of(
            LlmFailure.NOT_CONFIGURED,
            LlmFailure.MODEL_NOT_CONFIGURED,
            LlmFailure.INVALID_API_KEY,
            LlmFailure.MODEL_NOT_FOUND,
            LlmFailure.QUOTA_EXCEEDED
        ).stream().map(LlmFailure::safeReason)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(reasons).hasSize(5);
    }

    @Test
    void retryableMarksOnlyTheTransients() {
        assertThat(LlmFailure.RATE_LIMITED.retryable()).isTrue();
        assertThat(LlmFailure.TIMEOUT.retryable()).isTrue();
        assertThat(LlmFailure.NETWORK.retryable()).isTrue();
        assertThat(LlmFailure.MALFORMED_RESPONSE.retryable()).isTrue();
        assertThat(LlmFailure.UNKNOWN.retryable()).isTrue();

        // These need a human to change configuration or billing.
        assertThat(LlmFailure.NOT_CONFIGURED.retryable()).isFalse();
        assertThat(LlmFailure.MODEL_NOT_CONFIGURED.retryable()).isFalse();
        assertThat(LlmFailure.INVALID_API_KEY.retryable()).isFalse();
        assertThat(LlmFailure.MODEL_NOT_FOUND.retryable()).isFalse();
        assertThat(LlmFailure.QUOTA_EXCEEDED.retryable()).isFalse();
    }

    private static HttpClientErrorException status429(String body) {
        return new HttpClientErrorException(
            HttpStatus.TOO_MANY_REQUESTS, "429", HttpHeaders.EMPTY,
            body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** Guards the helper shape: the 429 must actually be a 429. */
    @Test
    void the429HelperBuildsA429() {
        HttpClientErrorException e = status429("rate limit exceeded");
        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(LlmClient.classify(e)).isEqualTo(LlmFailure.RATE_LIMITED);
    }
}