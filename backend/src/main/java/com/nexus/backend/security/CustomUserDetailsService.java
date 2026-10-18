package com.nexus.backend.security;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        return NexusUserPrincipal.from(user);
    }
}
