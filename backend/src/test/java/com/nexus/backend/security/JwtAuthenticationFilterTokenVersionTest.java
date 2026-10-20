package com.nexus.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

class JwtAuthenticationFilterTokenVersionTest {

    private static final String TOKEN = "signed-token";
    private static final String EMAIL = "ada@example.test";
    private static final int CURRENT_VERSION = 3;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void refusesATokenVersionOlderThanTheAccountRow() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        NexusUserPrincipal principal = principal();
        when(jwtUtil.extractUsername(TOKEN)).thenReturn(EMAIL);
        when(jwtUtil.validateToken(TOKEN, EMAIL, CURRENT_VERSION)).thenReturn(false);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principal);

        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) ->
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        filter(jwtUtil, userDetailsService).doFilter(request, response, chain);

        verify(jwtUtil).validateToken(TOKEN, EMAIL, CURRENT_VERSION);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void authenticatesATokenAtTheCurrentAccountVersion() throws Exception {
        JwtUtil jwtUtil = mock(JwtUtil.class);
        UserDetailsService userDetailsService = mock(UserDetailsService.class);
        NexusUserPrincipal principal = principal();
        when(jwtUtil.extractUsername(TOKEN)).thenReturn(EMAIL);
        when(jwtUtil.validateToken(TOKEN, EMAIL, CURRENT_VERSION)).thenReturn(true);
        when(userDetailsService.loadUserByUsername(EMAIL)).thenReturn(principal);

        FilterChain chain = (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isSameAs(principal);
            assertThat(TenantContext.getTenantId()).isEqualTo(31L);
        };

        filter(jwtUtil, userDetailsService).doFilter(request(), new MockHttpServletResponse(), chain);

        // The tenant is what this filter puts on the thread, so the tenant is what
        // it has to take off again: the request thread goes back to Tomcat's pool
        // and must not carry this request's company into the next one.
        assertThat(TenantContext.getTenantId()).isNull();
        // The security context is deliberately still populated here. Clearing it
        // after the request belongs to SecurityContextHolderFilter, which wraps
        // this filter in the real chain and runs its own finally outside this
        // one; this unit test drives the filter alone. Asserting it away asserted
        // a behaviour the filter does not own — which is why this failed the
        // first time it ran against a fresh compile.
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
            .isSameAs(principal);
    }

    private static JwtAuthenticationFilter filter(JwtUtil jwtUtil, UserDetailsService detailsService) {
        return new JwtAuthenticationFilter(
            jwtUtil,
            detailsService,
            mock(SupabaseTokenVerifier.class),
            mock(SupabaseUserService.class),
            mock(SupabaseProperties.class));
    }

    private static NexusUserPrincipal principal() {
        return new NexusUserPrincipal(
            9L, EMAIL, "", UserRole.MEMBER, 31L, false, false, CURRENT_VERSION);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }
}