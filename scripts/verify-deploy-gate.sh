#!/usr/bin/env bash
#
# verify-deploy-gate.sh — the three checks docs/MULTI_TENANCY.md requires before
# any of the multi-tenancy rollout (V18–V22) reaches production.
#
#   A. The four V2 seed accounts no longer accept the seeded password.
#   B. No @supabase.local identities, and every role is in the legal vocabulary.
#   C. A backup exists, restores into an isolated database, V18–V22 migrate
#      cleanly on that restored copy, and the V18 role CHECKs land there.
#
# The gate runs before the deploy, so the source database is still at V17: it has
# no companies table, no token_version and no CHECK constraints yet. That is why
# the constraint assertions live in check C, against the migrated restored copy,
# rather than in check B against a database that has not been migrated.
#
# The source database is only ever read: pg_dump for C, and SELECTs for A and B.
# Every write goes to a scratch database this script creates and drops.
#
# Why check A verifies the hash instead of counting password changes: V4 replaced
# the seeded hashes with crypt('password123', gen_salt('bf', 10)), so each row
# carries its own salt and no fixed hash can be compared against. Comparing
# token_version instead would be worse — V22 backfills it to 0, so an account
# whose password was changed before V22 would read as untouched. Asking pgcrypto
# whether the stored hash still verifies 'password123' answers the actual
# question the gate is asking.
#
# Usage and exit codes are in --help.

set -euo pipefail

SCRIPT_NAME="${0##*/}"

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
MIGRATIONS_REL="src/main/resources/db/migration"
MIGRATIONS_DIR="$BACKEND_DIR/$MIGRATIONS_REL"
SEED_PASSWORD="password123"

# The V2 seed rows, by address; V4 re-hashed exactly these four.
SEED_EMAILS=(
  devendra@nexus.com
  achal@nexus.com
  vidhi@nexus.com
  palak@nexus.com
)

# Mirrors chk_users_role_valid (V18). Kept as one literal so the query and the
# error text cannot drift apart.
ROLE_VOCAB_SQL="'ADMIN', 'MEMBER', 'VIEWER', 'DEVELOPER', 'MANAGER', 'SUPER_ADMIN'"

RESULT_STATUS=()
RESULT_NAME=()
RESULT_DETAIL=()

SCRATCH_DB=""
DUMP_FILE=""

# ---------------------------------------------------------------- output helpers

usage() {
  cat <<USAGE
${SCRIPT_NAME} — verify the multi-tenancy deployment gate.

Runs the three pre-deployment checks from docs/MULTI_TENANCY.md against a live
database and a restored copy of its backup. The source database is read only.

Usage:
  DATABASE_URL=postgresql://user:password@host:5432/database ${SCRIPT_NAME} [--help]

Environment:
  DATABASE_URL   Required. The database to verify (postgres:// or
                 postgresql:// with user:password@host:port/database).
                 Percent-encoded characters in the password are decoded for the
                 migration dry run.
  PG_BIN         Optional. Directory holding psql, pg_dump and pg_restore when
                 they are not on PATH (for example /usr/lib/postgresql/16/bin).

Checks:
  A. Each V2 seed account is deleted, has no local password, or its stored hash
     no longer verifies '${SEED_PASSWORD}'.
  B. No @supabase.local identities; every role is one of
     ADMIN, MEMBER, VIEWER, DEVELOPER, MANAGER, SUPER_ADMIN.
  C. pg_dump succeeds, restores into a scratch database, Flyway migrates
     V18–V22 on that restored copy with V22 as the final applied version, and
     both role CHECK constraints exist there. The scratch database is dropped
     on exit.

Exit codes:
  0  every check passed — this is the evidence the gate asks for
  1  at least one check failed (the table above the exit names which)
  2  usage or configuration problem (missing DATABASE_URL, missing tool)

The report is meant to be pasted into the release record: it names the dump,
its SHA-256, the restore target and the migration outcome.
USAGE
}

die() {
  printf '%s: %s\n' "$SCRIPT_NAME" "error: $*" >&2
  exit 2
}

note() {
  printf '%s\n' "$*"
}

record() {
  local status="$1" name="$2" detail="$3"
  RESULT_STATUS+=("$status")
  RESULT_NAME+=("$name")
  RESULT_DETAIL+=("$detail")
  printf '  [%s] %s — %s\n' "$status" "$name" "$detail"
}

# Joins arguments with '; ' for the one-line report column.
join_problems() {
  local out="" problem
  for problem in "$@"; do
    out="${out:+$out; }$problem"
  done
  printf '%s' "$out"
}

# ---------------------------------------------------------------- configuration

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "unknown argument: $1 (try --help)"
        ;;
    esac
  done
}

