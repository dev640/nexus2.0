package com.nexus.backend.security;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the credential handling of the Supabase Admin API client: which header a key of
 * each generation is sent on, and what happens when no key is configured.
 *
 * <p>These are exactly the two things a key rotation depends on, and neither needs a
 * network call — the header decision is a pure function, and an unconfigured client
 * refuses before it builds a request.
 */
class SupabaseAdminClientTest {

    /**
     * A legacy service-role key. Its header segment is the real
     * {@code {"alg":"HS256","typ":"JWT"}} encoding, which is the shape the client keys off.
     */
    private static final String LEGACY_SERVICE_ROLE_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoic2VydmljZV9yb2xlIn0.c2lnbmF0dXJlLWhlcmU";

    /** A newer-style key: opaque, not a JWT, safe to appear in a test. */
    private static final String NEW_SECRET_KEY = "sb_secret_0123456789abcdefghijklmnopqrstuv";

    @Test
    void legacyServiceRoleKeyIsSentOnBothRequiredHeaders() {
        // Measured against the live project: either header alone answers 401, so both
        // must be present or every admin call fails.
        Map<String, String> headers = SupabaseAdminClient.authHeaders(LEGACY_SERVICE_ROLE_KEY);

        assertThat(headers).containsEntry("apikey", LEGACY_SERVICE_ROLE_KEY);
        assertThat(headers).containsEntry("Authorization", "Bearer " + LEGACY_SERVICE_ROLE_KEY);
    }

    @Test
    void newSecretKeyIsNotSentAsABearerToken() {
        // A non-JWT key in the Authorization header would be parsed as a JWT and rejected,
        // so the newer key type must travel on apikey alone.
        Map<String, String> headers = SupabaseAdminClient.authHeaders(NEW_SECRET_KEY);

        assertThat(headers).containsEntry("apikey", NEW_SECRET_KEY);
        assertThat(headers).doesNotContainKey("Authorization");
    }

    @Test
    void onlyJwtShapedKeysAreTreatedAsTokens() {
        assertThat(SupabaseAdminClient.isJwt(LEGACY_SERVICE_ROLE_KEY)).isTrue();
        assertThat(SupabaseAdminClient.isJwt(NEW_SECRET_KEY)).isFalse();
        // Correct number of segments but not a JSON header — an opaque secret with dots.
        assertThat(SupabaseAdminClient.isJwt("a.b.c")).isFalse();
        assertThat(SupabaseAdminClient.isJwt("eyJhbGciOiJIUzI1NiJ9")).isFalse();
        assertThat(SupabaseAdminClient.isJwt("eyJhbGciOiJIUzI1NiJ9.")).isFalse();
        // Four segments is not the compact JWS form, however JWT-like it starts.
        assertThat(SupabaseAdminClient.isJwt("eyJa.eyJb.eyJc.eyJd")).isFalse();
        assertThat(SupabaseAdminClient.isJwt("")).isFalse();
        assertThat(SupabaseAdminClient.isJwt(null)).isFalse();
    }

    @Test
    void adminApiIsUnavailableWithoutAKey() {
        SupabaseProperties properties = new SupabaseProperties();
        properties.setUrl("https://example.supabase.co");

        SupabaseAdminClient client = new SupabaseAdminClient(properties);

        assertThat(client.isAvailable()).isFalse();
        assertThatThrownBy(() -> client.createUser("someone@example.com", "password", "Someone"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("NEXUS_SUPABASE_SERVICE_ROLE_KEY");
    }

    @Test
    void adminApiIsUnavailableWithoutAUrl() {
        SupabaseProperties properties = new SupabaseProperties();
        properties.setServiceRoleKey(NEW_SECRET_KEY);

        assertThat(new SupabaseAdminClient(properties).isAvailable()).isFalse();
    }

    @Test
    void adminApiIsAvailableWithBothAUrlAndAKey() {
        SupabaseProperties properties = new SupabaseProperties();
        properties.setUrl("https://example.supabase.co");
        properties.setServiceRoleKey(NEW_SECRET_KEY);

        assertThat(new SupabaseAdminClient(properties).isAvailable()).isTrue();
    }

    @Test
    void bestEffortCallsStayQuietWhenUnconfigured() {
        // Session revocation must never fail a password reset that has already succeeded,
        // and a user lookup must not block provisioning — both no-op instead of throwing.
        SupabaseAdminClient client = new SupabaseAdminClient(new SupabaseProperties());

        assertThatCode(() -> client.revokeSessions(UUID.randomUUID())).doesNotThrowAnyException();
        assertThat(client.userExists("someone@example.com")).isFalse();
    }
}
