package com.nexus.backend.security;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The password-change gate for WebSocket handshakes.
 *
 * <p>A handshake cannot be gated by {@link MustChangePasswordFilter}: a browser
 * opens a socket without an Authorization header — the token travels as a query
 * parameter and is read by the handshake interceptor — so no principal ever
 * reaches the security context for that request. The rule therefore has to be
 * applied where the token is understood, which is the interceptor.
 *
 * <p>One rule in one place, shared by both sockets, because a socket streams the
 * same chat and whiteboard traffic an account's HTTP requests are being refused.
 * A gate that only covered HTTP would have left the stream open on the same
 * account.
 *
 * <p>An unknown address is <em>not</em> gated: this is a second opinion for an
 * identity the token already proved, not the authority on whether it exists. The
 * socket's own failure to find an account stays whatever it was.
 */
@Component
@RequiredArgsConstructor
public class MustChangePasswordHandshakeGuard {

    private final UserRepository userRepository;

    /** True when this account may not open a socket until it changes its password. */
    public boolean mustChangePassword(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return findByEmail(email).map(User::isMustChangePassword).orElse(false);
    }

    private Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email)
            .or(() -> userRepository.findFirstByEmailIgnoreCase(email));
    }
}
