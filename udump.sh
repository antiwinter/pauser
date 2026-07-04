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

TAR="$ROOT/dump.tar.gz"
trap 'rm -f "$TAR"' EXIT

run_adb() {
  if ((${#ADB_ARGS[@]} > 0)); then
    adb "${ADB_ARGS[@]}" "$@"
  else
    adb "$@"
  fi
}

run_adb exec-out \
  "run-as com.insomnia.app sh -c 'cd cache && tar cz dump'" \
  > "$TAR"

tar xzf "$TAR" -C dump --strip-components=1

echo "Extracted to $ROOT/dump/"
