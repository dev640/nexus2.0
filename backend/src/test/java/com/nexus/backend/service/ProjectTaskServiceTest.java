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
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.task.TaskPriority;
import com.nexus.backend.domain.task.TaskStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.ProjectRequest;
import com.nexus.backend.dto.TaskRequest;
import com.nexus.backend.dto.UserResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectTaskServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private SprintRepository sprintRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ProjectService projectService;

    @InjectMocks
    private TaskService taskService;

    private Project project;

    @BeforeEach
    void setUp() {
        project = new Project("Demo", "demo project", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        project.setId(1L);
        project.setCreatedAt(LocalDateTime.now());
        project.setUpdatedAt(LocalDateTime.now());
    }

    // ---------- ProjectService ----------

    @Test
    void createProjectDefaultsHealthProgressAndMemberCount() {
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(2L);
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            return p;
        });

        var response = projectService.create(new ProjectRequest("New Project", "desc", null, null));

        assertThat(response.status()).isEqualTo(ProjectStatus.PLANNING);
        assertThat(response.health()).isEqualTo(ProjectHealth.ON_TRACK);
        assertThat(response.progress()).isZero();
        assertThat(response.memberCount()).isEqualTo(1);
    }

    @Test
    void findProjectByIdThrowsWhenMissing() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.findById(99L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("99");
    }

    @Test
    void deleteProjectThrowsWhenMissing() {
        when(projectRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> projectService.delete(99L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- TaskService ----------

    private TaskRequest taskRequest() {
        return new TaskRequest(
            "Write docs", "api docs", 1L, null,
            TaskStatus.TODO, TaskPriority.LOW, 3, null, List.of("docs"), null);
    }

    @Test
    void createTaskMapsProjectAndDefaultsStoryPoints() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = taskService.create(new TaskRequest(
            "Write docs", "api docs", 1L, null,
            TaskStatus.TODO, TaskPriority.LOW, null, null, List.of("docs"), null));

        assertThat(response.projectId()).isEqualTo(1L);
        assertThat(response.projectName()).isEqualTo("Demo");
        assertThat(response.storyPoints()).isZero();
        assertThat(response.labels()).containsExactly("docs");
    }

    @Test
    void createTaskThrowsWhenProjectMissing() {
        when(projectRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.create(new TaskRequest(
            "x", null, 404L, null, TaskStatus.TODO, TaskPriority.LOW, 1, null, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createTaskThrowsWhenAssigneeMissing() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userRepository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.create(new TaskRequest(
            "x", null, 1L, null, TaskStatus.TODO, TaskPriority.LOW, 1, 77L, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStatusPersistsChange() {
        Task task = new Task();
        task.setId(5L);
        task.setTitle("t");
        task.setProject(project);
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setLabels(new java.util.ArrayList<>());
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = taskService.updateStatus(5L, TaskStatus.DONE);

        assertThat(response.status()).isEqualTo(TaskStatus.DONE);
        verify(taskRepository).save(task);
    }

    @Test
    void assigneeIsMappedIntoResponse() {
        Task task = new Task();
        task.setId(6L);
        task.setTitle("t");
        task.setProject(project);
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        User assignee = new User();
        assignee.setId(3L);
        assignee.setName("Vidhi");
        assignee.setEmail("vidhi@nexus.com");
        assignee.setRole(UserRole.MEMBER);
        task.setAssignee(assignee);
        task.setLabels(new java.util.ArrayList<>());
        when(taskRepository.findById(6L)).thenReturn(Optional.of(task));

        var response = taskService.findById(6L);

        assertThat(response.assignee()).isEqualTo(new UserResponse(3L, "Vidhi", "vidhi@nexus.com", UserRole.MEMBER, null, null));
    }

    @Test
    void deleteTaskThrowsWhenMissing() {
        when(taskRepository.existsById(505L)).thenReturn(false);

        assertThatThrownBy(() -> taskService.delete(505L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateTaskMovesTaskToTheRequestedProject() {
        Project target = new Project("Second", "second", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        target.setId(2L);
        Task task = existingTask();
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(2L)).thenReturn(Optional.of(target));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = taskService.update(5L, new TaskRequest(
            "Moved", "d", 2L, null, TaskStatus.TODO, TaskPriority.HIGH, 5, null, List.of(), null));

        // projectId was previously ignored, so the task silently stayed put.
        assertThat(response.projectId()).isEqualTo(2L);
        assertThat(response.projectName()).isEqualTo("Second");
    }

    @Test
    void updateTaskKeepsStoryPointsWhenOmitted() {
        Task task = existingTask();
        task.setStoryPoints(8);
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = taskService.update(5L, new TaskRequest(
            "No points given", null, 1L, null, TaskStatus.TODO, TaskPriority.LOW, null, null, List.of(), null));

        // story_points is NOT NULL: an omitted field must not null the column.
        assertThat(response.storyPoints()).isEqualTo(8);
    }

    @Test
    void updateTaskRejectsSprintFromAnotherProject() {
        Project other = new Project("Second", "second", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        other.setId(2L);
        Sprint foreignSprint = new Sprint();
        foreignSprint.setId(9L);
        foreignSprint.setProject(other);
        foreignSprint.setNumber(1);
        foreignSprint.setGoal("g");
        foreignSprint.setStartDate(java.time.LocalDate.now());
        foreignSprint.setEndDate(java.time.LocalDate.now().plusDays(7));

        Task task = existingTask();
        when(taskRepository.findById(5L)).thenReturn(Optional.of(task));
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(sprintRepository.findById(9L)).thenReturn(Optional.of(foreignSprint));

        assertThatThrownBy(() -> taskService.update(5L, new TaskRequest(
            "Cross project", null, 1L, 9L, TaskStatus.TODO, TaskPriority.LOW, 1, null, List.of(), null)))
            .isInstanceOf(com.nexus.backend.exception.ValidationException.class)
            .hasMessageContaining("different project");
    }

    @Test
    void createTaskRejectsSprintFromAnotherProject() {
        Project other = new Project("Second", "second", ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        other.setId(2L);
        Sprint foreignSprint = new Sprint();
        foreignSprint.setId(9L);
        foreignSprint.setProject(other);
        foreignSprint.setNumber(1);
        foreignSprint.setGoal("g");
        foreignSprint.setStartDate(java.time.LocalDate.now());
        foreignSprint.setEndDate(java.time.LocalDate.now().plusDays(7));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(sprintRepository.findById(9L)).thenReturn(Optional.of(foreignSprint));

        assertThatThrownBy(() -> taskService.create(new TaskRequest(
            "x", null, 1L, 9L, TaskStatus.TODO, TaskPriority.LOW, 1, null, List.of(), null)))
            .isInstanceOf(com.nexus.backend.exception.ValidationException.class);
    }

    private Task existingTask() {
        Task task = new Task();
        task.setId(5L);
        task.setTitle("t");
        task.setProject(project);
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        task.setLabels(new java.util.ArrayList<>());
        return task;
    }
}
