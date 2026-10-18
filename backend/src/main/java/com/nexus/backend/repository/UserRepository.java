package com.nexus.backend.repository;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Account lookups.
 *
 * <p>The three email/Supabase finders are deliberately <b>not</b> company-scoped:
 * they answer "who is signing in", and a person has not told us which company
 * they belong to at that point. That is the single documented exception to
 * company scoping on this repository, it is listed in docs/MULTI_TENANCY.md, and
 * the Phase 4 architecture test allowlists it. Every other method here takes a
 * company.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Login path — cross-company by necessity. See the class comment. */
    Optional<User> findByEmail(String email);

    /** Login path — cross-company by necessity. See the class comment. */
    Optional<User> findBySupabaseId(java.util.UUID supabaseId);

    /**
     * Case-insensitive email lookup. Supabase lowercases the emails it issues,
     * while a local account may have been registered with mixed case; using
     * this as a fallback links the two instead of creating a duplicate.
     *
     * <p>Login path — cross-company by necessity. See the class comment.
     */
    Optional<User> findFirstByEmailIgnoreCase(String email);

    /** One account, only if it belongs to this company. */
    Optional<User> findByIdAndCompanyId(Long id, Long companyId);

    /** The company's people, in a stable order. */
    List<User> findByCompanyIdOrderByIdAsc(Long companyId);

    /**
     * How many accounts in this company hold this role. Refuses deleting the
     * last admin, which would leave the company with nobody able to manage it.
     * Scoped, because a company's own admin count is the only one that matters
     * to that guard.
     */
    long countByRoleAndCompanyId(UserRole role, Long companyId);

    /**
     * Everyone in the company except this account, in a stable order. Backs mail
     * written to the whole company, where the sender is the one person who does
     * not get a copy.
     */
    List<User> findByIdNotAndCompanyIdOrderByIdAsc(Long id, Long companyId);
}
