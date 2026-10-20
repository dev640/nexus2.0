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
| LLM Copilot (optional) | Google AI Studio or any OpenAI-compatible `/chat/completions` API | see §6 |
| Nexus AI (optional) | OpenAI, or any OpenAI-compatible chat + embeddings API | see §6 |

**The local `.vercel` link points at a different project than the one serving this site.** It is
pinned to `lexus` (projectId `prj_WrBf9MlErirqk4I3JXtzFKqbux2T`, serving `lexus-xi.vercel.app`), while
production is `nexus-2-0` (projectId `prj_vrT2YnrKSqVwuMqcLlkWHDY26DWM`). A `vercel env add` or
`vercel deploy` run from this directory without an explicit target therefore acts on the **wrong
project** and reports success while changing nothing that users see. Either re-link
(`vercel link --project nexus-2-0`) or pass the target on every command:

```bash
export VERCEL_PROJECT_ID=prj_vrT2YnrKSqVwuMqcLlkWHDY26DWM
export VERCEL_ORG_ID=team_KBNyh4qbBb9JoEunZRBAv08o
vercel project ls          # 5 projects share this account; confirm which one you are touching
```

`vercel project inspect <name>` prints the build settings to check against: for `nexus-2-0` these are
Root Directory `.`, Build Command `cd frontend && npm install && npm run build`, Output Directory
`frontend/dist`.

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
#      (the public key is safe to expose; it lives in .env.example as VITE_SUPABASE_ANON_KEY,
#       and either a legacy anon JWT or an sb_publishable_ key works here)
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
- **Nexus AI** (`/ai`) — asks a workspace question and the answer streams in with citations. A
  `Context mode` badge, or `configured: false` from `GET /api/ai/knowledge/status`, means the
  backend has no `OPENAI_API_KEY` yet (see §6) — retrieval still works, generation does not.

---

## 4. Rolling back

| Layer | How | Notes |
|-------|-----|-------|
| Frontend | Vercel → Deployments → promote an earlier deployment | The SPA is static, so this is instant and complete |
| Backend | Railway → service → Deploy → redeploy an earlier successful build | Never force-push a schema revert to fix this |
| Database | Nothing to revert — migrations are forward-only | See §5 |

**Schema rule: an applied Flyway migration is immutable.** If `V22` breaks production, the fix is a
new `V23__…` migration, not an edit to `V22`. Editing an applied migration makes Flyway fail its
checksum validation on the next boot, which takes the service down rather than rolling it back —
a much worse outcome than a bad column.

---

## 5. Database, migrations and backups

- **Flyway** runs on every boot (`spring.flyway.enabled=true`, `baseline-on-migrate=true`,
  `out-of-order=false`) from `backend/src/main/resources/db/migration/`. Current head: **V22**.
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

### Nexus AI (generated answers at `/ai`)

The `/ai` page is a **second, independent engine**, not a second view of the Copilot. It answers
from the workspace knowledge index with an OpenAI-compatible chat + embeddings API (the
`nexus.ai.*` keys in `application.properties`), so a Google AI Studio key that powers the Copilot is
not usable here.

Set on **Railway** (backend), then redeploy:

| Variable | Value |
|----------|-------|
| `OPENAI_API_KEY` | **Required for written answers.** Server-only; never on Vercel. Left empty, the page runs in **context-only mode** — it still retrieves and cites Nexus records, but the reply restates that context instead of composing an answer |
| `OPENAI_MODEL` | Optional. Default `gpt-4o-mini` |
| `OPENAI_EMBEDDING_MODEL` | Optional. Default `text-embedding-3-small`. Changing it invalidates every stored embedding — rebuild the index below afterwards |
| `OPENAI_BASE_URL` | Optional. Default `https://api.openai.com/v1` |
| `NEXUS_AI_TIMEOUT_SECONDS` | Optional. Seconds allowed for one provider call. Default 90 |
| `NEXUS_AI_RATE_LIMIT` | Optional. Questions per user per 60 s. Default 20; over the limit returns 429 |
| `NEXUS_AI_FTS_CANDIDATES`, `NEXUS_AI_KEYWORD_CANDIDATES`, `NEXUS_AI_MAX_CONTEXT_CHUNKS` | Optional retrieval tuning. Defaults 80 / 60 / 6 |

**Leave the `OPENAI_*` overrides unset, not blank.** Each is read through a fallback chain
(`OPENAI_MODEL` → `NEXUS_LLM_MODEL` → `gpt-4o-mini`), and an *empty* value wins over the default: with
`OPENAI_MODEL=` set, the model becomes the empty string and every call fails over to context-only.
That is also why `docker-compose.yml` comments these out instead of forwarding an empty value.

