package com.nexus.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side client for Supabase's Auth Admin API.
 *
 * <p>Supabase — not this application — stores the password for any Supabase-linked
 * account, so admins provisioning users or issuing a password reset have to go
 * through this API rather than the local {@code users.password} column.
 *
 * <p>The admin key it uses is a full-access credential, and it is read only from
 * server configuration — never returned to a client, never logged. Both key
 * generations are accepted; {@link #authHeaders(String)} documents how the
 * header shape differs between them.
 */
@Component
public class SupabaseAdminClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAdminClient.class);

    private final SupabaseProperties properties;
    // Instantiated directly rather than injected: the app has no Jackson
    // ObjectMapper bean (Spring Boot 4 autoconfigures Jackson 3, while this
    // codebase uses Jackson 2 explicitly). Same pattern as SupabaseTokenVerifier.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public SupabaseAdminClient(SupabaseProperties properties) {
        this.properties = properties;
    }

    /** True when admin provisioning and password resets can be carried out. */
    public boolean isAvailable() {
        return properties.isAdminApiAvailable();
    }

    /**
     * Creates a confirmed Supabase auth user and returns its UUID.
     *
     * <p>The account is created pre-confirmed: the person receives their password
     * from an admin out of band, so there is no confirmation email to wait on.
     *
     * @throws ValidationException if Supabase rejects the request (e.g. the
     *         address is already registered) or the admin API is not configured
     */
    public UUID createUser(String email, String password, String fullName) {
        requireAvailable();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("email_confirm", true);
        if (fullName != null && !fullName.isBlank()) {
            body.put("user_metadata", Map.of("full_name", fullName.trim()));
        }

        JsonNode response = send("POST", "/admin/users", body);
        String id = response.path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new ValidationException("Supabase did not return a user id");
        }
        log.info("Created Supabase auth user for {}", email);
        return UUID.fromString(id);
    }

    /**
     * Sets a new password for an existing Supabase auth user.
     *
     * @throws ValidationException if Supabase rejects the request or the admin
     *         API is not configured
     */
    public void updatePassword(UUID supabaseUserId, String password) {
        requireAvailable();
        send("PUT", "/admin/users/" + supabaseUserId, Map.of("password", password));
        log.info("Reset the password for Supabase auth user {}", supabaseUserId);
    }

    /** True when a Supabase auth user exists for this email (used before resetting). */
    public boolean userExists(String email) {
        // Retained for callers that need to probe an address before provisioning.
        if (!isAvailable()) {
            return false;
        }
        try {
            JsonNode response = send("GET", "/admin/users?page=1&per_page=200", null);
            for (JsonNode user : response.path("users")) {
                if (email.equalsIgnoreCase(user.path("email").asText(null))) {
                    return true;
                }
            }
            return false;
        } catch (ValidationException e) {
            // A failed lookup must not block provisioning; the local row is the
            // authority for whether the account exists.
            log.warn("Supabase user lookup failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Signs a Supabase user out everywhere, so the previous password cannot keep
     * working on a device that had cached its session.
     *
     * <p>Best effort by design: the password has already been replaced by the
     * time this runs, so a failure here must not fail the whole reset. It is
     * logged and the reset still succeeds.
     */
    public void revokeSessions(UUID supabaseUserId) {
        if (!isAvailable()) {
            return;
        }
        try {
            send("DELETE", "/admin/users/" + supabaseUserId + "/logout", null);
            log.info("Revoked active sessions for Supabase auth user {}", supabaseUserId);
        } catch (ValidationException e) {
            log.warn("Could not revoke sessions for {}: {}", supabaseUserId, e.getMessage());
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new ValidationException(
                "Supabase admin API is not configured. Set NEXUS_SUPABASE_SERVICE_ROLE_KEY "
                    + "on the server to create users or issue password resets."
            );
        }
    }

    /**
     * The headers Supabase's gateway requires for an admin call.
     *
     * <p>The two key generations are not sent the same way, and getting this wrong
     * fails every admin call with a bare 401:
     *
     * <ul>
     *   <li>A legacy {@code service_role} JWT needs <em>both</em> headers. Measured
     *       against the live project: {@code apikey} alone answers 401, {@code
     *       Authorization} alone answers 401, and only the pair answers 200.</li>
     *   <li>A newer {@code sb_secret_…} key is not a JWT. It belongs in {@code apikey};
     *       sending it as a Bearer token makes the gateway try to parse it as a JWT and
     *       reject it.</li>
     * </ul>
     *
     * <p>So the Bearer header is added only for a JWT-shaped key. That is deliberate:
     * it is what lets a rotation from the exposed legacy key to a secret key be a
     * variable change rather than a code change.
     *
     * <p>Package-private rather than private so the decision is unit-tested without a
     * network call.
     */
    static Map<String, String> authHeaders(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("apikey", key);
        if (isJwt(key)) {
            headers.put("Authorization", "Bearer " + key);
        }
        return headers;
    }

    /**
     * True for the compact JWS form ({@code header.payload.signature}) — which is what a
     * legacy Supabase key and a signed-in user's access token both are. The {@code eyJ}
     * prefix is base64url for the start of a JSON header, so an opaque secret that merely
     * happens to contain two dots is not mistaken for a token and sent as one.
     */
    static boolean isJwt(String key) {
        if (key == null || !key.startsWith("eyJ")) {
            return false;
        }
        int firstDot = key.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        int secondDot = key.indexOf('.', firstDot + 1);
        if (secondDot <= firstDot + 1 || secondDot >= key.length() - 1) {
            return false;
        }
        // Exactly three segments: a JWTs payload and signature are both non-empty, and a
        // fourth dot means this is not the compact JWS form at all.
        return key.indexOf('.', secondDot + 1) < 0;
    }

    private JsonNode send(String method, String path, Map<String, Object> body) {
        String url = properties.getUrl() + "/auth/v1" + path;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15));
            authHeaders(properties.getServiceRoleKey()).forEach(builder::header);

            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            }

            HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                throw new ValidationException(describeFailure(response));
            }
            String payload = response.body();
            return payload == null || payload.isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(payload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ValidationException("Interrupted while calling the Supabase admin API");
        } catch (IOException e) {
            throw new ValidationException("Could not reach the Supabase admin API: " + e.getMessage());
        }
    }

    /**
     * Turns a Supabase error body into a message safe to show an admin. The raw
     * body can contain the offending email or internal identifiers, so only the
     * documented message/code fields are surfaced.
     */
    private String describeFailure(HttpResponse<String> response) {
        String fallback = "Supabase admin API returned HTTP " + response.statusCode();
        try {
            JsonNode node = objectMapper.readTree(response.body());
            String message = node.path("msg").asText(null);
            String code = node.path("error_code").asText(null);
            if (message != null && !message.isBlank()) {
                return message + (code != null && !code.isBlank() ? " (" + code + ")" : "");
            }
        } catch (Exception ignored) {
            // Fall through to the generic message.
        }
        return fallback;
    }
}