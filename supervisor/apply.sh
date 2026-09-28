#!/usr/bin/env bash
# apply.sh <target-version>
#
# Runs inside the supervisor container (project mounted at /project, docker.sock mounted). Applies a VERIFIED
# update to the `server` + `caddy` services with a pre-update DB backup and AUTOMATIC ROLLBACK on any
# failure. Emits `PHASE <name>` / `ERR <msg>` / `OK <version>` lines on stdout — server.js parses these
# to drive /update/status. It NEVER touches `supervisor` or `postgres` (no self-destruct, no data loss).
#
# Sequence: backup → pull → recreate → healthcheck → done. A failed pull → rollback of .env only (no container
# changed yet). A later failure → rollback (restore versions, stop the server, restore the DB, recreate the old
# images, re-health-check) → rolled_back, or failed (non-zero, with ERR lines saying what state it is in) if the
# rollback itself can't recover.
#
# supervisor/test.js drives it with a stub docker (order, exit codes, messages). The restore was checked by hand
# against postgres:14 with a good and a deliberately broken dump; validate the full path on a staging deploy.
set -uo pipefail   # deliberately NOT -e: failures are handled explicitly so we can roll back.

VERSION="${1:?usage: apply.sh <version>}"
PROJECT_DIR="${COMPOSE_PROJECT_DIR:-/project}"
ENV_FILE="$PROJECT_DIR/.env"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
DB_USER="${DB_USER:-mdmesh}"
DB_NAME="${DB_NAME:-mdmesh}"
HEALTH_URL="${HEALTH_URL:-http://server:8080/rest/public/name}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

phase() { echo "PHASE $1"; }
errln() { echo "ERR $1" >&2; }
dc()    { docker compose "$@"; }

cd "$PROJECT_DIR" || { errln "cannot cd $PROJECT_DIR"; phase failed; exit 1; }

# --- .env helpers (the file is the bind-mounted host .env) ---
get_env() { grep -E "^$1=" "$ENV_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }
set_env() {
  local k="$1" v="$2"
  if grep -qE "^$k=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$ENV_FILE"
  else
    echo "${k}=${v}" >> "$ENV_FILE"
  fi
}

# --- health: poll the server's unauthenticated /public/name until 200 or timeout ---
healthy() {
  local deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS -m 5 "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    sleep 5
  done
  return 1
}

# --- restore_db <dump> <log>: put the database back to the pre-update dump. Returns non-zero after ERR lines. ---
# The dump is plain SQL from `pg_dump --clean --if-exists`: it drops and recreates every object. So:
#  1. the server is STOPPED first: it holds pooled connections whose locks would block (or race) the drops, and
#     whatever runs next must not see a half-restored schema. caddy keeps running, so /recovery stays up;
#  2. psql runs with ON_ERROR_STOP=1 in ONE transaction, so any error aborts it with a non-zero exit and the database
#     is left exactly as it was (without these, psql exits 0 on SQL errors and a failed restore went unreported).
# On failure the server is left stopped on purpose: the old version must not run on a database it was not restored
# for. The operator retries Roll back (same backup) once the cause is fixed; DEPLOY.md has the by-hand restore.
restore_db() {
  local sql="$1" log="$2"
  if ! dc stop server; then
    errln "could not stop the server, so the database was NOT restored and nothing else was changed; .env already names the old versions. Fix the cause (see the log above), then Roll back again from /recovery."
    return 1
  fi
  if ! dc exec -T postgres psql -X -q -v ON_ERROR_STOP=1 --single-transaction -U "$DB_USER" -d "$DB_NAME" < "$sql" > "$log" 2>&1; then
    errln "database restore failed: $(grep -m1 -E 'ERROR|FATAL' "$log" || tail -n1 "$log")"
    errln "the server is stopped (on purpose: the old version must not run on a database it was not restored for); the database is unchanged (the restore runs in one transaction); caddy and /recovery are up; .env names the old versions. Fix the cause (full psql output: docker compose exec supervisor cat $log), then Roll back again from /recovery; to restore by hand see DEPLOY.md (Recovery)."
    return 1
  fi
}

OLD_SERVER="$(get_env SERVER_VERSION)"
OLD_WEB="$(get_env WEB_VERSION)"
OLD_CURRENT="$(get_env CURRENT_VERSION)"
[ -n "$OLD_SERVER" ]  || OLD_SERVER="${CURRENT_VERSION:-latest}"
[ -n "$OLD_WEB" ]     || OLD_WEB="$OLD_SERVER"
# CURRENT_VERSION is the release the supervisor reports; it can differ from the image tag (a :latest quick start
# runs SERVER_VERSION=latest with CURRENT_VERSION=0.0.0), so it is snapshotted and restored on its own.
[ -n "$OLD_CURRENT" ] || OLD_CURRENT="${CURRENT_VERSION:-$OLD_SERVER}"

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_SQL="$BACKUP_DIR/$STAMP.sql"
BACKUP_ENV="$BACKUP_DIR/$STAMP.env"

# --- rollback [env-only]: restore previous versions; unless env-only, also stop the server, restore the DB, start
# the old server and re-health-check. env-only is for a failed pull: no container changed yet, so there is nothing
# to undo but .env (no downtime, and no writes since the backup are discarded). ---
rollback() {
  phase rollback
  set_env SERVER_VERSION "$OLD_SERVER"
  set_env WEB_VERSION "$OLD_WEB"
  set_env CURRENT_VERSION "$OLD_CURRENT"
  if [ "${1:-}" = env-only ]; then phase rolled_back; return; fi
  if [ -s "$BACKUP_SQL" ]; then
    restore_db "$BACKUP_SQL" "$BACKUP_DIR/$STAMP.restore.log" || { phase failed; return; }
  fi
  if ! dc up -d --no-deps server caddy; then
    errln "could not start the old server (see the log above); .env names the old versions and the database is restored: run 'docker compose up -d server caddy' in the install directory, or Roll back again from /recovery."
    phase failed
    return
  fi
  if healthy; then phase rolled_back; else errln "still unhealthy after rollback (${HEALTH_TIMEOUT}s) — manual intervention needed"; phase failed; fi
}

# ---------------- backup ----------------
phase backup
mkdir -p "$BACKUP_DIR"
{ echo "SERVER_VERSION=$OLD_SERVER"; echo "WEB_VERSION=$OLD_WEB"; echo "CURRENT_VERSION=$OLD_CURRENT"; } > "$BACKUP_ENV"
if ! dc exec -T postgres pg_dump --clean --if-exists -U "$DB_USER" "$DB_NAME" > "$BACKUP_SQL"; then
  errln "pg_dump failed — aborting before any change"
  rm -f "$BACKUP_SQL"
  phase failed
  exit 1
fi
echo "$STAMP" > "$BACKUP_DIR/latest"   # pointer the recovery page / rollback read

# ---------------- pull ----------------
phase pull
set_env SERVER_VERSION "$VERSION"
set_env WEB_VERSION "$VERSION"
set_env CURRENT_VERSION "$VERSION"
if ! dc pull server caddy; then
  errln "image pull failed"
  rollback env-only
  exit 1
fi

# ---------------- recreate ----------------
phase recreate
if ! dc up -d --no-deps server caddy; then
  errln "recreate failed"
  rollback
  exit 1
fi

# ---------------- healthcheck ----------------
phase healthcheck
if healthy; then
  phase "done"   # quoted: shellcheck SC1010 reads a bare done as the keyword
  echo "OK $VERSION"
  exit 0
fi
errln "health check failed after ${HEALTH_TIMEOUT}s"
rollback
exit 1
