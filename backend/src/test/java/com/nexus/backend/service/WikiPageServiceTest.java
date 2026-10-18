package com.nexus.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.project.ProjectHealth;
import com.nexus.backend.domain.project.ProjectStatus;
import com.nexus.backend.domain.wiki.WikiPage;
import com.nexus.backend.dto.WikiPageRequest;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.WikiPageRepository;
import com.nexus.backend.service.activity.WorkspaceActivity;
import com.nexus.backend.tenant.CurrentTenant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class WikiPageServiceTest {

    private static final Long COMPANY_ID = 11L;

    @Mock
    private WikiPageRepository wikiPageRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private CurrentTenant currentTenant;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private WikiPageService wikiPageService;

    @BeforeEach
    void setUp() {
        when(currentTenant.require()).thenReturn(COMPANY_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void findAllUsesTenantScopedListingForBlankQuery() {
        WikiPage page = wikiPage(5L, "Runbook", "Steps", null);
        when(wikiPageRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(page));

        var result = wikiPageService.findAll("  ");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Runbook");
        assertThat(result.getFirst().content()).isEqualTo("Steps");
        verify(wikiPageRepository).findByCompanyId(COMPANY_ID);
        verify(wikiPageRepository, never()).searchInCompany(any(), any());
    }

    @Test
    void findAllUsesTenantScopedSearchForNonBlankQuery() {
        when(wikiPageRepository.searchInCompany(COMPANY_ID, "security")).thenReturn(List.of());

        assertThat(wikiPageService.findAll("security")).isEmpty();

        verify(wikiPageRepository).searchInCompany(COMPANY_ID, "security");
        verify(wikiPageRepository, never()).findByCompanyId(any());
    }

    @Test
    void findByIdMapsProjectDetails() {
        Project project = project(3L, "Operations");
        WikiPage page = wikiPage(5L, "Runbook", "Steps", project);
        when(wikiPageRepository.findByIdAndCompanyId(5L, COMPANY_ID)).thenReturn(Optional.of(page));

        var result = wikiPageService.findById(5L);

        assertThat(result.projectId()).isEqualTo(3L);
        assertThat(result.projectName()).isEqualTo("Operations");
        verify(wikiPageRepository).findByIdAndCompanyId(5L, COMPANY_ID);
    }

    @Test
    void findByIdThrowsWhenPageIsOutsideTheTenantOrMissing() {
        when(wikiPageRepository.findByIdAndCompanyId(404L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wikiPageService.findById(404L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("404");
    }

    @Test
    void createMapsAuthorAndProjectAndPublishesWorkspaceActivity() {
        Project project = project(3L, "Operations");
        when(projectRepository.findByIdAndCompanyId(3L, COMPANY_ID)).thenReturn(Optional.of(project));
        when(wikiPageRepository.save(any(WikiPage.class))).thenAnswer(invocation -> {
            WikiPage saved = invocation.getArgument(0);
            saved.setId(8L);
            return saved;
        });
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("writer@nexus.test", "ignored"));

        var result = wikiPageService.create(new WikiPageRequest("Runbook", "Steps", 3L));

        assertThat(result.id()).isEqualTo(8L);
        assertThat(result.author()).isEqualTo("writer@nexus.test");
        assertThat(result.projectId()).isEqualTo(3L);
        assertThat(result.projectName()).isEqualTo("Operations");
        verify(eventPublisher).publishEvent(isA(WorkspaceActivity.class));
    }

    @Test
    void createRejectsAProjectNotFoundInTheCurrentTenant() {
        when(projectRepository.findByIdAndCompanyId(99L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> wikiPageService.create(new WikiPageRequest("Runbook", "Steps", 99L)))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("99");

        verify(wikiPageRepository, never()).save(any(WikiPage.class));
    }

    @Test
    void updateClearsProjectWhenRequestOmitsProjectId() {
        WikiPage page = wikiPage(5L, "Old title", "Old body", project(3L, "Operations"));
        when(wikiPageRepository.findByIdAndCompanyId(5L, COMPANY_ID)).thenReturn(Optional.of(page));
        when(wikiPageRepository.save(page)).thenReturn(page);

        var result = wikiPageService.update(5L, new WikiPageRequest("New title", "New body", null));

        assertThat(result.title()).isEqualTo("New title");
        assertThat(result.content()).isEqualTo("New body");
        assertThat(result.projectId()).isNull();
        verify(wikiPageRepository).save(page);
    }

    @Test
    void updateAssociatesOnlyAProjectResolvedForTheCurrentTenant() {
        Project project = project(3L, "Operations");
        WikiPage page = wikiPage(5L, "Old title", "Old body", null);
        when(wikiPageRepository.findByIdAndCompanyId(5L, COMPANY_ID)).thenReturn(Optional.of(page));
        when(projectRepository.findByIdAndCompanyId(3L, COMPANY_ID)).thenReturn(Optional.of(project));
        when(wikiPageRepository.save(page)).thenReturn(page);

        var result = wikiPageService.update(5L, new WikiPageRequest("New title", "New body", 3L));

        assertThat(result.projectId()).isEqualTo(3L);
        assertThat(result.projectName()).isEqualTo("Operations");
        verify(projectRepository).findByIdAndCompanyId(3L, COMPANY_ID);
    }

    @Test
    void deleteRemovesTheTenantScopedPage() {
        WikiPage page = wikiPage(5L, "Runbook", "Steps", null);
        when(wikiPageRepository.findByIdAndCompanyId(5L, COMPANY_ID)).thenReturn(Optional.of(page));

        wikiPageService.delete(5L);

        verify(wikiPageRepository).delete(page);
    }

    private static WikiPage wikiPage(Long id, String title, String content, Project project) {
        WikiPage page = new WikiPage();
        page.setId(id);
        page.setTitle(title);
        page.setContent(content);
        page.setAuthor("author@nexus.test");
        page.setProject(project);
        return page;
    }

    private static Project project(Long id, String name) {
        Project project = new Project(name, null, ProjectStatus.PLANNING, ProjectHealth.ON_TRACK);
        project.setId(id);
        return project;
    }
}