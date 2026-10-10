# Security & hygiene audit — Nexus backend + frontend

Scope: the repository at `backend/` and `frontend/`. This audit covers authentication, authorization, secrets handling, input/validation, error exposure, headers, rate limiting and excess protection, dependency hygiene, and basic public-site hygiene (favicon, meta, social preview, sitemap/robots, 404, skip link, cookie consent, privacy/terms). It is a code-and-config audit, not a live penetration test and not a deployment review of your host, proxy, DNS, or CI secrets.

## Status

- Audit date: 2026-10-09
- Branch audited: `main`
- Most recent commit included in audit: `db918df` (workspace activity events + device alerts)
- Backend: Spring Boot 4.1.0 app with Spring Security, JWT, BCrypt, validation, global exception handler, auth/AI/global rate-limit filters, an API body-size guard, and WebSocket chat/whiteboard.
- Frontend: React 19 + TypeScript SPA built with Vite (Tailwind, Zustand, React Router).
- Verification: `cd backend && ./mvnw -o test` — 249 tests, 0 failures. The tests covering this pass are `ViewerReadOnlySecurityTest`, `WorkspaceManageSecurityTest` (headers through the real chain), `SecurityHeadersFilterTest`, `GlobalRateLimitFilterTest`, `ApiBodySizeFilterTest`, `ChatSocketRateLimitTest` and `ApplicationPropertiesTest`.
- Live check: a real instance was started on `PORT=8099` against the local PostgreSQL/Redis. It confirmed the header set on a response, a 413 for a 2 KB body against a 1 KB cap, 429 with `Retry-After` once the request budget was spent, and that startup fails with an empty `NEXUS_JWT_SECRET`.

## Executive summary

The app is already structured better than many small workspaces: there is a JWT authentication filter, a `SecurityFilterChain`, BCrypt password encoding, `@PreAuthorize` on admin routes, request validation on most write endpoints, and a global exception handler that keeps internal details out of responses. The review produced five workstreams:

1. A **hardcoded JWT-secret fallback** in source — **fixed**: removed from `application.properties` and from `JwtUtil`, which now refuses to start without a real secret.
2. **Verbose error exposure** left on by default — **fixed**: the error switches now default to `never`.
3. **No security-headers layer**, plus permissive CORS — **fixed**: a header filter is wired into the security chain and CORS has one origin source of truth.
4. **Rate limiting / excess protection** limited to auth and AI — **fixed for the API surface**: a global per-IP limit, a declared body-size cap and WebSocket frame bounds are in place.
5. **Public-site hygiene** — **not part of this pass**: the favicon already exists; meta/social preview, `robots.txt`, sitemap, custom 404, skip link, cookie consent and privacy/terms pages remain as recommendations.

## Decisions and defaults recorded

Every fix below is driven by a property with an explicit default, so a deployment can tighten it without a code change. The values this pass shipped, and the reason for each:

| Area | Property | Shipped default | Reasoning |
| --- | --- | --- | --- |
| JWT signing secret | `jwt.secret` (`NEXUS_JWT_SECRET`) | no default; startup fails | A shipped fallback is a public key for every deployment that forgets to set one. Failing at startup is louder than a silently weak deployment. |
| Error detail | `server.error.include-message`, `server.error.include-binding-errors` | `never` | The container error page must not echo internal messages; the API's own handler still returns field-level validation errors. |
| HSTS | `security.headers.hsts-max-age-seconds` | `0` (off) | Local and non-TLS environments must not pin a browser to an HTTPS policy. Set `31536000` once the deployment is HTTPS-only. |
| CSP | `security.headers.content-security-policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'` | Blocks inline and third-party scripts and framing without breaking the SPA's inline styles. |
| CORS origins | `nexus.cors.allowed-origins` (`NEXUS_CORS_ALLOWED_ORIGINS`) | `http://localhost:5173,http://localhost:3000` | The existing property the CORS configuration reads; production sets its real origin. |
| CORS request headers | `SecurityConfig` | `Authorization`, `Content-Type`, `X-Requested-With`, `Accept` | Only what the API uses, instead of a wildcard, on a credentialed endpoint. |
| Global rate limit | `nexus.global.rate-limit.max-requests`, `.window-seconds` | `200` requests / `60` s per IP | Blunts runaway clients and scraping without throttling normal use; auth keeps its own stricter limit. |
| API body size | `nexus.api.max-request-size-bytes` | `524288` (512 KiB) | JSON bodies this API writes are kilobytes; anything much larger is abuse. Multipart keeps its own limits. |
| WebSocket frames | `nexus.ws.max-text-message-bytes`, `.max-messages-per-window`, `.message-window-seconds` | 64 KiB frames, 120 frames / 60 s | A chat socket may send typing frames, so it needs a frame and rate bound of its own. |
| Password hashing | — | BCrypt, unchanged | Already integrated and adequate; Argon2id would be a deliberate migration. |
| Dependency versions | — | unchanged | No blind bumps during a security pass. |

