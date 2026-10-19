package com.nexus.backend.repository;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Same lookup as {@link #findByEmail}, but with the account's company
     * eagerly fetched. Authentication reads the company's suspension status on
     * every request, and a lazily-loaded company would be a detached proxy
     * outside a transaction — reading it would throw instead of answering
     * whether the workspace is suspended.
     */
    @Query("select u from User u left join fetch u.company where u.email = :email")
    Optional<User> findByEmailWithCompany(@Param("email") String email);

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

    /**
     * Case-insensitive variant of {@link #findByEmailWithCompany}, used on the
     * same paths for the same reason (see that method and the class comment).
     */
    @Query("select u from User u left join fetch u.company where lower(u.email) = lower(:email)")
    Optional<User> findFirstByEmailIgnoreCaseWithCompany(@Param("email") String email);

    /** One account, only if it belongs to this company. */
    @Query("select u from User u where u.id = :id and u.company.id = :companyId")
    Optional<User> findByIdAndCompanyId(@Param("id") Long id, @Param("companyId") Long companyId);

    /** The company's people, in a stable order. */
    @Query("select u from User u where u.company.id = :companyId order by u.id asc")
    List<User> findByCompanyIdOrderByIdAsc(@Param("companyId") Long companyId);

    /**
     * How many accounts in this company hold this role. Refuses deleting the
     * last admin, which would leave the company with nobody able to manage it.
     * Scoped, because a company's own admin count is the only one that matters
     * to that guard.
     */
    @Query("select count(u) from User u where u.role = :role and u.company.id = :companyId")
    long countByRoleAndCompanyId(@Param("role") UserRole role, @Param("companyId") Long companyId);

    /**
     * Everyone in the company except this account, in a stable order. Backs mail
     * written to the whole company, where the sender is the one person who does
     * not get a copy.
     */
    @Query("select u from User u where u.id <> :id and u.company.id = :companyId order by u.id asc")
    List<User> findByIdNotAndCompanyIdOrderByIdAsc(
        @Param("id") Long id,
        @Param("companyId") Long companyId
    );

    /**
     * How many accounts each company holds, in one query. Feeds the SUPER_ADMIN
     * company listing, where an operator weighing a suspension wants the headcount
     * that action locks out — per-company {@code countBy...} calls would be one
     * round trip per company.
     *
     * <p>Groups on the account's own company and skips the companyless case
     * (SUPER_ADMIN), so the rows are always {@code [companyId, count]}.
     */
    @Query("select u.company.id, count(u) from User u where u.company is not null group by u.company.id")
    List<Object[]> countGroupedByCompany();
}
