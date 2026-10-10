package com.nexus.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headers layer is small but load-bearing: it is the only place the app sets
 * nosniff/frame/referrer/CSP, and HSTS must stay dark on plain-HTTP development.
 */
class SecurityHeadersFilterTest {

    private static final String CSP = "default-src 'self'";

    private static MockHttpServletResponse run(long hstsMaxAgeSeconds) throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter(hstsMaxAgeSeconds, CSP);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void setsTheDefensiveHeaderSetOnEveryResponse() throws Exception {
        MockHttpServletResponse response = run(0L);

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(response.getHeader("Permissions-Policy")).contains("camera=()");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(CSP);
    }

    @Test
    void hstsIsOptInSoHttpDevelopmentIsNotPinned() throws Exception {
        assertThat(run(0L).getHeader("Strict-Transport-Security")).isNull();

        assertThat(run(31_536_000L).getHeader("Strict-Transport-Security"))
            .isEqualTo("max-age=31536000; includeSubDomains");
    }
}
