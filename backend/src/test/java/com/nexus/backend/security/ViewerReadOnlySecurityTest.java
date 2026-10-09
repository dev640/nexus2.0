package com.nexus.backend.security;

import com.nexus.backend.config.SecurityConfig;
import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.task.TaskPriority;
import com.nexus.backend.domain.task.TaskStatus;
import com.nexus.backend.dto.TaskResponse;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.AdminAccountService;
import com.nexus.backend.service.AnalyticsService;
import com.nexus.backend.service.ChatService;
import com.nexus.backend.service.ChatSocketFacade;
import com.nexus.backend.service.CopilotService;
import com.nexus.backend.service.MessageService;
import com.nexus.backend.service.NotificationService;
import com.nexus.backend.service.ProjectService;
import com.nexus.backend.service.SprintService;
import com.nexus.backend.service.SupabaseUserService;
import com.nexus.backend.service.TaskService;
import com.nexus.backend.service.WhiteboardService;
import com.nexus.backend.service.WikiPageService;
import com.nexus.backend.service.UserService;
import com.nexus.backend.service.AvatarService;
import com.nexus.backend.web.ChatController;
import com.nexus.backend.web.CopilotController;
import com.nexus.backend.web.MessageController;
import com.nexus.backend.web.NotificationController;
import com.nexus.backend.web.ProjectController;
import com.nexus.backend.web.SprintController;
import com.nexus.backend.web.TaskController;
import com.nexus.backend.web.WhiteboardController;
import com.nexus.backend.web.WikiPageController;
import com.nexus.backend.web.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The phase-1 policy in executable form: a VIEWER account gets 403 on every
 * workspace-content write, MEMBER/ADMIN/DEVELOPER keep writing, and purely
 * personal endpoints (own notifications, chat read receipts) stay usable.
 *
 * The real SecurityConfig and JwtAuthenticationFilter are imported so the
 * @WorkspaceWrite meta-annotation is exercised exactly as wired, not a copy.
 */