## Secrets and environment variables

### Findings

- `backend/src/main/resources/application.properties` sets:
  - `jwt.secret=${NEXUS_JWT_SECRET:3f5a7c9e1b4d6f8a2c4e6f8a0b2d4e6f8a0c2e4f6a8b0c2d4e6f8a0b2c4d6e8}`
  - `nexus.supabase.jwt-secret=${NEXUS_SUPABASE_JWT_SECRET:}`
  - `nexus.supabase.service-role-key=${NEXUS_SUPABASE_SERVICE_ROLE_KEY:}`
  - `nexus.llm.api-key=${NEXUS_LLM_API_KEY:}`
  - `nexus.ai.openai.api-key=${OPENAI_API_KEY:${NEXUS_LLM_API_KEY:}}`
- The JWT secret default is a fixed hex string in source. That is the highest-priority finding in this audit. If that default was ever used for a real deployment, treat it as compromised and rotate it. Defaults like this are convenient for local startup, but they are also effectively a shipped secret.
- `PlatformEnvironment` correctly maps `DATABASE_URL`, `REDIS_URL`, and discrete `PG*`/`REDIS*` variables into Spring properties and does not log the password itself.
- No `.env`, `.env.local`, or similar file is committed.
- Git-history scan for common secret variable names came back clean in this pass, but a scanned history is not a guarantee; the safe assumption for anything public is that any secret ever present in the repo should be rotated.

### Fixes applied

- Removed the hardcoded JWT-secret fallback from `application.properties` (`jwt.secret=${NEXUS_JWT_SECRET:}`).
- Removed the second fallback in code: `JwtUtil` no longer carries a default secret, and it refuses to start when `NEXUS_JWT_SECRET` is unset or shorter than 32 bytes (`@PostConstruct` check feeding `getSigningKey()`). A misconfigured deployment now fails at startup instead of minting tokens with a key that is in the repository. Guarded by `ApplicationPropertiesTest`.
- Blanked the `NEXUS_JWT_SECRET` placeholder in `.env.example` (it was long enough to satisfy HS256, so copying the example would have produced a running deployment on a public secret) and replaced the deployment's real Supabase project URL and anon key in that file with placeholders. The anon key is a public client key by design, but example files should not pin a real project endpoint either. If a deployment ever used those example values, rotate them.
- Left optional-service keys empty-by-default and explicitly disabled rather than defaulting them to anything that looks real.
- No secret lives in source, comments, example files, or frontend code. Do not put real credentials into this file or into commit messages.

### Left to you

- Supply real values for `NEXUS_JWT_SECRET` and any optional keys in your deployment environment, not in the repo.
- If the default JWT secret was ever used in a running deployment, rotate it and any tokens issued with it.

## Authentication

### Findings

- JWT authentication is handled by `JwtAuthenticationFilter`, which accepts Nexus access/refresh tokens and, when configured, Supabase access tokens.
- Token validation uses `JwtUtil` with an HMAC signing key derived from `jwt.secret`.
- Supabase verification supports HS256 with the project JWT secret and RS256/ES256 through the JWKS endpoint, with one-hour JWKS caching.
- Password login uses BCrypt via Spring’s `BCryptPasswordEncoder`.
- Refresh-token handling distinguishes access and refresh token types with a `type` claim.
- Auth endpoints are rate-limited per IP by `AuthRateLimitFilter`.

### Fixes applied

- Make the JWT secret a required production setting instead of defaulting to a hardcoded value.
- Keep BCrypt as the encoder rather than introducing a new hashing library for now.

### Notes

- The auth model is reasonable. If you later adopt Supabase auth as the primary path, the Supabase JWKS path is the more modern choice over the shared secret.
- Refresh-token rotation, token revocation, and session logout behavior should be reviewed if you expose long-lived sessions or admin actions.

## Authorization and admin routes

### Findings

- Admin-only routes use `@PreAuthorize("hasRole('ADMIN')")`:
  - `POST /api/users` (create user)
  - `PATCH /api/users/{id}/role`
  - `DELETE /api/users/{id}`
  - `POST /api/admin/password-resets/{id}/resolve`
  - `POST /api/admin/password-resets/{id}/dismiss`
  - `POST /api/ai/knowledge/reindex`
