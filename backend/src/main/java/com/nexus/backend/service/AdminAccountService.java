package com.nexus.backend.service;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.user.PasswordResetRequest;
import com.nexus.backend.domain.user.ResetRequestStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.CreateUserRequest;
import com.nexus.backend.dto.CreatedUserResponse;
import com.nexus.backend.dto.PasswordResetResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.PasswordResetRequestRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.SupabaseAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Admin-mediated account lifecycle: only an admin can create users, and only an
 * admin can turn a password-reset request into a working password.
 *
 * <p>Where the password is written depends on how the account authenticates. A
 * Supabase-linked account authenticates against Supabase, so its password goes to
 * the Supabase Admin API and the local {@code users.password} column stays null.
 * A purely local account gets a bcrypt hash. {@link SupabaseAdminClient} decides
 * which path is available.
 */
@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);

    /** Ambiguous glyphs (0/O, 1/l/I) are excluded so a password can be read aloud. */
    private static final String PW_LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String PW_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PW_DIGITS = "23456789";
    private static final String PW_SYMBOLS = "!@#$%^&*-_=+";

    private final UserRepository userRepository;
    private final PasswordResetRequestRepository resetRequestRepository;
    private final PasswordEncoder passwordEncoder;
    private final SupabaseAdminClient supabaseAdminClient;
    private final SupabaseProperties supabaseProperties;
    private final SecureRandom random = new SecureRandom();

    public AdminAccountService(
        UserRepository userRepository,
        PasswordResetRequestRepository resetRequestRepository,
        PasswordEncoder passwordEncoder,
        SupabaseAdminClient supabaseAdminClient,
        SupabaseProperties supabaseProperties
    ) {
        this.userRepository = userRepository;
        this.resetRequestRepository = resetRequestRepository;
        this.passwordEncoder = passwordEncoder;
        this.supabaseAdminClient = supabaseAdminClient;
        this.supabaseProperties = supabaseProperties;
    }

    // ---------------------------------------------------------------- users

    /**
     * Creates an account on an admin's behalf. The password is returned once so
     * the admin can hand it over; it is never persisted in plain text.
     */
    @Transactional
    public CreatedUserResponse createUser(CreateUserRequest request) {
        // If the workspace authenticates through Supabase but the Admin API is
        // unreachable, a local-only account would be created that the login screen
        // can never authenticate — the admin would believe it worked. Refuse
        // instead, matching what resolveRequest already does.
        if (supabaseProperties.isEnabled() && !supabaseAdminClient.isAvailable()) {
            throw new ValidationException(
                "This workspace signs in through Supabase. Set NEXUS_SUPABASE_SERVICE_ROLE_KEY "
                    + "on the server so the new account can actually be used to sign in."
            );
        }

        String email = normalize(request.email());
        if (userRepository.findByEmail(email).isPresent()
            || userRepository.findFirstByEmailIgnoreCase(email).isPresent()) {
            throw new ValidationException("A user with that email already exists");
        }

        String name = request.name().trim();
        // An absent role must not accidentally create an admin: default to MEMBER.
        UserRole role = request.role() == null ? UserRole.MEMBER : request.role();

        boolean adminSuppliedPassword = request.password() != null && !request.password().isBlank();
        String password = adminSuppliedPassword ? request.password() : generatePassword();

        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);

        if (supabaseAdminClient.isAvailable()) {
            UUID supabaseId = supabaseAdminClient.createUser(email, password, name);
            user.setSupabaseId(supabaseId);
            // Supabase owns the credential; leave the local hash null.
            user.setPassword(null);
        } else {
            log.warn("Supabase admin API unavailable — creating {} with a local password only", email);
            user.setPassword(passwordEncoder.encode(password));
        }

        User saved = userRepository.save(user);
        log.info("Admin created account {} with role {}", saved.getEmail(), role);

        return new CreatedUserResponse(
            saved.getId(), saved.getName(), saved.getEmail(), saved.getRole(),
            adminSuppliedPassword ? null : password
        );
    }

    // ------------------------------------------------------- reset requests

    /**
     * Files a password-reset request. Deliberately always succeeds: telling a
     * caller whether an address is registered would leak which emails exist in
     * the workspace.
     */
    @Transactional
    public void requestPasswordReset(String email, String note) {
        if (email == null || email.isBlank()) {
            throw new ValidationException("Email is required");
        }
        User user = userRepository.findByEmail(normalize(email))
            .or(() -> userRepository.findFirstByEmailIgnoreCase(normalize(email)))
            .orElse(null);
        if (user == null) {
            // Same outcome either way, so the response cannot be used to probe
            // for registered addresses.
            return;
        }
        if (resetRequestRepository
            .findFirstByUserIdAndStatus(user.getId(), ResetRequestStatus.PENDING)
            .isPresent()) {
            return;
        }
        resetRequestRepository.save(new PasswordResetRequest(user, blankToNull(note)));
        log.info("Password reset requested for {}", user.getEmail());
    }

    @Transactional(readOnly = true)
    public List<PasswordResetResponse> listRequests(boolean pendingOnly) {
        List<PasswordResetRequest> rows = pendingOnly
            ? resetRequestRepository.findByStatusWithUser(ResetRequestStatus.PENDING)
            : resetRequestRepository.findAllWithUser();
        return rows.stream().map(AdminAccountService::toResponse).toList();
    }

    /**
     * Generates a new password for the requesting user, applies it wherever that
     * account authenticates, and closes the request.
     *
     * @return the new password, returned once and never stored in plain text
     */
    @Transactional
    public CreatedUserResponse resolveRequest(Long requestId, User admin) {
        PasswordResetRequest request = resetRequestRepository.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException("Password reset request", "id", requestId));
        if (request.getStatus() != ResetRequestStatus.PENDING) {
            throw new ValidationException("That request has already been " + request.getStatus().name().toLowerCase(Locale.ROOT));
        }

        User user = request.getUser();
        String password = generatePassword();

        if (user.getSupabaseId() != null) {
            if (!supabaseAdminClient.isAvailable()) {
                throw new ValidationException(
                    "This account signs in through Supabase. Set NEXUS_SUPABASE_SERVICE_ROLE_KEY "
                        + "on the server to issue its new password."
                );
            }
            supabaseAdminClient.updatePassword(user.getSupabaseId(), password);
            // Sign out any existing Supabase sessions so the old password cannot
            // keep working on a device that cached it.
            supabaseAdminClient.revokeSessions(user.getSupabaseId());
        } else if (user.getPassword() != null) {
            user.setPassword(passwordEncoder.encode(password));
        } else {
            throw new ValidationException("That account has no password store configured");
        }

        if (user.getPassword() != null) {
            userRepository.save(user);
        }
        request.setStatus(ResetRequestStatus.RESOLVED);
        request.setResolvedAt(java.time.LocalDateTime.now());
        request.setResolvedBy(admin);
        resetRequestRepository.save(request);

        return new CreatedUserResponse(
            user.getId(), user.getName(), user.getEmail(), user.getRole(), password
        );
    }

    /** Closes a request without issuing a password. */
    @Transactional
    public void dismissRequest(Long requestId, User admin) {
        PasswordResetRequest request = resetRequestRepository.findById(requestId)
            .orElseThrow(() -> new ResourceNotFoundException("Password reset request", "id", requestId));
        if (request.getStatus() != ResetRequestStatus.PENDING) {
            throw new ValidationException("That request has already been " + request.getStatus().name().toLowerCase(Locale.ROOT));
        }
        request.setStatus(ResetRequestStatus.DISMISSED);
        request.setResolvedAt(java.time.LocalDateTime.now());
        request.setResolvedBy(admin);
        resetRequestRepository.save(request);
    }

    /** True when the server can create users and issue passwords via Supabase. */
    public boolean isSupabaseAdminAvailable() {
        return supabaseAdminClient.isAvailable();
    }

    // ------------------------------------------------------------- helpers

    /**
     * A 16-character password drawn from four character classes, with at least
     * one from each. Sufficient entropy to be unguessable while staying short
     * enough for an admin to dictate over a call.
     */
    String generatePassword() {
        StringBuilder password = new StringBuilder(16);
        password.append(randomChar(PW_LOWER))
            .append(randomChar(PW_UPPER))
            .append(randomChar(PW_DIGITS))
            .append(randomChar(PW_SYMBOLS));
        while (password.length() < 16) {
            password.append(randomChar(PW_LOWER + PW_UPPER + PW_DIGITS + PW_SYMBOLS));
        }
        // Fisher-Yates with the same SecureRandom, so the guaranteed leading
        // characters are not always in a predictable position.
        char[] chars = password.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private char randomChar(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static PasswordResetResponse toResponse(PasswordResetRequest r) {
        return new PasswordResetResponse(
            r.getId(),
            r.getUser().getId(),
            r.getUser().getName(),
            r.getUser().getEmail(),
            r.getStatus(),
            r.getRequestedAt(),
            r.getResolvedAt(),
            r.getResolvedBy() != null ? r.getResolvedBy().getName() : null,
            r.getNote()
        );
    }
}