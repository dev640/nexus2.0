package com.nexus.backend.repository;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    Optional<User> findBySupabaseId(java.util.UUID supabaseId);

    /**
     * Case-insensitive email lookup. Supabase lowercases the emails it issues,
     * while a local account may have been registered with mixed case; using
     * this as a fallback links the two instead of creating a duplicate.
     */
    Optional<User> findFirstByEmailIgnoreCase(String email);

    /**
     * How many accounts hold this role. Used to refuse deleting the last admin,
     * which would leave the workspace with no one able to manage it.
     */
    long countByRole(UserRole role);

    /**
     * Everyone except this account, in a stable order. Backs mail written to the
     * whole workspace, where the sender is the one person who does not get a copy.
     */
    List<User> findByIdNotOrderByIdAsc(Long id);

    /**
     * Returns the next value of the employee-code sequence so admin-created
     * accounts get a real NX-#### code instead of failing the NOT NULL constraint.
     */
    @org.springframework.data.jpa.repository.Query("select nextval('user_employee_code_seq')")
    long nextEmployeeCodeSeq();
}