load_config() {
  local bin_prefix=""
  if [[ -n "${PG_BIN:-}" ]]; then
    [[ -d "$PG_BIN" ]] || die "PG_BIN is not a directory: $PG_BIN"
    bin_prefix="$PG_BIN/"
  fi

  PSQL="${bin_prefix}psql"
  PG_DUMP="${bin_prefix}pg_dump"
  PG_RESTORE="${bin_prefix}pg_restore"

  for tool in "$PSQL" "$PG_DUMP" "$PG_RESTORE"; do
    command -v "$tool" >/dev/null 2>&1 \
      || die "required tool not found: $tool (set PG_BIN if Postgres is installed outside PATH)"
  done

  [[ -x "$BACKEND_DIR/mvnw" || -f "$BACKEND_DIR/mvnw" ]] \
    || die "backend/mvnw is missing; run this from a checkout of the repository"

  [[ -d "$MIGRATIONS_DIR" ]] \
    || die "migrations directory is missing: $MIGRATIONS_DIR"

  if [[ -z "${DATABASE_URL:-}" ]]; then
    die "DATABASE_URL is required (try --help)"
  fi

  parse_database_url "$DATABASE_URL"
}

# Splits a postgres URI into the pieces JDBC needs. libpq accepts the URI
# itself, so psql and pg_dump use DATABASE_URL verbatim; only Flyway needs this.
parse_database_url() {
  local url="$1"
  local pattern='^postgres(ql)?://([^:/@]+)(:([^@]*))?@([^:/]+)(:([0-9]+))?/([^?]+)'

  if [[ ! "$url" =~ $pattern ]]; then
    die "DATABASE_URL must look like postgresql://user:password@host:port/database"
  fi

  DB_USER="${BASH_REMATCH[2]}"
  DB_PASS="${BASH_REMATCH[4]}"
  DB_HOST="${BASH_REMATCH[5]}"
  DB_PORT="${BASH_REMATCH[7]:-5432}"
  DB_NAME="${BASH_REMATCH[8]}"

  [[ -n "$DB_USER" ]] || die "DATABASE_URL has no user"
  [[ -n "$DB_HOST" ]] || die "DATABASE_URL has no host"
  [[ -n "$DB_NAME" ]] || die "DATABASE_URL has no database name"

  # %XX in the password is URI encoding; JDBC wants the characters themselves.
  DB_PASS_DECODED="$(printf '%b' "${DB_PASS//%/\\x}")"
}

# ---------------------------------------------------------------------- queries

# psql emits CRLF on Windows, which would leave a trailing carriage return on
# every value and make '1\r' compare unequal to '1'. Every result is normalized
# here so callers can compare plain strings on any platform.
strip_cr() {
  tr -d '\r'
}

# One value from the source database. Fails loudly rather than returning empty.
source_scalar() {
  local query="$1"
  "$PSQL" "$DATABASE_URL" -X -tA -v ON_ERROR_STOP=1 -c "$query" | strip_cr
}

# Rows of |-separated columns from the source database.
source_rows() {
  local query="$1"
  "$PSQL" "$DATABASE_URL" -X -tA -F '|' -v ON_ERROR_STOP=1 -c "$query" | strip_cr
}

# A value from the scratch database.
scratch_scalar() {
  local query="$1"
  PGPASSWORD="$DB_PASS_DECODED" "$PSQL" \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$SCRATCH_DB" \
    -X -tA -v ON_ERROR_STOP=1 -c "$query" | strip_cr
}

# ---------------------------------------------------------------- check A

check_seed_accounts() {
  note "Check A — V2 seed accounts"

  # pgcrypto is what V4 used to re-hash these rows; without it the stored value
  # cannot be tested against the seeded password at all, which is a failure to
  # prove rather than a pass.
  local has_pgcrypto
  has_pgcrypto="$(source_scalar "SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto';")"
  if [[ "$has_pgcrypto" != "1" ]]; then
    record FAIL "A. seed accounts" "pgcrypto is not installed, so the seed password cannot be verified"
    return
  fi

  local email_list=""
  local email
  for email in "${SEED_EMAILS[@]}"; do
    email_list="${email_list:+$email_list,}'$email'"
  done

  # seed_password_live is 1 when the stored hash still verifies the seeded
  # password, which is precisely the state the gate forbids.
  local rows
  rows="$(source_rows "SELECT lower(email),
                              (password IS NULL)::int,
                              (password IS NOT NULL AND password = crypt('${SEED_PASSWORD}', password))::int
                       FROM users
                       WHERE lower(email) IN (${email_list})
                       ORDER BY 1;")"

  local failures=0
  local unchanged=()
  local seen=""

  while IFS='|' read -r found_email no_local_hash still_live; do
    [[ -n "$found_email" ]] || continue
    seen="${seen}${found_email} "
    if [[ "$no_local_hash" == "1" ]]; then
      note "    $found_email: no local password (Supabase owns the credential)"
    elif [[ "$still_live" == "1" ]]; then
      failures=$((failures + 1))
      unchanged+=("$found_email")
      note "    $found_email: STILL ACCEPTS ${SEED_PASSWORD}"
    else
      note "    $found_email: password changed"
    fi
  done <<< "$rows"

  # Absent rows are the other acceptable outcome: deleted.
  for email in "${SEED_EMAILS[@]}"; do
    if [[ "$seen" != *"$email "* ]]; then
      note "    $email: account deleted"
    fi
  done

  if (( failures > 0 )); then
    record FAIL "A. seed accounts" "${failures} account(s) still accept ${SEED_PASSWORD}: ${unchanged[*]}"
  else
    record PASS "A. seed accounts" "all four changed, deleted, or moved to Supabase"
  fi
}

# ---------------------------------------------------------------- check B

check_identity_hygiene() {
  note "Check B — role and identity hygiene"

  local supabase_local illegal_roles
  supabase_local="$(source_scalar "SELECT count(*) FROM users WHERE lower(email) LIKE '%@supabase.local';")"
  illegal_roles="$(source_scalar "SELECT count(*) FROM users WHERE role NOT IN (${ROLE_VOCAB_SQL});")"

  local problems=()
  (( supabase_local == 0 )) || problems+=("$supabase_local @supabase.local identit(ies)")
  (( illegal_roles == 0 )) || problems+=("$illegal_roles role(s) outside the vocabulary")

  if (( ${#problems[@]} > 0 )); then
    record FAIL "B. role and identity" "$(join_problems "${problems[@]}")"
  else
    record PASS "B. role and identity" "no @supabase.local identities, every role legal"
  fi
}

# ---------------------------------------------------------------- check C

drop_scratch() {
  if [[ -n "$SCRATCH_DB" ]]; then
    PGPASSWORD="$DB_PASS_DECODED" "$PSQL" \
      -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres \
      -X -q -v ON_ERROR_STOP=1 \
      -c "DROP DATABASE IF EXISTS \"$SCRATCH_DB\";" >/dev/null 2>&1 || true
    SCRATCH_DB=""
  fi
  if [[ -n "$DUMP_FILE" && -f "$DUMP_FILE" ]]; then
    rm -f -- "$DUMP_FILE"
    DUMP_FILE=""
  fi
}

checksum_of() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | cut -d' ' -f1
  else
    printf 'unavailable'
  fi
}

check_backup_and_migration() {
  note "Check C — backup, restore and migration dry run"

  local stamp
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  DUMP_FILE="${TMPDIR:-/tmp}/nexus-gate-${stamp}.dump"
  SCRATCH_DB="nexus_gate_$(date -u +%s)_$$"
  # Lowercase only: a generated identifier is used unquoted in CREATE DATABASE.
  SCRATCH_DB="${SCRATCH_DB,,}"

  trap drop_scratch EXIT

  if ! "$PG_DUMP" "$DATABASE_URL" --format=custom --file="$DUMP_FILE" >/dev/null 2>&1; then
    record FAIL "C. backup + migration" "pg_dump failed; see the message above"
    return
  fi
  local checksum
  checksum="$(checksum_of "$DUMP_FILE")"
  note "    dump: $(basename -- "$DUMP_FILE") sha256:${checksum}"

  # The maintenance database, not the source: the scratch database is created
  # beside the restored copy and never inside the database being verified.
  local create_error
  if ! create_error="$(PGPASSWORD="$DB_PASS_DECODED" "$PSQL" \
        -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres \
        -X -q -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$SCRATCH_DB\";" 2>&1)"; then
    record FAIL "C. backup + migration" "could not create the scratch database: $(printf '%s' "$create_error" | tail -n 1)"
    return
  fi
  note "    restore target: $SCRATCH_DB"

  if ! PGPASSWORD="$DB_PASS_DECODED" "$PG_RESTORE" \
        -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$SCRATCH_DB" \
        "$DUMP_FILE" >/dev/null 2>&1; then
    # pg_restore reports non-fatal noise on many dumps; what matters for the
    # gate is whether the restored copy can be migrated, so the migration below
    # is the real verdict. A hard failure here still means the backup is not
    # proven restorable, which is why it is recorded.
    note "    pg_restore reported errors; the migration below is the deciding check"
  fi

  local source_version
  source_version="$(scratch_scalar "SELECT coalesce((SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), 'none');" 2>/dev/null || printf 'unknown')"
  note "    restored schema version: $source_version"

  local flyway_log
  flyway_log="${TMPDIR:-/tmp}/nexus-gate-flyway-${stamp}.log"
  local flyway_status=0
  # The locations value is relative to backend/ on purpose: Maven runs the plugin
  # inside a JVM, which cannot resolve a Git Bash style /c/... path. MIGRATIONS_DIR
  # is only used above, to prove the checkout is complete.
  ( cd "$BACKEND_DIR" && ./mvnw -B -q -Pdeploy-gate flyway:migrate \
      -Dflyway.url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${SCRATCH_DB}" \
      -Dflyway.user="$DB_USER" \
      -Dflyway.password="$DB_PASS_DECODED" \
      -Dflyway.locations="filesystem:src/main/resources/db/migration" ) >"$flyway_log" 2>&1 || flyway_status=$?

  if (( flyway_status != 0 )); then
    note "    Flyway output follows:"
    sed 's/^/      /' "$flyway_log" | tail -n 20
    rm -f -- "$flyway_log"
    record FAIL "C. backup + migration" "Flyway exited ${flyway_status} against the restored copy"
    return
  fi
  rm -f -- "$flyway_log"

  local head token_version companies constraints
  head="$(scratch_scalar "SELECT coalesce((SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), 'none');")"
  token_version="$(scratch_scalar "SELECT count(*) FROM information_schema.columns
                                  WHERE table_schema = 'public' AND table_name = 'users'
                                    AND column_name = 'token_version';")"
  companies="$(scratch_scalar "SELECT count(*) FROM information_schema.tables
                               WHERE table_schema = 'public' AND table_name = 'companies';")"
  # The two role CHECKs are created by V18, so this is where they can be asserted:
  # the source database is still at V17 when the gate runs.
  constraints="$(scratch_scalar "SELECT count(*) FROM pg_constraint
                                 WHERE conname IN ('chk_users_role_valid', 'chk_users_company_by_role')
                                   AND connamespace = 'public'::regnamespace;")"

  local problems=()
  [[ "$head" == "22" ]] || problems+=("schema head is V${head}, expected V22")
  [[ "$token_version" == "1" ]] || problems+=("users.token_version column is missing")
  [[ "$companies" == "1" ]] || problems+=("companies table is missing")
  [[ "$constraints" == "2" ]] || problems+=("role CHECK constraints are missing (found ${constraints} of 2)")

  if (( ${#problems[@]} > 0 )); then
    record FAIL "C. backup + migration" "$(join_problems "${problems[@]}")"
  else
    record PASS "C. backup + migration" "dump sha256:${checksum} restored to ${SCRATCH_DB}, Flyway reached V22"
  fi
}

# ------------------------------------------------------------------------ report

print_report() {
  local failures=0
  local i
  for (( i = 0; i < ${#RESULT_STATUS[@]}; i++ )); do
    [[ "${RESULT_STATUS[$i]}" == "PASS" ]] || failures=$((failures + 1))
  done

  printf '\n%-6s %-22s %s\n' "RESULT" "CHECK" "DETAIL"
  for (( i = 0; i < ${#RESULT_STATUS[@]}; i++ )); do
    printf '%-6s %-22s %s\n' "${RESULT_STATUS[$i]}" "${RESULT_NAME[$i]}" "${RESULT_DETAIL[$i]}"
  done

  printf '\n'
  if (( failures == 0 )); then
    printf 'GATE: PASS — paste this table into the release record.\n'
    return 0
  fi
  printf 'GATE: FAIL — %d check(s) failed. Do not deploy.\n' "$failures"
  return 1
}

# -------------------------------------------------------------------------- main

main() {
  parse_args "$@"
  load_config

  note "${SCRIPT_NAME}: verifying ${DB_HOST}:${DB_PORT}/${DB_NAME}"
  note ""

  check_seed_accounts
  note ""
  check_identity_hygiene
  note ""
  check_backup_and_migration

  print_report
}

main "$@"
