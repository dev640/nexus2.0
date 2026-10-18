package com.nexus.backend.security;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates API requests from a Bearer token. Two token types are accepted:
 *
 * 1. Nexus access tokens (subject = email) issued by /api/auth/login|register.
 * 2. Supabase access tokens (subject = UUID), when the optional Supabase
 *    integration is configured. Verified against Supabase's signing key and
 *    mapped to the local account (linked or auto-provisioned on first use).
 *
 * Anything else proceeds unauthenticated; Spring Security rejects protected
 * routes downstream.
 *
 * <p>The filter is also the single place the tenant is established. Once an
 * identity is in scope its company is put into {@link TenantContext} so every
 * scoped query and every insert during the request agrees on it, and it is
 * cleared in a {@code finally} on the way out — the thread goes back to
 * Tomcat's pool and would otherwise hand this company to the next request that
 * happens to land on it, which is the one bug that would leak one company's
 * data to another.
 *
 * <p>The company is read from the account row (through the principal) rather
 * than from a token claim, so moving someone between companies takes effect on
 * their next request instead of when their token expires.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final SupabaseTokenVerifier supabaseTokenVerifier;
    private final SupabaseUserService supabaseUserService;
    private final SupabaseProperties supabaseProperties;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            authenticate(request);
            filterChain.doFilter(request, response);
        } finally {
            // Unconditional, including the unauthenticated early returns below:
            // this thread is about to be reused and must not carry a company.
            TenantContext.clear();
        }
    }

    /** Establishes the authentication (and with it the tenant) for this request. */
    private void authenticate(HttpServletRequest request) {
        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return;
        }
        final String token = authHeader.substring(7);

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Authentication authentication = resolveAuthentication(token, request);
            if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        // Derived from whatever authentication is in scope, so a principal set by
        // an earlier filter is honoured too. Null for SUPER_ADMIN and for
        // unauthenticated requests, which is the correct scope for both.
        TenantContext.setTenantId(companyIdOf(
            SecurityContextHolder.getContext().getAuthentication()));
    }

    /**
     * The company of the authenticated account, or null when there is none
     * (an anonymous request, or a SUPER_ADMIN who belongs to no company).
     */
    private static Long companyIdOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof NexusUserPrincipal principal) {
            return principal.getCompanyId();
        }
        return null;
    }

    private Authentication resolveAuthentication(String token, HttpServletRequest request) {
        // Route 1: our own access token (subject = email).
        try {
            String email = jwtUtil.extractUsername(token);
            if (email != null && email.contains("@") && jwtUtil.validateToken(token, email)) {
                return authenticated(userDetailsService.loadUserByUsername(email), request);
            }
        } catch (Exception e) {
            // Not one of ours — fall through to the Supabase path.
            log.debug("Not a Nexus token: {}", e.getMessage());
        }

        // Route 2: a Supabase access token (subject = UUID), only when configured.
        if (!supabaseProperties.isEnabled()) {
            return null;
        }
        try {
            Claims claims = supabaseTokenVerifier.verify(token);
            User user = supabaseUserService.resolveUser(claims);
            return authenticated(userDetailsService.loadUserByUsername(user.getEmail()), request);
        } catch (JwtException | IllegalArgumentException e) {
            // IllegalArgumentException covers malformed UUID subjects.
            log.debug("Supabase token rejected: {}", e.getMessage());
            return null;
        } catch (UsernameNotFoundException e) {
            // A valid Supabase identity with no account in this workspace. The
            // request proceeds unauthenticated and is rejected by Spring Security,
            // which is the honest answer: this person may not sign in here. It is
            // not an error in the request, so it must not surface as a 500.
            log.debug("Supabase identity has no local account: {}", e.getMessage());
            return null;
        }
    }

    private Authentication authenticated(UserDetails userDetails, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return auth;
    }
}
