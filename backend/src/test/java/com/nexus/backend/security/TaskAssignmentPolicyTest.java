package com.nexus.backend.security;

import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.project.ProjectHealth;
import com.nexus.backend.domain.project.ProjectStatus;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.task.TaskPriority;
import com.nexus.backend.domain.task.TaskStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.TaskRequest;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.NotificationService;
import com.nexus.backend.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The service-layer half of the manager policy: handing a task to somebody
 * else — or taking it away — needs ADMIN or MANAGER, while a member editing a
 * task they do not own must still be able to save it.
 *
 * The rule cannot live on the endpoint, because it depends on whether the
 * assignee actually changed, so this test drives TaskService directly with a
 * real security context instead of mocking it away.
 */
@ExtendWith(MockitoExtension.class)
class TaskAssignmentPolicyTest {

    @Mock private TaskRepository taskRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private SprintRepository sprintRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationEventPublisher knowledgePublisher;

    private TaskService taskService;

    private Project project;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
            taskRepository, projectRepository, sprintRepository, userRepository, knowledgePublisher);
        // Field-injected and @Lazy in production, so it is not a constructor
        // argument here either.
        ReflectionTestUtils.setField(taskService, "notificationService", notificationService);

        project = new Project("Demo", "demo project", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        project.setId(1L);
        alice = user(2L, "alice@nexus.com");
        bob = user(3L, "bob@nexus.com");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------- create ----------

    @Test
    void memberCannotAssignWorkOnCreate() {
        authenticateAs(UserRole.MEMBER);

        assertThatThrownBy(() -> taskService.create(request(bob.getId())))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void managerAndAdminMayAssignWorkOnCreate() {
        for (UserRole role : List.of(UserRole.MANAGER, UserRole.ADMIN)) {
            authenticateAs(role);
            when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
            when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

            var response = taskService.create(request(bob.getId()));

            assertThat(response.assignee()).isNotNull();
            assertThat(response.assignee().email()).isEqualTo(bob.getEmail());
        }
    }

    @Test
    void creatingAnUnassignedTaskNeedsNoManageRights() {
        authenticateAs(UserRole.MEMBER);

        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(taskService.create(request(null)).assignee()).isNull();
    }

    // ---------- update ----------

    @Test
    void memberCannotReassignSomebodyElsesTask() {
        authenticateAs(UserRole.MEMBER);
        Task existing = taskAssignedTo(alice);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> taskService.update(5L, request(bob.getId())))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(existing.getAssignee()).isEqualTo(alice);
    }

    @Test
    void memberCannotTakeATaskAwayFromSomebody() {
        authenticateAs(UserRole.MEMBER);
        Task existing = taskAssignedTo(alice);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> taskService.update(5L, request(null)))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void memberMayEditATaskWhileLeavingTheAssigneeInPlace() {
        authenticateAs(UserRole.MEMBER);
        Task existing = taskAssignedTo(alice);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(userRepository.findById(alice.getId())).thenReturn(Optional.of(alice));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = taskService.update(5L, request(alice.getId()));

        assertThat(response.assignee().email()).isEqualTo(alice.getEmail());
    }

    @Test
    void clearingAnAlreadyUnassignedTaskNeedsNoManageRights() {
        authenticateAs(UserRole.MEMBER);
        Task existing = taskAssignedTo(null);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(taskService.update(5L, request(null)).assignee()).isNull();
    }

    @Test
    void managerMayHandATaskToSomebodyElse() {
        authenticateAs(UserRole.MANAGER);
        Task existing = taskAssignedTo(alice);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = taskService.update(5L, request(bob.getId()));

        assertThat(response.assignee().email()).isEqualTo(bob.getEmail());
    }

    @Test
    void viewerIsRefusedAtTheServiceLayerToo() {
        authenticateAs(UserRole.VIEWER);

        assertThatThrownBy(() -> taskService.create(request(bob.getId())))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void internalCallWithNoAuthenticationIsNotBlocked() {
        // Schedulers and tests reach the service without a request; every HTTP
        // call arrives authenticated, so this path cannot be an unauthenticated
        // API request.
        SecurityContextHolder.clearContext();
        when(userRepository.findById(bob.getId())).thenReturn(Optional.of(bob));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(taskService.create(request(bob.getId())).assignee().email()).isEqualTo(bob.getEmail());
    }

    // ---------- helpers ----------

    private static TaskRequest request(Long assigneeId) {
        return new TaskRequest("Do the thing", null, 1L, null, TaskStatus.TODO,
            TaskPriority.MEDIUM, 1, assigneeId, List.of(), false);
    }

    private Task taskAssignedTo(User assignee) {
        Task task = new Task("Do the thing", null, project, TaskStatus.TODO, TaskPriority.MEDIUM);
        task.setId(5L);
        task.setAssignee(assignee);
        return task;
    }

    private static User user(Long id, String email) {
        User user = new User("Person", email, "$bcrypt", UserRole.MEMBER);
        user.setId(id);
        return user;
    }

    private static void authenticateAs(UserRole role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "someone@nexus.com", null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}
