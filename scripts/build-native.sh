#!/usr/bin/env bash
# Builds the FreeRDP native libraries for Android and copies them into
# rdpcore/src/main/jniLibs/<abi>/. Dependencies (OpenSSL) are downloaded and
# built by FreeRDP's CMake superbuild on the first run.
#
# Usage: scripts/build-native.sh [abi]   (default abi: arm64-v8a)
# Env:   ANDROID_SDK_ROOT (default: $HOME/android-sdk)
set -euo pipefail

ABI="${1:-arm64-v8a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
NDK="$SDK/ndk/29.0.13113456"
CMAKE_BIN="$SDK/cmake/4.1.2/bin"
SRC="$ROOT/third_party/freerdp"
BUILD="$ROOT/build/native-$ABI"
OUT="$ROOT/rdpcore/src/main/jniLibs/$ABI"

for p in "$NDK" "$CMAKE_BIN" "$SRC"; do
	[[ -e "$p" ]] || { echo "missing: $p" >&2; exit 1; }
done

export PATH="$CMAKE_BIN:$PATH"

# Local fixes to FreeRDP's Android JNI code (scripts/patches/*.patch).
# The files they touch are first restored to the pinned FreeRDP commit, so an
# older version of a patch left in the tree never blocks applying the current
# one. Restoring and re-applying an unchanged patch rewrites identical content.
for patch in "$ROOT"/scripts/patches/*.patch; do
	[[ -e "$patch" ]] || continue
	git -C "$SRC" apply --numstat "$patch" | cut -f3 | while read -r file; do
		git -C "$SRC" checkout -- "$file"
	done
	git -C "$SRC" apply "$patch"
	echo "applied: $(basename "$patch")"
done

# Codecs are off: GNOME Remote Desktop works with the built-in RemoteFX and
# progressive codecs, and each codec adds several minutes of build time.
cmake -S "$SRC/client/Android/Studio/freeRDPCore/src/main/cpp" \
	-B "$BUILD" \
	-G Ninja \
	--toolchain "$NDK/build/cmake/android.toolchain.cmake" \
	-DANDROID_ABI="$ABI" \
	-DANDROID_PLATFORM=android-29 \
	-DCMAKE_BUILD_TYPE=Release \
	-DWITH_OPENSSL=ON \
	-DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_OPUS=OFF \
	-DWITH_PNG=OFF -DWITH_WEBP=OFF -DWITH_JPEG=OFF -DWITH_CJSON=OFF

cmake --build "$BUILD"

# The superbuild installs FreeRDP, OpenSSL and uriparser into the upstream
# freeRDPCore jniLibs folder. Only this fixed list is copied, so libraries left
# there by an older build are never shipped. OpenSSL's legacy.so provider is
# skipped: WinPR is built with its own MD4/RC4 for NTLM.
UPSTREAM_LIBS="$SRC/client/Android/Studio/freeRDPCore/src/main/jniLibs/$ABI"
LIBS=(libcrypto.so libssl.so libfreerdp3.so libfreerdp-client3.so libwinpr3.so liburiparser.so)
mkdir -p "$OUT"
rm -f "$OUT"/*.so
for lib in "${LIBS[@]}"; do
	cp "$UPSTREAM_LIBS/$lib" "$OUT/"
done
cp "$BUILD/libfreerdp-android.so" "$OUT/"
ls -1 "$OUT"
