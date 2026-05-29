#!/usr/bin/env bash
set -euo pipefail

# Build TDLib native libraries for Android (arm64-v8a, armeabi-v7a).
# All sources and build artifacts live under tdlib/ subdirectory:
#   tdlib/td/          — TDLib source
#   tdlib/openssl/     — OpenSSL source
#   tdlib/build/<abi>/ — build output per ABI
#   tdlib/libs/<abi>/  — final libtdjson.so per ABI
#
# zlib comes from NDK sysroot (no build needed).
# OpenSSL is built from source because Android does not ship it.
#
# Usage:
#   ./build-tdlib.sh            # build if .so not present
#   ./build-tdlib.sh --force    # always rebuild

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TDLIB_ROOT="$SCRIPT_DIR/tdlib"
TDLIB_SRC="$TDLIB_ROOT/td"
OPENSSL_SRC="$TDLIB_ROOT/openssl"
BUILD_ROOT="$TDLIB_ROOT/build"
LIBS_DIR="$SCRIPT_DIR/shim-jar/libs"

# ── Configuration ────────────────────────────────────────────────────────────
TDLIB_REPO="https://github.com/tdlib/td.git"
TDLIB_TAG="v1.8.0"
OPENSSL_REPO="https://github.com/openssl/openssl.git"
OPENSSL_TAG="openssl-3.1.7"

ABIS=("arm64-v8a" "armeabi-v7a")
API_LEVEL="21"

# ── Resolve Android SDK/NDK ──────────────────────────────────────────────────
resolve_sdk_root() {
  if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then echo "$ANDROID_HOME"; return; fi
  if [ -d "$HOME/Library/Android/sdk" ]; then echo "$HOME/Library/Android/sdk"; return; fi
  if [ -d "$HOME/Android/Sdk" ]; then echo "$HOME/Android/Sdk"; return; fi
  if [ -d "$HOME/Android/sdk" ]; then echo "$HOME/Android/sdk"; return; fi
  echo ""
}

ANDROID_SDK_ROOT="$(resolve_sdk_root)"
if [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "[tdlib] ERROR: Android SDK not found. Set ANDROID_HOME."; exit 1
fi

if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
  ANDROID_NDK="$ANDROID_NDK_HOME"
elif [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
  ANDROID_NDK=$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -type d -name '[0-9]*' 2>/dev/null | sort -V | tail -1)
fi

if [ -z "${ANDROID_NDK:-}" ] || [ ! -d "$ANDROID_NDK" ]; then
  echo "[tdlib] ERROR: Android NDK not found. Set ANDROID_NDK_HOME or install via SDK Manager."; exit 1
fi

TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
  echo "[tdlib] ERROR: NDK toolchain not found at $TOOLCHAIN_FILE"; exit 1
fi

CMAKE=$(which cmake 2>/dev/null || true)
if [ -z "$CMAKE" ]; then
  CMAKE="$ANDROID_NDK/prebuilt/darwin-x86_64/bin/cmake"
  [ ! -f "$CMAKE" ] && CMAKE="$ANDROID_NDK/prebuilt/linux-x86_64/bin/cmake"
  if [ ! -f "$CMAKE" ] && [ -d "$ANDROID_SDK_ROOT/cmake" ]; then
    CMAKE=$(find "$ANDROID_SDK_ROOT/cmake" -maxdepth 1 -type d | sort -V | tail -1)/bin/cmake
  fi
fi
if [ ! -f "$CMAKE" ]; then
  echo "[tdlib] ERROR: cmake not found. brew install cmake or install via SDK Manager."; exit 1
fi

FORCE=0
for arg in "$@"; do case "$arg" in --force|-f) FORCE=1;; esac; done

echo "[tdlib] NDK: $ANDROID_NDK"
echo "[tdlib] cmake: $CMAKE"

# ── Check rebuild needed ─────────────────────────────────────────────────────
NEEDS_BUILD=$FORCE
if [ "$NEEDS_BUILD" -eq 0 ]; then
  for abi in "${ABIS[@]}"; do
    if [ ! -f "$LIBS_DIR/$abi/libtdjson.so" ]; then
      NEEDS_BUILD=1; break
    fi
  done
  if [ "$NEEDS_BUILD" -eq 0 ]; then
    echo "[tdlib] All .so files present and up to date. Use --force to rebuild."; exit 0
  fi
fi

mkdir -p "$TDLIB_ROOT" "$BUILD_ROOT" "$LIBS_DIR"

# ── Clone repos into tdlib/ ──────────────────────────────────────────────────
if [ ! -d "$TDLIB_SRC/.git" ]; then
  echo "[tdlib] Cloning TDLib ($TDLIB_TAG)..."
  rm -rf "$TDLIB_SRC"
  git clone --depth 1 --branch "$TDLIB_TAG" "$TDLIB_REPO" "$TDLIB_SRC"
else
  echo "[tdlib] TDLib source present at $TDLIB_SRC"
