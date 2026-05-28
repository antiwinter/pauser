#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/../../.."
SRC="$SCRIPT_DIR/src"
BUILD="$SCRIPT_DIR/build"
DIST="$ROOT/dist"

ANDROID_JAR="${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-35/android.jar"
D8="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0/d8"

mkdir -p "$BUILD/classes" "$DIST"

# Compile
javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  $(find "$SRC" -name "*.java")

# Dex
"$D8" \
  --release \
  --lib "$ANDROID_JAR" \
  --min-api 21 \
  --output "$BUILD" \
  $(find "$BUILD/classes" -name "*.class")

# Repack classes.dex into a JAR so DexClassLoader can load it
rm -f "$DIST/catvod-shim.jar"
(cd "$BUILD" && jar cf "$DIST/catvod-shim.jar" classes.dex)

echo "[shim] built $DIST/catvod-shim.jar"
