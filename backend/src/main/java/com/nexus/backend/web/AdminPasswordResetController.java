package com.nexus.backend.web;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.dto.CreatedUserResponse;
import com.nexus.backend.dto.PasswordResetResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.service.AdminAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin half of the password-reset workflow: the queue of requests, and the
 * action that turns one into a working password.
 *
 * <p>Users cannot set their own password — Supabase holds the credential and this
 * flow exists precisely because they cannot reach it. Every route here is
 * ADMIN-only at both the class and the endpoint level.
 */
@RestController
@RequestMapping("/api/admin/password-resets")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminPasswordResetController {

    private final AdminAccountService adminAccountService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<PasswordResetResponse>> list(
        @RequestParam(defaultValue = "false") boolean pendingOnly
    ) {
        return ResponseEntity.ok(adminAccountService.listRequests(pendingOnly));
    }

    /** Issues a new password and returns it once, for the admin to hand over. */
    @PostMapping("/{id}/resolve")
    public ResponseEntity<CreatedUserResponse> resolve(@PathVariable Long id) {
        return ResponseEntity.ok(adminAccountService.resolveRequest(id, currentAdmin()));
    }

    /** Closes a request without issuing a password. */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Void> dismiss(@PathVariable Long id) {
        adminAccountService.dismissRequest(id, currentAdmin());
        return ResponseEntity.ok().build();
    }

    private User currentAdmin() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }
}