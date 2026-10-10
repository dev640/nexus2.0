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

/**
 * Rejects oversized JSON API bodies before they are read into memory.
 *
 * <p>This is a declared-size guard: a request whose {@code Content-Length} exceeds
 * the cap is answered with 413 without touching its body. Chunked requests, which
 * carry no declared length, are not covered here — file uploads are bounded by the
 * multipart configuration and per-endpoint validation bounds the rest.
 */
@Component
@Order(4)
public class ApiBodySizeFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public ApiBodySizeFilter(
        @Value("${nexus.api.max-request-size-bytes:524288}") long maxBytes
    ) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return true;
        }
        // Multipart uploads are bounded by the multipart limits, not this cap.
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"status\":413,\"message\":\"Request body is too large.\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