**Mind the fallback direction.** `OPENAI_*` falls back to `NEXUS_LLM_*` so one key can serve both
features, but if `NEXUS_LLM_*` holds a **Gemini** key it is then sent to `api.openai.com` and every
request fails. When both features should work, set `OPENAI_API_KEY` explicitly.

**Build the index once, by hand.** Migrations create the tables, not the embeddings, and the
5-minute background job (`nexus.ai.retrieval.refresh-ms=300000`) only **reloads** embeddings into
memory — it never creates them. Records embed when they change (a task, project, sprint, wiki page
or whiteboard note is saved) or when an admin reconciles the index:

```bash
# $TOKEN and $API from §3; the account must be ADMIN
curl -fsS -X POST "$API/api/ai/knowledge/reindex" -H "Authorization: Bearer $TOKEN"
curl -fsS "$API/api/ai/knowledge/status"          -H "Authorization: Bearer $TOKEN"
# configured: true, embeddedChunks > 0, vectors > 0
```

`reindex` reconciles only stale or orphaned records; add `?full=true` to drop the index and rebuild
it. So on a deployment that already has data and only now gets a key, expect context-only answers
until this call runs — there is no reindex button in the UI, which only displays the counts.

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
| `NEXUS_LLM_*` | Railway | key only | Copilot. §6 |
| `OPENAI_API_KEY` | Railway | **yes** | Nexus AI (`/ai`). Without it answers stay context-only. Never set on Vercel |
| `OPENAI_MODEL`, `OPENAI_EMBEDDING_MODEL`, `OPENAI_BASE_URL` | Railway | no | Optional; default `gpt-4o-mini` / `text-embedding-3-small` / `api.openai.com/v1`. An **empty** value overrides the default, so leave them unset rather than blank |
| `NEXUS_AI_TIMEOUT_SECONDS`, `NEXUS_AI_RATE_LIMIT` | Railway | no | Provider-call timeout (90 s) and per-user questions per 60 s (20) for Nexus AI |
| `NEXUS_AI_FTS_CANDIDATES`, `NEXUS_AI_KEYWORD_CANDIDATES`, `NEXUS_AI_MAX_CONTEXT_CHUNKS` | Railway | no | Nexus AI retrieval tuning; defaults 80 / 60 / 6 |
| `NEXUS_SUPABASE_SERVICE_ROLE_KEY` | Railway | **yes** | Full-access admin credential; accepts a legacy `service_role` JWT or an `sb_secret_…` key. Server-only; never in a `VITE_` variable, never committed. Rotation: §12 |
| `NEXUS_PUSH_VAPID_PUBLIC_KEY` | Railway | no | VAPID public half (base64url, 65 bytes). Served to browsers at `GET /api/push/public-key`. §13 |
| `NEXUS_PUSH_VAPID_PRIVATE_KEY` | Railway | **yes** | VAPID private half (base64url, 32 bytes). Signs every push as this application; anyone holding it can notify your users |
| `NEXUS_PUSH_SUBJECT` | Railway | no | Contact URL the push services require. Default `mailto:ops@nexus.app` |
| `NEXUS_PUSH_TTL_HOURS` | Railway | no | How long an undelivered notification is held. Default 24 |
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
| Admin → Create an account, or a password reset, returns 401 from Supabase | The key's generation does not match the headers sent, or the legacy keys were disabled before the new key was set | §12: a legacy `service_role` JWT needs `apikey` **and** `Authorization: Bearer`; an `sb_secret_…` key needs `apikey` alone |
| `Supabase admin API is not configured` after a rotation | `NEXUS_SUPABASE_SERVICE_ROLE_KEY` is empty or was set on the wrong service/environment | Re-set it on the Railway backend service, then redeploy — the value is read at startup |
| Copilot answers look generic | No LLM key/model, or the provider call is failing | §6; the Copilot names the reason next to the answer |
| Nexus AI shows `Context mode` and only restates retrieved records | No `OPENAI_API_KEY`, or the provider call is failing | §6; the page names the reason above the composer |
| Nexus AI is configured but cites nothing | The knowledge index is empty (fresh deploy, no record saved since) | Run `POST /api/ai/knowledge/reindex` as ADMIN, then check `/api/ai/knowledge/status` (§6) |
| Notifications appear in the Inbox but never as a popup | The browser never granted notification permission, or nothing has asked for it with a user gesture | Settings → Device Alerts → **Enable notifications** (a click is required on Android and Safari) |
| Popups work on the laptop but nothing arrives on the phone | No push subscription for that device, or iOS without the home-screen install | §13; the phone must be subscribed separately from the laptop, and iPhone/iPad require *Add to Home Screen* |
| "Send a test notification" reports 0 devices | This browser has not subscribed, or the server has no key pair | Settings → Device Alerts; then confirm both `NEXUS_PUSH_VAPID_*` variables are set on Railway — a half-configured pair leaves push off |
| Push worked, then silently stopped | The push service rotated or revoked the endpoint (410), and the row was pruned | Reload the app signed in: the browser re-files its subscription on load. `SELECT count(*) FROM push_subscriptions` should go back up |
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
- Local development reads `NEXUS_SUPABASE_SERVICE_ROLE_KEY` from a `.env` file beside
  `docker-compose.yml` (gitignored, and Compose loads it automatically). The compose file
  references the variable and must never carry a value.
