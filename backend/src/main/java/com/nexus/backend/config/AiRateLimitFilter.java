package com.nexus.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user rate limit on the AI question endpoints, to bound spend on the
 * upstream model and blunt abuse. Keyed by the authenticated principal (IP
 * only for unauthenticated requests); in-memory and per-instance, mirroring
 * {@link AuthRateLimitFilter} — a shared Redis counter can replace the map
 * when running many instances.
 */
@Component
@Order(2)
public class AiRateLimitFilter extends OncePerRequestFilter {

    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;

    public AiRateLimitFilter(
            @Value("${nexus.ai.rate-limit.max-requests:20}") int maxRequests,
            @Value("${nexus.ai.rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.equals("/api/ai/ask")) return false;
        return !uri.matches(".*/api/ai/conversations/[0-9a-fA-F-]+/regenerate");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String key = auth != null && auth.getName() != null && !auth.getName().isBlank()
            ? "u:" + auth.getName()
            : "ip:" + clientIp(request);
        long now = System.currentTimeMillis();

        Window window = windows.get(key);
        if (window == null || now - window.startMillis() > windowMillis) {
            windows.put(key, new Window(now, 1));
        } else if (window.count() >= maxRequests) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"status\":429,\"message\":\"You are asking Nexus AI too quickly. Try again in a moment.\"}"
            );
            return;
        } else {
            windows.put(key, new Window(window.startMillis(), window.count() + 1));
        }

        // Keep the map from growing without bound.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() > windowMillis);
        }

        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
