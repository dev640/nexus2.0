# Nexus — Operations Runbook

Day-2 operations for the deployed Nexus stack: what runs where, how a release is verified and
reversed, which variable lives on which platform, and what to check when something is wrong.

- **First-time setup** (installing, creating projects, env vars from scratch): [`README.md`](README.md).
- **Every environment variable, annotated**: [`.env.example`](.env.example).
- **What the product is and how it is built**: [`PRD.md`](PRD.md).

---

## 1. Environments at a glance

| Piece | Where | Address |
|-------|-------|---------|
| Frontend | Vercel project `nexus-2-0` (account `devsatpaise-9691`), repo root, `vercel.json` | https://nexus-2-0-omega.vercel.app |
| Backend API | Railway service, built from `backend/Dockerfile` | https://nexus20-production.up.railway.app |
| Postgres | Railway `Postgres` plugin (`DATABASE_URL`) | private network |
| Redis | Railway `Redis` plugin (`REDIS_URL`) — whiteboard fan-out | private network |
| Auth (optional) | Supabase project `ktvjotwmpynbjgnqjitg` | https://ktvjotwmpynbjgnqjitg.supabase.co |
| LLM (optional) | Google AI Studio or any OpenAI-compatible `/chat/completions` API | see §6 |

Public endpoints, all unauthenticated:

| Path | Returns | Proves |
|------|---------|--------|
| `GET /api/health` | `{"status":"ok"}` | the app is up and Spring MVC is routing. It is a **static handler** — it does not touch Postgres or Redis |
| `GET /actuator/health` | `{"status":"UP","groups":[…]}` | the app plus its dependency indicators (datasource, Redis). Component details are hidden from anonymous callers |
| `GET /actuator/info` | `{}` | nothing useful — no build-info contributor is configured. Identify a running build from the Railway deploy log and the Vercel deployment commit instead |

The backend is a **long-running container, not a serverless function** — that is what keeps
`/ws/chat` and `/ws/whiteboard` connected. Both WebSocket paths are open at the handshake and
authenticated from the JWT in the query string.

---

## 2. Releasing

Both platforms deploy from `main`. There is no manual build step and no separate release branch.

1. **Merge to `main`.** The frontend job and the backend job in
   [`.github/workflows/ci.yml`](.github/workflows/ci.yml) both start on every push and pull request:
   - frontend: `npm ci` → `npm run lint` → `npm test` (Vitest) → `npm run build` (`tsc -b` + Vite)
   - backend: `docker build --target test ./backend` (compiles **and** runs the unit tests), then a
     clean build of the runtime image
   A red check here blocks the merge, which is cheaper than a red deploy.
2. **Vercel** builds the repo-root project and serves `frontend/dist`. Every push to `main`
   deploys automatically.
3. **Railway** builds the backend service (Root Directory `backend`) and restarts it. Flyway
   migrates on boot; if a migration fails, the new revision never becomes healthy and the previous
   one keeps serving.
4. **Verify** — §3, every time. A deploy that has not been verified is not done.

### Configuration changes are deploys

`VITE_*` variables are inlined by Vite **at build time**: adding one in the Vercel dashboard
changes nothing until a redeploy. Railway applies backend variable changes on the next deploy —
trigger one from the dashboard if the service does not redeploy by itself.

---

## 3. Verifying a release

```bash
API=https://nexus20-production.up.railway.app
WEB=https://nexus-2-0-omega.vercel.app

# 1. App up, and dependencies too (the deep check) — no credentials needed
curl -fsS $API/api/health      # {"status":"ok"}
curl -fsS $API/actuator/health # {"status":"UP",...}  ← fails if Postgres/Redis are unreachable

# 2. Frontend serves the SPA and rewrites deep client routes
curl -fsS -o /dev/null -w '%{http_code}\n' $WEB/          # 200
curl -fsS -o /dev/null -w '%{http_code}\n' $WEB/projects   # 200 (rewrite, not a 404)
```

Steps 1 and 2 are credential-free and always work. The authenticated half of the check depends on
which auth provider the deployment is using, so pick the matching one:

```bash
# 3a. Built-in auth (Supabase NOT configured) — sign in, read the caller, load the workspace
TOKEN=$(curl -fsS -X POST $API/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"devendra@nexus.com","password":"password123"}' | node -pe 'JSON.parse(require("fs").readFileSync(0)).token')
curl -fsS $API/api/users/me   -H "Authorization: Bearer $TOKEN"
curl -fsS $API/api/projects   -H "Authorization: Bearer $TOKEN"   # empty is fine, 500 is not

# 3b. Supabase configured — get a token from Supabase, then use it against the same API
#      (the anon key is public; it lives in .env.example as VITE_SUPABASE_ANON_KEY)
TOKEN=$(curl -fsS -X POST https://ktvjotwmpynbjgnqjitg.supabase.co/auth/v1/token?grant_type=password \
  -H "apikey: $VITE_SUPABASE_ANON_KEY" -H 'Content-Type: application/json' \
  -d '{"email":"devendra@nexus.com","password":"<password set in Supabase>"}' \
  | node -pe 'JSON.parse(require("fs").readFileSync(0)).access_token')
curl -fsS $API/api/users/me -H "Authorization: Bearer $TOKEN"
```

