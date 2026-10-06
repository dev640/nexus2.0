# Nexus — Product Requirements Document

> **One intelligent workspace for planning, building, documenting and shipping software.**
> This PRD is the single reference for what Nexus is, what it does today, how it is built,
> and how it is deployed. Every claim below is implemented and verified against a running
> stack unless explicitly marked otherwise.

**Status:** Phases 0–8 complete — feature-complete for the current scope · Nexus AI assistant (RAG) added
**Last updated:** 2026-10-05
**Repository:** `dev640/nexus2.0` (branch `main`)

---

## 1. Problem & Vision

Small product teams juggle 3–5 disconnected tools: a tracker for tasks, a docs tool for specs,
a canvas for architecture, a spreadsheet for capacity. Context is lost between them, and the
plan drifts away from the work.

**Vision:** a single fast workspace where backlog, sprints, docs, whiteboards and an AI copilot
share one data model, so the plan and the work never diverge.

**Positioning:** free while in beta, no seat limits, no credit card.

---

## 2. Users & Roles

| Role | Capabilities |
|------|--------------|
| `ADMIN` | Everything, plus changing other users' roles |
| `MEMBER` | Create/edit projects, tasks, sprints, wiki pages, whiteboard notes |
| `DEVELOPER` | Same as `MEMBER` today; reserved for finer permission tuning |
| `VIEWER` | Read-only intent; not yet enforced per-endpoint (see §9) |

Authentication is email + password with JWT bearer tokens. Anyone can self-register and new
accounts join as `MEMBER`. The first seeded account is an `ADMIN`.

---

## 3. Feature Requirements

Legend — **✅ Built** (implemented + verified against the running stack) · **🔜 Planned**

### 3.1 Authentication — ✅ Built
- **FR-A1** Register with name, email and password (min 8 characters).
- **FR-A2** Log in and receive an access token (24 h) plus a refresh token (7 days).
- **FR-A3** Access tokens are refused as refresh tokens and vice versa (token-type claim).
- **FR-A4** Sessions survive a page reload: the token is persisted and the workspace re-fetched.
- **FR-A5** Every `/api/*` route except auth, health and the WebSocket handshake requires a token.
- **FR-A6** Log out clears the token and the in-memory workspace.
- **FR-A7** Repeated authentication attempts from one IP are rate limited (HTTP 429).

### 3.2 Projects — ✅ Built
- **FR-P1** List projects with status, health, progress, current sprint number and member count.
- **FR-P2** Create a project (defaults to `PLANNING` / `ON_TRACK`).
- **FR-P3** Read, update and delete a project by id.
- **FR-P4** Project detail surfaces the team and a quick "add task" entry point.

### 3.3 Sprints — ✅ Built
- **FR-S1** Create a sprint with goal, start/end dates and committed points.
- **FR-S2** Sprint numbers auto-increment per project.
- **FR-S3** End date must be after the start date (validated server-side).
- **FR-S4** Move sprint status `PLANNED` → `ACTIVE` → `COMPLETED`.

### 3.4 Tasks, Board & Backlog — ✅ Built
- **FR-T1** Create tasks with title, project, optional sprint, status, priority, points, assignee.
- **FR-T2** The board groups tasks across six lanes (`BACKLOG`, `TODO`, `IN_PROGRESS`,
  `IN_REVIEW`, `TESTING`, `DONE`).
- **FR-T3** Changing status on the board updates the API optimistically and rolls back on failure.
- **FR-T4** Backlog shows tasks with no sprint.
- **FR-T5** Labels are stored per task in a join table.
- **FR-T6** Assigning a task notifies the assignee (never for self-assignment).

### 3.5 Users, Roles & Settings — ✅ Built
- **FR-U1** Roster of all workspace users, used for every assignee dropdown.
- **FR-U2** Update your own display name; the change persists to the server.
- **FR-U3** `ADMIN` can change any user's role from Settings; others see a read-only badge.
- **FR-U4** Unauthorized role changes are rejected with **403** (not a 500).

