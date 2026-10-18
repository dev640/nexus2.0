package com.nexus.backend.repository;

import com.nexus.backend.domain.workspace.Workspace;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /**
     * The company's workspaces. Scoped rather than {@code findAll()} because
     * several companies now share this table, and a listing that forgot the
     * filter would show one company's work to another.
     */
    List<Workspace> findByCompanyIdOrderByIdAsc(Long companyId);
}
