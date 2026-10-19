package com.nexus.backend.security;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves an account by email for authentication.
 *
 * <p>The lookup is deliberately global rather than company-scoped: a person
 * signing in has not told us which company they belong to yet, so the login
 * itself is the one query that must cross companies. Everything after it is
 * scoped. This exception is recorded in docs/MULTI_TENANCY.md and is allowlisted
 * by the architecture test.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // The company is fetched with the account because the principal carries its
        // suspension status, and a lazily-loaded company would be a detached proxy
        // here — reading it outside a transaction would throw rather than answer.
        User user = userRepository.findByEmailWithCompany(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        return NexusUserPrincipal.from(user);
    }
}
