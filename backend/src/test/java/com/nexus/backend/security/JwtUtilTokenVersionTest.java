package com.nexus.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JwtUtilTokenVersionTest {

    private static final String SECRET = "jwt-token-version-test-secret-0123456789";
    private static final String EMAIL = "ada@example.test";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 60_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 60_000L);
    }

    @Test
    void accessAndRefreshTokensCarryTheAccountVersion() {
        String access = jwtUtil.generateToken(EMAIL, 7);
        String refresh = jwtUtil.generateRefreshToken(EMAIL, 7);

        assertThat(jwtUtil.extractTokenVersion(access)).isEqualTo(7);
        assertThat(jwtUtil.validateToken(access, EMAIL, 7)).isTrue();
        assertThat(jwtUtil.validateToken(access, EMAIL, 6)).isFalse();
        assertThat(jwtUtil.extractTokenVersion(refresh)).isEqualTo(7);
    }

    @Test
    void tokensWithoutAVersionClaimRemainAtInitialVersionZero() {
        long now = System.currentTimeMillis();
        String legacy = Jwts.builder()
            .subject(EMAIL)
            .issuedAt(new Date(now))
            .expiration(new Date(now + 60_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
            .compact();

        assertThat(jwtUtil.extractTokenVersion(legacy)).isZero();
        assertThat(jwtUtil.validateToken(legacy, EMAIL, 0)).isTrue();
    }
}