# Build TDLib native libraries for Android (arm64-v8a, armeabi-v7a).
#
#   make          — build if .so not present
#   make install  — build + copy stripped .so to ../shim-jar/libs/
#   make clean    — remove build artifacts, keep sources
#   make distclean — remove everything including sources

ABIS := arm64-v8a armeabi-v7a
API_LEVEL := 21

TDLIB_REPO := https://github.com/tdlib/td.git
TDLIB_TAG  := v1.8.0
OPENSSL_REPO := https://github.com/openssl/openssl.git
OPENSSL_TAG  := openssl-3.1.7

DIR       := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
TD_SRC    := $(DIR)/td
OPEN_SRC  := $(DIR)/openssl
BUILD_DIR := $(DIR)/build
LIBS_DIR  := $(DIR)/../shim-jar/libs
HOST_BUILD := $(BUILD_DIR)/host

# ── Resolve SDK/NDK ──────────────────────────────────────────────────────────
ANDROID_SDK_ROOT ?= $(HOME)/Library/Android/sdk
export ANDROID_NDK_ROOT ?= $(shell find $(ANDROID_SDK_ROOT)/ndk -maxdepth 1 -type d -name '[0-9]*' 2>/dev/null | sort -V | tail -1)
export PATH := $(ANDROID_NDK_ROOT)/toolchains/llvm/prebuilt/darwin-x86_64/bin:$(PATH)

TOOLCHAIN_FILE := $(ANDROID_NDK_ROOT)/build/cmake/android.toolchain.cmake
CMAKE          := $(shell which cmake 2>/dev/null || find $(ANDROID_SDK_ROOT)/cmake -maxdepth 4 -name cmake -type f 2>/dev/null | head -1)
LLVM_STRIP     := $(shell find $(ANDROID_NDK_ROOT) -name "llvm-strip" \( -type f -o -type l \) 2>/dev/null | head -1)

NDK_BIN := $(ANDROID_NDK_ROOT)/toolchains/llvm/prebuilt/darwin-x86_64/bin

# ── Default targets ──────────────────────────────────────────────────────────
.PHONY: all install clean distclean
all: $(foreach abi,$(ABIS),$(LIBS_DIR)/$(abi)/libtdjson.so)

install: all

# ── Install: strip and copy .so to shim-jar/libs ─────────────────────────────
INSTALL_TARGETS := $(foreach abi,$(ABIS),$(LIBS_DIR)/$(abi)/libtdjson.so)

$(LIBS_DIR)/arm64-v8a/libtdjson.so:   $(BUILD_DIR)/td/arm64-v8a/libtdjson.so
$(LIBS_DIR)/armeabi-v7a/libtdjson.so: $(BUILD_DIR)/td/armeabi-v7a/libtdjson.so

$(INSTALL_TARGETS):
	@if [ -n "$(LLVM_STRIP)" ]; then \
		"$(LLVM_STRIP)" --strip-all "$<"; \
		echo "[install] Stripped $(notdir $<)"; \
	fi
	@mkdir -p $(dir $@)
	@cp "$<" "$@"
	@echo "[install] $@"

# ── Generate auto files natively (needed before cross-compiling) ─────────────
$(TD_SRC)/.generated: $(TD_SRC)/.cloned
	@echo "[tdlib] Generating auto files (native build)..."
	@mkdir -p "$(HOST_BUILD)"
	@"$(CMAKE)" -S $(TD_SRC) -B "$(HOST_BUILD)" \
		-DTDUTILS_MIME_TYPE=ON \
		-DOPENSSL_FOUND=FALSE -DOPENSSL_INCLUDE_DIR="" -DOPENSSL_SSL_LIBRARY="" -DOPENSSL_CRYPTO_LIBRARY=""
	@"$(CMAKE)" --build "$(HOST_BUILD)" \
		--target prepare_cross_compiling \
		-j$$(sysctl -n hw.ncpu 2>/dev/null || echo 4) || true
	@touch "$@"

# ── Per-ABI OpenSSL working copy ─────────────────────────────────────────────
$(BUILD_DIR)/openssl/arm64-v8a/openssl_work/.prepared: $(OPEN_SRC)/.cloned
	@mkdir -p "$(@D)"
	@(cd $(OPEN_SRC) && tar cf - .) | (cd "$(@D)" && tar xf -)
	@touch "$@"

