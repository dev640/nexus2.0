package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.project.ProjectHealth;
import com.nexus.backend.domain.project.ProjectStatus;
import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.sprint.SprintStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.LoginRequest;
import com.nexus.backend.dto.RegisterRequest;
import com.nexus.backend.dto.SprintRequest;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.JwtUtil;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserSprintServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private SprintRepository sprintRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private UserService userService;

    @InjectMocks
    private SprintService sprintService;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("Demo", "demo", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        project.setId(1L);
    }

    // ---------- UserService ----------

    private RegisterRequest registerRequest() {
        return new RegisterRequest("Test User", "test@nexus.dev", "password123");
    }

    @Test
    void registerEncodesPasswordAndReturnsTokens() {
        when(userRepository.findByEmail("test@nexus.dev")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(10L);
            return u;
        });
        when(jwtUtil.generateToken("test@nexus.dev")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("test@nexus.dev")).thenReturn("refresh-token");

        var response = userService.register(registerRequest());

        assertThat(response.token()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.user().email()).isEqualTo("test@nexus.dev");
        verify(passwordEncoder).encode("password123");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.findByEmail("test@nexus.dev")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> userService.register(registerRequest()))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        User user = new User();
        user.setId(1L);
        user.setName("Dev");
        user.setEmail("devendra@nexus.com");
        user.setRole(UserRole.ADMIN);
        user.setPassword("$2a$10$hashed");
        when(userRepository.findByEmail("devendra@nexus.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$2a$10$hashed")).thenReturn(true);
        when(jwtUtil.generateToken("devendra@nexus.com")).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken("devendra@nexus.com")).thenReturn("refresh-token");

        var response = userService.login(new LoginRequest("devendra@nexus.com", "password123"));

        assertThat(response.token()).isEqualTo("access-token");
        assertThat(response.user().role()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = new User();
        user.setEmail("devendra@nexus.com");
        user.setPassword("$2a$10$hashed");
        when(userRepository.findByEmail("devendra@nexus.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("nope", "$2a$10$hashed")).thenReturn(false);

        assertThatThrownBy(() -> userService.login(new LoginRequest("devendra@nexus.com", "nope")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid email or password");
    }

    // ---------- SprintService ----------

    private SprintRequest sprintRequest() {
        return new SprintRequest(1L, "Ship billing", LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 14), 40, null);
    }

    @Test
    void createSprintNumbersSequentiallyAndUpdatesProject() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(sprintRepository.countByProject(project)).thenReturn(7L);
        when(sprintRepository.save(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = sprintService.create(sprintRequest());

        assertThat(response.number()).isEqualTo(8);
        assertThat(response.status()).isEqualTo(SprintStatus.PLANNED);
        verify(projectRepository).save(project);
        assertThat(project.getSprintNumber()).isEqualTo(8);
    }

    @Test
    void createSprintRejectsEndDateBeforeStartDate() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThatThrownBy(() -> sprintService.create(new SprintRequest(1L, "Backwards",
            LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 1), 40, null)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("End date must be after start date");
    }

    @Test
    void updateSprintStatusPersistsChange() {
        Sprint sprint = new Sprint();
        sprint.setId(3L);
        sprint.setProject(project);
        sprint.setNumber(1);
        sprint.setGoal("g");
        sprint.setStartDate(LocalDate.now());
        sprint.setEndDate(LocalDate.now().plusDays(7));
        sprint.setStatus(SprintStatus.PLANNED);
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(sprint));
        when(sprintRepository.save(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = sprintService.updateStatus(3L, SprintStatus.ACTIVE);

        assertThat(response.status()).isEqualTo(SprintStatus.ACTIVE);
        verify(sprintRepository).save(sprint);
    }

    @Test
    void findSprintByIdThrowsWhenMissing() {
        when(sprintRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sprintService.findById(404L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findAllReturnsEverySprintRegardlessOfProject() {
        Sprint other = new Sprint();
        other.setId(9L);
        Project second = new Project("Second", "second", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        second.setId(2L);
        other.setProject(second);
        other.setNumber(1);
        other.setGoal("g");
        other.setStartDate(LocalDate.now());
        other.setEndDate(LocalDate.now().plusDays(7));
        other.setStatus(SprintStatus.PLANNED);

        Sprint mine = new Sprint();
        mine.setId(1L);
        mine.setProject(project);
        mine.setNumber(2);
        mine.setGoal("g");
        mine.setStartDate(LocalDate.now());
        mine.setEndDate(LocalDate.now().plusDays(7));
        mine.setStatus(SprintStatus.ACTIVE);

        when(sprintRepository.findAll()).thenReturn(java.util.List.of(mine, other));

        // The frontend loads the whole workspace with no projectId filter; an
        // empty list here is what left the Sprints page blank.
        assertThat(sprintService.findAll())
            .extracting(com.nexus.backend.dto.SprintResponse::id)
            .containsExactly(1L, 9L);
    }

    // ---------- Sprint edit and delete ----------

    private Sprint existingSprint(int number) {
        Sprint sprint = new Sprint();
        sprint.setId(3L);
        sprint.setProject(project);
        sprint.setNumber(number);
        sprint.setGoal("old goal");
        sprint.setStartDate(LocalDate.now());
        sprint.setEndDate(LocalDate.now().plusDays(7));
        sprint.setCommittedPoints(5);
        sprint.setStatus(SprintStatus.PLANNED);
        return sprint;
    }

    private SprintRequest editRequest(Long projectId, String goal, LocalDate start, LocalDate end) {
        return new SprintRequest(projectId, goal, start, end, 8, SprintStatus.ACTIVE);
    }

    @Test
    void updateEditsTheSprintInPlace() {
        Sprint sprint = existingSprint(2);
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(sprint));
        when(sprintRepository.save(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = sprintService.update(3L,
            editRequest(1L, "ship checkout", LocalDate.now(), LocalDate.now().plusDays(14)));

        assertThat(response.goal()).isEqualTo("ship checkout");
        assertThat(response.committedPoints()).isEqualTo(8);
        assertThat(response.status()).isEqualTo(SprintStatus.ACTIVE);
        // The sprint number must survive an edit, or the project renumbers.
        assertThat(response.number()).isEqualTo(2);
    }

    /**
     * Moving a sprint would orphan its tasks and renumber the target project's
     * sequence, so the request is refused rather than half-applied.
     */
    @Test
    void updateRefusesToMoveASprintToAnotherProject() {
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(existingSprint(2)));

        assertThatThrownBy(() -> sprintService.update(3L,
            editRequest(2L, "g", LocalDate.now(), LocalDate.now().plusDays(7))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("different project");
    }

    @Test
    void updateRefusesAnEndDateBeforeTheStartDate() {
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(existingSprint(2)));

        assertThatThrownBy(() -> sprintService.update(3L,
            editRequest(1L, "g", LocalDate.now(), LocalDate.now().minusDays(1))))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("End date");
    }

    @Test
    void updateLeavesOmittedOptionalFieldsAlone() {
        Sprint sprint = existingSprint(2);
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(sprint));
        when(sprintRepository.save(any(Sprint.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = sprintService.update(3L,
            new SprintRequest(1L, "g", LocalDate.now(), LocalDate.now().plusDays(3), null, null));

        assertThat(response.committedPoints()).isEqualTo(5);
        assertThat(response.status()).isEqualTo(SprintStatus.PLANNED);
    }

    @Test
    void deleteRemovesAnEmptySprint() {
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(existingSprint(2)));
        when(taskRepository.countBySprint(any(Sprint.class))).thenReturn(0L);

        sprintService.delete(3L);

        verify(sprintRepository).delete(any(Sprint.class));
    }

    /**
     * The important one: deleting a sprint that still holds tasks would either
     * fail on the foreign key or, worse, leave those tasks pointing at nothing.
     */
    @Test
    void deleteRefusesWhileTheSprintStillHoldsTasks() {
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(existingSprint(2)));
        when(taskRepository.countBySprint(any(Sprint.class))).thenReturn(4L);

        assertThatThrownBy(() -> sprintService.delete(3L))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("4 tasks");

        verify(sprintRepository, org.mockito.Mockito.never()).delete(any(Sprint.class));
    }

    @Test
    void deleteRefusesAnUnknownSprint() {
        when(sprintRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sprintService.delete(404L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Keeps the next created sprint from reusing a number already spent. */
    @Test
    void deleteRollsBackTheProjectHighWaterSprintNumber() {
        project.setSprintNumber(4);
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(existingSprint(2)));
        when(taskRepository.countBySprint(any(Sprint.class))).thenReturn(0L);

        sprintService.delete(3L);

        assertThat(project.getSprintNumber()).isEqualTo(2);
        verify(projectRepository).save(project);
    }

    @Test
    void deleteLeavesTheProjectNumberAloneWhenItIsNotAhead() {
        project.setSprintNumber(2);
        when(sprintRepository.findById(3L)).thenReturn(Optional.of(existingSprint(2)));
        when(taskRepository.countBySprint(any(Sprint.class))).thenReturn(0L);

        sprintService.delete(3L);

        assertThat(project.getSprintNumber()).isEqualTo(2);
        verify(projectRepository, org.mockito.Mockito.never()).save(any(Project.class));
    }

    // ---------- account removal ----------

    private User user(Long id, String email, UserRole role) {
        User u = new User("Someone", email, "hash", role);
        u.setId(id);
        return u;
    }

    /**
     * An admin deleting themselves would leave the browser signed in as an
     * account that no longer exists, with no way back in.
     */
    @Test
    void anAdminCannotDeleteTheirOwnAccount() {
        User admin = user(1L, "dev@nexus.com", UserRole.ADMIN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.delete(1L, "DEV@nexus.com"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("your own account");

        verify(userRepository, org.mockito.Mockito.never()).delete(any(User.class));
    }

    /** Deleting the last admin leaves the workspace with nobody who can manage it. */
    @Test
    void theLastAdminCannotBeDeleted() {
        User admin = user(2L, "other@nexus.com", UserRole.ADMIN);
        when(userRepository.findById(2L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> userService.delete(2L, "dev@nexus.com"))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("only remaining admin");

        verify(userRepository, org.mockito.Mockito.never()).delete(any(User.class));
    }

    @Test
    void anAdminCanBeDeletedWhileAnotherRemains() {
        User admin = user(2L, "other@nexus.com", UserRole.ADMIN);
        when(userRepository.findById(2L)).thenReturn(Optional.of(admin));
        when(userRepository.countByRole(UserRole.ADMIN)).thenReturn(2L);

        userService.delete(2L, "dev@nexus.com");

        verify(userRepository).delete(admin);
    }

    @Test
    void aMemberCanAlwaysBeRemoved() {
        User member = user(3L, "member@nexus.com", UserRole.MEMBER);
        when(userRepository.findById(3L)).thenReturn(Optional.of(member));

        userService.delete(3L, "dev@nexus.com");

        verify(userRepository).delete(member);
    }

    @Test
    void deletingAnUnknownUserIs404() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.delete(404L, "dev@nexus.com"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
