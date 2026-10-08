package com.nexus.backend.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Service-layer counterpart to {@link WorkspaceManage}.
 *
 * Some rules cannot be expressed on the endpoint, because whether they apply
 * depends on the request body and the stored row: assigning a task is only a
 * management action when the assignee actually changes, and a member silently
 * re-submitting the form with the same assignee must not be rejected. The check
 * therefore lives with the code that performs the change, and the words
 * "ADMIN or MANAGER" are written here once.
 *
 * A call with no authentication at all is an internal one (tests, scheduled
 * work), not an HTTP request, so it passes through. Every HTTP request carries
 * an authentication, because SecurityConfig requires one for /api/**; an
 * anonymous token still fails this check.
 */
public final class RolePolicy {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_MANAGER = "ROLE_MANAGER";

    private RolePolicy() {
    }

    /** True for ADMIN and MANAGER, and for internal calls with no security context. */
    public static boolean hasWorkspaceManageRights() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return true;
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(a -> ROLE_ADMIN.equals(a) || ROLE_MANAGER.equals(a));
    }

    /** Assigning work to somebody else is an admin-or-manager action. */
    public static void requireWorkspaceManageRights() {
        if (!hasWorkspaceManageRights()) {
            throw new AccessDeniedException("Only an admin or a manager can assign work to other people");
        }
    }
}
