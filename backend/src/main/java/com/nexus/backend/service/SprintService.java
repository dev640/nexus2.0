package com.nexus.backend.service;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.sprint.SprintStatus;
import com.nexus.backend.domain.project.Project;
import com.nexus.backend.dto.SprintRequest;
import com.nexus.backend.dto.SprintResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.service.knowledge.KnowledgeEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SprintService {

    private final SprintRepository sprintRepository;
    private final ProjectRepository projectRepository;
private final TaskRepository taskRepository;
    private final ApplicationEventPublisher knowledgePublisher;

    @Transactional
    public SprintResponse create(SprintRequest request) {
        Project project = projectRepository.findById(request.projectId())
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", request.projectId()));

        if (request.endDate().isBefore(request.startDate())) {
            throw new ValidationException("End date must be after start date");
        }

        Sprint sprint = new Sprint();
        sprint.setProject(project);

        // Calculate sprint number
        long sprintCount = sprintRepository.countByProject(project);
        sprint.setNumber((int) sprintCount + 1);

        sprint.setGoal(request.goal());
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
        sprint.setCommittedPoints(request.committedPoints() != null ? request.committedPoints() : 0);
        sprint.setStatus(request.status() != null ? request.status() : SprintStatus.PLANNED);

        Sprint savedSprint = sprintRepository.save(sprint);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.SPRINT, savedSprint.getId());

        // Update project sprint number
        project.setSprintNumber(sprint.getNumber());
        projectRepository.save(project);

        return mapToResponse(savedSprint);
    }

    @Transactional(readOnly = true)
    public SprintResponse findById(Long id) {
        Sprint sprint = sprintRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", id));
        return mapToResponse(sprint);
    }

    @Transactional(readOnly = true)
    public List<SprintResponse> findAll() {
        return sprintRepository.findAll().stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SprintResponse> findByProjectId(Long projectId) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        return sprintRepository.findByProject(project).stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional
    public SprintResponse updateStatus(Long id, SprintStatus status) {
        Sprint sprint = sprintRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", id));
        sprint.setStatus(status);
        Sprint updatedSprint = sprintRepository.save(sprint);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.SPRINT, updatedSprint.getId());
        return mapToResponse(updatedSprint);
    }

    /**
     * Edits a sprint in place.
     *
     * <p>The project is deliberately not reassignable: {@code projectId} in the
     * request is validated to match the sprint's current project and otherwise
     * rejected, because moving a sprint would silently orphan its tasks and
     * renumber the target project's sprint sequence. Change the goal, dates,
     * commitment or status; to move work, create a sprint on the other project.
     */
    @Transactional
    public SprintResponse update(Long id, SprintRequest request) {
        Sprint sprint = sprintRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", id));

        if (request.projectId() != null && !request.projectId().equals(sprint.getProject().getId())) {
            throw new ValidationException("A sprint cannot be moved to a different project");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new ValidationException("End date must be after start date");
        }

        sprint.setGoal(request.goal());
        sprint.setStartDate(request.startDate());
        sprint.setEndDate(request.endDate());
        if (request.committedPoints() != null) {
            sprint.setCommittedPoints(request.committedPoints());
        }
        if (request.status() != null) {
            sprint.setStatus(request.status());
        }
        return mapToResponse(sprintRepository.save(sprint));
    }

    /**
     * Deletes a sprint. Only sprints with no tasks can be removed, because
     * deleting one holding tasks would either fail on the foreign key or leave
     * those tasks pointing at a sprint that no longer exists.
     */
    @Transactional
    public void delete(Long id) {
        Sprint sprint = sprintRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Sprint", "id", id));

        long taskCount = taskRepository.countBySprint(sprint);
        if (taskCount > 0) {
            throw new ValidationException(
                "Cannot delete a sprint that still holds " + taskCount
                    + (taskCount == 1 ? " task" : " tasks")
                    + " — move or delete them first");
        }

        Project project = sprint.getProject();
        sprintRepository.delete(sprint);
        // project.sprint_number tracks the high-water mark so the next created
        // sprint is not renumbered onto a number already used by a deleted one.
        if (project != null && project.getSprintNumber() != null && project.getSprintNumber() > sprint.getNumber()) {
            project.setSprintNumber(sprint.getNumber());
            projectRepository.save(project);
        }
    }

    private SprintResponse mapToResponse(Sprint sprint) {
        return new SprintResponse(
            sprint.getId(),
            sprint.getProject().getId(),
            sprint.getProject().getName(),
            sprint.getNumber(),
            sprint.getGoal(),
            sprint.getStartDate(),
            sprint.getEndDate(),
            sprint.getCommittedPoints(),
            sprint.getStatus(),
            sprint.getCreatedAt(),
            sprint.getUpdatedAt()
        );
    }
}
