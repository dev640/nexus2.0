package com.nexus.backend.security;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.service.SupabaseUserService;
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
        final String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        final String token = authHeader.substring(7);

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Authentication authentication = resolveAuthentication(token, request);
            if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
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
        }
    }

    private Authentication authenticated(UserDetails userDetails, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return auth;
    }
}
