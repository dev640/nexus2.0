package com.nexus.backend.repository;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
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
}
