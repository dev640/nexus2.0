package com.nexus.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies a defensive set of HTTP security headers to every response.
 *
 * <p>Registered as a component and added to the Spring Security chain by
 * {@link SecurityConfig}. Header values come from configuration so a deployment
 * can tighten them without a code change.
 *
 * <p>Headers are conservative rather than maximal, so they harden the app without
 * breaking the existing SPA in the common deployment shapes. The most opinionated
 * header is Content-Security-Policy: it refuses inline and third-party scripts and
 * blocks framing, while still allowing inline styles because the current frontend
 * uses them in a number of places.
 *
 * <p>HSTS is opt-in via {@code security.headers.hsts-max-age-seconds}. Local and
 * non-TLS environments must leave it at 0 so browsers are not pinned to an HTTPS
 * policy the app cannot honor.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CONTENT_TYPE_OPTIONS = "nosniff";
    private static final String FRAME_OPTIONS = "DENY";
    private static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=()";

    private final long hstsMaxAgeSeconds;
    private final String csp;

    public SecurityHeadersFilter(
            @Value("${security.headers.hsts-max-age-seconds:0}") long hstsMaxAgeSeconds,
            @Value("${security.headers.content-security-policy:default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'}") String csp) {
        this.hstsMaxAgeSeconds = hstsMaxAgeSeconds;
        this.csp = csp;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", CONTENT_TYPE_OPTIONS);
        response.setHeader("X-Frame-Options", FRAME_OPTIONS);
        response.setHeader("Referrer-Policy", REFERRER_POLICY);
        response.setHeader("Permissions-Policy", PERMISSIONS_POLICY);
        response.setHeader("Content-Security-Policy", csp);

        if (hstsMaxAgeSeconds > 0) {
            response.setHeader("Strict-Transport-Security",
                    "max-age=" + hstsMaxAgeSeconds + "; includeSubDomains");
        }

        filterChain.doFilter(request, response);
    }
}
