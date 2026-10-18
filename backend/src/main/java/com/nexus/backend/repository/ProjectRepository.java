package com.nexus.backend.repository;

import com.nexus.backend.domain.project.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * The company's projects. Listings go through here rather than
     * {@code findAll} because a workspace view is by definition one company's:
     * the whole point of the tenancy work is that no query answers with
     * everybody's rows.
     */
    List<Project> findByCompanyId(Long companyId);

    /** One project, only if it belongs to this company. */
    Optional<Project> findByIdAndCompanyId(Long id, Long companyId);

    /**
     * Existence within the company, so a delete can refuse a project belonging
     * to another company without first loading it.
     */
    boolean existsByIdAndCompanyId(Long id, Long companyId);

    /** Retained for callers that already hold a project of the right company. */
    List<Project> findByWorkspaceId(Long workspaceId);
}
