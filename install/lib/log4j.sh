# Shared "write the server's log4j config" rule for install/install-native.sh (bash) and docker/entrypoint.sh (POSIX sh;
# the server image copies this file). Keep it POSIX. Source this file; do not execute it.

# mdm_render_log4j TEMPLATE BASE_DIR: write BASE_DIR/log4j-mdmesh.xml, the file ROOT.xml's log4j.config names, from
# TEMPLATE (install/log4j_template.xml) with _BASE_DIRECTORY_ replaced by BASE_DIR, and create
# BASE_DIR/logs for its file appenders. Unrendered, log4j resolves "_BASE_DIRECTORY_/logs/..." against Tomcat's working
# directory and logs a FileNotFoundException each time it loads the file (every context reload).
# BASE_DIR must be absolute and free of the characters sed treats specially in this replacement: '#' (the delimiter),
# '&', '\' and newline. Both callers pass a fixed path (/opt/mdmesh).
# Both callers run this as root in a directory the server user owns, so it never writes through a link planted there:
# the output goes to a fresh mktemp file (O_EXCL) that is renamed over the old file in one step with GNU mv -fT (it
# replaces a planted link instead of following it, and fails on a planted directory instead of moving into it; both
# callers run on Debian/Ubuntu coreutils). On failure the old file is left as it was.
mdm_render_log4j() {
  mkdir -p "$2/logs" || return 1
  _mdm_log4j_tmp=$(mktemp "$2/log4j-mdmesh.xml.XXXXXX") || return 1
  if sed "s#_BASE_DIRECTORY_#$2#g" "$1" > "$_mdm_log4j_tmp" && mv -fT "$_mdm_log4j_tmp" "$2/log4j-mdmesh.xml"; then
    return 0
  fi
  rm -f "$_mdm_log4j_tmp"
  return 1
}
