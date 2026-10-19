package com.nexus.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nexus.backend.domain.user.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * The gate's decision table.
 *
 * <p>Every case is stated as "does the request reach the chain", because that is
 * the only thing the filter does: one refusal, and everything else passes
 * through untouched. The two allowed requests are the whole allowlist, so a
 * later endpoint added to it has to be a deliberate edit here as well.
 */
class MustChangePasswordFilterTest {

    private final MustChangePasswordFilter filter = new MustChangePasswordFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Puts a gated or ungated principal in scope, exactly as the JWT filter does. */
    private static void authenticate(boolean mustChangePassword) {
        NexusUserPrincipal principal = new NexusUserPrincipal(
            9L, "ada@nexus.com", "", UserRole.MEMBER, 31L, mustChangePassword, false);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
    }

    private void perform(String method, String uri) throws Exception {
        filter.doFilter(new MockHttpServletRequest(method, uri), response, chain);
    }

    // ------------------------------------------------------------- the refusal

    @Test
    void refusesAWorkspaceRequestWhileTheFlagIsSet() throws Exception {
        authenticate(true);

        perform("GET", "/api/tasks");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
            .contains(MustChangePasswordFilter.CODE)
            .contains(MustChangePasswordFilter.MESSAGE);
        verifyNoInteractions(chain);
    }

    @Test
    void refusesWritesAsWellAsReads() throws Exception {
        authenticate(true);

        perform("POST", "/api/tasks");

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    /**
     * A socket opened with a Bearer header is gated like any other request. Note
     * that a browser cannot open one that way — the handshake carries a token in
     * the query and has no authentication in scope — which is why the rule is
     * also applied in the handshake interceptors; see
     * MustChangePasswordHandshakeGuard and SocketHandshakeGateTest.
     */
    @Test
    void refusesTheWebSocketHandshake() throws Exception {
        authenticate(true);

        perform("GET", "/ws/chat");

        assertThat(response.getStatus()).isEqualTo(403);
        verifyNoInteractions(chain);
    }

    // ---------------------------------------------------------------- the way out

    @Test
    void allowsTheChangeItself() throws Exception {
        authenticate(true);

        perform("POST", "/api/users/me/password");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    /**
     * The profile read is allowed because the client cannot otherwise tell a gated
     * session from a dead one: it reads its own profile while restoring a session,
     * and a refusal there would sign the person out of the screen they need.
     */
    @Test
    void allowsReadingTheCallersOwnProfile() throws Exception {
        authenticate(true);

        perform("GET", "/api/users/me");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    /** The allowlist is method-specific, so it cannot be widened by verb. */
    @Test
    void doesNotAllowOtherVerbsOnTheAllowedPaths() throws Exception {
        authenticate(true);

        perform("DELETE", "/api/users/me");
        assertThat(response.getStatus()).isEqualTo(403);

        response.reset();
        perform("GET", "/api/users/me/password");
        assertThat(response.getStatus()).isEqualTo(403);

        verifyNoInteractions(chain);
    }

    /**
     * The allowlist is path-specific too: a prefix match would open every user
     * endpoint, which is most of the workspace.
     */
    @Test
    void doesNotAllowPrefixesOfTheAllowedPaths() throws Exception {
        authenticate(true);

        perform("GET", "/api/users");
        assertThat(response.getStatus()).isEqualTo(403);

        response.reset();
        perform("POST", "/api/users/me/password/extra");
        assertThat(response.getStatus()).isEqualTo(403);

        verifyNoInteractions(chain);
    }

    // ------------------------------------------------------------ pass-through

    /** CORS preflight carries no credentials and must not be answered with a 403. */
    @Test
    void allowsPreflight() throws Exception {
        authenticate(true);

        perform("OPTIONS", "/api/tasks");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void allowsEverythingOnceTheFlagIsCleared() throws Exception {
        authenticate(false);

        perform("GET", "/api/tasks");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void allowsAnAnonymousRequest() throws Exception {
        perform("GET", "/api/tasks");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    /**
     * An anonymous token is not an authenticated account, so treating it as gated
     * would refuse the public endpoints — including sign-in.
     */
    @Test
    void allowsAnAnonymousAuthenticationToken() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        perform("POST", "/api/auth/login");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    /**
     * A principal this filter does not understand is left to the endpoint's own
     * rules rather than refused: the gate knows only one kind of identity, and
     * guessing about the others is how a filter ends up locking out a route it
     * was never meant to touch.
     */
    @Test
    void allowsAPrincipalOfAnotherType() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "someone@nexus.com", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        perform("GET", "/api/tasks");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }
}
