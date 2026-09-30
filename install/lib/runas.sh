#!/usr/bin/env bash
# Running commands as another account, for the native installer and uninstaller (install/install-native.sh,
# install/uninstall-native.sh). Source this file; do not execute it. as_svc_user and as_postgres run CMD in a subshell
# that:
#   - cds to / (the caller's directory, the checkout under /root, is not the account's business);
#   - drops to the account with setpriv --no-new-privs (no sudo, which root-only hosts such as a Proxmox LXC container
#     do not have, and no su/runuser, which keep root's environment and terminal);
#   - runs under setsid, so CMD has no controlling terminal to push keystrokes into root's shell with (TIOCSTI);
#   - starts from env -i, so nothing in root's environment reaches CMD beyond the variables listed here.
# as_mdmesh_role (at the end) is a database client instead: it connects as the unprivileged mdmesh role.

# as_svc_user CMD...: runs CMD as $SVC_USER. For Tomcat's own scripts: $CATALINA is that account's tree, so bin/catalina.sh
# and the bin/setenv.sh it sources are code the account can rewrite, and root must never run them. CMD gets only
# Tomcat's settings (those the unit sets), and stdin from /dev/null. The caller sets SVC_USER, CATALINA and CATALINA_PID
# (JAVA_HOME and CATALINA_OPTS are passed on when set).
as_svc_user() {
  ( cd / && exec setsid -w setpriv --reuid="$SVC_USER" --regid="$SVC_USER" --init-groups --no-new-privs \
      env -i PATH=/usr/local/bin:/usr/bin:/bin LANG="${LANG:-C.UTF-8}" JAVA_HOME="${JAVA_HOME:-}" \
      CATALINA_HOME="$CATALINA" CATALINA_BASE="$CATALINA" CATALINA_PID="$CATALINA_PID" CATALINA_OPTS="${CATALINA_OPTS:-}" \
      "$@" < /dev/null )
}

# as_postgres CMD...: runs CMD as the postgres account (psql/pg_dump over peer authentication). root's PGHOST, PGPORT,
# PGDATABASE, PGOPTIONS or PSQLRC would otherwise redirect or alter what CMD does (with su, a PGHOST in root's shell made
# the uninstaller skip its dump and drop). Unlike as_svc_user, stdin is passed through: the installer sends the role
# password to psql there, never on a command line. Call psql with -X so the account's ~/.psqlrc is not run.
as_postgres() {
  ( cd / && exec setsid -w setpriv --reuid=postgres --regid=postgres --init-groups --no-new-privs \
      env -i PATH=/usr/local/bin:/usr/bin:/bin LANG="${LANG:-C.UTF-8}" "$@" )
}

# as_mdmesh_role PASSWORD CMD ARGS...: runs the client CMD (psql, pg_dump) connected as the mdmesh database role, over
# TCP to 127.0.0.1:5432 with password authentication, the way the server connects. Use it, never as_postgres, for
# anything that reads the mdmesh database. Its objects belong to mdmesh, and the service account can read that role's
# password in ROOT.xml, so a view or function planted there runs with the rights of whoever queries it; in a superuser
# session that hands out superuser. SET ROLE mdmesh in such a session is no sandbox either: planted code can RESET ROLE.
# PASSWORD reaches CMD in its environment, never on a command line. Root's other PG* settings and PSQLRC are dropped
# (they would redirect or alter the connection). CMD runs as root: it is only a client of an unprivileged session.
as_mdmesh_role() {
  ( cd / || exit 1
    unset PSQLRC "${!PG@}"
    PGPASSWORD=$1; export PGPASSWORD; export PATH=/usr/local/bin:/usr/bin:/bin
    shift
    exec "$1" -h 127.0.0.1 -p 5432 -U mdmesh -d mdmesh "${@:2}" )
}
