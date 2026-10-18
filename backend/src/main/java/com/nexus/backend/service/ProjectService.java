package com.nexus.backend.service;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.notification.Notification;
import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.project.ProjectHealth;
import com.nexus.backend.domain.project.ProjectStatus;
import com.nexus.backend.dto.ProjectRequest;
import com.nexus.backend.dto.ProjectResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.service.activity.ActivityEvents;
import com.nexus.backend.service.knowledge.KnowledgeEvents;
import com.nexus.backend.tenant.CurrentTenant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Projects of the caller's company.
 *
 * <p>Every read and write passes through a repository method that carries the
 * company, so a project id from another company is answered the same way as one
 * that does not exist. That is deliberate: a 404 rather than a 403 keeps ids
 * unprobeable, and there is nothing the caller could do with the knowledge that
 * the row exists elsewhere.
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final CurrentTenant currentTenant;
    private final ApplicationEventPublisher knowledgePublisher;

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        Project project = new Project();
        project.setName(request.name());
        project.setDescription(request.description());
        project.setStatus(request.status() != null ? request.status() : ProjectStatus.PLANNING);
        project.setHealth(ProjectHealth.ON_TRACK);
        project.setProgress(0);
        project.setSprintNumber(0);
        project.setMemberCount(1);
        // The company is stamped onto new rows by TenantStampListener from the
        // tenant the request filter established; a row with neither is refused
        // rather than inserted as somebody's by accident.

        Project savedProject = projectRepository.save(project);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.PROJECT, savedProject.getId());
        ActivityEvents.workspace(knowledgePublisher, Notification.Category.PROJECTS,
            "created the project \"" + savedProject.getName() + "\"",
            "/projects?project=" + savedProject.getId());
        return mapToResponse(savedProject);
    }

    public ProjectResponse findById(Long id) {
        Project project = projectRepository.findByIdAndCompanyId(id, currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", id));
        return mapToResponse(project);
    }

    public List<ProjectResponse> findAll() {
        return projectRepository.findByCompanyId(currentTenant.require()).stream()
            .map(this::mapToResponse)
            .toList();
    }

    @Transactional
    public ProjectResponse update(Long id, ProjectRequest request) {
        Project project = projectRepository.findByIdAndCompanyId(id, currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", id));

        project.setName(request.name());
        project.setDescription(request.description());
        if (request.status() != null) {
            project.setStatus(request.status());
        }

        Project updatedProject = projectRepository.save(project);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.PROJECT, updatedProject.getId());
        ActivityEvents.workspace(knowledgePublisher, Notification.Category.PROJECTS,
            "updated the project \"" + updatedProject.getName() + "\"",
            "/projects?project=" + updatedProject.getId());
        return mapToResponse(updatedProject);
    }

    @Transactional
    public void delete(Long id) {
        Project project = projectRepository.findByIdAndCompanyId(id, currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("Project", "id", id));
        projectRepository.delete(project);
        KnowledgeEvents.removed(knowledgePublisher, KnowledgeSourceType.PROJECT, id);
    }

    private ProjectResponse mapToResponse(Project project) {
        return new ProjectResponse(
            project.getId(),
            project.getName(),
            project.getDescription(),
            project.getStatus(),
            project.getHealth(),
            project.getProgress(),
            project.getSprintNumber(),
            project.getMemberCount(),
            project.getCreatedAt(),
            project.getUpdatedAt()
        );
    }
}
