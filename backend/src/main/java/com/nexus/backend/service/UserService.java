package com.nexus.backend.service;

import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.dto.LoginRequest;
import com.nexus.backend.dto.RegisterRequest;
import com.nexus.backend.dto.UserResponse;
import com.nexus.backend.dto.AuthResponse;
import com.nexus.backend.exception.ResourceNotFoundException;
import com.nexus.backend.exception.ValidationException;
import com.nexus.backend.repository.UserRepository;
import com.nexus.backend.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new ValidationException("Email already exists");
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.MEMBER);

        User savedUser = userRepository.save(user);

        String token = jwtUtil.generateToken(savedUser.getEmail());
        String refreshToken = jwtUtil.generateRefreshToken(savedUser.getEmail());

        return new AuthResponse(
            token,
            refreshToken,
            mapToResponse(savedUser)
        );
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new ValidationException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new ValidationException("Invalid email or password");
        }

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
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ValidationException("Invalid or expired refresh token"));

        return new AuthResponse(
            jwtUtil.generateToken(user.getEmail()),
            jwtUtil.generateRefreshToken(user.getEmail()),
            mapToResponse(user)
        );
    }

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream()
            .map(this::mapToResponse)
            .toList();
    }

    public UserResponse findById(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
        return mapToResponse(user);
    }

    public UserResponse findByEmail(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
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
     * <p>Two lockout guards apply, because this endpoint can make a workspace
     * permanently unadministrable and that is not recoverable from the UI:
     * an admin cannot delete themselves, and the last remaining admin cannot
     * be removed.
     */
    @Transactional
    public void delete(Long id, String actingEmail) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));

        if (user.getEmail().equalsIgnoreCase(actingEmail)) {
            throw new ValidationException("You cannot delete your own account");
        }
        if (user.getRole() == UserRole.ADMIN && countAdmins() <= 1) {
            throw new ValidationException(
                "Cannot delete the only remaining admin — promote another member first");
        }

        userRepository.delete(user);
    }

    @Transactional(readOnly = true)
    public long countAdmins() {
        return userRepository.countByRole(UserRole.ADMIN);
    }

    private UserResponse mapToResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getName(),
            user.getEmail(),
            user.getRole(),
            user.getSupabaseId() != null ? user.getSupabaseId().toString() : null,
            user.getEmployeeCode()
        );
    }
}
