package com.nexus.backend.web;

import com.nexus.backend.config.SecurityConfig;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.dto.CreatedCompanyResponse;
import com.nexus.backend.security.JwtAuthenticationFilter;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.security.SupabaseTokenVerifier;
import com.nexus.backend.service.CompanyService;
import com.nexus.backend.service.CompanySuspensionService;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.service.SuperAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cross-company surface is SUPER_ADMIN-only, route by route.
 *
 * <p>These endpoints see every company's data, so they are the exact place the
 * workspace rule that protects everyone else is deliberately lifted — and that
 * lift is only safe if the role gate is airtight. The real SecurityConfig is
 * imported so the {@code @PreAuthorize} is exercised as wired, not a copy: an
 * ordinary ADMIN, who manages one company, must be refused every one of them.
 */
@WebMvcTest(controllers = SuperAdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SuperAdminSecurityTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private SupabaseTokenVerifier supabaseTokenVerifier;
    @MockitoBean private SupabaseUserService supabaseUserService;
    @MockitoBean private SupabaseProperties supabaseProperties;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private SuperAdminService superAdminService;
    @MockitoBean private CompanySuspensionService suspensionService;
    @MockitoBean private CompanyService companyService;

    /** Every route on the controller. */
    private static final String[] ROUTES = {
        "POST /api/admin/companies",
        "GET /api/admin/companies",
        "GET /api/admin/accounts",
        "POST /api/admin/companies/1/suspend",
        "POST /api/admin/companies/1/resume",
    };

    /** An ADMIN runs one company; letting them see across companies is the bug. */
    @Test
    void adminIsRefusedEveryCrossCompanyRoute() throws Exception {
        for (String route : ROUTES) {
            String[] parts = route.split(" ");
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .request(org.springframework.http.HttpMethod.valueOf(parts[0]), parts[1])
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"name":"X Corp","adminName":"Ada","adminEmail":"ada@x.com"}
                        """)
                    .with(SecurityMockMvcRequestPostProcessors.user("a@nexus.com").roles("ADMIN")))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals(
                    403, result.getResponse().getStatus(),
                    route + " -> " + result.getResponse().getStatus()));
        }
    }

    /** The same routes are reachable for the one role that may cross companies. */
    @Test
    void superAdminIsAllowedEveryCrossCompanyRoute() throws Exception {
        when(superAdminService.listCompanies()).thenReturn(List.of());
        when(superAdminService.listAccounts(null)).thenReturn(List.of());
        when(companyService.create(any())).thenReturn(new CreatedCompanyResponse(
            41L, "X Corp", 42L, "Ada", "ada@x.com",
            com.nexus.backend.domain.user.UserRole.ADMIN, "one-time-password"));

        mockMvc.perform(post("/api/admin/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"X Corp","adminName":"Ada","adminEmail":"ada@x.com"}
                    """)
                .with(SecurityMockMvcRequestPostProcessors.user("root@nexus.com").roles("SUPER_ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("Location", "/api/admin/companies"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/admin/companies")
                .with(SecurityMockMvcRequestPostProcessors.user("root@nexus.com").roles("SUPER_ADMIN")))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/accounts")
                .with(SecurityMockMvcRequestPostProcessors.user("root@nexus.com").roles("SUPER_ADMIN")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/companies/1/suspend")
                .with(SecurityMockMvcRequestPostProcessors.user("root@nexus.com").roles("SUPER_ADMIN")))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/companies/1/resume")
                .with(SecurityMockMvcRequestPostProcessors.user("root@nexus.com").roles("SUPER_ADMIN")))
            .andExpect(status().isOk());
    }
}