**Step 3a is expected to fail with `400 Invalid email or password` on a Supabase deployment**, and
that is not a regression: linking an account to Supabase clears its local password hash, so
`password123` stops working the moment Supabase is turned on. Verify the authenticated layer through
3b, or in the browser, or issue a local password first from **Admin → Create an account** /
**Admin → Password requests**.

Then confirm by hand, because no probe covers these:

- **Board** — a task's status change survives a reload (optimistic update reconciled with the server).
- **Whiteboard** — two browsers see each other's sticky notes live; the connection badge turns green.
- **Chat** — a message in a channel arrives in a second browser without a reload; the unread badge
  increments and clears on read.
- **Copilot** — asks a question and the reply names its mode. `reason: "Copilot is not configured"`
  means the backend has no LLM key/model yet (see §6), which is expected, not a fault.

---

## 4. Rolling back

| Layer | How | Notes |
|-------|-----|-------|
| Frontend | Vercel → Deployments → promote an earlier deployment | The SPA is static, so this is instant and complete |
| Backend | Railway → service → Deploy → redeploy an earlier successful build | Never force-push a schema revert to fix this |
| Database | Nothing to revert — migrations are forward-only | See §5 |

**Schema rule: an applied Flyway migration is immutable.** If `V13` breaks production, the fix is a
new `V14__…` migration, not an edit to `V13`. Editing an applied migration makes Flyway fail its
checksum validation on the next boot, which takes the service down rather than rolling it back —
a much worse outcome than a bad column.

---

## 5. Database, migrations and backups

- **Flyway** runs on every boot (`spring.flyway.enabled=true`, `baseline-on-migrate=true`,
  `out-of-order=false`) from `backend/src/main/resources/db/migration/`. Current head: **V12**.
- **Adding one:** create `V<n+1>__Add_something.sql`, never renumber or edit an existing file. It
  applies on the next deploy of the backend.
- **What a fresh database contains** (from `V2__Insert_seed_data.sql` and later migrations):

  | Table | Rows |
  |-------|------|
  | organizations | 1 — Acme Corporation |
  | workspaces | 1 — Engineering Workspace |
  | users | 4 — Devendra (ADMIN), Achal, Vidhi, Palak |
  | projects | 1 — AI Commerce Platform (ACTIVE) |
  | sprints | 1 — #8 "Launch the billing foundation" (ACTIVE) |
  | tasks | 7 |
  | chat channels | **0** — no migration seeds channels; create one from the Chat page |

- **Reset a local database** back to that baseline:

  ```bash
  docker compose down -v && docker compose up -d --build
  ```

- **Backups.** Enable them on the database you actually run on (Railway `Postgres` backups, or
  Supabase point-in-time recovery), and test a restore before you need one. A manual snapshot for
  a `DATABASE_URL`:

  ```bash
  pg_dump "$DATABASE_URL" --format=custom --file="nexus-$(date +%F).dump"
  pg_restore --clean --if-exists --dbname="$DATABASE_URL" nexus-2026-10-04.dump
  ```

---

## 6. Turning on the LLM Copilot

The Copilot is useful without any key: it answers from workspace data and says so. Adding a
provider upgrades it, and every failure path falls back to the grounded answer while naming the
reason, so a broken key degrades quality rather than breaking the page.

Set on **Railway** (backend), then redeploy:

| Variable | Value |
|----------|-------|
| `NEXUS_LLM_PROVIDER` | `gemini` for Google AI Studio, or `openai` for any OpenAI-compatible `/chat/completions` API. Optional — inferred from the base URL when set |
| `NEXUS_LLM_API_KEY` | The provider key. **Never** committed, never set on Vercel |
| `NEXUS_LLM_MODEL` | Required once the key is set. There is deliberately no default: a stale model id fails on every request and looks like an outage |
| `NEXUS_LLM_BASE_URL` | Optional override for gateways/self-hosted endpoints |

Until **both** the key and the model are set, the Copilot stays grounded and reports
`"Copilot is not configured"`. Check your provider's current model list — `gemini-2.5-flash` and
`gpt-4o-mini` are starting points, not guarantees.

---

## 7. Configuration map

Where each variable lives. `VITE_*` = build time (Vercel, public in the bundle); everything else =
runtime (Railway, server-only).

