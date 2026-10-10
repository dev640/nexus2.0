package com.nexus.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broad per-IP rate limit for the JSON API, so endpoints outside the auth and AI
 * paths are not unlimited. Authentication endpoints keep their own, stricter
 * {@link AuthRateLimitFilter} and are skipped here.
 *
 * <p>In-memory and per-instance by design, like the other two limiters: a shared
 * store (Redis) can replace the counter map when running many instances.
 *
 * <p>Operational note: requests are counted per client IP, using the first
 * {@code X-Forwarded-For} hop when a proxy sets it and the socket address
 * otherwise. Deployments behind a proxy that does not sanitize that header should
 * prefer the proxy-supplied value, which this filter already uses.
 */
@Component
@Order(3)
public class GlobalRateLimitFilter extends OncePerRequestFilter {

    private record Window(long startMillis, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;

    public GlobalRateLimitFilter(
        @Value("${nexus.global.rate-limit.max-requests:200}") int maxRequests,
        @Value("${nexus.global.rate-limit.window-seconds:60}") long windowSeconds
    ) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String ip = clientIp(request);
        long now = System.currentTimeMillis();

        Window window = windows.get(ip);
        if (window == null || now - window.startMillis() > windowMillis) {
            windows.put(ip, new Window(now, 1));
        } else if (window.count() >= maxRequests) {
            long retryAfterSeconds = Math.max(1, (window.startMillis() + windowMillis - now) / 1000);
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"status\":429,\"message\":\"Too many requests. Try again shortly.\"}"
            );
            return;
        } else {
            windows.put(ip, new Window(window.startMillis(), window.count() + 1));
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
