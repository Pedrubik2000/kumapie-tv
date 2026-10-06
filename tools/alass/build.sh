#!/usr/bin/env bash
# Builds alass-cli 2.0.0 (kaegi/alass, GPL-3.0: subtitle sync) for Android arm64 as an executable named like a library,
# mobile/src/main/jniLibs/arm64-v8a/libalass.so (run from the app's nativeLibraryDir, as libllamacli.so).
# Needs Rust with the aarch64-linux-android target and the NDK (run in WSL): ANDROID_NDK_HOME=/path/to/ndk tools/alass/build.sh
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$HERE/../../mobile/src/main/jniLibs/arm64-v8a/libalass.so
BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
TMP=$(mktemp -d)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$BIN/aarch64-linux-android26-clang CC_aarch64_linux_android=$BIN/aarch64-linux-android26-clang AR_aarch64_linux_android=$BIN/llvm-ar
cargo install -q alass-cli --version 2.0.0 --locked --target aarch64-linux-android --root "$TMP"
"$BIN/llvm-strip" -o "$OUT" "$TMP/bin/alass-cli"
echo "built alass-cli 2.0.0 -> $OUT ($(stat -c %s "$OUT") bytes)"
rm -rf "$TMP"
