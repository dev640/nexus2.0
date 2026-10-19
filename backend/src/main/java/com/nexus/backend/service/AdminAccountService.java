package com.nexus.backend.service;

import com.nexus.backend.config.SupabaseProperties;
import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.user.PasswordResetRequest;
import com.nexus.backend.domain.user.ResetRequestStatus;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.CreateUserRequest;
import com.nexus.backend.dto.CreatedUserResponse;
import com.nexus.backend.dto.PasswordResetResponse;
import com.nexus.backend.exception.DuplicateEmailException;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.PasswordResetRequestRepository;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.SupabaseAdminClient;
import com.nexus.backend.tenant.CurrentTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final EmployeeCodeService employeeCodeService;
    private final AuditService auditService;
    private final CurrentTenant currentTenant;
    private final SecureRandom random = new SecureRandom();

    public AdminAccountService(
        UserRepository userRepository,
        PasswordResetRequestRepository resetRequestRepository,
        PasswordEncoder passwordEncoder,
        SupabaseAdminClient supabaseAdminClient,
        SupabaseProperties supabaseProperties,
        EmployeeCodeService employeeCodeService,
        AuditService auditService,
        CurrentTenant currentTenant
    ) {
        this.userRepository = userRepository;
        this.resetRequestRepository = resetRequestRepository;
        this.passwordEncoder = passwordEncoder;
        this.supabaseAdminClient = supabaseAdminClient;
        this.supabaseProperties = supabaseProperties;
        this.employeeCodeService = employeeCodeService;
        this.auditService = auditService;
        this.currentTenant = currentTenant;
    }

    // ---------------------------------------------------------------- users

    /**
     * Creates an account on an admin's behalf. The password is returned once so
     * the admin can hand it over; it is never persisted in plain text.
     */
    @Transactional
    public CreatedUserResponse createUser(CreateUserRequest request) {
        // The account joins the admin's own company. A company id is never taken
        // from the request: one company's admin placing accounts inside another
        // company is precisely what tenancy is here to prevent.
        Long companyId = currentTenant.require();
        String email = normalize(request.email());
        String name = request.name().trim();
        // An absent role must not accidentally create an admin: default to MEMBER.
        UserRole role = request.role() == null ? UserRole.MEMBER : request.role();

        ProvisionedUser provisioned;
        try {
            provisioned = provisionUser(companyId, name, email, role, request.password());
        } catch (DuplicateEmailException ex) {
            // Keep the established /api/users response unchanged. The global
            // SUPER_ADMIN provisioning path keeps the conflict status instead.
            throw new ValidationException("A user with that email already exists");
        }

        User saved = provisioned.user();
        auditService.record(AuditAction.USER_CREATED, "User", saved.getId(),
            Map.of("email", saved.getEmail(), "role", role.name(),
                "via", "admin", "generatedPassword", provisioned.generatedPassword()));

        return new CreatedUserResponse(
            saved.getId(), saved.getName(), saved.getEmail(), saved.getRole(),
            provisioned.generatedPassword() ? provisioned.password() : null
        );
    }

    /**
     * The persisted account and its one-time credential. Package-visible because
     * company provisioning composes the same policy with a different audit action.
     */
    record ProvisionedUser(User user, String password, boolean generatedPassword) {}

    /**
     * Creates a company-bound user without choosing the caller's tenant or
     * writing an audit action. The caller owns those two decisions.
     */
    @Transactional
    ProvisionedUser provisionUser(
        Long companyId,
        String name,
        String email,
        UserRole role,
        String suppliedPassword
    ) {
        String normalizedEmail = normalize(email);
        if (userRepository.findByEmail(normalizedEmail).isPresent()
            || userRepository.findFirstByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new DuplicateEmailException();
        }

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

        if (role == UserRole.SUPER_ADMIN) {
            // SUPER_ADMIN belongs to no company, which a company admin cannot
            // express: the account would be created outside every company and
            // then be unable to administer any of them. It is granted through
            // deliberate operator tooling, not from this endpoint.
            throw new ValidationException(
                "SUPER_ADMIN cannot be created from a company account. Ask an operator to set it up.");
        }

        boolean generatedPassword = suppliedPassword == null || suppliedPassword.isBlank();
        String password = generatedPassword ? generatePassword() : suppliedPassword;

        User user = new User();
        user.setName(name.trim());
        user.setEmail(normalizedEmail);
        user.setRole(role);
        user.setCompanyId(companyId);
        user.setEmployeeCode(employeeCodeService.nextCode(companyId));
        // A password the admin chose is one the person already knows; a generated
        // one must be replaced by its owner on first use.
        user.setMustChangePassword(generatedPassword);

        if (supabaseAdminClient.isAvailable()) {
            UUID supabaseId = supabaseAdminClient.createUser(normalizedEmail, password, name.trim());
            user.setSupabaseId(supabaseId);
            registerSupabaseRollbackCleanup(supabaseId);
            // Supabase owns the credential; leave the local hash null.
            user.setPassword(null);
        } else {
            log.warn("Supabase admin API unavailable — creating {} with a local password only", normalizedEmail);
            user.setPassword(passwordEncoder.encode(password));
        }

        User saved = userRepository.save(user);
        log.info("Admin created account {} with role {}", saved.getEmail(), role);

        return new ProvisionedUser(saved, password, generatedPassword);
    }

    private void registerSupabaseRollbackCleanup(UUID supabaseId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    supabaseAdminClient.deleteUser(supabaseId);
                } catch (RuntimeException cleanupFailure) {
                    // Do not include the exception message: remote errors may
                    // contain account data. The UUID is enough to investigate.
                    log.error("Failed to delete Supabase identity {} after database rollback ({})",
                        supabaseId, cleanupFailure.getClass().getSimpleName());
                }
            }
        });
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
        PasswordResetRequest request = new PasswordResetRequest(user, blankToNull(note));
        // This endpoint is public, so there is no tenant in scope: the request
        // belongs to the company of the account it is for. Without this the
        // tenant-aware stamp would refuse the insert, and the request could
        // never be filed by the very person who needs it.
        request.setCompanyId(user.getCompanyId());
        PasswordResetRequest saved = resetRequestRepository.save(request);

        // Recorded as the requester, not as the anonymous caller, so the trail
        // answers "who asked" rather than "somebody asked".
        auditService.recordAs(user, AuditAction.PASSWORD_RESET_REQUESTED,
            "PasswordResetRequest", saved.getId(), null);
        log.info("Password reset requested for {}", user.getEmail());
    }

    /** The caller's company's reset requests. */
    @Transactional(readOnly = true)
    public List<PasswordResetResponse> listRequests(boolean pendingOnly) {
        Long companyId = currentTenant.require();
        List<PasswordResetRequest> rows = pendingOnly
            ? resetRequestRepository.findByStatusAndCompanyIdWithUser(ResetRequestStatus.PENDING, companyId)
            : resetRequestRepository.findAllByCompanyIdWithUser(companyId);
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
        PasswordResetRequest request = resetRequestRepository
            .findByIdAndCompanyId(requestId, currentTenant.require())
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

        // The password just issued was generated by an admin and handed over
        // out loud, so it is known to somebody other than its owner: the same
        // rule createUser applies to a generated password applies here. Without
        // this, the one flow that most needs a forced change — a password an
        // admin chose and read out — would be the one flow that skips it.
        user.setMustChangePassword(true);
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
        request.setStatus(ResetRequestStatus.RESOLVED);
        request.setResolvedAt(java.time.LocalDateTime.now());
        request.setResolvedBy(admin);
        resetRequestRepository.save(request);

        // The new password is never recorded, only the fact that one was issued.
        auditService.record(AuditAction.PASSWORD_RESET_RESOLVED, "PasswordResetRequest", request.getId(),
            Map.of("targetUser", user.getEmail(), "targetUserId", user.getId()));

        return new CreatedUserResponse(
            user.getId(), user.getName(), user.getEmail(), user.getRole(), password
        );
    }

    /** Closes a request without issuing a password. */
    @Transactional
    public void dismissRequest(Long requestId, User admin) {
        PasswordResetRequest request = resetRequestRepository
            .findByIdAndCompanyId(requestId, currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("Password reset request", "id", requestId));
        if (request.getStatus() != ResetRequestStatus.PENDING) {
            throw new ValidationException("That request has already been " + request.getStatus().name().toLowerCase(Locale.ROOT));
        }
        request.setStatus(ResetRequestStatus.DISMISSED);
        request.setResolvedAt(java.time.LocalDateTime.now());
        request.setResolvedBy(admin);
        resetRequestRepository.save(request);

        auditService.record(AuditAction.PASSWORD_RESET_DISMISSED, "PasswordResetRequest", request.getId(),
            Map.of("targetUserId", request.getUser().getId()));
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