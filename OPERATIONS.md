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
| `NEXUS_SUPABASE_SERVICE_ROLE_KEY` | Railway | **yes** | Full-access admin credential; accepts a legacy `service_role` JWT or an `sb_secret_…` key. Server-only; never in a `VITE_` variable, never committed. Rotation: §12 |
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
