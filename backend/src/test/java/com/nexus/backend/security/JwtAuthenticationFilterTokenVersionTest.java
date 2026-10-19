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

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TenantContext.getTenantId()).isNull();
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