package com.nexus.backend.service.ai;

import com.nexus.backend.domain.ai.AiConversation;
import com.nexus.backend.domain.knowledge.AccessScope;
import com.nexus.backend.domain.user.User;
import com.nexus.backend.domain.user.UserRole;
import com.nexus.backend.repository.UserRepository;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Single place where the AI pipeline decides what the current user may see:
 * who is signed in (from the JWT), which knowledge scopes they may retrieve,
 * and whether they own a conversation. Retrieval applies these checks both in
 * SQL and again against the in-memory vector index before any context is built.
 */
@Service
public class PermissionService {

    private final UserRepository userRepository;

    public PermissionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** The signed-in user for the current request; 401 when unauthenticated. */
    public User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user");
        }
        return userRepository.findByEmail(auth.getName())
            .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Unknown user"));
    }

    public boolean isAdmin(User user) {
        return user != null && user.getRole() == UserRole.ADMIN;
    }

    /** Whether a knowledge item with this scope/owner is readable by the user. */
    public boolean canRead(AccessScope scope, Long ownerId, User user) {
        if (scope == null || user == null) return false;
        return switch (scope) {
            case WORKSPACE -> true;
            case PRIVATE -> ownerId != null && ownerId.equals(user.getId());
            case ADMIN -> isAdmin(user);
        };
    }

    /**
     * Conversations are strictly per-user: nobody, not even an admin, reads
     * another user's chat history.
     */
    public boolean owns(AiConversation conversation, User user) {
        return conversation != null && conversation.getUser() != null && user != null
            && conversation.getUser().getId().equals(user.getId());
    }
}
