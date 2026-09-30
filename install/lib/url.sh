#!/usr/bin/env bash
# Shared BASE_URL rule for the installers (setup.sh, install/install-native.sh; quickstart.sh keeps an identical inline
# copy, see there). Source this file; do not execute it.
#
# BASE_URL is the public address devices and the console use. It is written into .env and into Tomcat's ROOT.xml as an
# XML attribute value, and baked into the enrollment QR, so a typo or a stray character breaks the install in ways that
# surface much later. It is checked when it is entered or read, and must be:
#   - http:// or https:// followed by a non-empty host (an optional :port and /path are fine);
#   - free of whitespace, control characters and the characters " ' < > &.
# This only validates: escaping the values written into ROOT.xml is a separate job, done where they are written.

# mdm_valid_base_url URL: succeeds when URL follows the rule above; otherwise prints why to stderr and fails.
mdm_valid_base_url() {
  local url=$1 rest host why=
  case "$url" in
    *[[:cntrl:]]*)     why='it contains a control character (a tab, a newline or similar)' ;;
    *[[:space:]]*)     why='it contains whitespace' ;;
    *[\"\'\<\>\&]*)    why="it contains one of the characters \" ' < > &" ;;
    http://*|https://*)
      rest=${url#*://}; host=${rest%%[/?#]*}; host=${host##*@}; host=${host%:*}
      [ -n "$host" ] || why='it has no host after the scheme (expected e.g. https://mdm.example.com)' ;;
    *)                 why='it must start with http:// or https:// (e.g. https://mdm.example.com)' ;;
  esac
  [ -z "$why" ] && return 0
  case "$url" in *[[:cntrl:]]*) url=$(printf '%q' "$url") ;; esac   # shown as typed, unless that would garble the terminal
  printf 'Invalid public base URL %s: %s.\n' "${url:-(empty)}" "$why" >&2
  return 1
}
