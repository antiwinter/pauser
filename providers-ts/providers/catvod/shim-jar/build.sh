#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/../../.."
SRC="$SCRIPT_DIR/src"
BUILD="$SCRIPT_DIR/build"
DIST="$ROOT/dist"

ANDROID_JAR="${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-35/android.jar"
D8="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0/d8"

GSON_VERSION="2.13.1"
GSON_JAR="$BUILD/gson-${GSON_VERSION}.jar"
GSON_URL="https://repo1.maven.org/maven2/com/google/code/gson/gson/${GSON_VERSION}/gson-${GSON_VERSION}.jar"

mkdir -p "$BUILD/classes" "$DIST"

if [ ! -f "$GSON_JAR" ]; then
  echo "[shim] downloading gson ${GSON_VERSION}..."
  curl -fsSL -o "$GSON_JAR" "$GSON_URL"
fi

# Compile
javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR:$GSON_JAR" \
  -d "$BUILD/classes" \
  $(find "$SRC" -name "*.java")

# Dex — gson only; kotlin/okhttp/okio are in the app and reachable via shim's parent (ctx.classLoader)
"$D8" \
  --release \
  --lib "$ANDROID_JAR" \
  --min-api 21 \
  --output "$BUILD" \
  "$GSON_JAR" \
  $(find "$BUILD/classes" -name "*.class")

# Repack classes.dex into a JAR so DexClassLoader can load it
rm -f "$DIST/catvod-shim.jar"
(cd "$BUILD" && jar cf "$DIST/catvod-shim.jar" classes.dex)

echo "[shim] built $DIST/catvod-shim.jar"