- If an admin key is ever exposed — committed, logged, screenshotted, pasted into a ticket —
  follow §12, including the probe that proves the old key now answers 401.

---

## 11. Analytics, consent and transport security

### The chosen provider: Vercel Web Analytics

The frontend loads no third-party script by default, and it is provider-agnostic on purpose: a
script source is the whole integration, so changing provider is a variable change, not a code
change. **This project uses Vercel Web Analytics.**

Why this one:

- **It is cookieless**, which is what keeps the banner on the site a *disclosure with a real off
  switch* rather than a legal ritual: nothing is stored on the visitor's device for analytics, so
  nothing needs prior consent — yet page views are still only counted after they accept.
- **No new vendor and no new data processor.** The script is same-origin (`/_vercel/...`) and Vercel
  is already the host, so the privacy policy's third-party list does not change.
- **50,000 events/month on the free Hobby plan**, with no site or domain to register.
- **It is the only one of the candidates with a free, documented API for reading page views**, so a
  deploy can be verified by command rather than by memory (see below).

The alternatives, and why they are second choice: **Umami Cloud** has the largest free allowance
(100k events/month) but its API — the thing that makes verification possible — needs the $20/month
Pro tier. **Plausible** has no permanent free tier and its Stats API is a Business feature.
**Fathom** has no free tier either. All three cookieless and fine accounts; none of them lets us
confirm delivery for free.

### Turning it on

Three steps, and the first two are dashboard actions that cannot be done from the CLI:

1. **Enable Analytics on the project** — Vercel → the `nexus-2-0` project → **Analytics** →
   **Enable**. Vercel adds the collection routes on the *next* deployment, so step 3 is not
   optional.
2. **Set the script source** on Vercel (Settings → Environment Variables), for Production and
   Preview, then redeploy. `VITE_*` values are inlined at build time, so a variable added without a
   deploy changes nothing:

   | Variable | Value |
   |----------|-------|
   | `VITE_ANALYTICS_SCRIPT_SRC` | `/_vercel/insights/script.js` |

   Vercel is the only provider here that needs no second variable — the script path identifies the
   project. The others need one of `VITE_ANALYTICS_WEBSITE_ID` (Umami), `VITE_ANALYTICS_DOMAIN`
   (Plausible) or `VITE_ANALYTICS_SITE_ID` (Fathom).
3. **Redeploy**, then confirm the script is actually being served — a wrong path fails silently, with
   no error anywhere, because the consent gate injects a tag that 404s:

   ```bash
   curl -o /dev/null -s -w '%{http_code} %{content_type}\n' \
     https://nexus-2-0-omega.vercel.app/_vercel/insights/script.js
   # expect 200 and a javascript content-type; a 404 means the path is wrong or
   # Analytics was not enabled before this deployment
   ```

### Confirming that page views arrive

Do not trust the dashboard's empty state — it looks identical to "not deployed yet". Ask the API:

```bash
curl -s -H "Authorization: Bearer $VERCEL_ACCESS_TOKEN" \
  "https://api.vercel.com/v1/query/web-analytics/visits/count?projectId=$VERCEL_PROJECT_ID&teamId=$VERCEL_ORG_ID" \
  | node -pe 'const d=JSON.parse(require("fs").readFileSync(0)); JSON.stringify(d.data ?? d)'
# {"visitors":1,"pageviews":1}
```

**Current state (verified):** `VITE_ANALYTICS_SCRIPT_SRC` is set on `nexus-2-0` for Production and
the deployed build inlines it. In a browser on the production URL, before any decision the only
script on the page is the app bundle; accepting injects `/_vercel/insights/script.js`, which loads
(200) and posts the view to `/_vercel/insights/view` (200). The API above then reports the page
view, so both halves of the chain — the tag and the recording — are confirmed, not assumed.

