package com.nexus.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Supabase Auth integration settings. All optional: when {@code url} and one of
 * the credential properties are absent, Supabase authentication is disabled and
 * the built-in email/password flow is used exclusively.
 */
@ConfigurationProperties(prefix = "nexus.supabase")
public class SupabaseProperties {

    /** e.g. https://abcdefgh.supabase.co — the project URL (no trailing slash). */
    private String url;

    /**
     * JWT signing secret (Settings → API → JWT Secret). Verifies HS256 tokens.
     * Preferred for simple deployments.
     */
    private String jwtSecret;

    /**
     * When true, tokens are verified against the project's JWKS endpoint
     * ({url}/auth/v1/.well-known/jwks.json) instead of the shared secret.
     * Required for asymmetric keys (RS256/ES256) — the modern default for
     * new Supabase projects.
     */
    private boolean useJwks = false;

    /** Audience claim expected in Supabase tokens ("authenticated" for logins). */
    private String audience = "authenticated";

    /** Issuer claim expected in Supabase tokens; derived from {@link #url} when blank. */
    private String issuer;

    /**
     * Service-role (admin) API key. Server-side only: it can create users and
     * set any password, so it must never reach the browser, the repository or a
     * frontend build. Required for admin-provisioned accounts and admin-issued
     * password resets, because Supabase — not this app — stores those passwords.
     */
    private String serviceRoleKey;

    /** True when the Admin API can be used: needs a URL and the service-role key. */
    public boolean isAdminApiAvailable() {
        return url != null && !url.isBlank() && serviceRoleKey != null && !serviceRoleKey.isBlank();
    }

    public boolean isEnabled() {
        return url != null && !url.isBlank() && (useJwks || (jwtSecret != null && !jwtSecret.isBlank()));
    }

    public String issuerOrDerived() {
        if (issuer != null && !issuer.isBlank()) {
            return issuer;
        }
        return url == null ? null : url + "/auth/v1";
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

    public boolean isUseJwks() { return useJwks; }
    public void setUseJwks(boolean useJwks) { this.useJwks = useJwks; }

    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }

    public String getServiceRoleKey() { return serviceRoleKey; }
    public void setServiceRoleKey(String serviceRoleKey) { this.serviceRoleKey = serviceRoleKey; }
}
