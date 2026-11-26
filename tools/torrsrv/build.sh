#!/bin/bash
set -e
cd "$(dirname "$0")"
export PATH=$HOME/gotool/go/bin:$PATH
ndk=$(ls -d $HOME/Android/Sdk/ndk/* | tail -1)/toolchains/llvm/prebuilt/linux-x86_64/bin
build() {
  mkdir -p ../../core/src/$1/jniLibs/$2
  CGO_ENABLED=1 GOOS=android GOARCH=$3 GOARM=7 CC=$ndk/$4 CXX=$ndk/$4++ \
    go build -tags nosqlite,noboltdb -trimpath -ldflags "-s -w -checklinkname=0" -o ../../core/src/$1/jniLibs/$2/libtorrsrv.so .
}
build main arm64-v8a arm64 aarch64-linux-android24-clang
build main armeabi-v7a arm armv7a-linux-androideabi24-clang
build debug x86 386 i686-linux-android24-clang
build debug x86_64 amd64 x86_64-linux-android24-clang
ls -l ../../core/src/*/jniLibs/*/libtorrsrv.so
