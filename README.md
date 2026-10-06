# NEXUS — Agile Project Management Platform

One workspace for planning, building, documenting and shipping software: projects, sprints,
a task board and backlog, a wiki, an inbox, a calendar, analytics, a real-time whiteboard and an
AI copilot — all on one data model.

> **Product requirements, API surface and delivery history: [`PRD.md`](PRD.md).**
> **Deployment variables: [`.env.example`](.env.example).**

---

## Stack

| Layer | Technology |
|-------|------------|
| Frontend | React 19 · TypeScript · Vite 8 · Tailwind 4 · React Router 7 · Zustand · axios |
| Backend | Spring Boot 4.1 (Java 21) · Spring Security 7 · Spring Data JPA · Flyway · WebSocket |
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

Password for all seed accounts: **`password123`**

| Email | Role |
|-------|------|
| `devendra@nexus.com` | ADMIN |
| `achal@nexus.com` | MEMBER |
| `vidhi@nexus.com` | MEMBER |
| `palak@nexus.com` | MEMBER |

Flyway applies `V1 → V7` on first boot, including the seed data above.

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

Without it the bundle falls back to `/api`, which only works behind the local dev proxy.

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
   | `NEXUS_LLM_API_KEY` | Optional — leave empty for grounded (data-only) Copilot answers |
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

### Live URL

- Production frontend: **https://nexus-2-0-omega.vercel.app**

---

## Project structure

```
frontend/            React SPA — pages, components, Zustand store, API client
  src/lib/api.ts       typed client for every backend endpoint (JWT injected automatically)
  src/lib/whiteboardSocket.ts  live whiteboard connection with reconnect
backend/             Spring Boot API
  src/main/java/com/nexus/backend/web/       REST controllers
  src/main/java/com/nexus/backend/service/   business logic
  src/main/java/com/nexus/backend/whiteboard/ WebSocket + Redis fan-out
  src/main/resources/db/migration/           Flyway migrations V1–V7
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

# Frontend: typecheck, lint and production build
cd frontend && npx tsc -b && npm run lint && npm run build
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs both on every push and pull
request to `main`, and additionally verifies that the backend runtime image builds cleanly.

---

## Notes

- Never commit secrets. `.env*` files (except `.env.example`) are gitignored.
- Database schema changes go in a **new** Flyway migration; existing migrations are never edited
  once applied.
