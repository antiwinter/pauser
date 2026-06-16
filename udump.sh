#!/usr/bin/env bash
set -euo pipefail

# Pull Emby API dump from device cache/emby_dump/ into ./dump/
# Usage: ./windump.sh [adb-serial]
#   or:  ADB_SERIAL=25b41579 ./windump.sh

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

ADB_ARGS=()
if [[ $# -gt 0 ]]; then
  ADB_ARGS=(-s "$1")
elif [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_ARGS=(-s "$ADB_SERIAL")
fi

rm -rf dump
mkdir dump

TAR="$ROOT/emby_dump.tar.gz"
trap 'rm -f "$TAR"' EXIT

run_adb() {
  if ((${#ADB_ARGS[@]} > 0)); then
    adb "${ADB_ARGS[@]}" "$@"
  else
    adb "$@"
  fi
}

run_adb exec-out \
  "run-as com.opentune.app sh -c 'cd cache && tar cz emby_dump'" \
  > "$TAR"

tar xzf "$TAR" -C dump

echo "Extracted to $ROOT/dump/emby_dump/"
