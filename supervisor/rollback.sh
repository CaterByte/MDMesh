#!/usr/bin/env bash
# rollback.sh — restore the most recent pre-update backup (the .env version snapshot + DB dump that
# apply.sh wrote): stop `server`, restore the DB, recreate `server` + `caddy` on the old versions, health-check.
# The break-glass recovery action. Emits PHASE/ERR/OK lines for server.js, mirroring apply.sh, and exits non-zero on
# any failure. Never touches `supervisor` or `postgres` containers. Data written since the update is discarded.
set -uo pipefail

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

set_env() {
  local k="$1" v="$2"
  if grep -qE "^$k=" "$ENV_FILE" 2>/dev/null; then
    sed -i "s|^${k}=.*|${k}=${v}|" "$ENV_FILE"
  else
    echo "${k}=${v}" >> "$ENV_FILE"
  fi
}

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

phase rollback
STAMP="$(cat "$BACKUP_DIR/latest" 2>/dev/null)"
if [ -z "$STAMP" ]; then errln "no backup recorded to roll back to"; phase failed; exit 1; fi

ENV_SNAP="$BACKUP_DIR/$STAMP.env"
SQL_SNAP="$BACKUP_DIR/$STAMP.sql"

# Restore the previous image versions.
if [ -f "$ENV_SNAP" ]; then
  OLD_SERVER="$(grep -E '^SERVER_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  OLD_WEB="$(grep -E '^WEB_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  # Snapshots from before CURRENT_VERSION was recorded fall back to the server tag, as they always did.
  OLD_CURRENT="$(grep -E '^CURRENT_VERSION=' "$ENV_SNAP" | head -1 | cut -d= -f2-)"
  [ -n "$OLD_CURRENT" ] || OLD_CURRENT="$OLD_SERVER"
  [ -n "$OLD_SERVER" ] && set_env SERVER_VERSION "$OLD_SERVER"
  [ -n "$OLD_CURRENT" ] && set_env CURRENT_VERSION "$OLD_CURRENT"
  [ -n "$OLD_WEB" ] && set_env WEB_VERSION "$OLD_WEB"
else
  errln "version snapshot $ENV_SNAP missing — recreating current images"
fi

# Restore the database dump with the server stopped, then start the old server on it.
if [ -s "$SQL_SNAP" ]; then
  restore_db "$SQL_SNAP" "$BACKUP_DIR/$STAMP.restore.log" || { phase failed; exit 1; }
else
  errln "db dump $SQL_SNAP missing/empty — restored versions only"
fi

if ! dc up -d --no-deps server caddy; then
  errln "could not start the old server (see the log above); .env names the old versions and the database is restored: run 'docker compose up -d server caddy' in the install directory, or Roll back again."
  phase failed
  exit 1
fi

if healthy; then phase rolled_back; echo "OK ${OLD_CURRENT:-}"; exit 0; fi
errln "still unhealthy after rollback (${HEALTH_TIMEOUT}s) — manual intervention needed"
phase failed
exit 1
