#!/usr/bin/env bash
# Builds QuickJS-NG for Android arm64 into mobile/src/main/jniLibs/arm64-v8a/libqjs.so (an executable, named like a
# library so Android installs it where apps may run it). yt-dlp needs a JavaScript runtime for YouTube's signature
# challenges (mobile/src/main/python/youtube.py). Needs the Android NDK: ANDROID_NDK_HOME=/path/to/ndk tools/quickjs/build.sh
set -euo pipefail
VERSION=${1:-v0.17.0}
OUT=$(cd "$(dirname "$0")/../.." && pwd)/mobile/src/main/jniLibs/arm64-v8a/libqjs.so
TMP=$(mktemp -d)
git clone -q --depth 1 --branch "$VERSION" https://github.com/quickjs-ng/quickjs.git "$TMP/src"
cd "$TMP/src"
BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin
"$BIN/aarch64-linux-android26-clang" -O2 -D_GNU_SOURCE -I. -o qjs \
  qjs.c quickjs.c quickjs-libc.c libregexp.c libunicode.c dtoa.c gen/repl.c gen/standalone.c -lm -ldl
"$BIN/llvm-strip" qjs
cp qjs "$OUT"
echo "built QuickJS-NG $VERSION -> $OUT ($(stat -c %s "$OUT") bytes)"
