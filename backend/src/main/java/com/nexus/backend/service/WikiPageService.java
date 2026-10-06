package com.nexus.backend.service;

import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.wiki.WikiPage;
import com.nexus.backend.dto.WikiPageRequest;
import com.nexus.backend.dto.WikiPageResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.WikiPageRepository;
import com.nexus.backend.service.knowledge.KnowledgeEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WikiPageService {

    private final WikiPageRepository wikiPageRepository;
    private final ProjectRepository projectRepository;
    private final ApplicationEventPublisher knowledgePublisher;

    public WikiPageService(
            WikiPageRepository wikiPageRepository,
            ProjectRepository projectRepository,
            ApplicationEventPublisher knowledgePublisher) {
        this.wikiPageRepository = wikiPageRepository;
        this.projectRepository = projectRepository;
        this.knowledgePublisher = knowledgePublisher;
    }

    @Transactional(readOnly = true)
    public List<WikiPageResponse> findAll(String query) {
        List<WikiPage> pages = (query == null || query.isBlank())
            ? wikiPageRepository.findAll()
            : wikiPageRepository.findByTitleContainingIgnoreCaseOrContentContainingIgnoreCase(query, query);
        return pages.stream().map(this::mapToResponse).toList();
    }

    @Transactional(readOnly = true)
    public WikiPageResponse findById(Long id) {
        WikiPage page = wikiPageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Wiki page", "id", id));
        return mapToResponse(page);
    }

    @Transactional
    public WikiPageResponse create(WikiPageRequest request) {
        WikiPage page = new WikiPage();
        page.setTitle(request.title());
        page.setContent(request.content());
        page.setAuthor(currentUserEmail());
        if (request.projectId() != null) {
            Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", request.projectId()));
            page.setProject(project);
        }
        WikiPage saved = wikiPageRepository.save(page);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.WIKI_PAGE, saved.getId());
        return mapToResponse(saved);
    }

    @Transactional
    public WikiPageResponse update(Long id, WikiPageRequest request) {
        WikiPage page = wikiPageRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Wiki page", "id", id));
        page.setTitle(request.title());
        page.setContent(request.content());
        if (request.projectId() != null) {
            Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", request.projectId()));
            page.setProject(project);
        } else {
            page.setProject(null);
        }
        WikiPage saved = wikiPageRepository.save(page);
        KnowledgeEvents.changed(knowledgePublisher, KnowledgeSourceType.WIKI_PAGE, saved.getId());
        return mapToResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!wikiPageRepository.existsById(id)) {
            throw new ResourceNotFoundException("Wiki page", "id", id);
        }
        wikiPageRepository.deleteById(id);
        KnowledgeEvents.removed(knowledgePublisher, KnowledgeSourceType.WIKI_PAGE, id);
    }

    private String currentUserEmail() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    private WikiPageResponse mapToResponse(WikiPage page) {
        return new WikiPageResponse(
            page.getId(),
            page.getTitle(),
            page.getContent(),
            page.getAuthor(),
            page.getProject() != null ? page.getProject().getId() : null,
            page.getProject() != null ? page.getProject().getName() : null,
            page.getCreatedAt(),
            page.getUpdatedAt()
        );
    }
}
