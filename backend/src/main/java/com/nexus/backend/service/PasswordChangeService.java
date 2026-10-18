package com.nexus.backend.service;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.ChangePasswordRequest;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.NexusUserPrincipal;
import com.nexus.backend.security.SupabaseAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * A signed-in person replacing their own password.
 *
 * <p>Where the password lives decides how it is checked and written, and the two
 * stores are not interchangeable. A Supabase-linked account authenticates
 * against Supabase, which is the only place that credential exists — this server
 * holds no hash of it, and cannot check one. A purely local account gets a bcrypt
 * hash in {@code users.password} and is checked here. The branch follows the
 * account, never the deployment: a workspace can hold both kinds at once.
 *
 * <p>Two rules are enforced here rather than in the DTO, because both need the
 * stored credential to decide:
 *
 * <ul>
 *   <li>The current password must be proven. The caller already holds a valid
 *       token, so without this a stolen token could be turned into a stolen
 *       account by setting a new password with it.</li>
 *   <li>The new password must not be the current one. It is compared both as
 *       plain text and against the stored hash, because bcrypt only considers the
 *       first 72 bytes: two strings that share a long prefix are different
 *       strings but the same password, and a plain-text check alone would call
 *       that a change.</li>
 * </ul>
 *
 * <p><b>Known limitation.</b> For a local account the change does not invalidate
 * access tokens already issued to that account — they are stateless JWTs and
 * remain valid until they expire. Someone who already held one keeps it for that
 * window, which cannot be closed without recording a token version or a
 * password-changed timestamp on the account and checking it per request. A
 * Supabase-linked account has no such window: Supabase ends that account's
 * sessions when its password changes.
 */
@Service
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SupabaseAdminClient supabaseAdminClient;
    private final AuditService auditService;

    public PasswordChangeService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        SupabaseAdminClient supabaseAdminClient,
        AuditService auditService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.supabaseAdminClient = supabaseAdminClient;
        this.auditService = auditService;
    }

    /**
     * Replaces the caller's own password and clears the forced-change flag.
     *
     * <p>The account is loaded by the id on the authenticated principal, which
     * came from the database rather than from the request, so this cannot be
     * pointed at somebody else's account — there is no id in the request to
     * tamper with.
     *
     * @throws ValidationException when the current password does not match, is
     *         the same as the new one, or (for a Supabase account) cannot be
     *         checked at all
     */
    @Transactional
    public void changePassword(NexusUserPrincipal principal, ChangePasswordRequest request) {
        User user = userRepository.findById(principal.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", principal.getUserId()));

        String current = request.currentPassword();
        String next = request.newPassword();

        if (next.equals(current)) {
            throw new ValidationException(REUSE_MESSAGE);
        }

        verifyCurrentPassword(user, current, next);

        if (user.getSupabaseId() != null) {
            supabaseAdminClient.updatePassword(user.getSupabaseId(), next);
            // The account's existing sessions outliving the change is precisely
            // what matters after an admin has known the old password, so they are
            // ended here as well rather than relied on to lapse.
            supabaseAdminClient.revokeSessions(user.getSupabaseId());
        } else {
            user.setPassword(passwordEncoder.encode(next));
        }

        // The whole point of the flag: it is cleared by the act that satisfies it,
        // in the same transaction, so there is no window in which the password has
        // been changed and the account is still gated.
        user.setMustChangePassword(false);
        userRepository.save(user);

        // Recorded without the password or anything derived from it: the trail
        // answers "who changed their own password, and when".
        auditService.record(AuditAction.PASSWORD_CHANGED, "User", user.getId(),
            Map.of("email", user.getEmail()));

        log.info("User {} changed their own password", user.getEmail());
    }

    /** Message used for both kinds of reuse, since the caller cannot tell them apart. */
    private static final String REUSE_MESSAGE =
        "That is your current password. Choose a new one.";

    /**
     * Proves the current password against whichever store owns it.
     *
     * <p>The account is treated as Supabase-owned whenever it is linked, even if a
     * local hash somehow remains: the link means Supabase is the store that
     * authenticates it, and checking a stale hash instead would accept a password
     * that no longer signs anyone in.
     */
    private void verifyCurrentPassword(User user, String current, String next) {
        if (user.getSupabaseId() != null) {
            switch (supabaseAdminClient.verifyPassword(user.getEmail(), current)) {
                case MATCH -> { }
                case NO_MATCH -> throw new ValidationException(WRONG_PASSWORD_MESSAGE);
                case UNAVAILABLE -> throw new ValidationException(
                    "Your password could not be checked with Supabase just now, so it was not "
                        + "changed. Try again shortly — or ask an administrator to issue you a "
                        + "new password if it keeps failing.");
            }
            return;
        }

        if (user.getPassword() == null) {
            // No hash and no Supabase link: nothing in this system can authenticate
            // the account, so there is no password here to replace.
            throw new ValidationException("That account has no password store configured");
        }
        if (!passwordEncoder.matches(current, user.getPassword())) {
            throw new ValidationException(WRONG_PASSWORD_MESSAGE);
        }
        // Catches reuse that the plain-text comparison cannot see, because bcrypt
        // ignores everything past its 72nd byte.
        if (passwordEncoder.matches(next, user.getPassword())) {
            throw new ValidationException(REUSE_MESSAGE);
        }
    }

    /**
     * Deliberately does not say whether the account exists or which store holds
     * the password — only that this password is not it.
     */
    private static final String WRONG_PASSWORD_MESSAGE = "That is not your current password.";
}