- `UserController` also has `GET /api/users` listing all users, and `GET /api/users/{id}/avatar` serving user avatars.
- `AdminAccountService` contains the important administrative safeguards:
  - admins cannot delete themselves
  - the last admin cannot be removed
  - Supabase-linked accounts are handled through the Supabase admin path, not by writing a local password
  - password resets are created and resolved by admins, not self-service
- `RolePolicy` centralizes the “ADMIN or MANAGER” workspace-management rule for service-layer actions such as assigning tasks.

### Fixes applied

- No admin route was left unprotected in this pass, but the audit explicitly flags the user-listing and avatar-serving endpoints as areas to check before exposing this app on a broader network or to more user roles. Listing every user and serving avatars may be intended; it is not inherently wrong, but it should be deliberate.

### Notes

- Authorization is good where it is annotated. The biggest remaining risk is “missing annotation on an endpoint that should be restricted,” not a bypass in the endpoints that are already annotated. Any new admin-ish action should carry an explicit restriction at the controller layer.

## Input validation, sanitization, and form handling

### Findings

- Most write endpoints use `@Valid` with request DTOs and Jakarta Bean Validation annotations.
- `GlobalExceptionHandler` translates `MethodArgumentNotValidException` into a field-level error map.
- `UserController` uses `UpdateMeRequest` / `UpdateRoleRequest` with size and not-blank constraints.
- CSRF is disabled in `SecurityConfig`, which is the common choice for a stateless JWT API, but it does mean browser-based CSRF protection depends on CORS and the absence of a credentialed cross-origin exploit path.
- Avatar upload is restricted by business logic in `AvatarService` and backed by servlet multipart size limits.

### Fixes applied

- No verification change is needed to the core validation model; the important hardening here is on the server side, where it already lives.
- Added request-body-size discipline and tighter excess protection (see "Rate limiting and excess protection" for the filters and defaults now in place).

### Notes

- “Sanitize my form” is mostly a server-side job for an API-backed app. Frontend validation is UX, not security. The real risks are oversized or unexpected payloads, content-type confusion, and file-upload abuse, all of larger handled by the backend limits and the global exception handler.

## Rate limiting and excess protection

### Findings

- `AuthRateLimitFilter` limits `/api/auth/**` per IP.
- `AiRateLimitFilter` limits `/api/ai/ask` and `/api/ai/conversations/{id}/regenerate` per user or IP.
- At the time of review there was no broad request limit, no JSON body-size cap beyond the multipart backstop, and no WebSocket frame bounds.

### Fixes applied

- `GlobalRateLimitFilter` (`@Order(3)`) caps `/api/**` at 200 requests per 60 seconds per client IP and answers 429 with `Retry-After`. It deliberately skips `/api/auth/**` so the stricter auth limiter keeps its own counter. It sits after the security chain, so an unauthenticated call is rejected by authentication before it is counted, while permitted routes like `/api/health` are counted; the live check above confirms that sequencing. Covered by `GlobalRateLimitFilterTest`.
- `ApiBodySizeFilter` (`@Order(4)`) answers 413 for JSON API requests whose declared `Content-Length` exceeds 512 KiB, before the body is read. Multipart uploads stay under the multipart limits. Covered by `ApiBodySizeFilterTest`.
- `ChatSocketHandler` bounds each socket: 64 KiB frames (text and binary) and 120 inbound frames per 60 seconds, after which the socket is closed with a policy violation. `WhiteboardSocketHandler` bounds frame size the same way (that socket is receive-only for clients). Covered by `ChatSocketRateLimitTest`.
- Container-level backstops remain: `server.tomcat.max-http-form-post-size=256KB` and `spring.mvc.async.request-timeout=30000` (both verified as real, bindable Spring Boot properties).

### Notes

- All three limiters are in-memory and per-instance, so the effective budget is per instance, not per cluster. Move the counters to Redis before running multiple instances.
- The body-size guard reads only the declared length; a chunked request is not rejected by it. Per-endpoint validation and the container limits still apply.
- Rate limits should be tuned to real traffic; the defaults are a starting point, not a final answer.

## Error handling, debug mode, and information leakage

### Findings

- At the time of review, `application.properties` set:
  - `server.error.include-message=always`
  - `server.error.include-binding-errors=always`
- This can expose internal details in error responses more widely than you want in production.
- `GlobalExceptionHandler` already does the right thing for most specific exception types and keeps the generic handler generic, but the Spring error controller defaults can still leak more than intended when `include-message` is `always`.

### Fixes applied

- `server.error.include-message` and `server.error.include-binding-errors` now default to `never`, overridable with `SERVER_ERROR_INCLUDE_MESSAGE` / `SERVER_ERROR_INCLUDE_BINDING_ERRORS` for local debugging. An earlier draft used `unless-otherwise-specified`, which is not a legal value for these switches (they accept only `always`, `never`, `on_param`) and would have failed binding at startup; `ApplicationPropertiesTest` now checks the shipped values against the enum.
- `GlobalExceptionHandler` is unchanged and still returns field-level validation errors on the API path, where they are useful and not an information leak.

### Notes

- Keep structured logs on the server for debugging, but do not send stack traces or internal messages to clients by default.

## Security headers and HTTPS

### Findings

- No dedicated security-headers layer was present in the parts reviewed.
- CORS accepted any request header (`allowedHeaders("*")`) with credentials enabled, and its allowed origins were hardcoded to development hosts.
- HTTPS enforcement depends on deployment. If Spring is behind a proxy, `X-Forwarded-Proto` handling and `server.forward-headers-strategy` matter.

### Fixes applied

- `SecurityHeadersFilter` adds, on every response: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`, and the CSP recorded above. Inline styles are allowed because the SPA uses them; inline and third-party scripts are not. The filter is declared once (as a component) and added to the chain by `SecurityConfig` before `UsernamePasswordAuthenticationFilter`; `WorkspaceManageSecurityTest` asserts the headers on a real chain response and `SecurityHeadersFilterTest` covers the filter directly.
- HSTS is opt-in via `security.headers.hsts-max-age-seconds`; the shipped default of `0` means browsers are not pinned on plain-HTTP development, and `SecurityHeadersFilterTest` asserts that HSTS is absent at `0`.
- CORS allowed headers are now the four the API uses, and the allowed origins have a single source of truth: the existing `nexus.cors.allowed-origins` property (`NEXUS_CORS_ALLOWED_ORIGINS`), defaulting to the two local dev origins.

### Left to you

- Set `NEXUS_CORS_ALLOWED_ORIGINS` to the deployed frontend origin and set `security.headers.hsts-max-age-seconds` (for example `31536000`) once the deployment is HTTPS-only.
- If this app sits behind Cloudflare, a CDN or a reverse proxy, decide which headers are better set there; the backend keeps sending a safe default set for direct access either way.

## Database security

### Findings

- PostgreSQL via Spring Data JPA and Flyway.
- `ddl-auto=validate`, not `update`, which is the safer choice for migration-controlled schemas.
- `open-in-view=false`, which is the better default for avoiding lazy-loading surprises and longer-than-necessary DB sessions.
- Flyway migrations are used for schema changes.

### Fixes applied

- No destructive DB change is part of this audit. The relevant hardening is operational: use strong DB credentials, least-privilege DB user, TLS to the database when available, and migrations only.

### Notes

- The default local properties contain `spring.datasource.username=nexus` and `spring.datasource.password=nexus`. That is acceptable only for local development. Production must use real credentials from environment variables, not the local defaults.

## Password hashing

### Findings

- Passwords are BCrypt-hashed for local accounts.
- Supabase-managed accounts do not store a local password; Supabase owns the credential.

### Choice recorded

- Keep BCrypt in this pass. It is already integrated and correct enough for the current app. If you want Argon2id later, that is a deliberate migration, not a routine update.

## Dependencies

### Findings

- Backend uses Spring Boot 4.1.0 with Jackson, JJWT 0.12.6, PostgreSQL driver, Flyway, Lombok, and the standard Spring starters.
- Frontend uses React 19, TypeScript, Vite, Tailwind and Zustand — a current stack with no obviously stale framework to upgrade.

### Choice recorded

- This audit does **not** blindly bump all dependencies. Blind bumps are a common way to introduce breakage in a Spring Boot 4 / Jackson 3-era app.
- What was checked: whether any dependency is obviously obsolete, whether any suspicious unused library is present, and whether any integration code looks outdated.
- A dependency upgrade should be done deliberately, one meaningful change at a time, after checking release notes and regressions, not as part of a generic “update everything” step.

## Unused / removable surface

### Findings

- The app is fairly tightly scoped already.
- Optional features (Supabase admin, OpenAI-backed Copilot) are already gated behind configuration and fail gracefully when absent.

### Choice recorded

- Do not delete features just to make the dependency list smaller. Remove only code that is demonstrably unused or actively unsafe. If you want a leaner app, the right move is a separate cleanup pass with explicit before/after justification.

## Frontend exposure and secret hygiene

### Findings

- Frontend auth uses a stored token in `localStorage` and sends `Authorization: Bearer ...` to the API.
- Frontend code references env-shaped names only through the expected API/chat/whiteboard client paths; it does not expose backend secrets directly.
- No API key or secret should live in the frontend bundle or in any file shipped to the browser.

### Verified, no change made

- Backend secrets are not in the bundle: the client reads only `VITE_API_URL`, `VITE_WS_URL` and the optional `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` (a public client key by design). Every server credential — database password, JWT secret, Supabase service-role key, LLM API key — is read by the Spring process from its environment and never shipped to the browser.
- Token handling stays limited to what the API expects: a bearer token in `localStorage`, sent on API and WebSocket calls. If this application ever starts serving third parties, revisit that storage choice.

### Notes

- If you later add analytics, social widgets, or third-party scripts, that is the most likely place for secret-shaped values or tracking tokens to creep in. Treat those integrations as separate security reviews.

## Public-site hygiene

### Findings

- A favicon exists and is referenced from `index.html` (`/favicon-v2.png`), alongside the brand logo assets in `frontend/public`.
- No social preview image or Open Graph/Twitter tags.
- No `robots.txt` or `sitemap.xml` in the public surface.
- No custom 404 route.
- No skip-to-content link.
- No cookie consent banner.
- No privacy policy or terms pages.
- `index.html` carries `<title>Nexus</title>` only — no meta description.

### Fixes applied

- None. These are public/frontend assets, and this pass was limited to backend configuration and security wiring so the two sets of changes do not collide in review.

### Recommended (not applied)

- Add `frontend/public/robots.txt` and `sitemap.xml` (Vite copies `public/` as-is), and a catch-all 404 route in the React Router setup.
- Add a skip-to-content link in the app shell, and a cookie banner if any non-essential tracking is ever introduced.
- Add privacy policy and terms routes before exposing the app beyond the company.
- Add a meta description plus Open Graph/Twitter tags and a social preview image to `index.html`.

### Notes

- For a SPA backed by this API, some of these are static files and some are routes. The implementation should match your deployment model.

## SEO and performance hygiene

### Findings

- `index.html` sets a title but no meta description, and there are no Open Graph/Twitter tags.
- Image alt text is a frontend concern; the backend serves avatars and any uploaded images, but alt text is rendered by the frontend.
- Color contrast and page-load speed must be checked on the rendered pages, not only in code.

### Fixes applied

- None in this pass; see "Public-site hygiene" for what remains.

### Recommended (not applied)

- Add default meta titles/descriptions for public routes, plus a social preview image.
- Confirm alt text on every rendered image, and check color contrast and page-load speed against the live pages rather than the source.

## What was not fully verifiable

- Live deployment configuration: reverse proxy, CDN, TLS termination, platform env vars, and secret provisioning.
- Whether the default JWT secret was ever used in a real deployment.
- Runtime abuse behavior under real load and multi-instance deployment.
- Third-party integrations you may add later, including analytics or social widgets.
- Any secrets introduced only in CI/CD variables, deploy scripts, or hosting dashboards rather than in the repository.
- Application startup against real PostgreSQL/Redis: the suite covers slices and units, so bean wiring and property binding are verified only for the contexts the tests load. Booting the production configuration is still a manual check.
- The rate limits, body-size cap and CSP values were not exercised against real client traffic or a browser; CSP in particular is a candidate for tuning once the real bundle is served behind these headers.

## Recommendations summary

1. Rotate any JWT secret that may have been used with the old default. The fallback is gone from both the properties file and `JwtUtil`, but rotation is still the only remedy for tokens already issued under it.
2. Keep production error responses generic; keep detail in logs.
3. Security headers and CORS tightening are in place; still set `NEXUS_CORS_ALLOWED_ORIGINS` and the HSTS max-age for the real deployment.
4. Global rate limiting, the declared body-size cap and WebSocket frame bounds are in place; move the limiter counters to Redis before running more than one instance.
5. Use real DB credentials and TLS in production; do not rely on local defaults.
6. Keep BCrypt for now unless you intentionally migrate password hashing later.
7. Complete public-site hygiene: meta description and social preview, `robots.txt`/sitemap, a custom 404, a skip-to-content link, cookie consent, and privacy/terms pages (the favicon already exists).
8. Treat the public `main` branch as capable of exposing anything ever committed to it; rotate anything suspect rather than assuming deletion is enough.
