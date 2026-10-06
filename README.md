# NEXUS — Agile Project Management Platform

One workspace for planning, building, documenting and shipping software: projects, sprints,
a task board and backlog, a wiki, an inbox, a calendar, analytics, a real-time whiteboard and an
AI copilot — all on one data model.

> **Product requirements, API surface and delivery history: [`PRD.md`](PRD.md).**
> **Deployment variables: [`.env.example`](.env.example).**
> **Day-2 operations (deploy, verify, roll back, troubleshoot): [`OPERATIONS.md`](OPERATIONS.md).**

---

## Stack

| Layer | Technology |
|-------|------------|
| Frontend | React 19 · TypeScript · Vite 8 · Tailwind 4 · React Router 7 · Zustand · axios |
| Backend | Spring Boot 4.1 (Java 21) · Spring Security 7 · Spring Data JPA · Flyway · WebSocket |
| Auth | Built-in email/password JWTs, plus **optional Supabase Auth** (see [Authentication](#authentication)) |
| Chat | Slack-style channels, DMs, reactions, unread badges, mentions — live over WebSocket (`/ws/chat`) |
| Data | PostgreSQL 16 · Redis 7 (whiteboard pub/sub) |
| Local infra | Docker Compose (Postgres + Redis + API) |

---

## Quick start (local)

```bash
# 1. Postgres + Redis + API  →  http://localhost:8080
docker compose up -d --build

# 2. Frontend dev server      →  http://localhost:5173
cd frontend && npm install && npm run dev
```

The Vite dev server proxies `/api` and `/ws` to `localhost:8080`, so no frontend
configuration is needed locally.

### Seed logins

Accounts are **admin-provisioned** — there is no public signup, and
`POST /api/auth/register` is ADMIN-only.

| Email | Role |
|-------|------|
| `devendra@nexus.com` | ADMIN |
| `achal@nexus.com` | MEMBER |
| `vidhi@nexus.com` | MEMBER |
| `palak@nexus.com` | MEMBER |

Before Supabase is configured these sign in with the built-in flow and the seeded
password **`password123`**. Once Supabase is configured, each account signs in
through Supabase and its password is set there instead, so `password123` no longer
applies; an admin issues passwords from **Admin → Create an account** or
**Admin → Password requests**. Linking an account to Supabase also clears its local
password hash, so the two credentials never both work.

Flyway applies `V1 → V12` on first boot, including the seed data above. A fresh database has no chat
channels — create one from the Chat page.

---

## Deployment

### Frontend → Vercel

The Vercel project is connected to this GitHub repository (`dev640/nexus2.0`, branch `main`),
so **every push to `main` deploys automatically**. The build is defined by
[`vercel.json`](vercel.json) (repo-root project):

- install: skipped at the root, dependencies are installed in `frontend/`
- build: `cd frontend && npm install && npm run build`
- output: `frontend/dist`
- rewrite: all routes fall back to `index.html` so client-side routing works

A [`frontend/vercel.json`](frontend/vercel.json) is also present for the case where the Vercel
project's root directory is set to `frontend/` instead of the repo root.

Set this environment variable in the Vercel project (Settings → Environment Variables):

| Variable | Value |
|----------|-------|
| `VITE_API_URL` | Backend API base URL, **including the `/api` suffix**, e.g. `https://nexus-api.up.railway.app/api`. The client appends route paths to this value verbatim, so a bare host would resolve to `https://host/auth/login` and 404. |
| `VITE_SUPABASE_URL` | Optional. Enables Supabase Auth on the login screen. |
| `VITE_SUPABASE_ANON_KEY` | Optional. The project's anon public key (safe to expose). |

Without `VITE_API_URL` the bundle falls back to `/api`, which only works behind the local dev
proxy. Both Supabase variables must be set (and the site redeployed — Vite inlines them at build
time) or neither is used.

### Backend → Railway (or any Docker host)

The API is a long-running Spring Boot container — not a serverless function. That is what makes
the WebSocket whiteboard work in production.

1. Create a Railway project and add the **PostgreSQL** and **Redis** plugins.
2. Add a service from this repository, then set **Settings → Source → Root Directory** to
   `backend`. This makes Railway build `backend/Dockerfile` with `backend/` as the build
   context, which is what the Dockerfile's `COPY pom.xml .` expects.
3. Set the environment variables (full list in [`.env.example`](.env.example)). Reference the
   plugin variables so they stay in sync — Railway substitutes the private network address:

   | Variable | Notes |
   |----------|-------|
   | `NEXUS_JWT_SECRET` | **Required.** `openssl rand -hex 32` — HS256 needs ≥32 bytes |
   | `NEXUS_CORS_ALLOWED_ORIGINS` | The deployed Vercel origin, e.g. `https://nexus-2-0-omega.vercel.app` |
   | `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` — the app derives the JDBC URL from it |
   | `REDIS_URL` | `${{Redis.REDIS_URL}}` — the app derives host/port/password from it |
   | `NEXUS_SUPABASE_URL` | Optional — enables Supabase Auth (see below) |
   | `NEXUS_LLM_API_KEY` | Optional — leave empty for grounded (data-only) Copilot answers |
| `NEXUS_LLM_MODEL` | Required once a key is set; there is no default on purpose ([why](OPERATIONS.md#6-turning-on-the-llm-copilot)) |
   | `OPENAI_API_KEY` | Optional — enables Nexus AI generated answers (RAG chat at `/ai`); without it answers run in context-only mode. `OPENAI_MODEL` (default `gpt-4o-mini`) and `OPENAI_EMBEDDING_MODEL` (default `text-embedding-3-small`) are also read. |

   `PORT` is injected by the platform automatically, and the app derives its datasource from
   `DATABASE_URL` (or the discrete `PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`/`PGDATABASE` form) and
   its Redis client from `REDIS_URL` (or `REDISHOST`/`REDISPORT`/`REDISPASSWORD`). Set
   `SPRING_DATASOURCE_URL` explicitly and it takes precedence.

4. **Get the public URL:** service → **Settings → Networking → Public Networking → Generate
   Domain**. You get `https://<service>-<environment>.up.railway.app`.
5. Set the healthcheck path to `/api/health` (Settings → Deploy) so a broken boot is rolled
   back. Flyway migrates the schema automatically on first boot.
6. Point the frontend at it: set `VITE_API_URL` on Vercel to `<that URL>/api` and redeploy.

Any other Docker host works the same way: `docker build ./backend` (with `backend/` as the
context) and provide the same environment variables.

> **Why there is no `railway.json`:** Railway deprecated Config as Code (`railway.json` /
> `railway.toml`) — new services do not read it, and existing files stop working on
> 2026-12-01 ([docs](https://docs.railway.com/reference/config-as-code)). Configuration now
> lives in the dashboard as above. The Dockerfile is auto-detected because it sits at the root
> of the service's source directory.

### Live deployment

- Frontend: **https://nexus-2-0-omega.vercel.app**
- Backend API: **https://nexus20-production.up.railway.app** (health: `/api/health`)

Both deploy automatically on every push to `main`. [`OPERATIONS.md`](OPERATIONS.md) covers the
release procedure, how to verify one, how to roll one back, and what to check when something is
wrong.

---

## Authentication

Nexus issues its own HS256 JWTs. `POST /api/auth/login` and `/register` return an access token
plus a refresh token; `JwtAuthenticationFilter` validates them on every request and
`GET /api/users/me` returns the caller. Nothing else is required to run or deploy the app.

### Supabase (optional)

Adding Supabase Auth lets users sign in with Supabase identities while keeping the same Nexus
permissions. It is off until the variables below are present — with them unset, behaviour is
identical to the built-in flow.

Set on the **backend**:

| Variable | Notes |
|----------|-------|
| `NEXUS_SUPABASE_URL` | Project URL, e.g. `https://abcdefgh.supabase.co`. Required to enable the integration. |
| `NEXUS_SUPABASE_JWT_SECRET` | JWT secret, for projects that sign tokens with HS256. |
| `NEXUS_SUPABASE_USE_JWKS` | `true` for projects that sign asymmetrically — tokens are then verified against `<url>/auth/v1/.well-known/jwks.json` (cached for an hour, refetched on rotation). |
| `NEXUS_SUPABASE_AUDIENCE` | Defaults to `authenticated`. |
| `NEXUS_SUPABASE_ISSUER` | Optional; derived as `<url>/auth/v1`. |

Set on the **frontend** (Vercel): `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY`. Find all of
these in the Supabase dashboard under **Project Settings → API**. A credential of some kind is
required, so a half-configured project simply leaves the integration off.

How identities map to accounts (`SupabaseUserService`):

1. a known `users.supabase_id` — the normal repeat-login path;
2. otherwise an existing account with the same email is **linked** rather than duplicated, and
   its local password is cleared so the account is Supabase-only from then on;
3. otherwise a new account is provisioned with the `MEMBER` role and no local password.

The whiteboard WebSocket handshake accepts Supabase tokens too, so the live board works
regardless of which provider signed the user in.

---

## Project structure

```
frontend/            React SPA — pages, components, Zustand store, API client
  src/lib/api.ts       typed client for every backend endpoint (JWT injected automatically)
  src/lib/whiteboardSocket.ts  live whiteboard connection with reconnect
  src/lib/chatSocket.ts        live chat connection (messages, typing, presence)
  src/pages/Chat.tsx           Slack page: channels, DMs, thread, composer
backend/             Spring Boot API
  src/main/java/com/nexus/backend/web/       REST controllers
  src/main/java/com/nexus/backend/service/   business logic
  src/main/java/com/nexus/backend/chat/      chat WebSocket + per-user delivery
  src/main/java/com/nexus/backend/whiteboard/ WebSocket + Redis fan-out
  src/main/java/com/nexus/backend/security/   JWT filter + optional Supabase token verifier
  src/main/resources/db/migration/           Flyway migrations V1–V12
  src/test/java/                             service unit tests
docker-compose.yml   Postgres + Redis + backend
(no Railway config file — see "Backend → Railway" below)
vercel.json          frontend deployment descriptor
PRD.md               product requirements and delivery history
.env.example         every environment variable, documented
```

---

## Tests & quality gates

```bash
# Backend: compiles and runs the unit test suite inside Docker
docker build --target test ./backend
# (or locally: cd backend && sh mvnw test — 47 tests)

# Frontend: lint, component/store tests, then typecheck + production build
cd frontend && npm run lint && npm test && npm run build
```

`npm test` runs the Vitest suite (`src/**/*.test.ts(x)`); `npm run build` typechecks with `tsc -b`
before bundling. Frontend tests use the store directly — the API client is mocked per test — so no
database or backend is needed.

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs the same four steps on every push
and pull request to `main`, and additionally verifies that the backend runtime image builds cleanly.

---

## Notes

- Never commit secrets. `.env*` files (except `.env.example`) are gitignored.
- Database schema changes go in a **new** Flyway migration; existing migrations are never edited
  once applied.