It returns `pageviews` and `visitors`. Two caveats worth knowing before concluding anything from a
zero: this endpoint reports **Production** data only — Preview traffic is visible in the dashboard
under its own environment selector — and the app's own tracker does not run in local development,
so a page view must come from a deployed origin.

If you would rather not hold an access token, read the number from Vercel → Analytics and accept
that it is a manual check.

With every analytics variable unset, **no banner is shown at all** — there is nothing optional to
consent to. That is the intended default; a banner asking permission for nothing trains people to
dismiss the one that matters.

### Verifying it on a deployment

```bash
# 1. Before any choice is made: the script must not be requested.
#    Open the site, and in the browser console run:
#      performance.getEntriesByType('resource').filter(r => r.name.includes('insights'))
#    Expect an empty array, and no <script data-nexus-analytics> in the DOM.

# 2. Decline, reload: still empty, and localStorage['nexus-analytics-consent'] === 'declined'.
# 3. Clear that key, reload, accept: the script tag appears and the request is made once.
```

### Local gotcha on Windows

A value that starts with `/` is rewritten by Git Bash before it reaches Vite —
`/_vercel/insights/script.js` arrives as `C:/Program Files/Git/_vercel/insights/script.js`. Put the
values in `.env.local` (Vite loads it, no shell involved) or prefix the command with
`MSYS_NO_PATHCONV=1`, and use a full `https://` URL for local testing.

### Transport security

- The static site sends `Strict-Transport-Security: max-age=63072000` plus `nosniff`,
  `Referrer-Policy` and `X-Frame-Options: DENY`, from the `headers` block in `vercel.json`.
  `includeSubDomains` and `preload` are deliberately omitted: the host is a shared
  `*.vercel.app` name, where both are inappropriate.
- The backend's own HSTS is **opt-in and off by default** (`SECURITY_HEADERS_HSTS_MAX_AGE=0`).
  Set it to `63072000` on Railway once the API is served over HTTPS on a domain you control.
- The API sets a CSP for its own responses. The static site does **not** have one yet: a CSP for
the SPA has to allow the API origin, Supabase, and the analytics script, so it needs its own
change and its own test rather than a guessed policy that breaks sign-in.

---

## 12. Rotating an exposed Supabase admin key

### What the key can do

`NEXUS_SUPABASE_SERVICE_ROLE_KEY` is the Supabase **Admin API** credential. It bypasses Row Level
Security, can read every table in the project, list every auth user and set any user's password.
Nothing in this application's own authorisation layer constrains it — treat a leak as the database
password *and* control of every account at once, and rotate rather than hope.

### Two key generations, two header shapes

Supabase issues two kinds of server credential and **they are not sent the same way**. This is the
whole reason a rotation is a variable change and not a code change:

| Key | Shape | Headers that authenticate it |
|-----|-------|------------------------------|
| Legacy `service_role` | three-part JWT beginning `eyJ` | `apikey` **and** `Authorization: Bearer <key>`. Measured against a live project: either header alone answers **401**, only the pair answers 200 |
| New secret key | `sb_secret_…`, opaque, not a JWT | `apikey` only. Sent as a Bearer token the gateway tries to parse it as a JWT and rejects it |

`SupabaseAdminClient` chooses the header set from the key's shape, so both generations work without
touching code, and `SupabaseAdminClientTest` pins that decision down.

### Procedure

Replace before you revoke. Creating the successor first is what keeps admin provisioning up; the
reverse order takes it down until the new variable is deployed.

1. **Create the successor.** Supabase → **Project Settings → API Keys** → *Publishable and secret
   API keys* → **Create secret key**. Name it for where it lives and when, e.g.
   `railway-backend-2026-10`, and copy the value — it is shown once, at creation.

   Dashboard-free alternative, with a scoped personal access token (`secrets:write`, or the
   fine-grained permission `api_gateway_keys_write`):

   ```bash
   curl -fsS -X POST "https://api.supabase.com/v1/projects/$SUPABASE_PROJECT_REF/api-keys?reveal=true" \
     -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN" -H 'Content-Type: application/json' \
     -d '{"type":"secret","name":"railway-backend-2026-10"}'
   ```