$(BUILD_DIR)/openssl/armeabi-v7a/openssl_work/.prepared: $(OPEN_SRC)/.cloned
	@mkdir -p "$(@D)"
	@(cd $(OPEN_SRC) && tar cf - .) | (cd "$(@D)" && tar xf -)
	@touch "$@"

# ── Explicit build rules per ABI ─────────────────────────────────────────────
# arm64-v8a
$(BUILD_DIR)/td/arm64-v8a/libtdjson.so: $(TD_SRC)/.generated \
	$(BUILD_DIR)/openssl/arm64-v8a/openssl_work/.prepared
	@echo ""; \
	echo "═══════════════════════════════════════════════════"; \
	echo "[tdlib] Building for arm64-v8a"; \
	echo "═══════════════════════════════════════════════════"; \
	OS_BUILD="$(BUILD_DIR)/openssl/arm64-v8a"; \
	if [ ! -f "$$OS_BUILD/lib/libcrypto.a" ]; then \
		echo "[openssl] Configuring for arm64-v8a (android-arm64)..."; \
		(cd $(BUILD_DIR)/openssl/arm64-v8a/openssl_work && \
		export ANDROID_NDK_ROOT="$(ANDROID_NDK_ROOT)"; \
		./Configure android-arm64 \
			--prefix="$$OS_BUILD" \
			--openssldir="$$OS_BUILD" \
			no-shared no-engine no-dso no-tests \
			-D__ANDROID_API__=$(API_LEVEL) \
			-Os && \
		$(MAKE) CC="$(NDK_BIN)/aarch64-linux-android$(API_LEVEL)-clang" \
		        CXX="$(NDK_BIN)/aarch64-linux-android$(API_LEVEL)-clang++" \
		        AR="$(NDK_BIN)/llvm-ar" \
		        RANLIB="$(NDK_BIN)/llvm-ranlib" \
		        build_libs -j$$(sysctl -n hw.ncpu 2>/dev/null || echo 4) && \
		$(MAKE) install_sw); \
		echo "[openssl] Done for arm64-v8a"; \
	else \
		echo "[openssl] Already built for arm64-v8a — skipping"; \
	fi; \
	TD_BUILD="$(BUILD_DIR)/td/arm64-v8a"; \
	mkdir -p "$$TD_BUILD"; \
	echo "[tdlib] Configuring CMake for arm64-v8a..."; \
	"$(CMAKE)" \
		-DCMAKE_TOOLCHAIN_FILE="$(TOOLCHAIN_FILE)" \
		-DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_C_FLAGS_RELEASE="-Os -DNDEBUG" \
		-DCMAKE_CXX_FLAGS_RELEASE="-Os -DNDEBUG" \
		-DANDROID_ABI="arm64-v8a" \
		-DANDROID_NATIVE_API_LEVEL="$(API_LEVEL)" \
		-DANDROID_TOOLCHAIN=clang \
		-DOPENSSL_ROOT_DIR="$(BUILD_DIR)/openssl/arm64-v8a" \
		-DOPENSSL_USE_STATIC_LIBS=ON \
		-DOPENSSL_CRYPTO_LIBRARY="$(BUILD_DIR)/openssl/arm64-v8a/lib/libcrypto.a" \
		-DOPENSSL_SSL_LIBRARY="$(BUILD_DIR)/openssl/arm64-v8a/lib/libssl.a" \
		-DOPENSSL_INCLUDE_DIR="$(BUILD_DIR)/openssl/arm64-v8a/include" \
		-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic" \
		-B "$$TD_BUILD" \
		-S $(TD_SRC); \
	echo "[tdlib] Building tdjson..."; \
	"$(CMAKE)" --build "$$TD_BUILD" --config Release --target tdjson \
		-j$$(sysctl -n hw.ncpu 2>/dev/null || echo 4); \
	echo "[tdlib] Built arm64-v8a"

