package com.nexus.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses every request from an account whose company is suspended.
 *
 * <p>Suspension is a workspace-level withdrawal of access. Like the password
 * gate, the flag alone is not enforcement — a client that ignores it, an old
 * build, or a hand-rolled curl would otherwise reach the whole workspace — so
 * the rule is applied on the server, once per request, for every endpoint. The
 * client's suspended-workspace screen is a courtesy on top of it.
 *
 * <p>The status is read from the account row (through
 * {@link NexusUserPrincipal#isCompanySuspended()}) rather than a token claim, so
 * suspending a company takes effect on its people's very next request instead of
 * when their token happens to expire.
 *
 * <p>The rule is applied for every HTTP request that carries a Bearer token. A
 * WebSocket handshake is not one of those, so it is applied again where that
 * token is read — see {@code SocketPrincipalResolver}, which refuses a socket
 * for a suspended company. Neither alone is the whole enforcement; together they
 * are.
 *
 * <p>Deliberately not a {@code @Component}, for the same reason as
 * {@link MustChangePasswordFilter}: as a bean it would also register on the
 * servlet container's own chain, where it would run ahead of authentication and
 * silently do nothing. It is instantiated by the security config and placed
 * after {@link MustChangePasswordFilter}, where the principal is guaranteed to
 * be in scope.
 */
public class SuspendedCompanyFilter extends OncePerRequestFilter {

    /** Marker in the refusal body. The client routes to the suspended screen on it. */
    public static final String CODE = "WORKSPACE_SUSPENDED";

    /** Shown as-is; it names who can lift it. Matches the exception's wording. */
    public static final String MESSAGE =
        "This workspace is suspended. Contact your administrator to restore access.";

    /**
     * The one read a suspended account is still allowed: its own profile.
     *
     * <p>The client reads the profile to establish who is signed in. Refusing it
     * would make a reload look like a dead session and hide the very fact — that
     * the workspace is suspended — the screen exists to explain. Reading one's
     * own profile grants no workspace data, so it stays open the same way the
     * password gate keeps it open for its own screen.
     */
    private static final String SELF_PATH = "/api/users/me";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isSuspended() || isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        refuse(response);
    }

    /** True when the caller is authenticated, in a suspended company. */
    private static boolean isSuspended() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
            && authentication.getPrincipal() instanceof NexusUserPrincipal principal
            && principal.isCompanySuspended();
    }

    private static boolean isAllowed(HttpServletRequest request) {
        // CORS preflight carries no Authorization header, so it can never be a
        // suspended request — but it must not be answered with a refusal either,
        // or the browser reports the sign-out as a network failure.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // GET only: a suspended account may read its own profile and nothing else.
        return "GET".equalsIgnoreCase(request.getMethod()) && SELF_PATH.equals(request.getRequestURI());
    }

    /**
     * Written directly rather than thrown: the exception handlers live in the
     * dispatcher, which this filter runs in front of, so an exception raised here
     * would leave the container to render its own error page instead of the JSON
     * the client is waiting for.
     */
    private static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // Both values are constants with no quote or backslash in them, so this
        // needs no escaping and no serialiser.
        response.getWriter().write(
            "{\"status\":403,\"code\":\"" + CODE + "\",\"message\":\"" + MESSAGE + "\"}");
    }
}
