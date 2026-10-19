package com.nexus.backend.security;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
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
 * its own to match against. An account in a suspended company is refused for the
 * same class of reason: its workspace's access has been withdrawn, and a socket
 * would carry the very traffic its HTTP requests are being refused. Requiring a
 * live, unsuspended company here is what makes the fan-out filters total — a
 * session either has one or it never opens.
 */
@Component
public class SocketPrincipalResolver {

    /**
     * Who is on the other end of a socket.
     *
     * @param userId   the account, which is what a targeted send addresses
    * @param companyId the account's company, which is what a broadcast filters on
    * @param tokenVersion the current credential version from the account row
     */
    public record Identity(Long userId, Long companyId, String email, int tokenVersion) {}

    private final UserRepository userRepository;

    public SocketPrincipalResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The identity behind an address, or empty when it may not hold a socket. */
    public Optional<Identity> resolve(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return findByEmail(email)
            .filter(user -> user.getRole() != UserRole.SUPER_ADMIN)
            // A suspended workspace must not keep, or open, a stream its HTTP
            // requests are refused: the socket carries the same traffic. Refusing
            // here closes the handshake path; the servlet filter refuses HTTP, and
            // CompanySocketCloser drops the sessions already open.
            .filter(user -> !isSuspended(user))
            .map(user -> new Identity(
                user.getId(), user.getCompanyId(), user.getEmail(), user.getTokenVersion()))
            // A company is required, not merely preferred: see the class note.
            .filter(identity -> identity.companyId() != null);
    }

    /**
     * Reads suspension off the account's company. The company is fetched with the
     * account (see {@code findByEmailWithCompany}) so this is not a detached-proxy
     * read outside a transaction.
     */
    private static boolean isSuspended(User user) {
        return user.getCompany() != null && user.getCompany().isSuspended();
    }

    private Optional<User> findByEmail(String email) {
        return userRepository.findByEmailWithCompany(email)
            .or(() -> userRepository.findFirstByEmailIgnoreCaseWithCompany(email));
    }
}