# armeabi-v7a
$(BUILD_DIR)/td/armeabi-v7a/libtdjson.so: $(TD_SRC)/.generated \
	$(BUILD_DIR)/openssl/armeabi-v7a/openssl_work/.prepared
	@echo ""; \
	echo "═══════════════════════════════════════════════════"; \
	echo "[tdlib] Building for armeabi-v7a"; \
	echo "═══════════════════════════════════════════════════"; \
	OS_BUILD="$(BUILD_DIR)/openssl/armeabi-v7a"; \
	if [ ! -f "$$OS_BUILD/lib/libcrypto.a" ]; then \
		echo "[openssl] Configuring for armeabi-v7a (android-arm)..."; \
		(cd $(BUILD_DIR)/openssl/armeabi-v7a/openssl_work && \
		export ANDROID_NDK_ROOT="$(ANDROID_NDK_ROOT)"; \
		./Configure android-arm \
			--prefix="$$OS_BUILD" \
			--openssldir="$$OS_BUILD" \
			no-shared no-engine no-dso no-tests \
			-D__ANDROID_API__=$(API_LEVEL) \
			-Os && \
		$(MAKE) CC="$(NDK_BIN)/armv7a-linux-androideabi$(API_LEVEL)-clang" \
		        CXX="$(NDK_BIN)/armv7a-linux-androideabi$(API_LEVEL)-clang++" \
		        AR="$(NDK_BIN)/llvm-ar" \
		        RANLIB="$(NDK_BIN)/llvm-ranlib" \
		        build_libs -j$$(sysctl -n hw.ncpu 2>/dev/null || echo 4) && \
		$(MAKE) install_sw); \
		echo "[openssl] Done for armeabi-v7a"; \
	else \
		echo "[openssl] Already built for armeabi-v7a — skipping"; \
	fi; \
	TD_BUILD="$(BUILD_DIR)/td/armeabi-v7a"; \
	mkdir -p "$$TD_BUILD"; \
	echo "[tdlib] Configuring CMake for armeabi-v7a..."; \
	"$(CMAKE)" \
		-DCMAKE_TOOLCHAIN_FILE="$(TOOLCHAIN_FILE)" \
		-DCMAKE_BUILD_TYPE=Release \
		-DCMAKE_C_FLAGS_RELEASE="-Os -DNDEBUG" \
		-DCMAKE_CXX_FLAGS_RELEASE="-Os -DNDEBUG" \
		-DANDROID_ABI="armeabi-v7a" \
		-DANDROID_NATIVE_API_LEVEL="$(API_LEVEL)" \
		-DANDROID_TOOLCHAIN=clang \
		-DOPENSSL_ROOT_DIR="$(BUILD_DIR)/openssl/armeabi-v7a" \
		-DOPENSSL_USE_STATIC_LIBS=ON \
		-DOPENSSL_CRYPTO_LIBRARY="$(BUILD_DIR)/openssl/armeabi-v7a/lib/libcrypto.a" \
		-DOPENSSL_SSL_LIBRARY="$(BUILD_DIR)/openssl/armeabi-v7a/lib/libssl.a" \
		-DOPENSSL_INCLUDE_DIR="$(BUILD_DIR)/openssl/armeabi-v7a/include" \
		-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-Bsymbolic" \
		-B "$$TD_BUILD" \
		-S $(TD_SRC); \
	echo "[tdlib] Building tdjson..."; \
	"$(CMAKE)" --build "$$TD_BUILD" --config Release --target tdjson \
		-j$$(sysctl -n hw.ncpu 2>/dev/null || echo 4); \
	echo "[tdlib] Built armeabi-v7a"

# ── Clone sources ────────────────────────────────────────────────────────────
$(TD_SRC)/.cloned:
	@mkdir -p $(DIR)
	@echo "[tdlib] Cloning TDLib ($(TDLIB_TAG))..."
	@rm -rf $(TD_SRC)
	@git clone --depth 1 --branch $(TDLIB_TAG) $(TDLIB_REPO) $(TD_SRC)
	@touch $@

$(OPEN_SRC)/.cloned:
	@mkdir -p $(DIR)
	@echo "[tdlib] Cloning OpenSSL ($(OPENSSL_TAG))..."
	@rm -rf $(OPEN_SRC)
	@git clone --depth 1 --branch $(OPENSSL_TAG) $(OPENSSL_REPO) $(OPEN_SRC)
	@touch $@

# ── Clean targets ────────────────────────────────────────────────────────────
clean:
	rm -rf $(BUILD_DIR)

distclean: clean
	rm -rf $(TD_SRC) $(OPEN_SRC)

.DELETE_ON_ERROR:
