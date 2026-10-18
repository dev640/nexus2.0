package com.nexus.backend.security;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Establishes who a WebSocket connection belongs to, and which company that
 * makes it a member of.
 *
 * <p>A handshake proves an address, not an account: the token carries an email,
 * but the company - the fact every fan-out decision depends on - lives on the
 * account row. Reading it from the database rather than from a token claim keeps
 * one source of truth, so moving somebody between companies takes effect on
 * their next connection instead of when their token expires.
 *
 * <p>Two identities are refused rather than resolved. An account with no company
 * is a SUPER_ADMIN: a global operator whose rows are nobody's, and whose socket
 * would otherwise be a subscriber to every company's traffic with no company of
 * its own to match against. Requiring a company here is what makes the fan-out
 * filters total - a session either has one or it never opens.
 */
@Component
@RequiredArgsConstructor
public class SocketPrincipalResolver {

    /**
     * Who is on the other end of a socket.
     *
     * @param userId   the account, which is what a targeted send addresses
     * @param companyId the account's company, which is what a broadcast filters on
     */
    public record Identity(Long userId, Long companyId, String email) {}

    private final UserRepository userRepository;

    /** The identity behind an address, or empty when it may not hold a socket. */
    public Optional<Identity> resolve(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return findByEmail(email)
            .filter(user -> user.getRole() != UserRole.SUPER_ADMIN)
            .map(user -> new Identity(user.getId(), user.getCompanyId(), user.getEmail()))
            // A company is required, not merely preferred: see the class note.
            .filter(identity -> identity.companyId() != null);
    }

    private Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email)
            .or(() -> userRepository.findFirstByEmailIgnoreCase(email));
    }
}