2. **Set it on Railway.** Dashboard: the backend service → **Variables** → edit
   `NEXUS_SUPABASE_SERVICE_ROLE_KEY` → deploy.

   Or with the CLI, which keeps the value out of both your shell history and any transcript.
   Install it once with `npm i -g @railway/cli`, then authenticate: `railway login` opens a
   browser, and `railway login --browserless` prints a pairing code when there is none. It needs
   to know where to write, so link the directory and check what it picked before setting anything:

   ```bash
   railway link --project <project-id> --environment <environment-id>
   railway status          # confirm project, environment and service before writing
   ```

   Then set the variable from the same `.env` the local stack reads, so the value is typed exactly
   once and never echoed:

   ```bash
   KEY=$(grep -E '^NEXUS_SUPABASE_SERVICE_ROLE_KEY=' .env | cut -d= -f2-)
   printf '%s' "$KEY" | railway variable set NEXUS_SUPABASE_SERVICE_ROLE_KEY --stdin \
     --service backend --environment production --skip-deploys
   ```

   `--skip-deploys` stages the change without a redeploy — trigger one when you are ready. The
   older `--set KEY=VALUE` form still works but puts the value in your shell history.
   Never paste `railway variable list`, or `… list --json` / `--kv`, anywhere: the CLI documents
   that those print **raw values**, so their output is the whole key ring, not a summary. `railway
   status` gives you the project, environment and service without any value at all.

3. **Verify the new key while the old one still works.** This is the step that catches a truncated
   paste before it can take provisioning down:

   ```bash
   REF=ktvjotwmpynbjgnqjitg
   curl -s -o /dev/null -w '%{http_code}\n' \
     "https://$REF.supabase.co/auth/v1/admin/users?page=1&per_page=1" \
     -H "apikey: $NEW_KEY"        # add -H "Authorization: Bearer $NEW_KEY" for a legacy JWT
   ```

   Expect 200. Then exercise the two product paths that use it: **Admin → Create an account**, and
   **Admin → Password requests → set a password**.

4. **Disable the legacy keys.** Supabase → **Project Settings → API Keys** → the legacy tab →
   disable; or:

   ```bash
   curl -fsS -X PUT "https://api.supabase.com/v1/projects/$SUPABASE_PROJECT_REF/api-keys/legacy?enabled=false" \
     -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN"
   ```

   Note what this does: the legacy **anon and `service_role` keys are disabled together** — the
   pair cannot be revoked individually, and neither the dashboard nor this endpoint offers a
   service-role-only revoke. The deployed frontend already authenticates with a `sb_publishable_`
   key, so it is unaffected; but check any other legacy consumer (a mobile build, a script, a
   third-party integration) first, because Supabase offers no per-key usage report to find them
   for you. Skip this step only if such a consumer still needs a legacy key — and then treat the
   project as still partially exposed.

5. **Prove the exposed key is dead.** Re-run the step 3 probe with the **old** key. It must answer
   **401**. A rotation that has not been verified this way is not a rotation — the credential in
   the old commit is still a working master key.

6. **Update local development.** Put the new value in a local `.env` beside `docker-compose.yml`
   (gitignored, loaded automatically). Never in the tracked compose file.

### Rotation neutralises history; deleting the line does not

Removing the value in a new commit leaves it in every earlier commit, and this repository is
public, so the edit alone changes nothing about who can use it. Step 4 — the revocation — is what
makes the key in the history stop working, and that is the goal. Scrubbing history
(`git filter-repo`, then a force-push) is optional extra hygiene for making the string disappear
from view: it rewrites every commit hash, so it needs a coordinated window and every clone must be
re-taken. Do it only if that visibility matters as much as usability.

### Rotation log

Record every rotation here. An undated key is a key nobody can age.

| Date | Credential | Reason | Old key verified dead (401) |
|------|------------|--------|-----------------------------|
| — | legacy `service_role` | committed in `docker-compose.yml`; present in public git history | ☐ |

### Rotate when

- A key appears in a commit, a log, a screenshot, a ticket or an error report.
- Someone with dashboard access leaves.
- A key has been in service for more than a year, or nobody can say where it is used.

---

## 13. Web Push notifications

Notifications have two delivery paths, and they cover different situations:

| Path | Reaches | How |
|------|---------|-----|
| Live socket | a tab that is open, anywhere | `/ws/chat` push frame → the app raises the popup and plays the sound |
| Web Push | a browser with the app closed, including a locked phone | VAPID-signed push → the service worker's `push` handler → a system notification |

Both are best-effort: the notification itself is always stored, so a message that fails to pop up
is still in the Inbox on the next load. A recipient with a live socket is deliberately **not**
pushed to, so an open laptop does not announce the same event twice.

### What is deployed where

