package com.nexus.backend.web;

import com.nexus.backend.config.SecurityConfig;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.dto.PushSelfCheckResponse;
import com.nexus.backend.security.JwtAuthenticationFilter;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.SupabaseTokenVerifier;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.service.push.PushSubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The self-check as it is actually reached: over HTTP, through the security chain.
 *
 * <p>The field names are the interface, not an implementation detail — an operator
 * diagnosing a deployment reads them out of a JSON response with no build of the
 * application to hand, so they are pinned here rather than left to whatever the
 * record happens to be called.
 */
@WebMvcTest(controllers = PushController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PushSelfCheckEndpointTest {

    private static final String EMAIL = "ada@nexus.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private SupabaseTokenVerifier supabaseTokenVerifier;
    @MockitoBean private SupabaseUserService supabaseUserService;
    @MockitoBean private SupabaseProperties supabaseProperties;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private PushSubscriptionService pushSubscriptionService;

    @Test
    void servesTheWholeReportToASignedInAccount() throws Exception {
        when(pushSubscriptionService.selfCheck()).thenReturn(new PushSelfCheckResponse(
            true,
            "0123456789ab",
            Boolean.TRUE,
            "mailto:ops@nexus.test",
            24,
            2,
            new PushSelfCheckResponse.LastAttempt(
                Instant.parse("2026-10-14T09:30:00Z"), 2, 1, "REFUSED", 503)));

        mockMvc.perform(get("/api/push/self-check").with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pushEnabled").value(true))
            .andExpect(jsonPath("$.publicKeyFingerprint").value("0123456789ab"))
            .andExpect(jsonPath("$.publicKeyMatchesPrivateKey").value(true))
            .andExpect(jsonPath("$.subject").value("mailto:ops@nexus.test"))
            .andExpect(jsonPath("$.ttlHours").value(24))
            .andExpect(jsonPath("$.devices").value(2))
            .andExpect(jsonPath("$.lastAttempt.attempted").value(2))
            .andExpect(jsonPath("$.lastAttempt.accepted").value(1))
            .andExpect(jsonPath("$.lastAttempt.outcome").value("REFUSED"))
            .andExpect(jsonPath("$.lastAttempt.status").value(503));
    }

    /**
     * Nulls are part of the answer: "no key pair" and "nothing sent yet" are states
     * worth reporting exactly, and a caller has to be able to tell them apart from a
     * field that was silently dropped.
     */
    @Test
    void reportsWhatCannotBeKnownAsNullRatherThanOmittingIt() throws Exception {
        when(pushSubscriptionService.selfCheck()).thenReturn(new PushSelfCheckResponse(
            false, null, null, "mailto:ops@nexus.test", 24, 0, null));

        mockMvc.perform(get("/api/push/self-check").with(user(EMAIL).roles("MEMBER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pushEnabled").value(false))
            .andExpect(jsonPath("$.publicKeyFingerprint").value(nullValue()))
            .andExpect(jsonPath("$.publicKeyMatchesPrivateKey").value(nullValue()))
            .andExpect(jsonPath("$.devices").value(0))
            .andExpect(jsonPath("$.lastAttempt").value(nullValue()));
    }

    /**
     * The report describes this deployment's key state and the caller's own devices,
     * so it stays behind authentication like the rest of {@code /api}.
     *
     * <p>401 rather than 403: {@code ApiAuthenticationEntryPoint} is wired in
     * SecurityConfig, so an anonymous caller is told to sign in. A signed-in
     * caller whose role is refused still gets 403 from the access-denied handler.
     */
    @Test
    void refusesAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/push/self-check"))
            .andExpect(status().isUnauthorized());
    }
}
