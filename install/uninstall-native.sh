#!/usr/bin/env bash
# Remove a native (non-Docker) MDMesh install made by install/install-native.sh.
#
#   sudo ./install/uninstall-native.sh              # interactive: shows what goes, asks you to type UNINSTALL
#   sudo ./install/uninstall-native.sh --keep-data  # remove code/services but keep the database + uploaded files
#   sudo ./install/uninstall-native.sh -y           # unattended (still takes a final pg_dump unless --no-backup)
#
# Removes: Tomcat (/opt/mdmesh-tc), the app dir (/opt/mdmesh), the mdmesh-supervisor systemd unit and its settings
# (/etc/mdmesh), the install log, and — unless --keep-data — the PostgreSQL database + role "mdmesh". A final dump is
# written first.
# Leaves alone: apt packages (postgresql, maven, node, …), your reverse proxy/TLS, and the git checkout.
set -euo pipefail
umask 077
export PATH="/usr/sbin:/sbin:$PATH"   # useradd/userdel/pg tools live here; not every root shell has it
[ "$(id -u)" = "0" ] || { echo "Run as root (sudo)."; exit 1; }

BASE_DIR=/opt/mdmesh
CATALINA=/opt/mdmesh-tc
UNIT=/etc/systemd/system/mdmesh-supervisor.service
SUP_ENV_DIR=/etc/mdmesh   # the supervisor's settings (install-native.sh)
SERVER_UNIT=/etc/systemd/system/mdmesh-server.service
SVC_USER=mdmesh
INSTALL_LOG=/var/log/mdmesh-install.log
KEEP_DATA=0; YES=0; BACKUP=1
for a in "$@"; do
  case "$a" in
    --keep-data) KEEP_DATA=1 ;;
    -y|--yes)    YES=1 ;;
    --no-backup) BACKUP=0 ;;
    -h|--help)   sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "Unknown flag: $a (see --help)"; exit 1 ;;
  esac
done

# svc_user_pids: the pids of $SVC_USER's processes, one per line, leaving out container processes that merely run as the
# same numeric uid (another PID namespace, but the host's user namespace: only a privileged container runtime sets that
# up). Same function as in install-native.sh; see the reasoning there.
svc_user_pids() {
  local host_pid host_user p
  host_pid=$(readlink /proc/1/ns/pid) host_user=$(readlink /proc/1/ns/user)
  for p in $(pgrep -u "$SVC_USER" || true); do
    [ "$(readlink "/proc/$p/ns/pid")" != "$host_pid" ] && [ "$(readlink "/proc/$p/ns/user")" = "$host_user" ] && continue
    echo "$p"
  done
}
db_exists() { su -s /bin/sh postgres -c "psql -tAc \"SELECT 1 FROM pg_database WHERE datname='mdmesh'\"" 2>/dev/null | grep -q 1; }
counts=""
if db_exists; then
  counts=$(su -s /bin/sh postgres -c "psql -d mdmesh -tAc \"SELECT (SELECT count(*) FROM devices)||' device(s), '||(SELECT count(*) FROM configurations)||' configuration(s), '||(SELECT count(*) FROM users)||' user(s)'\"" 2>/dev/null || echo "unreadable")
fi

echo
echo "  MDMesh native uninstall — this host will lose:"
[ -d "$CATALINA" ] && echo "    • Tomcat + deployed server:   $CATALINA"
[ -f "$SERVER_UNIT" ] && echo "    • server service:             mdmesh-server (systemd unit removed)"
id -u "$SVC_USER" >/dev/null 2>&1 && [ "$KEEP_DATA" != 1 ] && echo "    • service user:               $SVC_USER"
[ -f "$UNIT" ]     && echo "    • updater service:            mdmesh-supervisor (systemd unit removed)"
[ -d "$SUP_ENV_DIR" ] && echo "    • updater settings:           $SUP_ENV_DIR"
if [ "$KEEP_DATA" = 1 ]; then
  [ -d "$BASE_DIR" ] && echo "    • app dir (KEEPING files/ and backups/): $BASE_DIR"
  [ -n "$counts" ]   && echo "    • database:                   KEPT ($counts)"
else
  [ -d "$BASE_DIR" ] && echo "    • app dir, uploads, backups:  $BASE_DIR"
  [ -n "$counts" ]   && echo "    • database + role 'mdmesh':   DROPPED — $counts"
fi
[ -f "$INSTALL_LOG" ] && echo "    • install log:                $INSTALL_LOG"
echo "  Not touched: apt packages, your TLS proxy, this git checkout."
[ "$BACKUP" = 1 ] && [ -n "$counts" ] && echo "  A final database dump is written to /root before anything is removed."
echo
if [ "$YES" != 1 ]; then
  printf '  Type UNINSTALL to proceed: '
  read -r _c
  [ "$_c" = "UNINSTALL" ] || { echo "  Aborted — nothing changed."; exit 1; }
fi

