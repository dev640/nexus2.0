package com.nexus.backend.service.knowledge;

import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.knowledge.KnowledgeSourceType;
import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.task.Task;
import com.nexus.backend.domain.whiteboard.WhiteboardNote;
import com.nexus.backend.domain.wiki.WikiPage;
import com.nexus.backend.repository.ProjectRepository;
import com.nexus.backend.repository.SprintRepository;
import com.nexus.backend.repository.TaskRepository;
import com.nexus.backend.repository.WhiteboardNoteRepository;
import com.nexus.backend.repository.WikiPageRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The ingestion layer's source feeders: turns Nexus records (wiki pages,
 * projects, tasks, sprints, whiteboard notes) into normalized
 * {@link SourceDocument}s with the metadata the knowledge store needs.
 *
 * Every document indexes as WORKSPACE scope today — matching the existing
 * flat workspace permission model — while the schema and retrieval already
 * support PRIVATE/ADMIN scopes for finer-grained data later.
 */
@Component
public class SourceDocuments {

    private final WikiPageRepository wikiPageRepository;
    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final SprintRepository sprintRepository;
    private final WhiteboardNoteRepository whiteboardNoteRepository;

    public SourceDocuments(
            WikiPageRepository wikiPageRepository,
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            SprintRepository sprintRepository,
            WhiteboardNoteRepository whiteboardNoteRepository) {
        this.wikiPageRepository = wikiPageRepository;
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.sprintRepository = sprintRepository;
        this.whiteboardNoteRepository = whiteboardNoteRepository;
    }

    /** The normalized document for one source record; empty when deleted. */
    public Optional<SourceDocument> find(KnowledgeSourceType type, Long id) {
        if (id == null) return Optional.empty();
        return switch (type) {
            case WIKI_PAGE -> wikiPageRepository.findById(id).map(this::wikiPage);
            case PROJECT -> projectRepository.findById(id).map(this::project);
            case TASK -> taskRepository.findById(id).map(this::task);
            case SPRINT -> sprintRepository.findById(id).map(this::sprint);
            case WHITEBOARD_NOTE -> whiteboardNoteRepository.findById(id).map(this::note);
        };
    }

    /** Every current source document — used by the incremental re-index. */
    public List<SourceDocument> all() {
        List<SourceDocument> documents = new ArrayList<>();
        wikiPageRepository.findAll().forEach(page -> documents.add(wikiPage(page)));
        projectRepository.findAll().forEach(p -> documents.add(project(p)));
        taskRepository.findAll().forEach(t -> documents.add(task(t)));
        sprintRepository.findAll().forEach(s -> documents.add(sprint(s)));
        whiteboardNoteRepository.findAll().forEach(n -> documents.add(note(n)));
        return documents;
    }

    // ---------- feeders ----------

    private SourceDocument wikiPage(WikiPage page) {
        return new SourceDocument(
            KnowledgeSourceType.WIKI_PAGE,
            page.getId(),
            nullSafe(page.getTitle()),
            nullSafe(page.getContent()),
            "WIKI",
            page.getProject() != null ? page.getProject().getId() : null,
            null,
            AccessScope.WORKSPACE,
            null,
            page.getUpdatedAt());
    }

    private SourceDocument project(Project project) {
        StringBuilder content = new StringBuilder();
        content.append("Status: ").append(project.getStatus())
            .append(" · Health: ").append(project.getHealth())
            .append(" · Progress: ").append(project.getProgress()).append('%')
            .append(" · Members: ").append(project.getMemberCount());
        if (project.getDescription() != null && !project.getDescription().isBlank()) {
            content.append("\n\n").append(project.getDescription().strip());
        }
        return new SourceDocument(
            KnowledgeSourceType.PROJECT,
            project.getId(),
            nullSafe(project.getName()),
            content.toString(),
            "PROJECT",
            project.getId(),
            null,
            AccessScope.WORKSPACE,
            null,
            project.getUpdatedAt());
    }

    private SourceDocument task(Task task) {
        StringBuilder content = new StringBuilder();
        content.append("Status: ").append(task.getStatus())
            .append(" · Priority: ").append(task.getPriority())
            .append(" · Story points: ").append(task.getStoryPoints());
        if (task.getAssignee() != null) content.append(" · Assignee: ").append(task.getAssignee().getName());
        if (task.getSprint() != null) content.append(" · Sprint ").append(task.getSprint().getNumber());
        if (task.getProject() != null) content.append(" · Project: ").append(task.getProject().getName());
        if (task.getTaskType() != null && !task.getTaskType().isBlank()) {
            content.append(" · Type: ").append(task.getTaskType());
        }
        if (task.getLabels() != null && !task.getLabels().isEmpty()) {
            content.append(" · Labels: ").append(String.join(", ", task.getLabels()));
        }
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            content.append("\n\n").append(task.getDescription().strip());
        }
        return new SourceDocument(
            KnowledgeSourceType.TASK,
            task.getId(),
            nullSafe(task.getTitle()),
            content.toString(),
            "TASK",
            task.getProject() != null ? task.getProject().getId() : null,
            task.getLabels() != null && !task.getLabels().isEmpty() ? String.join(",", task.getLabels()) : null,
            AccessScope.WORKSPACE,
            null,
            task.getUpdatedAt());
    }

    private SourceDocument sprint(Sprint sprint) {
        StringBuilder content = new StringBuilder();
        content.append("Status: ").append(sprint.getStatus())
            .append(" · Committed points: ").append(sprint.getCommittedPoints());
        if (sprint.getStartDate() != null) content.append(" · Start: ").append(sprint.getStartDate());
        if (sprint.getEndDate() != null) content.append(" · End: ").append(sprint.getEndDate());
        if (sprint.getProject() != null) content.append(" · Project: ").append(sprint.getProject().getName());
        if (sprint.getGoal() != null && !sprint.getGoal().isBlank()) {
            content.append("\n\nGoal: ").append(sprint.getGoal().strip());
        }
        String title = "Sprint " + sprint.getNumber()
            + (sprint.getProject() != null ? " — " + sprint.getProject().getName() : "");
        return new SourceDocument(
            KnowledgeSourceType.SPRINT,
            sprint.getId(),
            title,
            content.toString(),
            "SPRINT",
            sprint.getProject() != null ? sprint.getProject().getId() : null,
            null,
            AccessScope.WORKSPACE,
            null,
            sprint.getUpdatedAt());
    }

    private SourceDocument note(WhiteboardNote note) {
        String text = nullSafe(note.getText()).strip();
        String title = text.length() > 60 ? text.substring(0, 60) + "…" : (text.isBlank() ? "Sticky note" : text);
        StringBuilder content = new StringBuilder();
        content.append("Board: ").append(note.getBoard());
        if (note.getAuthor() != null) content.append(" · Author: ").append(note.getAuthor());
        if (!text.isBlank()) content.append("\n\n").append(text);
        return new SourceDocument(
            KnowledgeSourceType.WHITEBOARD_NOTE,
            note.getId(),
            title,
            content.toString(),
            "NOTE",
            null,
            null,
            AccessScope.WORKSPACE,
            null,
            note.getUpdatedAt());
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
