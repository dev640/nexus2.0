package com.nexus.backend.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.backend.config.SupabaseProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Verifies Supabase access tokens (the JWT stored by supabase-js in the
 * browser). Supports both signing schemes a Supabase project can use:
 *
 * - HS256 with the project's JWT secret (legacy default): set
 *   NEXUS_SUPABASE_JWT_SECRET.
 * - RS256/ES256 verified through the project's JWKS endpoint (current
 *   default for new projects): set NEXUS_SUPABASE_USE_JWKS=true.
 *
 * The JWKS is cached for an hour and refetched when a key id is unknown, so
 * key rotations do not require a restart.
 */
@Component
public class SupabaseTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(SupabaseTokenVerifier.class);
    private static final Duration JWKS_CACHE = Duration.ofHours(1);

    private final SupabaseProperties properties;
    // Instantiated directly rather than injected: the app has no Jackson
    // ObjectMapper bean (Spring Boot 4 autoconfigures Jackson 3, while this
    // codebase uses Jackson 2 explicitly). Same pattern as LlmClient and the
    // whiteboard classes.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private volatile Map<String, PublicKey> jwksKeys;
    private volatile Instant jwksFetchedAt;
    private final ReentrantLock jwksLock = new ReentrantLock();

    public SupabaseTokenVerifier(SupabaseProperties properties) {
        this.properties = properties;
    }

    /**
     * Parses and fully validates a Supabase token, or throws {@link JwtException}.
     * Returns the token claims on success.
     */
    public Claims verify(String token) {
        if (!properties.isEnabled()) {
            throw new JwtException("Supabase authentication is not configured");
        }
        String issuer = properties.issuerOrDerived();

        Claims claims = properties.isUseJwks()
            ? parseWithJwks(token)
            : parseWithSecret(token);

        if (issuer != null && !issuer.equals(claims.getIssuer())) {
            throw new JwtException("Unexpected issuer in Supabase token: " + claims.getIssuer());
        }
        if (claims.getExpiration() == null || claims.getExpiration().before(new java.util.Date())) {
            throw new JwtException("Supabase token has expired");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new JwtException("Supabase token has no subject");
        }
        return claims;
    }

    private Claims parseWithSecret(String token) {
        SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
            properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    private Claims parseWithJwks(String token) {
        // Unknown kid → refetch the cached JWKS once (Supabase rotates keys).
        JwtException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, PublicKey> keys = currentJwks(attempt > 0);
            String kid = headerKid(token);
            PublicKey key = kid != null ? keys.get(kid) : singleKey(keys);
            if (key == null) {
                lastError = new JwtException("No matching JWKS key for token kid=" + kid);
                continue;
            }
            try {
                return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            } catch (JwtException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new JwtException("Supabase token could not be verified");
    }

    private PublicKey singleKey(Map<String, PublicKey> keys) {
        return keys.size() == 1 ? keys.values().iterator().next() : null;
    }

    /** Extracts the "kid" header without verifying (it selects the verification key). */
    private String headerKid(String token) {
        try {
            int firstDot = token.indexOf('.');
            if (firstDot < 0) return null;
            byte[] headerBytes = Base64.getUrlDecoder().decode(token.substring(0, firstDot));
            String header = new String(headerBytes, StandardCharsets.UTF_8);
            JsonNode headerJson = objectMapper.readTree(header);
            JsonNode kid = headerJson.get("kid");
            return kid != null && kid.isTextual() ? kid.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, PublicKey> currentJwks(boolean forceRefresh) {
        Map<String, PublicKey> cached = jwksKeys;
        Instant fetched = jwksFetchedAt;
        if (!forceRefresh && cached != null && fetched != null
            && fetched.isAfter(Instant.now().minus(JWKS_CACHE))) {
            return cached;
        }
        jwksLock.lock();
        try {
            if (!forceRefresh && jwksKeys != null && jwksFetchedAt != null
                && jwksFetchedAt.isAfter(Instant.now().minus(JWKS_CACHE))) {
                return jwksKeys;
            }
            String url = properties.getUrl() + "/auth/v1/.well-known/jwks.json";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new JwtException("JWKS endpoint returned HTTP " + response.statusCode());
            }
            Map<String, PublicKey> parsed = parseJwks(response.body());
            if (parsed.isEmpty()) {
                throw new JwtException("Supabase JWKS contains no usable asymmetric keys");
            }
            jwksKeys = parsed;
            jwksFetchedAt = Instant.now();
            log.info("Loaded {} asymmetric key(s) from the Supabase JWKS", parsed.size());
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JwtException("Interrupted while fetching Supabase JWKS", e);
        } catch (JwtException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtException("Failed to fetch Supabase JWKS: " + e.getMessage(), e);
        } finally {
            jwksLock.unlock();
        }
    }

    /**
     * Parses a JWKS document into kid → PublicKey entries. Symmetric (oct) keys
     * are skipped: HS256 verification uses the configured secret directly.
     */
    Map<String, PublicKey> parseJwks(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode keys = root.get("keys");
        Map<String, PublicKey> result = new HashMap<>();
        if (keys == null || !keys.isArray()) {
            return result;
        }
        for (JsonNode jwk : keys) {
            String kty = jwk.path("kty").asText("");
            String kid = jwk.path("kid").asText(null);
            try {
                PublicKey key = "RSA".equals(kty) ? rsaKey(jwk)
                    : "EC".equals(kty) ? ecKey(jwk)
                    : null;
                if (key != null) {
                    result.put(kid != null ? kid : key.getAlgorithm(), key);
                }
            } catch (Exception e) {
                log.warn("Skipping unusable JWKS entry (kty={}, kid={}): {}", kty, kid, e.getMessage());
            }
        }
        return result;
    }

    private PublicKey rsaKey(JsonNode jwk) throws Exception {
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private PublicKey ecKey(JsonNode jwk) throws Exception {
        String crv = jwk.path("crv").asText("");
        String stdName = switch (crv) {
            case "P-256" -> "secp256r1";
            case "P-384" -> "secp384r1";
            case "P-521" -> "secp521r1";
            default -> throw new IllegalArgumentException("Unsupported EC curve: " + crv);
        };
        BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("x").asText()));
        BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("y").asText()));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec(stdName));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }
}
