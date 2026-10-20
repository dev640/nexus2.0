package com.nexus.backend.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusal an unauthenticated caller receives.
 *
 * <p>Until this entry point existed, Spring Security's default
 * {@code Http403ForbiddenEntryPoint} answered {@code 403} for a request with no
 * valid credential, so "not signed in" and "not allowed" were the same answer.
 * That made a token invalidated by a password change (V22 bumps
 * {@code users.token_version}) indistinguishable from a permission problem, and
 * a client could not tell to sign in again.
 *
 * <p>Both assertions below are about the contract rather than the wording: the
 * status a client branches on, and the absence of anything that explains the
 * failure beyond "your session is not valid".
 */
class ApiAuthenticationEntryPointTest {

    private final ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint();

    private MockHttpServletResponse commence(AuthenticationException failure) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        entryPoint.commence(
            new MockHttpServletRequest("GET", "/api/projects"), response, failure);
        return response;
    }

    @Test
    void answersAnUnauthenticatedRequestWith401AndTheMarker() throws Exception {
        MockHttpServletResponse response = commence(
            new InsufficientAuthenticationException("no credentials"));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
            .contains("\"status\":401")
            .contains("\"code\":\"" + ApiAuthenticationEntryPoint.CODE + "\"");
    }

    @Test
    void saysNothingAboutWhyTheCredentialFailed() throws Exception {
        // A real exception message can name a token, an expiry or a signature
        // problem. Repeating it would tell an attacker which half of a forged
        // credential to fix.
        MockHttpServletResponse response = commence(
            new InsufficientAuthenticationException("JWT signature does not match"));

        assertThat(response.getContentAsString())
            .doesNotContain("signature")
            .doesNotContain("JWT")
            .isEqualTo("{\"status\":401,\"code\":\"UNAUTHENTICATED\",\"message\":\""
                + ApiAuthenticationEntryPoint.MESSAGE + "\"}");
    }
}