### 3.6 Wiki — ✅ Built
- **FR-W1** Create, edit and delete markdown pages.
- **FR-W2** Pages can be linked to a project or left general.
- **FR-W3** The author is recorded from the authenticated user, never from the request body.
- **FR-W4** Full-text search across titles and content (`GET /api/wiki?q=`).

### 3.7 Inbox & Calendar — ✅ Built
- **FR-I1** Notifications are generated on task assignment.
- **FR-I2** Unread count, mark-one-read, mark-all-read, archive.
- **FR-I3** Inbox filters by category and honours muted categories.
- **FR-I4** Calendar renders sprint windows and today's marker on a real month grid.

### 3.8 Analytics — ✅ Built
- **FR-AN1** Velocity per sprint: committed vs completed points.
- **FR-AN2** Breakdowns by status and priority.
- **FR-AN3** Team load: open tasks and open points per person.
- **FR-AN4** Risk detection from live task state: stale reviews, urgent-but-unstarted work,
  work stuck in testing, unassigned high-priority items.
- **FR-AN5** Summary tiles: total tasks, completion rate, committed vs done points in the active
  sprint, open risks.

### 3.9 AI Copilot — ✅ Built
- **FR-C1** Answers questions about blockers, urgent work, sprint progress, workload and risk.
- **FR-C2** Answers are computed from live task/sprint/user data — deterministic, offline,
  no external service and no API key required (**grounded mode**).
- **FR-C3** Side panels show detected risks, sprint progress and team capacity.
- **FR-C4** If `NEXUS_LLM_API_KEY` is configured, the server forwards the question plus a
  grounded data summary to the configured LLM (**LLM mode**) and reports which mode answered.
- **FR-C5** The API key never reaches the browser, and any LLM failure degrades to grounded mode
  rather than erroring.

### 3.10 Whiteboard — ✅ Built
- **FR-WB1** Sticky notes with colour, text and free positioning; add, edit, drag, delete.
- **FR-WB2** Notes persist to the server (not the browser), so boards survive reloads and devices.
- **FR-WB3** Live sync over WebSocket: created/updated/deleted events reach every open session
  without a refresh, with automatic reconnect and backoff.
- **FR-WB4** The connection is authenticated with the session token during the handshake.
- **FR-WB5** Redis pub/sub fans events out so multiple backend instances stay consistent, with
  origin tagging so an instance never replays its own echo.
- **FR-WB6** A truthful status badge shows Live or Offline based on the real socket state.

### 3.11 My Work & Help — ✅ Built
- **FR-M1** "My Work" lists the signed-in user's tasks and their inbox summary.
- **FR-H1** Help page documents the workspace.

### 3.12 Nexus AI Assistant (RAG over Nexus data) — ✅ Implemented
> Unit-tested and build-verified (47 backend tests, frontend lint + typecheck + build).
> Not yet re-run against a live stack in this change set (no container runtime in the
> authoring environment) — `docker compose up` plus an `OPENAI_API_KEY` reproduces it end to end.

- **FR-AI1** Full AI chat at `/ai`: conversation history sidebar, Markdown answers with code
  blocks and tables, per-answer source citations, token streaming (SSE), regeneration,
  copy-to-clipboard, loading and error states.
- **FR-AI2** Server-side question pipeline: authenticate → validate → conversation ownership →
  permission-aware retrieval → minimal context package → OpenAI (streamed) → persisted answer
  with citations. All OpenAI calls happen in the backend; the key never reaches the browser.
- **FR-AI3** Knowledge/indexing service: wiki pages, projects, tasks, sprints and whiteboard
  notes are normalized, chunked (~1k chars, paragraph-aware, overlapping) and stored in
  `knowledge_chunks` with metadata (source, record id, title, category, project, tags,
  source timestamps, access scope) plus an OpenAI embedding and a generated `tsvector`.
