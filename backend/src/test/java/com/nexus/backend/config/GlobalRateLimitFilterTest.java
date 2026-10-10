package com.nexus.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The global limiter is the backstop for API routes that have no limiter of their
 * own; auth and non-API traffic must pass through untouched.
 */
class GlobalRateLimitFilterTest {

    private static MockHttpServletResponse call(GlobalRateLimitFilter filter, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void rejectsRequestsPastTheWindowBudget() throws Exception {
        GlobalRateLimitFilter filter = new GlobalRateLimitFilter(2, 60);

        assertThat(call(filter, "/api/projects").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/projects").getStatus()).isEqualTo(200);

        MockHttpServletResponse limited = call(filter, "/api/projects");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(limited.getContentAsString()).contains("Too many requests");
    }

    @Test
    void countsEachClientSeparately() throws Exception {
        GlobalRateLimitFilter filter = new GlobalRateLimitFilter(1, 60);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/projects");
        first.setRemoteAddr("10.0.0.1");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/projects");
        second.setRemoteAddr("10.0.0.2");

        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(secondResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void leavesAuthAndNonApiTrafficToTheOtherFilters() throws Exception {
        GlobalRateLimitFilter filter = new GlobalRateLimitFilter(1, 60);

        assertThat(call(filter, "/api/auth/login").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/api/auth/login").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/actuator/health").getStatus()).isEqualTo(200);
        assertThat(call(filter, "/actuator/health").getStatus()).isEqualTo(200);
    }
}
