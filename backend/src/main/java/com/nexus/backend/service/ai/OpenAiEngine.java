package com.nexus.backend.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.backend.service.ai.AiEngineException.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server-side OpenAI client (chat completions + embeddings) using the current
 * recommended REST API: streaming chat completions and the /embeddings endpoint.
 *
 * The API key is read from the environment on the server only and is never
 * logged, never returned to callers and never leaves this class. The model is
 * configurable so it can be changed without touching application code.
 */
@Component
public class OpenAiEngine implements AiEngine {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEngine.class);

    /** Embeddings are batched to bound request and payload size. */
    private static final int EMBED_BATCH = 16;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String embeddingModel;
    private final Duration requestTimeout;

    public OpenAiEngine(
            @Value("${nexus.ai.openai.api-key:}") String apiKey,
            @Value("${nexus.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${nexus.ai.openai.model:gpt-4o-mini}") String model,
            @Value("${nexus.ai.openai.embedding-model:text-embedding-3-small}") String embeddingModel,
            @Value("${nexus.ai.openai.timeout-seconds:90}") long timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.model = model;
        this.embeddingModel = embeddingModel;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public String answerModel() {
        return model;
    }

    @Override
    public String embeddingModel() {
        return embeddingModel;
    }

    @Override
    public String streamAnswer(List<Message> messages, Consumer<String> onDelta) {
        requireConfigured();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        payload.put("stream", true);

        HttpResponse<InputStream> response = send("/chat/completions", json(payload), "text/event-stream");

        StringBuilder full = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JsonNode node;
                try {
                    node = mapper.readTree(data);
                } catch (IOException notJson) {
                    continue; // SSE comment or keep-alive line
                }
                String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    full.append(delta);
                    onDelta.accept(delta);
                }
            }
        } catch (IOException e) {
            throw new AiEngineException(Kind.UNAVAILABLE, "failed reading the AI response stream", e);
        }

        if (full.isEmpty()) {
            throw new AiEngineException(Kind.UNAVAILABLE, "the AI returned an empty completion");
        }
        return full.toString();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        requireConfigured();
        List<float[]> vectors = new ArrayList<>(texts.size());

        for (int start = 0; start < texts.size(); start += EMBED_BATCH) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + EMBED_BATCH));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", embeddingModel);
            payload.put("input", batch);

            HttpResponse<InputStream> response = send("/embeddings", json(payload), "application/json");
            try (InputStream in = response.body()) {
                JsonNode root = mapper.readTree(in);
                JsonNode data = root.path("data");
                if (!data.isArray() || data.size() != batch.size()) {
                    throw new AiEngineException(Kind.UNKNOWN, "unexpected embeddings response shape");
                }
                List<JsonNode> items = new ArrayList<>();
                data.forEach(items::add);
                items.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
                for (JsonNode item : items) {
                    vectors.add(toFloatArray(item.path("embedding")));
                }
            } catch (IOException e) {
                throw new AiEngineException(Kind.UNAVAILABLE, "unreadable embeddings response", e);
            }
        }
        return vectors;
    }

    // ---------- internals ----------

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new AiEngineException(Kind.NOT_CONFIGURED, "OPENAI_API_KEY is not set");
        }
    }

    private HttpResponse<InputStream> send(String path, String body, String accept) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(requestTimeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", accept)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();

        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw classify(response.statusCode());
            }
            return response;
        } catch (HttpTimeoutException e) {
            throw new AiEngineException(Kind.TIMEOUT, "AI request timed out", e);
        } catch (IOException e) {
            throw new AiEngineException(Kind.UNAVAILABLE, "AI service unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiEngineException(Kind.UNAVAILABLE, "AI request interrupted", e);
        }
    }

    /**
     * Maps an HTTP status to a classified error. Only the status is kept: the
     * provider's error body may echo the request (and therefore workspace
     * context), so it is deliberately not logged or propagated.
     */
    private AiEngineException classify(int status) {
        String detail = "AI request failed with HTTP " + status;
        if (status == 401 || status == 403) return new AiEngineException(Kind.AUTH, detail);
        if (status == 429) return new AiEngineException(Kind.RATE_LIMIT, detail);
        if (status == 400 || status == 404 || status == 413 || status == 422) {
            return new AiEngineException(Kind.INVALID_REQUEST, detail);
        }
        if (status >= 500) return new AiEngineException(Kind.UNAVAILABLE, detail);
        return new AiEngineException(Kind.UNKNOWN, detail);
    }

    private String json(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AiEngineException(Kind.UNKNOWN, "could not serialize the AI request", e);
        }
    }

    private float[] toFloatArray(JsonNode array) {
        if (!array.isArray()) {
            throw new AiEngineException(Kind.UNKNOWN, "embedding was not an array");
        }
        float[] vector = new float[array.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) array.get(i).asDouble();
        }
        return vector;
    }
}