The frontend ships three things Vercel serves as static files — `sw.js`, `manifest.webmanifest` and
the icons — and the backend owns the key pair and the subscriptions.

```
GET    /api/push/public-key      the VAPID public key browsers subscribe with ("" when off)
POST   /api/push/subscriptions   record this browser against the signed-in account
DELETE /api/push/subscriptions   silence this browser
POST   /api/push/test            push to the caller's own devices; returns how many were accepted
```

Subscriptions live in `push_subscriptions` (V17), one row per browser. An endpoint is addressed to
the browser that created it, so rows are never shared and never inspected by hand.

### Generating the key pair

One pair serves the whole deployment, split across two variables — there is no key file on the
server. Run the generator at the repository root:

```bash
node gen-vapid.cjs
```

It prints the public key (safe to share) and writes both values to `.env.push.local`, which is
gitignored — the private half must never appear in a commit, a transcript or a ticket. If the script
is ever lost, this is the whole of it:

```js
// gen-vapid.cjs — writes .env.push.local and prints only the public half
const { createHash, generateKeyPairSync } = require('node:crypto')
const fs = require('node:fs')
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const dec = (v) => Buffer.from(v.replace(/-/g, '+').replace(/_/g, '/'), 'base64')
const enc = (b) => b.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
const jwk = publicKey.export({ format: 'jwk' })
const pub = Buffer.concat([Buffer.from([0x04]), dec(jwk.x), dec(jwk.y)]) // 65 bytes
const priv = dec(privateKey.export({ format: 'jwk' }).d)                 // 32 bytes
fs.writeFileSync('.env.push.local', [
  `NEXUS_PUSH_VAPID_PUBLIC_KEY=${enc(pub)}`,
  `NEXUS_PUSH_VAPID_PRIVATE_KEY=${enc(priv)}`,
].join('\n'))
console.log('public:', enc(pub))
// The same fingerprint GET /api/push/self-check reports, so the deployed pair can be
// compared against the one on disk without the private half leaving this file.
console.log('fingerprint (sha256, first 12):', createHash('sha256').update(pub).digest('hex').slice(0, 12))
```

### Turning it on

1. Generate the pair (above).
2. Railway → the backend service → **Variables**: add `NEXUS_PUSH_VAPID_PUBLIC_KEY` and
   `NEXUS_PUSH_VAPID_PRIVATE_KEY` as **two separate variables**, then redeploy. Both are read at
   startup; setting one alone leaves push off, which is why the app logs
   `Web Push is off: no VAPID key pair configured` when it happens.
3. Open the deployed site and confirm the key is being served:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://nexus20-production.up.railway.app/api/push/public-key
   # 403 — this endpoint is behind authentication like the rest of /api. A 200 here
   # would mean the security chain stopped covering it.
   ```

   The empty-key case answers `{"publicKey":""}` to a signed-in caller, which the UI reads as
   "push is switched off" rather than as an error.
4. On each device: **Settings → Device Alerts → Enable notifications**. Permission prompts, the
   browser subscribes, and the row lands in `push_subscriptions`.
5. Press **Send a test notification**. It reports how many devices the push service accepted, and
   it re-files the subscription first, so it also works as a repair.

### Verifying it end to end

The only test that matters is a notification arriving while nothing is open.

```bash
# 1. One row per subscribed browser, and it belongs to the right account.
#    (Railway → Postgres → Data, or psql with $DATABASE_URL)
SELECT id, user_id, left(endpoint, 40) AS endpoint_prefix, created_at FROM push_subscriptions;

# 2. From a second account, assign a task to the first. No tab open on the target device.
# 3. The phone should buzz with a system notification, and tapping it opens /board?task=<id>.
```

On the desktop, the same thing is testable without a second account: press **Send a test
notification** and then lock the screen. A push that arrives with the app closed is the entire
feature; if it only works while a tab is open, the socket path is working and the push path is not.

To watch the sender rather than the receiver, the backend logs `Push service refused a notification
for subscription <id>: HTTP <status>` on a refusal and deletes the row on a 404/410.

### One call that answers most of it

`GET /api/push/self-check` reports what this deployment believes about push, for the account
asking, and it is the first thing to run when a phone does not buzz. It is behind authentication, so
start with a token:

```bash
API=https://nexus20-production.up.railway.app
TOKEN=$(curl -s -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"you@nexus.com","password":"<your password>"}' \
  | node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync(0,"utf8")).token)')

