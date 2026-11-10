#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
: "${ANDROID_NDK_HOME:?Ustaw ANDROID_NDK_HOME na folder Android NDK 27.2.12479018}"
if [[ ! -d "$ANDROID_NDK_HOME/toolchains/llvm" ]]; then
  echo 'ANDROID_NDK_HOME nie wskazuje na poprawny katalog NDK' >&2; exit 1
fi
if [[ ! -d "$ROOT/vcpkg/.git" ]]; then
  git clone https://github.com/microsoft/vcpkg.git "$ROOT/vcpkg"
fi
export VCPKG_ROOT="$ROOT/vcpkg"
if [[ ! -x "$VCPKG_ROOT/vcpkg" ]]; then
  "$VCPKG_ROOT/bootstrap-vcpkg.sh" -disableMetrics
fi
"$VCPKG_ROOT/vcpkg" install --triplet arm64-android asio fmt lz4 openssl xxhash jsoncpp
printf '\nWymagane przy uruchomieniu Gradle:\n  export VCPKG_ROOT="%s"\n' "$VCPKG_ROOT"