| Variable | Platform | Secret? | Notes |
|----------|----------|---------|-------|
| `VITE_API_URL` | Vercel | no | **Must end in `/api`** — the client appends route paths verbatim, so a bare host 404s every request |
| `VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY` | Vercel | anon key is public by design | Both or neither; enables Supabase Auth on the login screen |
| `VITE_WS_URL` | Vercel | no | Optional. Derived from `VITE_API_URL` when unset (`http`→`ws`, drop `/api`) |
| `DATABASE_URL`, `REDIS_URL` | Railway | yes | Reference the plugin variables (`${{Postgres.DATABASE_URL}}`) so they stay in sync |
| `NEXUS_JWT_SECRET` | Railway | **yes** | ≥32 bytes for HS256 (`openssl rand -hex 32`). Rotating it invalidates every issued token, i.e. signs everyone out |
| `NEXUS_CORS_ALLOWED_ORIGINS` | Railway | no | Comma-separated browser origins; must include the deployed frontend origin |
| `NEXUS_AUTH_RATE_LIMIT` | Railway | no | Requests per 60 s per IP on `/api/auth/**`. Default 20 |
| `NEXUS_LLM_*` | Railway | key only | §6 |
| `NEXUS_SUPABASE_SERVICE_ROLE_KEY` | Railway | **yes** | Full-access admin credential. Server-only; never in a `VITE_` variable, never committed |
| `PORT` | platform | no | Injected by the platform; defaults to 8080 |

---

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| Every API call 404s from the deployed frontend | `VITE_API_URL` lost its `/api` suffix | Append `/api`, redeploy |
| Browser console: CORS error; curl works | Frontend origin missing from `NEXUS_CORS_ALLOWED_ORIGINS` | Add the exact origin (scheme + host, no trailing slash), redeploy the backend |
| Login works in curl, not in the browser | Stale bundle pointing at another API | Redeploy the frontend; confirm the deployment promoted is the current commit |
| Login screen loops back immediately | `NEXUS_JWT_SECRET` changed, or token storage cleared | Expected after a secret rotation — sign in again |
| 403 on an edit that worked for other users | The account is `VIEWER` (read-only by design) | Grant the role from Admin |
| Whiteboard/chat never connects | Backend sleeps, is redeploying, or is not a long-running service | Check `/actuator/health`, then Railway's service logs; WebSockets need a container, not serverless |
| Whiteboard notes vanish on refresh | Redis unreachable → fan-out degraded | Check the `redis` health indicator; notes themselves persist in Postgres |
| Backend restarts on boot with a Flyway error | An applied migration was edited or one failed | Never edit applied migrations; add a new one. Read the stack trace in the deploy log |
| Supabase sign-in unavailable, built-in login gone | Half-configured Supabase (no usable credential) | Set the URL **and** a JWT secret or `NEXUS_SUPABASE_USE_JWKS=true`; the integration stays off until complete |
| Copilot answers look generic | No LLM key/model, or the provider call is failing | §6; the Copilot names the reason next to the answer |
| 429 from `/api/auth/**` | Per-IP auth rate limit (20 requests / 60 s) | Wait out the window. A shared counter is still a known gap (see PRD §9) |

### Known inconsistencies

- `docker-compose.yml` sets `NEXUS_JWT_EXPIRATION_MINUTES: 60`, but **nothing reads it** — the
  access-token lifetime is the fixed `jwt.expiration=86400000` (24 h) in `application.properties`.
  Treat 24 h as the real value until that variable is either wired up or removed.
- `/api/health` is a static handler, so it stays green while Postgres is down. It is the right
  platform healthcheck (fast, unauthenticated, never causes a spurious rollback), but it is not a
  dependency check — use `/actuator/health` for that.

---

## 9. Incident checklist

First five minutes:

1. `curl -fsS $API/actuator/health` — is the API up, and are its dependencies?
2. `curl -fsS -o /dev/null -w '%{http_code}' $WEB/` — is the frontend serving?
3. Railway → service → **Logs**: boot failures, Flyway errors and stack traces live there.
4. Vercel → **Deployments**: is the live build the commit you expect?
5. Did anything change? A variable edit, a migration, or a deploy in the last few minutes is the
   usual answer. Roll back the layer (§4) before debugging further.

Then: capture the failing request (method, path, status, body), reproduce it against
`/api/users/me` to separate auth from application faults, and write down what you learned in the
PR or an issue even when the fix was a redeploy.

---

## 10. Secrets rules

- `.env*` is gitignored except `.env.example`, which documents names and shapes — never values.
- Keys live in the platform dashboards (Railway, Vercel, Supabase), never in the repository.
- The Supabase **anon key is public** and ships in the bundle by design; the **service-role key is
  not** and must never be prefixed with `VITE_`.
- An OpenAI/LLM key is set only in Railway, and only through the dashboard.
- Rotate by changing the platform variable and redeploying; for `NEXUS_JWT_SECRET` expect everyone
  to sign in again.
