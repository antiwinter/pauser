#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVIDERS="$SCRIPT_DIR/../providers"

for build_sh in "$PROVIDERS"/*/shim-jar/build.sh; do
  [ -f "$build_sh" ] || continue
  bash "$build_sh"
done
