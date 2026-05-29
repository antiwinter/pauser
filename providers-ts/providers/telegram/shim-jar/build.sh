#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/../../.."
SRC="$SCRIPT_DIR/src"
BUILD="$SCRIPT_DIR/build"
DIST="$ROOT/dist"
ASSETS="$ROOT/../app/src/main/assets"
LIBS="$SCRIPT_DIR/libs"

ANDROID_JAR="${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-35/android.jar"
D8="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0/d8"

OUT="$DIST/telegram-shim.jar"

mkdir -p "$BUILD/classes" "$DIST" "$ASSETS"

# ── Check .so availability ───────────────────────────────────────────────────
SO_FILES=()
if [ -d "$LIBS" ]; then
  for abi_dir in "$LIBS"/*/; do
    abi=$(basename "$abi_dir")
    so="$abi_dir/libtdjson.so"
    if [ -f "$so" ]; then
      SO_FILES+=("$so:$abi")
    fi
  done
fi

if [ ${#SO_FILES[@]} -eq 0 ]; then
  echo "[shim] WARNING: no libtdjson.so found in $LIBS/"
  echo "[shim] Run providers/telegram/build-tdlib.sh to build TDLib native libraries"
  echo "[shim] Building JAR without native libs (runtime will fail on device)"
fi

# ── Rebuild only if sources are newer than output ────────────────────────────
if [ -f "$OUT" ]; then
  NEWEST_SRC=$(find "$SRC" -name "*.java" -exec stat -f "%m" {} + | sort -rn | head -1)
  OUT_MTIME=$(stat -f "%m" "$OUT")
  # Also check if .so files are newer
  for entry in "${SO_FILES[@]}"; do
    so_path="${entry%%:*}"
    so_mtime=$(stat -f "%m" "$so_path" 2>/dev/null || echo 0)
    if [ "$so_mtime" -gt "$OUT_MTIME" ]; then
      NEWEST_SRC=0  # force rebuild
      break
    fi
  done
  if [ "$NEWEST_SRC" -le "$OUT_MTIME" ]; then
    echo "[shim] telegram-shim.jar is up to date"
    exit 0
  fi
fi

# ── Compile ──────────────────────────────────────────────────────────────────
javac -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  $(find "$SRC" -name "*.java")

# ── Dex ──────────────────────────────────────────────────────────────────────
"$D8" \
  --release \
  --lib "$ANDROID_JAR" \
  --min-api 21 \
  --output "$BUILD" \
  $(find "$BUILD/classes" -name "*.class")

# ── Pack JAR with native libs ────────────────────────────────────────────────
rm -f "$OUT"

JAR_ARGS="cf $OUT"
JAR_ARGS="$JAR_ARGS -C $BUILD classes.dex"

for entry in "${SO_FILES[@]}"; do
  so_path="${entry%%:*}"
  abi="${entry##*:}"
  # Bundle as lib/$abi/libtdjson.so (standard Android native lib path in JAR)
  JAR_ARGS="$JAR_ARGS -C $LIBS lib/$abi/libtdjson.so"
  # Create temp symlink for jar to find it
  mkdir -p "$BUILD/lib/$abi"
  cp "$so_path" "$BUILD/lib/$abi/libtdjson.so"
done

(cd "$BUILD" && jar $JAR_ARGS)

# Copy to assets
cp "$OUT" "$ASSETS/telegram-shim.jar"

echo "[shim] built $OUT ($(ls -lh "$OUT" | awk '{print $5}'))"
if [ ${#SO_FILES[@]} -gt 0 ]; then
  echo "[shim] bundled ${#SO_FILES[@]} native lib(s): ${SO_FILES[*]}"
fi
