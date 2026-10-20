package com.nexus.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Answers an unauthenticated API request with {@code 401} and a JSON body.
 *
 * <p>Without an entry point configured, Spring Security falls back to
 * {@code Http403ForbiddenEntryPoint}, which conflates two different answers:
 * "you are not signed in" and "you are signed in but may not do this". The
 * distinction became load-bearing with the token-version column (V22): a
 * password change bumps {@code users.token_version}, which invalidates every
 * token minted before it, and a client still holding one has to be told to sign
 * in again rather than to ask for permission.
 *
 * <p>Authorization refusals are deliberately untouched. An authenticated caller
 * who lacks a role still gets {@code 403} from Spring Security's access-denied
 * handler, and the two gates that refuse a signed-in account
 * ({@link MustChangePasswordFilter}, {@link SuspendedCompanyFilter}) keep
 * writing their own {@code 403} markers: those are states of an authenticated
 * session, not an absent one.
 *
 * <p>The body is a fixed string with no interpolation, so nothing about why the
 * credential failed is disclosed and there is no serialiser to get wrong.
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Marker in the refusal body, so a client can branch without parsing prose. */
    public static final String CODE = "UNAUTHENTICATED";

    /** Shown as-is; it names the one action available to the caller. */
    public static final String MESSAGE =
        "Sign in to continue. Your session is missing or is no longer valid.";

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // RFC 6750: a 401 on a Bearer-protected resource names the scheme.
        response.setHeader("WWW-Authenticate", "Bearer");
        // Both values are constants with no quote or backslash in them, so this
        // needs no escaping. The exception's own message is deliberately not
        // used: it can name a token, an expiry or a signature problem.
        response.getWriter().write(
            "{\"status\":401,\"code\":\"" + CODE + "\",\"message\":\"" + MESSAGE + "\"}");
    }
}
