#!/usr/bin/env bash
# Shared BASE_URL rule for the installers (setup.sh, install/install-native.sh; quickstart.sh keeps an identical inline
# copy, see there). Source this file; do not execute it. bash 3.2 compatible (quickstart may run under macOS's bash).
#
# BASE_URL is the public address devices and the console use. It is written into .env and into Tomcat's ROOT.xml as an
# XML attribute value, and baked into the enrollment QR, so a typo or a stray character breaks the install in ways that
# surface much later. It is checked when it is entered or read, and must be:
#   - http:// or https://, in any letter case (RFC 3986: the scheme is case-insensitive); it is rewritten in lowercase;
#   - then a host: a name, an IPv4 address or a [bracketed] IPv6 literal, optionally followed by :port (digits only);
#   - free of whitespace, control characters and the characters " ' < > &.
# Not checked: the path (a /path is allowed as it is, "." and ".." segments included), the host's own syntax beyond the
# above (e.g. a label that starts with "-"), and the port's range. This only validates: escaping the values written into
# ROOT.xml is a separate job, done where they are written.

# mdm_check_base_url VAR: checks the URL held in the variable named VAR. When it passes, VAR's scheme is rewritten in
# lowercase (HTTPS://… becomes https://…; nothing else changes) and it succeeds. Otherwise it prints why to stderr,
# leaves VAR alone and fails. It takes a variable name, not the value, so the caller's variable is normalised in place.
mdm_check_base_url() {
  local url=${!1-} rest='' hp host port why=''
  case "$url" in
    *[[:cntrl:]]*)                why='it contains a control character (a tab, a newline or similar)' ;;
    *[[:space:]]*)                why='it contains whitespace' ;;
    *[\"\'\<\>\&]*)               why="it contains one of the characters \" ' < > &" ;;
    [Hh][Tt][Tt][Pp]://*)         rest=${url#*://}; url="http://$rest" ;;
    [Hh][Tt][Tt][Pp][Ss]://*)     rest=${url#*://}; url="https://$rest" ;;
    *)                            why='it must start with http:// or https:// (e.g. https://mdm.example.com)' ;;
  esac
  case "$rest" in
    [Hh][Tt][Tt][Pp]://*|[Hh][Tt][Tt][Pp][Ss]://*) why='it has the scheme twice (where a hostname is asked for, enter the name only)' ;;
  esac
  if [ -z "$why" ]; then
    hp=${rest%%[/?#]*}; hp=${hp##*@}; host=$hp; port=
    case "$hp" in
      *\]) ;;                                                           # [IPv6] with no port
      *:*) host=${hp%:*}; port=${hp##*:}; [ -n "$port" ] || port=- ;;   # "-" marks an empty port: never valid
    esac
    case "$host" in
      '')            why='it has no host after the scheme (expected e.g. https://mdm.example.com)' ;;
      \[*\])         ;;
      *:*|*\[*|*\]*) why="\"$hp\" is not a host or host:port" ;;
    esac
    if [ -z "$why" ]; then
      case "$port" in *[!0-9]*) why="\"$hp\" does not end in a port number after the colon" ;; esac
    fi
  fi
  if [ -z "$why" ]; then printf -v "$1" '%s' "$url"; return 0; fi
  url=${!1-}
  case "$url" in *[[:cntrl:]]*) url=$(printf '%q' "$url") ;; esac   # shown as typed, unless that would garble the terminal
  printf 'Invalid public base URL %s: %s.\n' "${url:-(empty)}" "$why" >&2
  return 1
}
