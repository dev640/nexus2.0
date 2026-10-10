package com.nexus.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The body-size cap is a declared-length guard: it must stop oversized JSON
 * bodies without stepping on multipart uploads or non-API traffic.
 */
class ApiBodySizeFilterTest {

    private static MockHttpServletResponse call(ApiBodySizeFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void rejectsDeclaredBodiesOverTheCapBeforeTheyAreRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/messages");
        request.setContent(new byte[2048]);

        MockHttpServletResponse response = call(new ApiBodySizeFilter(1024), request);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("too large");
    }

    @Test
    void allowsBodiesAtOrUnderTheCap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/messages");
        request.setContent(new byte[1024]);

        assertThat(call(new ApiBodySizeFilter(1024), request).getStatus()).isEqualTo(200);
    }

    @Test
    void leavesMultipartUploadsToTheMultipartLimits() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users/1/avatar");
        request.setContent(new byte[2048]);
        request.setContentType("multipart/form-data; boundary=x");

        assertThat(call(new ApiBodySizeFilter(1024), request).getStatus()).isEqualTo(200);
    }

    @Test
    void ignoresNonApiRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws/chat");
        request.setContent(new byte[2048]);

        assertThat(call(new ApiBodySizeFilter(1024), request).getStatus()).isEqualTo(200);
    }
}
