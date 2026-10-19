package com.nexus.backend.service;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.LoginRequest;
import com.nexus.backend.dto.RegisterRequest;
import com.nexus.backend.dto.UserResponse;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.exception.CompanySuspendedException;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.JwtUtil;
import com.nexus.backend.tenant.CurrentTenant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmployeeCodeService employeeCodeService;
    private final AuditService auditService;
    private final CurrentTenant currentTenant;

    public UserService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtUtil jwtUtil,
        EmployeeCodeService employeeCodeService,
        AuditService auditService,
        CurrentTenant currentTenant
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.employeeCodeService = employeeCodeService;
        this.auditService = auditService;
        this.currentTenant = currentTenant;
    }

    /**
     * Creates an account in the caller's company. ADMIN-only at the endpoint.
     *
     * <p>The account joins the admin's company rather than choosing one: an
     * admin manages their own company's people, and accepting a company from the
     * request would let one admin place accounts inside another company.
     *
     * <p>The employee code is allocated here because the database no longer
     * supplies one (V19 dropped the column default) and requires it for every
     * role except SUPER_ADMIN.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        Long companyId = currentTenant.require();

        if (userRepository.findByEmail(request.email()).isPresent()
            || userRepository.findFirstByEmailIgnoreCase(request.email()).isPresent()) {
            throw new ValidationException("Email already exists");
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.MEMBER);
        user.setCompanyId(companyId);
        user.setEmployeeCode(employeeCodeService.nextCode(companyId));

        User savedUser = userRepository.save(user);

        auditService.record(AuditAction.USER_CREATED, "User", savedUser.getId(),
            Map.of("email", savedUser.getEmail(), "role", savedUser.getRole().name(), "via", "register"));

        String token = jwtUtil.generateToken(savedUser.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(savedUser.getEmail());

        return new AuthResponse(
            token,
            refreshToken,
            mapToResponse(savedUser)
        );
    }

    /**
     * Resolves an account by email across all companies.
     *
     * <p>This is the login path, so it deliberately ignores the company: nobody
     * has proved which company they belong to yet, and the same address can only
     * exist once because {@code ux_users_email_lower} makes email unique across
     * the whole table. It is the documented exception to company scoping.
     */
    public AuthResponse login(LoginRequest request) {
        // Company fetched with the account so suspension is checked here: a
        // suspended workspace must be refused at the door, not handed a token its
        // next request would be rejected with.
        User user = userRepository.findByEmailWithCompany(request.email())
            .orElseThrow(() -> new ValidationException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ValidationException("Invalid email or password");
        }

        refuseIfSuspended(user);

        String token = jwtUtil.generateToken(user.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(user.getEmail());

        return new AuthResponse(
            token,
            refreshToken,
            mapToResponse(user)
        );
    }

    /** Exchange a valid refresh token for a fresh access token. */
    public AuthResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank() || !jwtUtil.isRefreshToken(refreshToken)) {
            throw new ValidationException("Invalid or expired refresh token");
        }
        String email = jwtUtil.extractUsername(refreshToken);
        User user = userRepository.findByEmailWithCompany(email)
            .orElseThrow(() -> new ValidationException("Invalid or expired refresh token"));

        // Refreshing a suspended workspace's token would extend the credential its
        // requests are refused with; refuse it here for the same reason as login.
        refuseIfSuspended(user);

        return new AuthResponse(
            jwtUtil.generateToken(user.getEmail()),
            jwtUtil.generateRefreshToken(user.getEmail()),
            mapToResponse(user)
        );
    }

    /**
     * Refuses a sign-in or refresh for an account whose company is suspended.
     *
     * <p>Called on the two endpoints that run before any authentication exists,
     * where there is no principal for the servlet filter to gate. A SUPER_ADMIN
     * belongs to no company and so is never suspended by this rule.
     */
    private static void refuseIfSuspended(User user) {
        if (user.getCompany() != null && user.getCompany().isSuspended()) {
            throw new CompanySuspendedException();
        }
    }

    /** The caller's company's people. */
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findByCompanyIdOrderByIdAsc(currentTenant.require()).stream()
            .map(UserService::mapToResponse)
            .toList();
    }

    /** One account, only if it belongs to the caller's company. */
    public UserResponse findById(Long id) {
        User user = userRepository.findByIdAndCompanyId(id, currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        return mapToResponse(user);
    }

    /**
     * Removes an account entirely. ADMIN-only.
     *
     * <p>The database cascades the account's own rows (avatar, notifications,
     * channel memberships, password resets) and nulls the references other
     * people should not lose: their tasks become unassigned, their chat
     * messages stay but lose an author. That is the intended behaviour rather
     * than an accident — deleting a person must not delete the work they did.
     *
     * <p>Two lockout guards apply, because this endpoint can make a company
     * permanently unadministrable and that is not recoverable from the UI:
     * an admin cannot delete themselves, and the last remaining admin cannot
     * be removed. The count is over the caller's own company, since that is the
     * only administration the guard protects.
     *
     * <p>Scoped to the caller's company, so one company's admin cannot delete
     * another company's account by guessing an id.
     */
    @Transactional
    public void delete(Long id, String actingEmail) {
        Long companyId = currentTenant.require();

        User user = userRepository.findByIdAndCompanyId(id, companyId)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        if (user.getEmail().equalsIgnoreCase(actingEmail)) {
            throw new ValidationException("You cannot delete your own account");
        }
        if (user.getRole() == UserRole.ADMIN && countAdmins() <= 1) {
            throw new ValidationException(
                "Cannot delete the only remaining admin — promote another member first");
        }

        // Recorded before the delete: the trail keeps the account's identity even
        // though the row is about to be gone.
        auditService.record(AuditAction.USER_DELETED, "User", user.getId(),
            Map.of("email", user.getEmail(), "role", user.getRole().name()));

        userRepository.delete(user);
    }

    /** How many admins the caller's company has. */
    @Transactional(readOnly = true)
    public long countAdmins() {
        return userRepository.countByRoleAndCompanyId(UserRole.ADMIN, currentTenant.require());
    }

    static UserResponse mapToResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getName(),
            user.getEmail(),
            user.getRole(),
            user.getSupabaseId() != null ? user.getSupabaseId().toString() : null,
            user.getEmployeeCode(),
            user.isMustChangePassword()
        );
    }
}
