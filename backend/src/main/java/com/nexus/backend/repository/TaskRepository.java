package com.nexus.backend.repository;

import com.nexus.backend.domain.project.Project;
import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.task.Task;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {

    /** The company's tasks, for the workspace board and backlog. */
    List<Task> findByCompanyId(Long companyId);

    /** One task, only if it belongs to this company. */
    Optional<Task> findByIdAndCompanyId(Long id, Long companyId);

    /** Existence within the company, so a delete can refuse a foreign task. */
    boolean existsByIdAndCompanyId(Long id, Long companyId);

    /** Retained: the parent is resolved through a company-scoped lookup first. */
    List<Task> findByProjectId(Long projectId);

    List<Task> findBySprintId(Long sprintId);

    List<Task> findByProject(Project project);

    List<Task> findBySprint(Sprint sprint);

    /**
     * How many tasks sit in this sprint. Used to refuse deleting a sprint that
     * still holds work, rather than letting the delete fail on a foreign key.
     */
    long countBySprint(Sprint sprint);
}