curl -s -H "Authorization: Bearer $TOKEN" "$API/api/push/self-check"
```

```json
{
  "pushEnabled": true,
  "publicKeyFingerprint": "0123456789ab",
  "publicKeyMatchesPrivateKey": true,
  "subject": "mailto:ops@nexus.app",
  "ttlHours": 24,
  "devices": 1,
  "lastAttempt": { "at": "2026-10-14T09:30:00Z", "attempted": 1, "accepted": 1, "outcome": "ACCEPTED", "status": 201 }
}
```

| Field | What it rules out |
|---|---|
| `pushEnabled` false, `publicKeyFingerprint` null | No key pair, or only one half of it set — step 2 of "Turning it on" |
| `publicKeyMatchesPrivateKey` **false** | The two halves come from different generations. Nothing else matters until this reads `true`: every push is signed with a key the browser did not subscribe with, and refused with 403 |
| `publicKeyMatchesPrivateKey` null | The check could not be made on this JVM, which is not the same as a mismatch. The startup log says why |
| `publicKeyFingerprint` | Compare with the pair on disk (below). Matching *and* a `true` above is what proves the deployed pair is the intended one |
| `devices` 0 | This account has no registered browser, so there is nowhere to send |
| `lastAttempt` null | Nothing has been sent to this account since the process started. Press **Send a test notification** and read it again |
| `lastAttempt.outcome` `ACCEPTED` | The push service took it. A silent phone after this is a device-side problem, not a server-side one |
| `REFUSED`, `status` 403 | The push service rejected the signature or the subject: a rotated pair, or a subject it will not accept |
| `GONE`, `status` 410 | That browser is gone and the row has been deleted — re-subscribe on the device |
| `UNREACHABLE`, `status` 0 | No answer at all: DNS, a refused connection, TLS, or the ten-second deadline. The application's network, not the phone |
| `NOT_SENT` / `UNSENDABLE` | The request or its payload could not be built. An application fault, not the push service's |

The fingerprint is the first twelve hex characters of the SHA-256 of the public key — the value
browsers subscribe with, and never the private half. Compute the same digest from the pair on disk
and the two must match character for character:

```bash
node -e '
const { createHash } = require("node:crypto")
const line = require("node:fs").readFileSync(".env.push.local", "utf8")
  .split("\n").find((l) => l.startsWith("NEXUS_PUSH_VAPID_PUBLIC_KEY="))