fi

if [ ! -d "$OPENSSL_SRC/.git" ]; then
  echo "[tdlib] Cloning OpenSSL ($OPENSSL_TAG)..."
  rm -rf "$OPENSSL_SRC"
  git clone --depth 1 --branch "$OPENSSL_TAG" "$OPENSSL_REPO" "$OPENSSL_SRC"
else
  echo "[tdlib] OpenSSL source present at $OPENSSL_SRC"
fi

# ── ABI mappings ─────────────────────────────────────────────────────────────
ndk_triple() {
  case "$1" in
    arm64-v8a)   echo "aarch64-linux-android";;
    armeabi-v7a) echo "armv7a-linux-androideabi";;
    *)           echo "$1";;
  esac
}

openssl_target() {
  case "$1" in
    arm64-v8a)   echo "android-arm64";;
    armeabi-v7a) echo "android-arm";;
    *)           echo "$1";;
  esac
}

NDK_CC="$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
export PATH="$NDK_CC:$PATH"

# ── Build OpenSSL + TDLib for each ABI ───────────────────────────────────────
for abi in "${ABIS[@]}"; do
  echo ""
  echo "═══════════════════════════════════════════════════"
  echo "[tdlib] Building for $abi"
  echo "═══════════════════════════════════════════════════"

  TRIPLE=$(ndk_triple "$abi")
  MIN_API="$API_LEVEL"

  # Toolchain
  CC="$NDK_CC/${TRIPLE}${MIN_API}-clang"
  CXX="$NDK_CC/${TRIPLE}${MIN_API}-clang++"
  AR="$NDK_CC/llvm-ar"
  RANLIB="$NDK_CC/llvm-ranlib"
  MAKE="make"

  # ── Build OpenSSL (static) ─────────────────────────────────────────────────
  OPENSSL_BUILD="$BUILD_ROOT/openssl/$abi"
  mkdir -p "$OPENSSL_BUILD"

  if [ ! -f "$OPENSSL_BUILD/lib/libcrypto.a" ] || [ "$FORCE" -eq 1 ]; then
    echo "[openssl] Configuring for $abi ($(openssl_target "$abi"))..."
    (
      export ANDROID_NDK_ROOT="$ANDROID_NDK"
      cd "$OPENSSL_SRC" || exit 1
      ./Configure $(openssl_target "$abi") \
        --prefix="$OPENSSL_BUILD" \
        --openssldir="$OPENSSL_BUILD" \
        no-shared no-ui no-engine no-dso no-tests \
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" \
        -Os -D__ANDROID_API__=21 || exit 1
    ) || exit 1

    echo "[openssl] Building (static libs only, skipping apps)..."
    $MAKE -C "$OPENSSL_SRC" build_libs -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)" || exit 1
    $MAKE -C "$OPENSSL_SRC" install_sw || exit 1
    echo "[openssl] Built for $abi"
  else
    echo "[openssl] Already built for $abi — skipping"
  fi

  # ── Build TDLib ────────────────────────────────────────────────────────────
  TD_BUILD="$BUILD_ROOT/td/$abi"
  mkdir -p "$TD_BUILD"

  echo "[tdlib] Configuring CMake for $abi..."
  "$CMAKE" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_ABI="$abi" \
    -DANDROID_NATIVE_API_LEVEL="$API_LEVEL" \
    -DANDROID_TOOLCHAIN=clang \
    -DOPENSSL_ROOT_DIR="$OPENSSL_BUILD" \
    -DOPENSSL_USE_STATIC_LIBS=ON \
    -DOPENSSL_CRYPTO_LIBRARY="$OPENSSL_BUILD/lib/libcrypto.a" \
    -DOPENSSL_SSL_LIBRARY="$OPENSSL_BUILD/lib/libssl.a" \
    -DOPENSSL_INCLUDE_DIR="$OPENSSL_BUILD/include" \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic" \
    -DCMAKE_C_COMPILER="$CC" \
    -DCMAKE_CXX_COMPILER="$CXX" \
    -B "$TD_BUILD" \
    -S "$TDLIB_SRC"

  echo "[tdlib] Building tdjson target..."
  "$CMAKE" --build "$TD_BUILD" --config Release --target tdjson \
    -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

  SO_PATH=$(find "$TD_BUILD" -name "libtdjson.so" -type f 2>/dev/null | head -1)
  if [ -z "$SO_PATH" ]; then
    echo "[tdlib] ERROR: libtdjson.so not found after build"; exit 1
  fi

  mkdir -p "$LIBS_DIR/$abi"
  cp "$SO_PATH" "$LIBS_DIR/$abi/libtdjson.so"
  echo "[tdlib] Copied libtdjson.so for $abi ($(ls -lh "$SO_PATH" | awk '{print $5}'))"
done

echo ""
echo "[tdlib] Done. Final .so files:"
ls -lh "$LIBS_DIR"/*/libtdjson.so