- **FR-AI4** Incremental synchronization: create/update/delete events on source services
  re-index only the affected record (after commit); `POST /api/ai/knowledge/reindex` (ADMIN)
  reconciles stale/orphaned chunks and can rebuild from scratch (`?full=true`).
- **FR-AI5** Hybrid retrieval — Postgres full-text (GIN) + keyword match + semantic ranking
  from an in-memory vector index (warmed at startup, updated incrementally, refreshed every
  5 min), fused with reciprocal-rank fusion; only the smallest useful context is sent
  (≤ 6 chunks / ~6 000 chars, ≤ 2 chunks per source) — never the whole database.
- **FR-AI6** Permission-aware: retrieval filters access scope in SQL *and* again against the
  vector index in-process; conversations are strictly per-user (404 across users); PRIVATE and
  ADMIN scopes are supported alongside WORKSPACE for future fine-grained data.
- **FR-AI7** OpenAI integration behind a clean `aiService.ask(question, context, conversation)`
  abstraction (`AiEngine` interface): model configurable via `OPENAI_MODEL`, embeddings via
  `OPENAI_EMBEDDING_MODEL`, key via `OPENAI_API_KEY` (falls back to `NEXUS_LLM_*`); without a
  key the assistant degrades to a source-cited context-only answer instead of erroring.
- **FR-AI8** AI behavior rules in the system prompt: authoritative Nexus context first,
  inline `[n]` citations, an explicit "could not find sufficient information" when context is
  missing, no invented facts, numbers/dates/names preserved verbatim.
- **FR-AI9** Security: JWT auth on every route, per-user rate limit (`NEXUS_AI_RATE_LIMIT`,
  HTTP 429), question input validation (≤ 2 000 chars), classified engine errors returned as
  friendly messages (timeouts, rate limits, auth, provider outages), logging without keys,
  prompts or provider payloads.
- **FR-AI10** Context management: recent history verbatim, older turns summarized, bounded by
  a character budget so long conversations stay cheap.

---

## 4. Non-Functional Requirements

- **NFR-1 Performance** — API responses are single-digit to low-tens of milliseconds against
  seeded data volumes; the frontend bundle is **403 kB (118 kB gzipped)**.
- **NFR-2 Security** — Passwords are hashed with BCrypt; JWTs use HS256 with a secret supplied by
  environment (≥32 bytes, enforced by jjwt); token types are separated; auth endpoints are rate
  limited; CORS origins come from the environment; secrets are never committed (`.env*` ignored).
- **NFR-3 Data integrity** — Flyway owns the schema (`ddl-auto=validate`); migrations are
  append-only and never edited after being applied; orphaned columns were migrated, not dropped.
- **NFR-4 Reliability** — The full stack starts from a clean checkout with one command
  (`docker compose up -d --build`) and migrates `V1 → V7` on boot.
- **NFR-5 Quality gates** — CI runs frontend lint + production build and the backend unit test
  suite (**47 tests**) plus a clean image build on every push/PR to `main`.
- **NFR-6 Portability** — The backend is a plain Docker image that honours the platform-provided
  `PORT`; the frontend builds to static assets.

---

## 5. Architecture

```
frontend/   React 19 · TypeScript · Vite 8 · Tailwind 4 · React Router 7 · Zustand · axios
backend/    Spring Boot 4.1 (Java 21) · Spring Security 7 · Spring Data JPA · Flyway ·
            PostgreSQL 16 · Redis 7 · WebSocket (JWT handshake)
docker-compose.yml   postgres + redis + backend
frontend/vercel.json frontend deployment descriptor (SPA rewrite)
```

No Railway config file is committed: Config as Code (`railway.json`/`railway.toml`) is deprecated
and unread for new Railway services, so the deployment is configured in the dashboard instead
(Root Directory `backend`, plugin variables, healthcheck path).

**Request flow:** React → axios client (JWT bearer injected from `localStorage`) → Spring Boot
controllers → services → repositories → PostgreSQL. Flyway manages the schema and Hibernate
validates against it on boot.