const pub = Buffer.from(line.split("=")[1].trim().replace(/-/g, "+").replace(/_/g, "/"), "base64")
console.log(createHash("sha256").update(pub).digest("hex").slice(0, 12))
'
```

The last attempt is held in memory for the most recent 512 accounts and is forgotten on restart: it
answers "did the thing I just did work?", not "what has ever happened".

### The phone test, in order

Work through this in sequence — each step rules out a whole class of cause, and stopping at the
first failure is much faster than guessing at the end.

**0. The deployment carries the worker.** A subscription needs `sw.js`, and the deploy has to be
newer than the change that added it:

```bash
WEB=https://nexus-2-0-omega.vercel.app
curl -s -o /dev/null -w 'sw.js: %{http_code} %{content_type}\n' "$WEB/sw.js"
curl -s -o /dev/null -w 'manifest: %{http_code}\n' "$WEB/manifest.webmanifest"
```

`sw.js` must be **200 with a JavaScript content type**. A 404, or `text/html`, means the running
frontend predates the worker and no device can subscribe from it — redeploy before going further.
"200 text/html" is the SPA fallback answering for a file that is not there, not a worker.

**1. The server is holding the key pair you think it is.** Read the self-check above: `pushEnabled`
must be true, `publicKeyMatchesPrivateKey` must be `true` rather than `false` or `null`, and
`publicKeyFingerprint` must equal the digest of the pair on disk. All three together are what proves
the deployed pair; any one of them alone does not.

Both endpoints answer **403 with an empty body** to a request without a token, which is the security
chain working and not a symptom.

**2. This device is registered.** Enable in Settings, then confirm the row:

```sql
SELECT id, user_id, left(endpoint, 40) AS endpoint_prefix, created_at
FROM push_subscriptions ORDER BY created_at DESC;
```

**3. The device is reachable at all.** Press **Send a test notification** and check the phone. This
is the only check that isolates keys + transport, and it is worth passing before touching anything
else.

**4. The notification path.** Assign a task from a second account, with the target account signed out
or closed everywhere — see the suppression rule below for why that matters.

### Two silent suppressions that look like a broken push

Both of these drop a push on purpose, and neither leaves an error:

| Suppression | Rule | How to test around it |
|-------------|------|-----------------------|
| Connected user | `NotificationService.notifyUsers` skips Web Push for any recipient with a **live socket anywhere** — a laptop tab left open is enough | Close every tab for the target account, or use a second account that has none |
| Watched window | `sw.js` refuses to draw the system notification when a window of this app is **visible and focused**, because the app has already alerted off the socket | Lock the screen or switch away — the phone must not be showing the app |

The first one also means the **test button is not a test of the notification path**: `/api/push/test`
posts straight to the devices and bypasses the socket check entirely. Passing it proves keys, worker
and transport are fine; it says nothing about whether a real assignment will buzz.

### Why nothing arrived

| What you see | What it means | Where to look |
|---|---|---|
| The panel says *"The server has no push key configured, so nothing can be sent yet."* | No key pair, or only one of the two variables set | Railway variables; startup log `Web Push is off` |
| Push off, and the log names a cause | `VAPID key pair could not be read, so Web Push stays off: <cause>` — the two halves are from different generations, or the value was truncated on paste | Regenerate as one pair |
| `/sw.js` 404 or `text/html` | The deployed frontend predates the worker | Redeploy the frontend (step 0) |
| *"This page is not served over HTTPS, so notifications cannot work."* | No secure context — an `http://` LAN address has no service worker at all | Use the https origin, or `localhost` |
| *"On iPhone and iPad, add Nexus to your Home Screen first…"* | iOS Safari in a tab: `PushManager` exists only in an installed app | Share → Add to Home Screen, then open from there |
| *"Not allowed yet. Press the button again and choose Allow."* | No gesture on the stack, or the prompt was dismissed | Press **Enable notifications** itself |
| *"Blocked. Allow notifications for this site in your browser, then reload."* | The origin's notifications are blocked in the browser's own settings | Site settings → Notifications → allow |
| *"This browser cannot show system notifications."* | No `Notification`/`PushManager`/service worker in this browser | Try a current Chrome, Edge, Firefox or Safari |
| *"No device is registered yet. Press \"Enable notifications\" first."* | Nothing subscribed for this account, so the send had nowhere to go | Step 2 above |
| *"Sent to 1 device. Lock your phone — it should still arrive."* and nothing does | The push was accepted; the delivery failure is at the device or the push service | The two rows below |
| Test reports `devices: 1` and `HTTP 403` in the log | The subscription was created under a **different VAPID key** (a rotation). A 403 is deliberately not pruned | Switch Device Alerts off and on for that device |
| Test works, real assignments never do, and the log says **nothing** | The connected-user suppression above | Close the target account's other sessions |
| `Dropped push subscription <id>`, row gone | HTTP 404/410: the browser is gone, or replaced its subscription | Re-subscribe on the device |
| `HTTP 413`, `429` or `5xx` | Not now, not never — the row is kept | Nothing; the next notification retries |

### Platform requirements

| Platform | Requirement |
|----------|-------------|
| Chrome / Edge / Firefox, desktop | Works signed in on an https origin. Nothing else needed |
| Android Chrome | Works in the browser. `new Notification(...)` does **not** — every alert goes through `ServiceWorkerRegistration.showNotification`, which is what the shipped `sw.js` exists for |
| iOS / iPadOS 16.4+ Safari | The app **must be added to the Home Screen** (Share → Add to Home Screen) and opened from there. A Safari tab cannot subscribe at all, which the Settings page says outright when it detects the situation |

Two consequences worth knowing before debugging: push needs a **secure origin** (`https://`, or
`localhost`), so a phone pointed at a plain-http LAN address has no service worker and cannot
subscribe; and the browser only re-files its subscription when the app is loaded signed in, so a
rotated endpoint is repaired by opening the app rather than by waiting.

### Rotating the VAPID pair

Rotating signs out nothing and loses nothing — every notification is stored regardless — but it does
invalidate every existing subscription, because the browser rejects a push it cannot verify.

1. Generate a new pair.
2. Update both Railway variables and redeploy.
3. Expect the first push after that to fail to arrive. Each device repairs itself the next time the
   app is opened signed in; to force it sooner, press **Send a test notification** on that device,
   which re-files the subscription before sending.
4. Optionally clear the stale rows — they are harmless, and the next registration for the same
   endpoint updates the row in place:

   ```sql
   DELETE FROM push_subscriptions;
   ```

### Rotate when

- The private key appears anywhere outside Railway and `.env.push.local`.
- A phone or laptop is lost or handed on, and the user cannot sign in to silence it (signing out
  does not unsubscribe — the row follows the *browser*).
- Nobody can account for the deployment that holds the key.
