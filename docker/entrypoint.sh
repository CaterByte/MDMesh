#!/bin/sh
# Generates the Tomcat ROOT context (server config) from environment at start, then runs Tomcat.
# Keeping it env-driven means the same image serves any deployment — the setup wizard supplies
# values via .env. Secrets are generated alphanumeric (no XML-special chars), so plain interpolation
# is safe. MQTT is intentionally off (our agent wakes over WebSocket; see ROOT.xml mqtt.server.uri="").
set -e

: "${DB_HOST:=postgres}"
: "${DB_PORT:=5432}"
: "${DB_NAME:=mdmesh}"
: "${DB_USER:=mdmesh}"
: "${BASE_URL:?BASE_URL is required}"
: "${HASH_SECRET:?HASH_SECRET is required}"
: "${DB_PASSWORD:?DB_PASSWORD is required}"
: "${SECURE_ENROLLMENT:=0}"
: "${SMTP_HOST:=}"
: "${SMTP_PORT:=25}"
: "${SMTP_FROM:=mdm@localhost}"
: "${JWT_SECRET:=}"

CONF_DIR=/usr/local/tomcat/conf/Catalina/localhost
mkdir -p "$CONF_DIR" /opt/mdmesh/files /opt/mdmesh/plugins

# jwt.secretkey signs the JWTs of REST API clients (/rest/public/jwt/login; the console uses its session cookie). Left
# empty, the server picks a random key at every start and signs those clients out on each restart. So: an explicit
# JWT_SECRET wins; otherwise the key is generated once into the persistent /opt/mdmesh volume and reused on every
# start. That covers every Docker install with no manual step, including quick-start ones whose compose never changes.
# JJWT 0.9.1 base64-decodes the key and silently DROPS characters outside the base64 alphabet and a trailing partial
# 4-character group, so accept only hex, a multiple of 4 characters, at least 128 (512 bits, the HS512 minimum).
JWT_SECRET_FILE=/opt/mdmesh/jwt.secret
jwt_secret_ok() {
  case "$1" in '' | *[!0-9a-fA-F]*) return 1 ;; esac
  [ "${#1}" -ge 128 ] && [ $(( ${#1} % 4 )) -eq 0 ]
}
if [ -n "$JWT_SECRET" ]; then
  if ! jwt_secret_ok "$JWT_SECRET"; then
    echo "JWT_SECRET must be hex, a multiple of 4 characters and at least 128 long (the JWT library would silently drop anything else). Generate one with: openssl rand -hex 64 (or unset JWT_SECRET to use the key kept in $JWT_SECRET_FILE)" >&2
    exit 1
  fi
else
  [ -f "$JWT_SECRET_FILE" ] && JWT_SECRET=$(cat "$JWT_SECRET_FILE")
  if ! jwt_secret_ok "$JWT_SECRET"; then
    [ -f "$JWT_SECRET_FILE" ] && echo "WARNING: $JWT_SECRET_FILE does not hold a valid key; replacing it (REST API clients sign in again once)." >&2
    JWT_SECRET=$(od -An -v -tx1 -N64 /dev/urandom | tr -d ' \n')
    jwt_secret_ok "$JWT_SECRET" || { echo "Could not generate a JWT signing key from /dev/urandom." >&2; exit 1; }
    # umask 077 -> mode 600, written aside then renamed (atomic). The chown -R below hands it to the server user.
    (umask 077 && printf '%s\n' "$JWT_SECRET" > "$JWT_SECRET_FILE.tmp")
    mv -f "$JWT_SECRET_FILE.tmp" "$JWT_SECRET_FILE"
  fi
  chmod 600 "$JWT_SECRET_FILE"
fi

cat > "$CONF_DIR/ROOT.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<Context>
    <Parameter name="JDBC.driver"   value="org.postgresql.Driver"/>
    <Parameter name="JDBC.url"      value="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"/>
    <Parameter name="JDBC.username" value="${DB_USER}"/>
    <Parameter name="JDBC.password" value="${DB_PASSWORD}"/>

    <Parameter name="base.directory"  value="/opt/mdmesh"/>
    <Parameter name="files.directory" value="/opt/mdmesh/files"/>
    <Parameter name="base.url"        value="${BASE_URL}"/>

    <Parameter name="usage.scenario"    value="private"/>
    <Parameter name="secure.enrollment" value="${SECURE_ENROLLMENT}"/>
    <Parameter name="hash.secret"       value="${HASH_SECRET}"/>
    <Parameter name="jwt.secretkey"     value="${JWT_SECRET}"/>

    <Parameter name="plugins.files.directory" value="/opt/mdmesh/plugins"/>
    <Parameter name="plugin.devicelog.persistence.config.class"
               value="com.hmdm.plugins.devicelog.persistence.postgres.DeviceLogPostgresPersistenceConfiguration"/>
    <Parameter name="role.orgadmin.id" value="2"/>

    <Parameter name="swagger.host"      value=""/>
    <Parameter name="swagger.base.path" value="/rest"/>

    <Parameter name="initialization.completion.signal.file" value="/opt/mdmesh/initialized.txt"/>
    <Parameter name="log4j.config" value="file:///opt/mdmesh/log4j-mdmesh.xml"/>
    <Parameter name="aapt.command" value="aapt"/>

    <!-- MQTT broker disabled: the agent wakes over the WebSocket, not MQTT. -->
    <Parameter name="mqtt.server.uri" value=""/>
    <Parameter name="mqtt.auth" value="0"/>

    <Parameter name="device.fast.search.chars" value="5"/>

    <Parameter name="smtp.host" value="${SMTP_HOST}"/>
    <Parameter name="smtp.port" value="${SMTP_PORT}"/>
    <Parameter name="smtp.ssl" value="false"/>
    <Parameter name="smtp.starttls" value="false"/>
    <Parameter name="smtp.username" value="${SMTP_USERNAME:-}"/>
    <Parameter name="smtp.password" value="${SMTP_PASSWORD:-}"/>
    <Parameter name="smtp.from" value="${SMTP_FROM}"/>

    <Parameter name="email.recovery.subj" value="/opt/mdmesh/emails/_LANGUAGE_/recovery_subj.txt"/>
    <Parameter name="email.recovery.body" value="/opt/mdmesh/emails/_LANGUAGE_/recovery_body.txt"/>
</Context>
EOF

# Volumes from older deployments are root-owned; make them writable for the unprivileged user, then drop
# root for good. setpriv ships with util-linux on the Debian-based tomcat image (no gosu needed).
chown -R mdmesh:mdmesh /opt/mdmesh /usr/local/tomcat/conf/Catalina /usr/local/tomcat/logs /usr/local/tomcat/work /usr/local/tomcat/temp /usr/local/tomcat/webapps
exec setpriv --reuid=mdmesh --regid=mdmesh --init-groups catalina.sh run