**Frontend state:** a single Zustand store mirrors server state and preserves the app's original
public interface, so pages stayed unchanged while the internals moved from mock data to the API.
Reads come from `loadWorkspace()`; writes call the API and update the store from the response
(optimistic for board moves and whiteboard drags, with rollback on failure). Numeric backend IDs
are mapped to the string IDs the UI already used (`p-1`, `t-5`).

**Real-time path:** whiteboard mutations → service → broadcaster → local sockets + Redis channel
→ other instances' sockets → `applyWhiteboardEvent()` in each client store.

### 5.1 API surface (all implemented)

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/auth/register` | Create account, returns access + refresh tokens |
| POST | `/api/auth/login` | Authenticate, returns access + refresh tokens |
| POST | `/api/auth/refresh` | Exchange a refresh token for a new token pair |
| GET | `/api/health` | Liveness (public) |
| GET | `/api/users` | List workspace users |
| PATCH | `/api/users/me` | Update own display name |
| PATCH | `/api/users/{id}/role` | Change a user's role (**ADMIN**) |
| GET · POST | `/api/projects` | List · create projects |
| GET · PUT · DELETE | `/api/projects/{id}` | Read · update · delete |
| GET · POST | `/api/sprints` | List (`?projectId`) · create |
| GET | `/api/sprints/{id}` | Read sprint |
| PATCH | `/api/sprints/{id}/status` | Change sprint status |
| GET · POST | `/api/tasks` | List (`?projectId`,`?sprintId`) · create |
| GET · PUT · DELETE | `/api/tasks/{id}` | Read · update · delete |
| PATCH | `/api/tasks/{id}/status` | Move a task across the board |
| GET · POST | `/api/wiki` | List or search (`?q`) · create page |
| GET · PATCH · DELETE | `/api/wiki/{id}` | Read · update · delete page |
| GET | `/api/notifications` | My notifications |
| GET | `/api/notifications/unread-count` | Unread badge count |
| PATCH | `/api/notifications/{id}/read` | Mark one read |
| POST | `/api/notifications/read-all` | Mark all read |
| DELETE | `/api/notifications/{id}` | Archive |
| GET | `/api/analytics/overview` | Velocity, breakdowns, team load, risks (`?projectId`) |
| POST | `/api/copilot/ask` | Ask the Copilot (`?projectId`); returns `mode` + `answer` |
| POST | `/api/ai/ask` | Ask Nexus AI — SSE stream (`meta`, `delta`, `done`, `error`); body `{question, conversationId?, projectId?}` |
| GET | `/api/ai/conversations` | List my conversations (threads are created by `/ask`) |
| GET · DELETE | `/api/ai/conversations/{id}` | Read · delete an owned conversation (404 otherwise) |
| POST | `/api/ai/conversations/{id}/regenerate` | Re-answer the last question — SSE stream |
| GET | `/api/ai/knowledge/status` | Engine config, indexed/embedded chunk counts, vectors loaded |
| POST | `/api/ai/knowledge/reindex` | Incremental reconcile of the knowledge index (**ADMIN**; `?full=true` rebuilds) |
| GET · POST | `/api/whiteboard/notes` | List · create sticky notes (`?board`) |
| PATCH · DELETE | `/api/whiteboard/notes/{id}` | Move/edit · delete a note |
| WS | `/ws/whiteboard?token=` | Live whiteboard event stream (JWT handshake) |
| GET | `/actuator/health` | Actuator liveness |

### 5.2 Data model

`organizations` · `workspaces` · `users` · `projects` · `sprints` · `tasks` · `task_labels` ·
`wiki_pages` · `notifications` · `whiteboard_notes` — all `BIGSERIAL` primary keys with
`created_at` / `updated_at` timestamps. The AI layer adds `knowledge_chunks` (indexed,
permission-scoped copies of source records with `tsvector` + embedding) and
`chat_conversations` / `chat_messages` (`UUID` keys, per-user ownership, citations as JSON).

| Migration | Contents |
|-----------|----------|
| `V1` | Initial schema |
| `V2` | Seed data (4 users, project, sprints, tasks) |
| `V3` | `task_labels` join table (converted from the V1 `labels TEXT[]` column) |
| `V4` | Repairs the seed password hashes so the documented credentials work |
| `V5` | `wiki_pages` |
| `V6` | `notifications` (+ a seed wiki page) |
| `V7` | `whiteboard_notes` |
| `V8` | `knowledge_chunks` (FTS vector + embeddings) · `chat_conversations` · `chat_messages` |

### 5.3 Configuration

Every environment-dependent setting is overrideable; see [`.env.example`](.env.example) for the
full list and generation hints.

| Variable | Purpose | Default |
|----------|---------|---------|
| `PORT` | HTTP port | `8080` |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Database connection | local compose values |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | Redis for whiteboard fan-out | `localhost:6379` |
| `NEXUS_JWT_SECRET` | HS256 signing key (**≥32 bytes**) | dev-only fallback |
| `NEXUS_CORS_ALLOWED_ORIGINS` | Comma-separated allowed browser origins | `localhost:5173,localhost:3000` |
| `NEXUS_AUTH_RATE_LIMIT` | Max auth requests per IP per 60 s window | `20` |
| `NEXUS_LLM_API_KEY` / `_BASE_URL` / `_MODEL` | Optional Copilot LLM | empty → grounded mode |
| `OPENAI_API_KEY` / `_BASE_URL` / `_MODEL` / `_EMBEDDING_MODEL` | Nexus AI engine (server-side only; falls back to `NEXUS_LLM_*`) | key empty → context-only mode; model `gpt-4o-mini`; embeddings `text-embedding-3-small` |
| `NEXUS_AI_RATE_LIMIT` | Max Nexus AI questions per user per 60 s | `20` |
| `VITE_API_URL` (frontend) | Backend API base **including `/api`**, baked into the build at build time | `/api` via dev proxy |
| `VITE_WS_URL` (frontend) | Explicit whiteboard socket URL | derived from `VITE_API_URL` (http→ws, trailing `/api` stripped) |

---

## 6. Delivery Plan

| Phase | Scope | Status |
|-------|-------|--------|
| 0 | CI pipeline, Docker test stage, backend unit tests | ✅ Done |
| 1 | Frontend ↔ backend integration (auth, projects, sprints, board, backlog) | ✅ Done |
| 2 | Users, roles and settings | ✅ Done |
| 3 | Wiki (CRUD + search) | ✅ Done |
| 4 | Inbox notifications and calendar on real data | ✅ Done |
| 5 | Analytics endpoints + Analytics page + grounded Copilot | ✅ Done |
| 6 | Whiteboard with server persistence and live sync | ✅ Done |
| 7 | Optional LLM-backed Copilot with grounded fallback | ✅ Done |
| 8 | Production deployment config and hardening | ✅ Done |
| 9 | Nexus AI assistant: knowledge index + incremental sync, hybrid retrieval, server-side OpenAI layer, streaming RAG chat | ✅ Implemented |

### Phase 8 detail
- Refresh-token endpoint with token-type separation (an access token cannot be replayed).
- Per-IP rate limiting on `/api/auth/**` with a bounded in-memory window (verified 429s).
- Environment-provided JWT secret, CORS origins, DB/Redis URLs and platform `PORT`.
- Actuator limited to `health,info`; all other routes authenticated.
- A pre-startup adapter (`PlatformEnvironment`) translates the platform's single connection
  strings into Spring properties: `DATABASE_URL` → JDBC datasource (+username/password), or the
  discrete `PGHOST`/`PGPORT`/`PGUSER`/`PGPASSWORD`/`PGDATABASE` form; `REDIS_URL`/`REDIS_TLS_URL`
  → Redis host/port/password/TLS, or `REDISHOST`/`REDISPORT`/`REDISPASSWORD`. Explicit Spring
  configuration always wins, so local and docker-compose runs are unaffected.
- Deployment descriptors: `frontend/vercel.json` (static build + SPA rewrite); `.env.example`
  documents every variable.

---

## 7. Deployment

### Backend — Railway (or any Docker host)
1. Create a project and add the **PostgreSQL** and **Redis** plugins.
2. Add a service from this repository and set **Settings → Source → Root Directory** to
   `backend`, so the build context matches what `backend/Dockerfile` copies.
3. Set `NEXUS_JWT_SECRET` (`openssl rand -hex 32`), `NEXUS_CORS_ALLOWED_ORIGINS` (the Vercel
   origin), `DATABASE_URL` = `${{Postgres.DATABASE_URL}}` and `REDIS_URL` = `${{Redis.REDIS_URL}}`.
   `PORT` is injected automatically and the app derives its connections from those URLs.
4. Health check: `/api/health`. Flyway migrates on boot.
5. The backend's public URL is created at **Settings → Networking → Generate Domain**.

### Frontend — Vercel
1. The Vercel project is connected to `dev640/nexus2.0` (`main`); root directory
   `frontend/`, framework Vite, output `dist`.
2. Set `VITE_API_URL` to the backend's public URL **plus `/api`** (e.g.
   `https://your-backend.up.railway.app/api`), then redeploy — Vite inlines `VITE_*`
   variables at build time, so adding the variable alone changes nothing.
3. Every push to `main` deploys automatically; the SPA rewrite keeps client routes working.

> The backend is **not** a serverless function — it is a long-running Spring Boot container,
> which is what allows the WebSocket-based whiteboard to work in production.

---

## 8. Local Development

```bash
# 1. Backend stack (Postgres + Redis + Spring Boot API on :8080)
docker compose up -d --build

# 2. Frontend dev server (proxies /api and /ws to localhost:8080)
cd frontend && npm install && npm run dev        # http://localhost:5173

# 3. Seed logins (password: password123)
#    devendra@nexus.com (ADMIN) · achal@nexus.com · vidhi@nexus.com · palak@nexus.com

# 4. Quality gates
docker build --target test ./backend             # backend unit tests (47)
cd frontend && npx tsc -b && npm run lint && npm run build
```

### Verified end to end
- Clean database volume → Flyway applies `V1 → V7` with seed data.
- Login, refresh-token exchange, and rejection of an access token used as a refresh token.
- Anonymous `/api/*` → **403**; authenticated access to all eight domains → **200**.
- Task creation, board status change and whiteboard note create/delete persist to Postgres.
- Live whiteboard events reach an open session without a reload, from a separate client.
- Rate limiter returns **429** once an IP exceeds its window.
- CORS preflight from the frontend origin returns **200**.

---

## 9. Known Gaps & Next Milestones

1. **`VIEWER` is not enforced per-endpoint.** The role exists and is assignable; read-only
   enforcement across controllers is the next permission milestone.
2. **Team/team-assignment UI is still client-side.** Member and team grouping on the Team page is
   not backed by tables yet, unlike users and roles which are real.
3. **Refresh tokens are not rotated client-side.** The endpoint and token types exist; the
   frontend holds a 24 h access token and does not yet silently refresh on 401.
4. **A dev-only JWT fallback secret is present in `application.properties`.** Production must set
   `NEXUS_JWT_SECRET`; a startup check that refuses the default profile is a worthwhile hardening.
5. **Rate limiting is per instance.** A shared Redis counter is needed for multi-instance
   deployments.
6. **No end-to-end (browser) test suite in CI.** Coverage today is backend unit tests plus
   frontend lint/build; a Playwright smoke path would guard the wiring regressions found during
   Phase 1.

---

## 10. Success Metrics

- A new engineer can register, create a project, sprint and task, and see them on the board in
  under five minutes.
- Every page in the navigation renders live data — no mock-only views remain.
- CI is green on `main`; the stack starts from a clean checkout with one command.
- Two browsers on the whiteboard see each other's notes within a second.
