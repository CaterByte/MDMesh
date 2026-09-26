#!/usr/bin/env bash
# Shared "which version is running" rule for the from-source installers (setup.sh and
# install/install-native.sh). Source this file; do not execute it.
#
# A source install runs whatever the checkout holds, so its version is the checkout's latest
# reachable release tag, without the leading "v" (v0.3.1 -> 0.3.1). The supervisor compares it to
# GitHub's latest release to decide "update available", so a stale or placeholder value (0.0.0)
# shows a false banner. Prints nothing when it cannot tell (no git, no .git dir, no tags), and
# nothing for a tag with characters that do not belong in a .env value.
mdm_repo_version() {
  local v
  v=$(git -C "${1:-.}" describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)
  case "$v" in
    ''|*[!0-9A-Za-z.+_-]*) return 0 ;;
  esac
  printf '%s\n' "$v"
}

# The major.minor.patch core of a version as "X Y Z" (leading "v" and any suffix such as -rc1 ignored); nothing when it
# has no such core ("latest", "", "0.3").
mdm_version_core() {
  printf '%s\n' "$1" | sed -nE 's/^v?([0-9]+)\.([0-9]+)\.([0-9]+).*$/\1 \2 \3/p'
}

# mdm_version_gt A B: succeeds when A is newer than B, compared on major.minor.patch as numbers — the supervisor's
# semverGt rule (supervisor/lib.js). Equal, older, or either side without a core → fails.
mdm_version_gt() {
  local a b i
  read -r -a a <<< "$(mdm_version_core "$1")"
  read -r -a b <<< "$(mdm_version_core "$2")"
  [ "${#a[@]}" -eq 3 ] && [ "${#b[@]}" -eq 3 ] || return 1
  for i in 0 1 2; do
    if [ "$((10#${a[i]}))" -ne "$((10#${b[i]}))" ]; then [ "$((10#${a[i]}))" -gt "$((10#${b[i]}))" ]; return; fi
  done
  return 1
}
