# Shared "write the server's log4j config" rule for install/install-native.sh (bash) and docker/entrypoint.sh (POSIX sh;
# the server image copies this file). Keep it POSIX. Source this file; do not execute it.

# mdm_render_log4j TEMPLATE BASE_DIR: write BASE_DIR/log4j-mdmesh.xml, the file ROOT.xml's log4j.config names, from
# TEMPLATE (install/log4j_template.xml) with _BASE_DIRECTORY_ replaced by BASE_DIR (absolute, no '#'), and create
# BASE_DIR/logs for its file appenders. Unrendered, log4j resolves "_BASE_DIRECTORY_/logs/..." against Tomcat's working
# directory and logs a FileNotFoundException each time it loads the file (every context reload).
mdm_render_log4j() {
  mkdir -p "$2/logs" && sed "s#_BASE_DIRECTORY_#$2#g" "$1" > "$2/log4j-mdmesh.xml"
}
