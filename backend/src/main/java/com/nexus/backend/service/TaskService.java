package com.nexus.backend.service;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.task.TaskStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.TaskRequest;
import com.nexus.backend.dto.TaskResponse;
import com.nexus.backend.dto.UserResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.knowledge.KnowledgeEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final SprintRepository sprintRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher knowledgePublisher;

    @Autowired
    @Lazy
    private NotificationService notificationService;

    @Transactional
    public TaskResponse create(TaskRequest request) {
        Project project = projectRepository.findById(request.projectId())
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", request.projectId()));

        Task task = new Task();
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setProject(project);

        if (request.sprintId() != null) {
            Sprint sprint = sprintRepository.findById(request.sprintId())
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", request.sprintId()));
            requireSameProject(project, sprint);
            task.setSprint(sprint);
        }

        task.setStatus(request.status());
        task.setPriority(request.priority());
        task.setStoryPoints(request.storyPoints() != null ? request.storyPoints() : 0);

        if (request.assigneeId() != null) {
            User assignee = userRepository.findById(request.assigneeId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.assigneeId()));
            task.setAssignee(assignee);
        }

        task.setLabels(request.labels());
        if (request.blocked() != null) {
            task.setBlocked(request.blocked());
        }

        Task savedTask = taskRepository.save(task);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.TASK, savedTask.getId());
        if (savedTask.getAssignee() != null) {
            notificationService.notifyTaskAssigned(savedTask, savedTask.getAssignee());
        }
        return mapToResponse(savedTask);
    }

    @Transactional(readOnly = true)
    public TaskResponse findById(Long id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Task", "id", id));
        return mapToResponse(task);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> findAll() {
        return taskRepository.findAll().stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> findByProjectId(Long projectId) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        return taskRepository.findByProject(project).stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> findBySprintId(Long sprintId) {
        Sprint sprint = sprintRepository.findById(sprintId)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", sprintId));
        return taskRepository.findBySprint(sprint).stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional
    public TaskResponse updateStatus(Long id, TaskStatus status) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Task", "id", id));
        task.setStatus(status);
        Task updatedTask = taskRepository.save(task);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.TASK, updatedTask.getId());
        return mapToResponse(updatedTask);
    }

    @Transactional
    public TaskResponse update(Long id, TaskRequest request) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Task", "id", id));

        // A task can be moved between projects, so honour projectId rather than
        // silently keeping the old one and ignoring part of the request body.
        Project project = projectRepository.findById(request.projectId())
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", request.projectId()));
        task.setProject(project);

        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setStatus(request.status());
        task.setPriority(request.priority());
        // story_points is NOT NULL: an omitted field must keep the current value
        // rather than nulling the column out from under Hibernate.
        if (request.storyPoints() != null) {
            task.setStoryPoints(request.storyPoints());
        }
        task.setLabels(request.labels() != null ? request.labels() : task.getLabels());
        // Null keeps the current value; only an explicit false unblocks.
        if (request.blocked() != null) {
            task.setBlocked(request.blocked());
        }

        // Notify on a newly added assignee (not on reassignment to the same person)
        User previousAssignee = task.getAssignee();
        if (request.assigneeId() != null) {
            User assignee = userRepository.findById(request.assigneeId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.assigneeId()));
            if (!assignee.equals(previousAssignee)) {
                task.setAssignee(assignee);
                task = taskRepository.save(task);
                notificationService.notifyTaskAssigned(task, assignee);
            }
        } else {
            task.setAssignee(null);
        }

        if (request.sprintId() != null) {
            Sprint sprint = sprintRepository.findById(request.sprintId())
                .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", request.sprintId()));
            // A task and its sprint must live in the same project, otherwise the
            // task silently disappears from that project's backlog and velocity.
            requireSameProject(project, sprint);
            task.setSprint(sprint);
        } else {
            task.setSprint(null);
        }

        Task updatedTask = taskRepository.save(task);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.TASK, updatedTask.getId());
        return mapToResponse(updatedTask);
    }

    @Transactional
    public void delete(Long id) {
        if (!taskRepository.existsById(id)) {
            throw new ResourceNotFoundException("Task", "id", id);
        }
        taskRepository.deleteById(id);
        KnowledgeEvents.removed(knowledgePublisher, KnowledgeSourceType.TASK, id);
    }

    /** A sprint from another project cannot hold this task's work. */
    private void requireSameProject(Project project, Sprint sprint) {
        Project sprintProject = sprint.getProject();
        if (sprintProject == null || !project.getId().equals(sprintProject.getId())) {
            throw new ValidationException("The selected sprint belongs to a different project");
        }
    }

    private TaskResponse mapToResponse(Task task) {
        UserResponse assigneeResponse = null;
        if (task.getAssignee() != null) {
            User assignee = task.getAssignee();
            assigneeResponse = new UserResponse(
                assignee.getId(),
                assignee.getName(),
                assignee.getEmail(),
                assignee.getRole(),
                assignee.getSupabaseId() != null ? assignee.getSupabaseId().toString() : null,
                assignee.getEmployeeCode()
            );
        }

        // Copy the persistent bag into a plain list so it is fully initialized
        // inside the session and Jackson never sees a lazy proxy.
        java.util.List<String> labels = task.getLabels() == null
            ? java.util.List.of()
            : new java.util.ArrayList<>(task.getLabels());

        return new TaskResponse(
            task.getId(),
            task.getTitle(),
            task.getDescription(),
            task.getProject().getId(),
            task.getProject().getName(),
            task.getSprint() != null ? task.getSprint().getId() : null,
            task.getStatus(),
            task.getPriority(),
            task.getStoryPoints(),
            assigneeResponse,
            labels,
            task.isBlocked(),
            task.getCreatedAt(),
            task.getUpdatedAt()
        );
    }
}
