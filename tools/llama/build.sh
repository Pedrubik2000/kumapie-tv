#!/usr/bin/env bash
# Builds llama.cpp's llama-completion for Android arm64 into mobile/src/main/jniLibs/arm64-v8a/libllamacli.so (a
# program, named like a library so Android installs it where apps may run it; local/Gemma.kt runs it). Static, no
# shared libs. Needs the Android NDK, CMake and Ninja: ANDROID_NDK_HOME=/path/to/ndk tools/llama/build.sh [commit]
set -euo pipefail
COMMIT=${1:-d7a695e}   # tested 2026-10-06 with Gemma 4 E2B on a Tab S7+
OUT=$(cd "$(dirname "$0")/../.." && pwd)/mobile/src/main/jniLibs/arm64-v8a/libllamacli.so
TMP=$(mktemp -d)
git clone -q https://github.com/ggml-org/llama.cpp.git "$TMP/src" && cd "$TMP/src" && git checkout -q "$COMMIT"
cmake -G Ninja -B build -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-march=armv8.2-a+dotprod+fp16" -DCMAKE_CXX_FLAGS="-march=armv8.2-a+dotprod+fp16" \
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF
cmake --build build --target llama-completion
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" build/bin/llama-completion
cp build/bin/llama-completion "$OUT"
echo "built llama-completion $COMMIT -> $OUT ($(stat -c %s "$OUT") bytes)"
