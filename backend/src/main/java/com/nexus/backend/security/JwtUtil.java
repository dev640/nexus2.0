package com.nexus.backend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    /**
     * HMAC signing secret. There is deliberately no default: a shipped fallback
     * would let anyone who reads the repository mint tokens for a deployment that
     * forgot to set one. Startup fails fast when it is missing or too short.
     */
    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.expiration:86400000}") // 24 hours in milliseconds
    private Long expiration;

    @Value("${jwt.refresh-expiration:604800000}") // 7 days in milliseconds
    private Long refreshExpiration;

    private SecretKey getSigningKey() {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                "jwt.secret (NEXUS_JWT_SECRET) is not set or is shorter than 32 bytes. "
                    + "Refusing to sign or verify tokens with an unset or weak secret.");
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * Fail at startup rather than on the first login, so a misconfigured deployment
     * is caught by health checks instead of by users.
     */
    @jakarta.annotation.PostConstruct
    void requireSecret() {
        getSigningKey();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    public String generateToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_ACCESS);
        return createToken(claims, username, expiration);
    }

    public String generateRefreshToken(String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, TYPE_REFRESH);
        return createToken(claims, username, refreshExpiration);
    }

    private String createToken(Map<String, Object> claims, String subject, Long expirationTime) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Valid token for normal API authentication.
     * Refresh tokens are rejected: they may only be exchanged for a new access token.
     */
    public Boolean validateToken(String token, String username) {
        final String extractedUsername = extractUsername(token);
        if (!extractedUsername.equals(username) || isTokenExpired(token)) {
            return false;
        }
        String type = extractClaim(token, claims -> claims.get(CLAIM_TYPE, String.class));
        // Tokens issued before the type claim existed are treated as access tokens.
        return type == null || TYPE_ACCESS.equals(type);
    }

    /** True when the token is a well-formed, unexpired refresh token. */
    public boolean isRefreshToken(String token) {
        String type = extractClaim(token, claims -> claims.get(CLAIM_TYPE, String.class));
        return TYPE_REFRESH.equals(type) && !isTokenExpired(token);
    }
}
