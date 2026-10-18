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
 * Refuses every request from an account that is still on an admin-issued
 * password, except the request that ends that state.
 *
 * <p>The flag alone is not enforcement. A client that ignores it — an old build,
 * a script, a hand-rolled call with curl — would otherwise reach the whole
 * workspace while the password its owner was handed out loud still works, which
 * is the entire point of the flag. So the rule is applied on the server, once
 * per request, for every endpoint, and the client's forced-change screen is a
 * courtesy on top of it rather than the mechanism.
 *
 * <p>The rule is applied here for every HTTP request that carries a Bearer
 * token. A WebSocket handshake is not one of those: a browser opens a socket
 * without an Authorization header, so nothing is in scope when it arrives, and
 * the same rule is applied again where that token is read — see
 * {@link MustChangePasswordHandshakeGuard}. Neither alone is the whole
 * enforcement; together they are.
 *
 * <p>Deliberately not a {@code @Component}: a filter bean is also registered on
 * the servlet container's own chain, where it would run outside Spring
 * Security's and therefore before any authentication exists. It is instantiated
 * by {@code SecurityConfig} and placed immediately after
 * {@link JwtAuthenticationFilter} instead, where the principal is guaranteed to
 * be in scope and the refusal happens before authorization.
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /**
     * Marker in the refusal body. The client routes to the forced-change screen
     * on this value, so it can react to being gated even when its own copy of the
     * profile was stale — which is the only way a stale client can recover.
     */
    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    /** Shown as-is; it names the one action that is available. */
    public static final String MESSAGE =
        "Your password was issued by an administrator and must be changed before you can use the workspace.";

    /**
     * The only two requests an account in this state is allowed.
     *
     * <p>{@code GET /api/users/me} is on the list because the client cannot
     * otherwise tell a gated session from a dead one: it reads its own profile to
     * establish who is signed in, and refusing that would make a reload of the
     * forced-change screen look like an expired session and sign the person out
     * of the very screen they need. Reading one's own profile grants nothing —
     * the workspace endpoints stay refused.
     */
    private static final String CHANGE_PATH = "/api/users/me/password";
    private static final String SELF_PATH = "/api/users/me";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isGated() || isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        refuse(response);
    }

    /** True when the caller is authenticated, gated, and this is not their way out. */
    private static boolean isGated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
            && authentication.getPrincipal() instanceof NexusUserPrincipal principal
            && principal.isMustChangePassword();
    }

    private static boolean isAllowed(HttpServletRequest request) {
        // CORS preflight carries no Authorization header, so it can never be a
        // gated request — but it must not be answered with a refusal either, or
        // the browser reports the change as a network failure instead of showing
        // its result.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return ("POST".equalsIgnoreCase(request.getMethod()) && CHANGE_PATH.equals(path))
            || ("GET".equalsIgnoreCase(request.getMethod()) && SELF_PATH.equals(path));
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
