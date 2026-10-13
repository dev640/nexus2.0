package com.nexus.backend.web;

import com.nexus.backend.dto.LoginRequest;
import com.nexus.backend.dto.RegisterRequest;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.service.AdminAccountService;
import com.nexus.backend.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

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
        if (isBot(payload.company())) {
            // Answered exactly like a real request so a bot cannot learn that it
            // was spotted, and nothing is filed for an admin to read later. This
            // is the endpoint worth protecting: it is public, unauthenticated,
            // and every request costs a person's attention.
            log.info("Password-reset request dropped: honeypot field was filled");
            return ResponseEntity.ok().build();
        }
        adminAccountService.requestPasswordReset(payload.email(), payload.note());
        return ResponseEntity.ok().build();
    }

    /**
     * The web form renders a field no person can see or reach, so anything in it
     * was typed by something reading the DOM rather than the page. It is checked
     * here rather than only in the browser because a bot that walks the markup
     * posts straight to this endpoint without running any of our JavaScript.
     */
    private static boolean isBot(String honeypot) {
        return honeypot != null && !honeypot.isBlank();
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
        String note,

        /**
         * Honeypot. Deliberately unannotated and undocumented to the client: it
         * must stay empty, and a validation message about it would tell a bot
         * exactly which field gave it away.
         */
        String company
    ) {}
}