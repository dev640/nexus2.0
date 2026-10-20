package com.nexus.backend.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.backend.config.SecurityConfig;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.AdminAccountService;
import com.nexus.backend.service.AuditService;
import com.nexus.backend.service.AvatarService;
import com.nexus.backend.service.PasswordChangeService;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.service.UserService;
import com.nexus.backend.tenant.CurrentTenant;
import com.nexus.backend.web.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

/**
 * The gate as it is actually wired: the real {@code SecurityConfig}, the real
 * filter chain, and an authentication of the kind the JWT filter produces.
 *
 * <p>{@link MustChangePasswordFilterTest} already states the decision table, but
 * a unit test of a filter proves nothing about whether it runs — a filter is
 * easy to build correctly and then register in the wrong place, or not at all.
 * This closes that gap against the one controller every account is sure to call
 * after signing in.
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class MustChangePasswordGateSecurityTest {

    private static final long USER_ID = 9L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private SupabaseTokenVerifier supabaseTokenVerifier;
    @MockitoBean private SupabaseUserService supabaseUserService;
    @MockitoBean private SupabaseProperties supabaseProperties;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private AdminAccountService adminAccountService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserService userService;
    @MockitoBean private AvatarService avatarService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private PasswordChangeService passwordChangeService;
    @MockitoBean private CurrentTenant currentTenant;

    /** The account as the caller sees it once it has been loaded. */
    private User account(boolean mustChangePassword) {
        User user = new User("Ada", "ada@nexus.com", null, UserRole.MEMBER);
        user.setId(USER_ID);
        user.setMustChangePassword(mustChangePassword);
        when(userRepository.findByEmail("ada@nexus.com")).thenReturn(java.util.Optional.of(user));
        // `PATCH /me` returns the saved row, so the stub has to answer with one.
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        return user;
    }

    /**
     * An authenticated request carrying a principal of the kind
     * JwtAuthenticationFilter builds. No Authorization header is sent, so the
     * filter leaves this authentication alone and TenantContext stays empty —
     * which is exactly the shape of a request after the token has been read.
     */
    private static RequestPostProcessor principal(boolean mustChangePassword) {
        return authentication(new UsernamePasswordAuthenticationToken(
            new NexusUserPrincipal(USER_ID, "ada@nexus.com", "", UserRole.MEMBER, 31L, mustChangePassword, false),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))));
    }

    @Test
    void refusesAnEndpointTheAccountIsNotSupposedToReach() throws Exception {
        account(true);

        mockMvc.perform(patch("/api/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\"}")
                .with(principal(true)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(MustChangePasswordFilter.CODE))
            .andExpect(jsonPath("$.message").value(MustChangePasswordFilter.MESSAGE));

        verify(passwordChangeService, never()).changePassword(any(), any());
    }

    /** The one write it is allowed: the request that clears the flag. */
    @Test
    void allowsTheChangeThatClearsTheFlag() throws Exception {
        mockMvc.perform(post("/api/users/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"issued-pass\",\"newPassword\":\"chosen-pass\"}")
                .with(principal(true)))
            .andExpect(status().isNoContent());

        verify(passwordChangeService).changePassword(any(), any());
    }

    /**
     * Allowed because the client reads its own profile while restoring a session.
     * Refusing it would make a reload of the forced-change screen look like an
     * expired session and sign the person out of the very screen they need.
     */
    @Test
    void allowsReadingTheCallersOwnProfile() throws Exception {
        account(true);

        mockMvc.perform(get("/api/users/me").with(principal(true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void leavesAnUngatedAccountAlone() throws Exception {
        account(false);

        mockMvc.perform(patch("/api/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Ada\"}")
                .with(principal(false)))
            .andExpect(status().isOk());
    }

    /**
     * An unauthenticated caller is refused by the security chain rather than by
     * this gate. The answer is 401 from {@code ApiAuthenticationEntryPoint}:
     * this API issues bearer tokens, so there is no login redirect to send an
     * anonymous caller to, but the caller is still told to sign in — which is a
     * different answer from the 403 a signed-in caller without the right role
     * receives. What matters here is that nothing reached the service.
     */
    @Test
    void doesNotRunForAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(post("/api/users/me/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"issued-pass\",\"newPassword\":\"chosen-pass\"}"))
            .andExpect(status().isUnauthorized());

        verify(passwordChangeService, never()).changePassword(any(), any());
    }
}