@WebMvcTest(controllers = {
    TaskController.class,
    SprintController.class,
    ProjectController.class,
    WikiPageController.class,
    WhiteboardController.class,
    ChatController.class,
    NotificationController.class,
    MessageController.class,
    CopilotController.class,
    SprintController.class,
    UserController.class,
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ViewerReadOnlySecurityTest {

    private static final String TASK_BODY =
        "{\"title\":\"x\",\"projectId\":1,\"status\":\"TODO\",\"priority\":\"LOW\",\"storyPoints\":1}";
    private static final String SPRINT_BODY =
        "{\"projectId\":1,\"goal\":\"g\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-01-31\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private SupabaseTokenVerifier supabaseTokenVerifier;
    @MockitoBean private SupabaseUserService supabaseUserService;
    @MockitoBean private SupabaseProperties supabaseProperties;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private AdminAccountService adminAccountService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private AnalyticsService analyticsService;
    @MockitoBean private UserService userService;
    @MockitoBean private ChatService chatService;
    @MockitoBean private ChatSocketFacade chatSocketFacade;
    @MockitoBean private CopilotService copilotService;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private MessageService messageService;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private SprintService sprintService;
    @MockitoBean private TaskService taskService;
    @MockitoBean private AvatarService avatarService;
    @MockitoBean private WhiteboardService whiteboardService;
    @MockitoBean private WikiPageService wikiPageService;

    /** Every @WorkspaceWrite endpoint in the app. */
    private static Stream<NamedRequest> workspaceWrites() {
        return Stream.of(
            new NamedRequest("create task", request("POST", "/api/tasks").content(TASK_BODY)),
            new NamedRequest("update task", request("PUT", "/api/tasks/1").content(TASK_BODY)),
            new NamedRequest("move task", request("PATCH", "/api/tasks/1/status").content("{\"status\":\"DONE\"}")),
            new NamedRequest("delete task", request("DELETE", "/api/tasks/1")),
            new NamedRequest("create sprint", request("POST", "/api/sprints").content(SPRINT_BODY)),
            new NamedRequest("move sprint", request("PATCH", "/api/sprints/1/status").content("{\"status\":\"ACTIVE\"}")),
            new NamedRequest("create project", request("POST", "/api/projects").content("{\"name\":\"Proj\"}")),
            new NamedRequest("update project", request("PUT", "/api/projects/1").content("{\"name\":\"Proj\"}")),
            new NamedRequest("delete project", request("DELETE", "/api/projects/1")),
            new NamedRequest("create wiki page", request("POST", "/api/wiki").content("{\"title\":\"T\",\"content\":\"c\",\"projectId\":1}")),
            new NamedRequest("update wiki page", request("PATCH", "/api/wiki/1").content("{\"title\":\"T\",\"content\":\"c\",\"projectId\":1}")),
            new NamedRequest("delete wiki page", request("DELETE", "/api/wiki/1")),
            new NamedRequest("create note", request("POST", "/api/whiteboard/notes").content("{\"color\":\"yellow\"}")),
            new NamedRequest("update note", request("PATCH", "/api/whiteboard/notes/1").content("{\"text\":\"hi\",\"color\":\"blue\"}")),
            new NamedRequest("delete note", request("DELETE", "/api/whiteboard/notes/1")),
            new NamedRequest("update sprint", request("PUT", "/api/sprints/1").content(SPRINT_BODY)),
            new NamedRequest("delete sprint", request("DELETE", "/api/sprints/1")),
            new NamedRequest("delete channel", request("DELETE", "/api/chat/channels/1")),
            new NamedRequest("create channel", request("POST", "/api/chat/channels").content("{\"name\":\"c\"}")),
            new NamedRequest("open dm", request("POST", "/api/chat/channels/dm/1")),
            new NamedRequest("set topic", request("PATCH", "/api/chat/channels/1/topic").content("{\"topic\":\"t\"}")),
            new NamedRequest("post message", request("POST", "/api/chat/channels/1/messages").content("{\"body\":\"hi\"}")),
            new NamedRequest("edit message", request("PATCH", "/api/chat/messages/1").content("{\"body\":\"hi\"}")),
            new NamedRequest("delete message", request("DELETE", "/api/chat/messages/1")),
            new NamedRequest("react", request("POST", "/api/chat/messages/1/reactions").content("{\"emoji\":\"+1\",\"add\":true}")),
            // ADMIN-only rather than @WorkspaceWrite, so a VIEWER is refused
            // twice over; swept here so adding it cannot regress either guard.
            new NamedRequest("delete user", request("DELETE", "/api/users/1"))
        );
    }

    @Test
    void viewerGets403OnEveryWorkspaceWrite() throws Exception {
        for (NamedRequest r : workspaceWrites().toList()) {
            mockMvc.perform(r.request().with(user("v@nexus.com").roles("VIEWER")))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertEquals(
                    403, result.getResponse().getStatus(),
                    r.name() + " -> " + result.getResponse().getStatus()));
        }
    }

    @Test
    void memberIsNotRejectedByRoleOnTaskWrites() throws Exception {
        TaskResponse response = new TaskResponse(
            1L, "t", null, 1L, "p", null, TaskStatus.DONE, TaskPriority.LOW,
            3, null, List.of(), false, LocalDateTime.now(), LocalDateTime.now());
        when(taskService.updateStatus(eq(1L), eq(TaskStatus.DONE))).thenReturn(response);
        when(taskService.create(any())).thenReturn(response);

        mockMvc.perform(request("PATCH", "/api/tasks/1/status")
                .content("{\"status\":\"DONE\"}")
                .with(user("m@nexus.com").roles("MEMBER")))
            .andExpect(status().isOk());
        mockMvc.perform(request("POST", "/api/tasks")
                .content(TASK_BODY)
                .with(user("m@nexus.com").roles("MEMBER")))
            .andExpect(status().isCreated());
    }

    @Test
    void personalEndpointsStayUsableForViewer() throws Exception {
        mockMvc.perform(request("PATCH", "/api/notifications/1/read")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isNoContent());
        mockMvc.perform(request("POST", "/api/notifications/read-all")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isNoContent());
        mockMvc.perform(request("DELETE", "/api/notifications/1")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isNoContent());
        mockMvc.perform(request("POST", "/api/chat/channels/1/read")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isNoContent());
        // Internal mail is correspondence, not workspace content: a read-only
        // account still writes to colleagues and manages its own boxes.
        mockMvc.perform(request("POST", "/api/messages")
                .content("{\"recipientId\":2,\"subject\":\"s\",\"body\":\"b\"}")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isCreated());
        // Writing to everyone is the same kind of act as writing to one person.
        mockMvc.perform(request("POST", "/api/messages/broadcast")
                .content("{\"subject\":\"s\",\"body\":\"b\"}")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isCreated());
        mockMvc.perform(request("DELETE", "/api/messages/1")
                .with(SecurityMockMvcRequestPostProcessors.user("v@nexus.com").roles("VIEWER")))
            .andExpect(status().isNoContent());
    }

    private record NamedRequest(String name,
                                org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {}

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
        String method, String uri) {
        // JSON content-type even on bodyless calls: the sweep hits @RequestBody
        // endpoints and octet-stream would fail resolution before authorization.
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
            HttpMethod.valueOf(method), uri)
            .contentType(MediaType.APPLICATION_JSON);
    }
}
