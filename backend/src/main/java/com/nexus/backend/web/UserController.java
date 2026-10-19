package com.nexus.backend.web;

import com.nexus.backend.domain.audit.AuditAction;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserAvatar;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.ChangePasswordRequest;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.dto.CreateUserRequest;
import com.nexus.backend.dto.CreatedUserResponse;
import com.nexus.backend.dto.UserResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.NexusUserPrincipal;
import com.nexus.backend.service.AdminAccountService;
import com.nexus.backend.service.AuditService;
import com.nexus.backend.service.AvatarService;
import com.nexus.backend.service.PasswordChangeService;
import com.nexus.backend.service.UserService;
import com.nexus.backend.tenant.CurrentTenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * User management endpoints. Self-service profile updates are open to any
 * authenticated user; account creation and role changes are ADMIN-only.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final AdminAccountService adminAccountService;
    private final AvatarService avatarService;
    private final UserService userService;
    private final AuditService auditService;
    private final PasswordChangeService passwordChangeService;
    private final CurrentTenant currentTenant;

    public UserController(
        UserRepository userRepository,
        AdminAccountService adminAccountService,
        AvatarService avatarService,
        UserService userService,
        AuditService auditService,
        PasswordChangeService passwordChangeService,
        CurrentTenant currentTenant
    ) {
        this.userRepository = userRepository;
        this.adminAccountService = adminAccountService;
        this.avatarService = avatarService;
        this.userService = userService;
        this.auditService = auditService;
        this.passwordChangeService = passwordChangeService;
        this.currentTenant = currentTenant;
    }

    /**
     * Creates an account on an admin's behalf. There is no public signup: the
     * only way into the workspace is through an admin.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CreatedUserResponse> createUser(
        @Valid @RequestBody CreateUserRequest payload
    ) {
        return ResponseEntity.ok(adminAccountService.createUser(payload));
    }

    /** The caller's company's people. */
    @GetMapping
    public ResponseEntity<List<UserResponse>> listUsers() {
        return ResponseEntity.ok(userService.findAll());
    }

    /** The authenticated user's own profile — used to bootstrap a session. */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(toResponse(currentUser()));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserResponse> updateMe(@Valid @RequestBody UpdateMeRequest payload) {
        User current = currentUser();
        if (payload.name() != null && !payload.name().isBlank()) {
            current.setName(payload.name().trim());
        }
        User saved = userRepository.save(current);
        return ResponseEntity.ok(toResponse(saved));
    }

    /**
     * Replaces the caller's own password.
     *
     * <p>Deliberately open to every authenticated role, VIEWER included, and
     * deliberately not a {@code @WorkspaceWrite}: the accounts most likely to be
     * holding an admin-issued password are the ones with the least access, and a
     * read-only account that cannot rotate its own password would be stuck on the
     * one an admin knows. The request carries no account id — the target is
     * whoever is authenticated — so there is nothing to point at somebody else.
     *
     * <p>Local accounts receive a new versioned session because the old bearer
     * token has just been invalidated. Supabase accounts return no content and
     * sign out because Supabase revokes their sessions on password change.
     */
    @PostMapping("/me/password")
    public ResponseEntity<AuthResponse> changeOwnPassword(@Valid @RequestBody ChangePasswordRequest payload) {
        AuthResponse response = passwordChangeService.changePassword(callerPrincipal(), payload);
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }

    /**
     * Uploads the caller's own avatar. This is personal profile state, so it
     * stays open to every authenticated role including VIEWER, exactly like
     * {@code PATCH /me} — a read-only workspace role still owns its own
     * profile picture.
     */
    @PostMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> uploadAvatar(@RequestParam("file") MultipartFile file) {
        avatarService.store(currentUser(), file);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<Void> deleteAvatar() {
        avatarService.remove(currentUser());
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves a user's avatar bytes.
     *
     * <p>The bytes are only ever reachable through an Authorization header, so
     * the response is explicitly private-cacheable: a shared cache must never
     * hand one user's avatar to another. The ETag is derived from the bytes, so
     * re-uploading a picture invalidates every cached copy immediately.
     */
    @GetMapping("/{id}/avatar")
    public ResponseEntity<byte[]> getAvatar(
        @PathVariable Long id,
        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        UserAvatar avatar = avatarService.read(id)
            .orElseThrow(() -> new ResourceNotFoundException("Avatar", "user", id));
        String etag = etagFor(avatar.getBytes());
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(avatar.getContentType()))
            .eTag(etag)
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
            .body(avatar.getBytes());
    }

    /**
     * A quoted strong ETag over the image bytes. Spring requires the quotes, so
     * they are added here rather than left to the caller.
     */
    private static String etagFor(byte[] bytes) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
        StringBuilder hex = new StringBuilder("\"");
        for (int i = 0; i < 16; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.append('"').toString();
    }

    /**
     * Permanently removes an account. ADMIN-only, and guarded against the two
     * ways it could lock the workspace out of its own management.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        String actingEmail = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication().getName();
        userService.delete(id, actingEmail);
        return ResponseEntity.noContent().build();
    }

    /**
     * Changes an account's role. ADMIN-only, and confined to the caller's own
     * company: a role decides what someone may do with that company's data, so
     * one company's admin has no business setting another's.
     */
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> updateRole(
        @PathVariable Long id,
        @Valid @RequestBody UpdateRoleRequest payload
    ) {
        User user = userRepository.findByIdAndCompanyId(id, currentTenant.require())
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        UserRole previous = user.getRole();
        if (previous == payload.role()) {
            return ResponseEntity.ok(toResponse(user));
        }
        user.setRole(payload.role());
        User saved = userRepository.save(user);

        auditService.record(AuditAction.USER_ROLE_CHANGED, "User", saved.getId(),
            Map.of("email", saved.getEmail(), "from", previous.name(), "to", saved.getRole().name()));

        return ResponseEntity.ok(toResponse(saved));
    }

    /**
     * The authenticated account, as the request filter established it.
     *
     * <p>Refuses rather than returning null when the principal is absent or of
     * another type: this endpoint is reachable through the filter chain, and
     * acting on a half-populated identity would be worse than refusing. A 401 is
     * the honest answer, and it is what the caller can act on.
     */
    private static NexusUserPrincipal callerPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
            && authentication.getPrincipal() instanceof NexusUserPrincipal principal) {
            return principal;
        }
        throw new AuthenticationCredentialsNotFoundException("Sign in to change your password");
    }

    private User currentUser() {
        String email = org.springframework.security.core.context.SecurityContextHolder
            .getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    private static UserResponse toResponse(User u) {
        return new UserResponse(
            u.getId(),
            u.getName(),
            u.getEmail(),
            u.getRole(),
            u.getSupabaseId() != null ? u.getSupabaseId().toString() : null,
            u.getEmployeeCode(),
            u.isMustChangePassword()
        );
    }

    public record UpdateMeRequest(
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        String name
    ) {}

    public record UpdateRoleRequest(
        @NotNull(message = "Role is required")
        UserRole role
    ) {}
}
