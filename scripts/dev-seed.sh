#!/usr/bin/env bash
#
# Seed the dev stack's database the way the installers do (install/lib/db.sh), then set the admin password through
# the real first-login flow (the console's Set Password call). Re-runnable: on a seeded database it only re-applies
# the idempotent post-seed repairs.
#
# Usage: scripts/dev-seed.sh          (after: docker compose --env-file docker/dev.env up -d --build)
#   DEV_ADMIN_PASSWORD   admin password to set on a fresh database (default: admin, scripts/agent-v1-e2e.sh's default)
set -euo pipefail
cd "$(CDPATH='' cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=install/lib/db.sh
. install/lib/db.sh
DC=(docker compose --env-file docker/dev.env)
# shellcheck disable=SC2034  # PSQL is used by install/lib/db.sh
PSQL=("${DC[@]}" exec -T postgres psql -U mdmesh -d mdmesh)
PW="${DEV_ADMIN_PASSWORD:-admin}"
md5u() { printf '%s' "$1" | md5sum | awk '{print toupper($1)}'; }

# Act only on a dev stack. docker/dev.env names the project mdmesh-dev, but an exported COMPOSE_PROJECT_NAME (how a
# second, isolated dev stack runs) or an edited dev.env can point these commands at another project, such as a
# production install's (setup.sh names it mdmesh). Compose labels each container with the files it was created from,
# so require the dev overlay on every container this script uses; a production stack's never carry it.
[ -f docker/dev.env ] || { echo "docker/dev.env is missing; this script only seeds the dev stack" >&2; exit 1; }
L=com.docker.compose.project
for svc in postgres server; do
  ids=$("${DC[@]}" ps -q --status running "$svc")
  [ -n "$ids" ] || { echo "the dev stack's $svc is not running; start it:" \
                         "docker compose --env-file docker/dev.env up -d --build" >&2; exit 1; }
  for id in $ids; do
    meta=$(docker inspect --format "{{index .Config.Labels \"$L\"}}|{{index .Config.Labels \"$L.config_files\"}}" "$id")
    case ",${meta#*|}," in
      *[/,]docker-compose.dev.yml,*) ;;
      *) echo "refusing: project '${meta%%|*}' was not started with docker-compose.dev.yml, so it is not a dev stack" \
              "(its $svc was created from: ${meta#*|})" >&2; exit 1 ;;
    esac
  done
done
project=${meta%%|*}
API="http://$("${DC[@]}" port server 8080)"   # the published host port, wherever DEV_API_PORT put it

# initialized.txt lives on the server's data volume and survives restarts, so also wait for the API to answer.
ready() { "${DC[@]}" exec -T server test -f /opt/mdmesh/initialized.txt 2>/dev/null \
          && curl -fsS -o /dev/null -m 5 "$API/rest/public/auth/options"; }
echo "dev stack $project: waiting for the server (the first boot runs Liquibase)..."
for _ in $(seq 1 60); do ready && break; sleep 5; done
ready || { echo "server not ready after 5 min; see: docker compose --env-file docker/dev.env logs server" >&2; exit 1; }

state=$(mdm_db_state)
case "$state" in
  fresh)
    tmp=$(mdm_rand)
    mdm_seed admin@localhost install/sql/hmdm_init.en.sql "$tmp" "$(openssl rand -hex 16)"
    mdm_post_seed install/sql/post_seed.sql
    # First login with the temporary password returns a reset token; setting the password with it is what the
    # console's Set Password page does.
    token=$(curl -fsS -H 'Content-Type: application/json' \
        -d "{\"login\":\"admin\",\"password\":\"$(md5u "$tmp")\"}" "$API/rest/public/auth/login" \
      | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; assert d["passwordReset"]; print(d["passwordResetToken"])')
    curl -fsS -H 'Content-Type: application/json' \
        -d "{\"passwordResetToken\":\"$token\",\"newPassword\":\"$(md5u "$PW")\"}" "$API/rest/public/passwordReset/reset" \
      | python3 -c 'import sys,json; assert json.load(sys.stdin)["status"] == "OK"'
    echo "seeded; console login: admin / $PW" ;;
  seeded)
    mdm_post_seed install/sql/post_seed.sql
    echo "already seeded; post-seed repairs applied (admin password unchanged)" ;;
  *) echo "database state is '$state'; refusing to seed (docker compose --env-file docker/dev.env down -v resets it)" >&2
     exit 1 ;;
esac
