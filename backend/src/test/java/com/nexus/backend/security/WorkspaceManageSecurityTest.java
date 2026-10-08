package com.nexus.backend.security;

import com.nexus.backend.config.SecurityConfig;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.project.ProjectHealth;
import com.nexus.backend.domain.project.ProjectStatus;
import com.nexus.backend.dto.ProjectResponse;
import com.nexus.backend.service.ProjectService;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.web.ProjectController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating a project is an ADMIN/MANAGER action; contributing to an existing
 * one is not. Both halves matter — the second is what stops a management rule
 * from quietly turning into "members cannot work".
 *
 * The real SecurityConfig and JwtAuthenticationFilter are imported so the
 * @WorkspaceManage meta-annotation is exercised as wired.
 */
@WebMvcTest(controllers = {ProjectController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class WorkspaceManageSecurityTest {

    private static final String PROJECT_BODY = "{\"name\":\"Proj\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private SupabaseTokenVerifier supabaseTokenVerifier;
    @MockitoBean private SupabaseUserService supabaseUserService;
    @MockitoBean private SupabaseProperties supabaseProperties;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private ProjectService projectService;

    @Test
    void memberCannotCreateAProject() throws Exception {
        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY)
                .with(user("m@nexus.com").roles("MEMBER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void developerCannotCreateAProject() throws Exception {
        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY)
                .with(user("d@nexus.com").roles("DEVELOPER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void viewerCannotCreateAProject() throws Exception {
        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY)
                .with(user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void managerCanCreateAProject() throws Exception {
        when(projectService.create(any())).thenReturn(projectResponse());

        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY)
                .with(user("mgr@nexus.com").roles("MANAGER")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Proj"));
    }

    @Test
    void adminCanCreateAProject() throws Exception {
        when(projectService.create(any())).thenReturn(projectResponse());

        mockMvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY)
                .with(user("a@nexus.com").roles("ADMIN")))
            .andExpect(status().isCreated());
    }

    @Test
    void memberCanStillUpdateAnExistingProject() throws Exception {
        when(projectService.update(eq(1L), any())).thenReturn(projectResponse());

        mockMvc.perform(put("/api/projects/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(PROJECT_BODY)
                .with(user("m@nexus.com").roles("MEMBER")))
            .andExpect(status().isOk());
    }

    private static ProjectResponse projectResponse() {
        return new ProjectResponse(1L, "Proj", null, ProjectStatus.PLANNING, ProjectHealth.ON_TRACK,
            0, 1, 0, LocalDateTime.now(), LocalDateTime.now());
    }
}
