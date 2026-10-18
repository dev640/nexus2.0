package com.nexus.backend.service;

import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.sprint.SprintStatus;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.task.TaskStatus;
import com.nexus.backend.dto.AnalyticsResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.tenant.CurrentTenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analytics over the caller's company only.
 *
 * <p>The numbers are aggregates, which makes them an easy place to leak: a
 * count says nothing about a specific row, but a chart of another company's
 * velocity still tells you their velocity. Both the chosen project and the
 * fallback "the whole workspace" path are therefore company-scoped.
 */
@Service
public class AnalyticsService {

    private final ProjectRepository projectRepository;
    private final SprintRepository sprintRepository;
    private final TaskRepository taskRepository;
    private final CurrentTenant currentTenant;

    public AnalyticsService(ProjectRepository projectRepository, SprintRepository sprintRepository,
                            TaskRepository taskRepository, CurrentTenant currentTenant) {
        this.projectRepository = projectRepository;
        this.sprintRepository = sprintRepository;
        this.taskRepository = taskRepository;
        this.currentTenant = currentTenant;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse getOverview(Long projectId) {
        Long companyId = currentTenant.require();
        Project project = projectId != null
            ? projectRepository.findByIdAndCompanyId(projectId, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId))
            : projectRepository.findByCompanyId(companyId).stream().findFirst().orElse(null);

        List<Task> tasks = project != null
            ? taskRepository.findByProject(project)
            : taskRepository.findByCompanyId(companyId);
        List<Sprint> sprints = project != null
            ? sprintRepository.findByProject(project)
            : sprintsOf(tasks);

        // Velocity per sprint (ordered by number)
        List<AnalyticsResponse.SprintVelocity> perSprint = sprints.stream()
            .sorted(Comparator.comparing(Sprint::getNumber))
            .map(s -> new AnalyticsResponse.SprintVelocity(
                s.getId(),
                s.getNumber(),
                s.getGoal(),
                s.getCommittedPoints(),
                tasks.stream()
                    .filter(t -> t.getSprint() != null && t.getSprint().getId().equals(s.getId()))
                    .filter(t -> t.getStatus() == TaskStatus.DONE)
                    .mapToInt(t -> t.getStoryPoints() != null ? t.getStoryPoints() : 0)
                    .sum()
            ))
            .toList();

        // Status / priority breakdowns
        Map<String, Long> byStatus = tasks.stream()
            .collect(Collectors.groupingBy(t -> t.getStatus().name(), Collectors.counting()));
        Map<String, Long> byPriority = tasks.stream()
            .collect(Collectors.groupingBy(t -> t.getPriority().name(), Collectors.counting()));

        // Team load: open tasks and points per assignee
        List<AnalyticsResponse.MemberLoad> teamLoad = tasks.stream()
            .filter(t -> t.getAssignee() != null)
            .filter(t -> t.getStatus() != TaskStatus.DONE)
            .collect(Collectors.groupingBy(t -> t.getAssignee(), Collectors.toList()))
            .entrySet().stream()
            .map(e -> new AnalyticsResponse.MemberLoad(
                e.getKey().getId(),
                e.getKey().getName(),
                (long) e.getValue().size(),
                e.getValue().stream().mapToInt(t -> t.getStoryPoints() != null ? t.getStoryPoints() : 0).sum()
            ))
            .sorted(Comparator.comparing(AnalyticsResponse.MemberLoad::openPoints).reversed())
            .toList();

        // Risks: blocked-ish patterns computed from real data
        List<AnalyticsResponse.RiskItem> risks = tasks.stream()
            .filter(t -> t.getStatus() != TaskStatus.DONE)
            .map(t -> {
                if (t.getStatus() == TaskStatus.TESTING) {
                    return riskOf(t, "In testing — verify before sprint close");
                }
                if (t.getStatus() == TaskStatus.IN_REVIEW) {
                    return riskOf(t, "Waiting on review — stale reviews stall sprints");
                }
                if (t.getPriority() == com.nexus.backend.domain.task.TaskPriority.URGENT
                    && t.getStatus() != TaskStatus.IN_PROGRESS) {
                    return riskOf(t, "Urgent priority but not yet started");
                }
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .limit(10)
            .toList();

        long done = tasks.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
        Sprint activeSprint = sprints.stream()
            .filter(s -> s.getStatus() == SprintStatus.ACTIVE)
            .findFirst().orElse(null);

        Integer committedActive = activeSprint != null ? activeSprint.getCommittedPoints() : 0;
        Integer doneActive = activeSprint != null
            ? tasks.stream()
                .filter(t -> t.getSprint() != null && t.getSprint().getId().equals(activeSprint.getId()))
                .filter(t -> t.getStatus() == TaskStatus.DONE)
                .mapToInt(t -> t.getStoryPoints() != null ? t.getStoryPoints() : 0)
                .sum()
            : 0;

        return new AnalyticsResponse(
            new AnalyticsResponse.VelocityData(perSprint),
            byStatus.entrySet().stream()
                .map(e -> new AnalyticsResponse.StatusCount(e.getKey(), e.getValue())).toList(),
            byPriority.entrySet().stream()
                .map(e -> new AnalyticsResponse.PriorityCount(e.getKey(), e.getValue())).toList(),
            teamLoad,
            risks,
            new AnalyticsResponse.Summary(
                (long) tasks.size(),
                done,
                tasks.isEmpty() ? 0.0 : Math.round(1000.0 * done / tasks.size()) / 10.0,
                committedActive,
                doneActive
            )
        );
    }

    private AnalyticsResponse.RiskItem riskOf(Task t, String reason) {
        return new AnalyticsResponse.RiskItem(
            t.getId(), t.getTitle(), reason, t.getPriority().name(), t.getStatus().name()
        );
    }

    private List<Sprint> sprintsOf(List<Task> tasks) {
        return tasks.stream().map(Task::getSprint)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }
}
