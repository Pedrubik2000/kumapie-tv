#!/usr/bin/env bash
# Builds hoshidicts (bee-san's fork, GPL-3.0) with kumapie's JNI layer for Android into
# lang/src/main/jniLibs/<abi>/libhoshidicts_jni.so (Yomitan dictionaries: lang/.../Hoshidicts.kt); ABI=arm64-v8a (phones,
# the default) or armeabi-v7a (32-bit TVs).
# Needs the Android NDK and CMake >= 3.22 (run in WSL): ANDROID_NDK_HOME=/path/to/ndk [ABI=...] tools/hoshidicts/build.sh [commit]
set -euo pipefail
COMMIT=${1:-7ae305f}
HERE=$(cd "$(dirname "$0")" && pwd)
ABI=${ABI:-arm64-v8a}
OUT=$HERE/../../lang/src/main/jniLibs/$ABI/libhoshidicts_jni.so
mkdir -p "$(dirname "$OUT")"
TMP=$(mktemp -d)
git clone -q https://github.com/bee-san/hoshidicts.git "$TMP/src"
git -C "$TMP/src" checkout -q "$COMMIT"
git -C "$TMP/src" submodule update -q --init --recursive
cmake -S "$HERE/jni" -B "$TMP/build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DHOSHIDICTS_SRC="$TMP/src" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI \
  -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static > "$TMP/cmake.log"
cmake --build "$TMP/build" -j"$(nproc)" > "$TMP/build.log"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT" "$TMP/build/libhoshidicts_jni.so"
echo "built hoshidicts $COMMIT -> $OUT ($(stat -c %s "$OUT") bytes)"
rm -rf "$TMP"
