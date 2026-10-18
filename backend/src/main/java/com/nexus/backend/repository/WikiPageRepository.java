package com.nexus.backend.repository;

import com.nexus.backend.domain.wiki.WikiPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {

    /** The company's pages. */
    List<WikiPage> findByCompanyId(Long companyId);

    /**
     * One page, only if it belongs to this company.
     *
     * <p>Written as a query rather than a derived method on purpose:
     * {@code findByCompanyIdAndTitleContainingIgnoreCaseOrContentContainingIgnoreCase}
     * would parse as <em>(company AND title) OR content</em> — the right-hand
     * side of the Or is not under the company predicate — and would hand back
     * another company's pages whose content happened to match.
     */
    @Query("""
        select p from WikiPage p
        where p.companyId = :companyId
          and (lower(p.title) like lower(concat('%', :q, '%'))
            or lower(p.content) like lower(concat('%', :q, '%')))
        """)
    List<WikiPage> searchInCompany(@Param("companyId") Long companyId, @Param("q") String query);

    List<WikiPage> findByProjectId(Long projectId);

    Optional<WikiPage> findByIdAndCompanyId(Long id, Long companyId);

    boolean existsByIdAndCompanyId(Long id, Long companyId);
}
