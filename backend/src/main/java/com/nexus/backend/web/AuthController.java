package com.nexus.backend.web;

import com.nexus.backend.dto.LoginRequest;
import com.nexus.backend.dto.RegisterRequest;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.service.AdminAccountService;
import com.nexus.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AdminAccountService adminAccountService;

    /**
     * Self-service registration, now closed to the public: the workspace is
     * admin-provisioned only, so an anonymous caller is refused. Admins create
     * accounts through POST /api/users instead, which can also set the initial
     * role and return a generated password.
     */
    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = userService.register(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(userService.refresh(request.refreshToken()));
    }

    /**
     * Files a password-reset request. Public, because a locked-out user cannot
     * authenticate, and it always reports success so it cannot be used to
     * discover which addresses are registered.
     */
    @PostMapping("/password-reset-request")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequestBody payload) {
        adminAccountService.requestPasswordReset(payload.email(), payload.note());
        return ResponseEntity.ok().build();
    }

    public record RefreshRequest(
        @jakarta.validation.constraints.NotBlank(message = "Refresh token is required")
        String refreshToken
    ) {}

    public record PasswordResetRequestBody(
        @jakarta.validation.constraints.NotBlank(message = "Email is required")
        @jakarta.validation.constraints.Email(message = "Email should be valid")
        String email,

        @jakarta.validation.constraints.Size(max = 500, message = "Note must be 500 characters or fewer")
        String note
    ) {}
}