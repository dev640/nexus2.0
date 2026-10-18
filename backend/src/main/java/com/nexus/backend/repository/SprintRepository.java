package com.nexus.backend.repository;

import com.nexus.backend.domain.sprint.Sprint;
import com.nexus.backend.domain.project.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SprintRepository extends JpaRepository<Sprint, Long> {

    /** The company's sprints, for the workspace view. */
    List<Sprint> findByCompanyId(Long companyId);

    /** One sprint, only if it belongs to this company. */
    Optional<Sprint> findByIdAndCompanyId(Long id, Long companyId);

    /** Retained: the project is resolved through a company-scoped lookup first. */
    List<Sprint> findByProjectId(Long projectId);

    List<Sprint> findByProject(Project project);

    Long countByProject(Project project);
}