# 1. Final backup — cheap insurance even when --keep-data (the dump is the portable copy).
if [ "$BACKUP" = 1 ] && db_exists; then
  DUMP="/root/mdmesh-final-$(date +%Y%m%d-%H%M%S).dump"
  su -s /bin/sh postgres -c "pg_dump -Fc mdmesh" > "$DUMP" && chmod 600 "$DUMP" && echo "  ✓ final dump: $DUMP  (restore: pg_restore -c -d mdmesh $DUMP)"
fi

# 2. Stop Tomcat for good: the systemd unit first (cgroup-tracked), then legacy fallbacks for Tomcats
#    started by older versions of the installer without a unit. $CATALINA is $SVC_USER's tree, so root never runs its
#    bin/catalina.sh (nor the bin/setenv.sh it sources): with the unit, systemd stops Tomcat; without one, catalina.sh
#    runs as $SVC_USER (no controlling terminal, none of root's environment; see as_svc_user in install-native.sh), and
#    before that account existed (Tomcat ran as root) the signals below stop it.
if [ -f "$SERVER_UNIT" ] || [ -n "$(systemctl list-unit-files --no-legend mdmesh-server.service 2>/dev/null)" ]; then
  systemctl disable --now mdmesh-server >/dev/null 2>&1 || true
  rm -f "$SERVER_UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ mdmesh-server service removed"
elif [ -x "$CATALINA/bin/catalina.sh" ] && id -u "$SVC_USER" >/dev/null 2>&1; then
  ( cd / && exec setsid -w setpriv --reuid="$SVC_USER" --regid="$SVC_USER" --init-groups --no-new-privs \
      env -i PATH=/usr/local/bin:/usr/bin:/bin JAVA_HOME="${JAVA_HOME:-}" CATALINA_HOME="$CATALINA" \
      CATALINA_BASE="$CATALINA" CATALINA_PID="$CATALINA/tomcat.pid" "$CATALINA/bin/catalina.sh" stop 20 -force \
      < /dev/null ) >/dev/null 2>&1 || true
fi
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill "$p" 2>/dev/null || true; done
for _ in $(seq 1 20); do pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" >/dev/null || break; sleep 1; done
for p in $(pgrep -f "^[^ ]*/java .*catalina.base=$CATALINA" || true); do kill -9 "$p" 2>/dev/null || true; done
echo "  ✓ Tomcat stopped"

# 3. Updater service.
if [ -f "$UNIT" ] || [ -n "$(systemctl list-unit-files --no-legend mdmesh-supervisor.service 2>/dev/null)" ]; then
  systemctl disable --now mdmesh-supervisor >/dev/null 2>&1 || true
  rm -f "$UNIT"; systemctl daemon-reload 2>/dev/null || true
  echo "  ✓ mdmesh-supervisor service removed"
fi
if [ -d "$SUP_ENV_DIR" ]; then
  rm -f "$SUP_ENV_DIR/supervisor.env"
  if rmdir "$SUP_ENV_DIR" 2>/dev/null; then echo "  ✓ removed $SUP_ENV_DIR/supervisor.env and $SUP_ENV_DIR"
  else echo "  ✓ removed $SUP_ENV_DIR/supervisor.env (kept $SUP_ENV_DIR: it holds other files)"; fi
fi

# 4. Database.
if [ "$KEEP_DATA" != 1 ] && db_exists; then
  su -s /bin/sh postgres -c "psql -qc \"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='mdmesh' AND pid<>pg_backend_pid();\"" >/dev/null 2>&1 || true
  su -s /bin/sh postgres -c "psql -qc 'DROP DATABASE mdmesh;'"
  su -s /bin/sh postgres -c "psql -qc 'DROP ROLE IF EXISTS mdmesh;'"
  echo "  ✓ database + role dropped"
fi

# 5. Files.
rm -rf "$CATALINA"
if [ "$KEEP_DATA" = 1 ]; then
  for d in emails plugins supervisor; do rm -rf "${BASE_DIR:?}/$d"; done
  rm -f "$BASE_DIR"/initialized.txt "$BASE_DIR"/log4j-mdmesh.xml "$BASE_DIR"/supervisor.env   # log4j-mdmesh.xml: written by v0.2.1–v0.3.x
  echo "  ✓ removed $CATALINA and app code; kept $BASE_DIR/files and $BASE_DIR/backups"
else
  rm -rf "$BASE_DIR"
  echo "  ✓ removed $CATALINA and $BASE_DIR"
fi
rm -f "$INSTALL_LOG"
# 6. Service account — only when its files are gone too (a kept files/ dir stays owned by it).
if [ "$KEEP_DATA" != 1 ] && id -u "$SVC_USER" >/dev/null 2>&1; then
  # userdel refuses while the account has processes (it ignores those in another root, such as a container's).
  for _ in $(seq 1 20); do
    pids=$(svc_user_pids); [ -n "$pids" ] || break
    # shellcheck disable=SC2086  # one pid per word
    kill -KILL $pids 2>/dev/null || true; sleep 0.2
  done
  if userdel "$SVC_USER" 2>/dev/null; then echo "  ✓ service user $SVC_USER removed"
  else echo "  ! could not remove the service user $SVC_USER (run: userdel $SVC_USER)"; fi
fi
echo
echo "  MDMesh removed. Devices still enrolled will keep polling this server's URL until factory-reset or re-provisioned."
