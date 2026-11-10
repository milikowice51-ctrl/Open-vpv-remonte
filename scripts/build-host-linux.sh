#!/usr/bin/env bash
# Linux / WSL2 / CI: native OpenVPN3 + Android Host APK. Requires real SDK and network.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_NDK_HOME:=${ANDROID_HOME}/ndk/27.2.12479018}"
if [[ ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
  echo "ERROR: Android SDK platform 35 not found under ANDROID_HOME=$ANDROID_HOME" >&2
  exit 10
fi
if [[ ! -d "$ANDROID_NDK_HOME/toolchains/llvm" ]]; then
  echo "ERROR: Android NDK 27.2.12479018 not found: $ANDROID_NDK_HOME" >&2
  exit 11
fi
if ! command -v gradle >/dev/null; then
  echo "ERROR: Gradle 8.9 is missing; install it or use the GitHub Actions workflow." >&2
  exit 12
fi
if [[ ! -f "$ROOT/native/openvpn3/client/ovpncli.cpp" ]]; then
  bash "$ROOT/native/setup-upstream.sh"
fi
if [[ ! -d "$ROOT/native/vcpkg/installed/arm64-android" ]]; then
  ANDROID_NDK_HOME="$ANDROID_NDK_HOME" bash "$ROOT/native/setup-deps.sh"
fi
export ANDROID_NDK_HOME VCPKG_ROOT="$ROOT/native/vcpkg"
bash "$ROOT/tests/run_policy_tests.sh"
bash "$ROOT/tests/check_native_syntax.sh"
(
  cd "$ROOT/android-host"
  gradle --no-daemon :app:assembleDebug --stacktrace
)
APK="$ROOT/android-host/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
  echo "ERROR: Gradle did not generate APK" >&2
  exit 13
fi
mkdir -p "$ROOT/dist"
cp -f "$APK" "$ROOT/dist/AndroidRemoteVPN-v0.5.1-host-arm64-debug.apk"
sha256sum "$ROOT/dist/AndroidRemoteVPN-v0.5.1-host-arm64-debug.apk"
echo "APK ready: $ROOT/dist/AndroidRemoteVPN-v0.5.1-host-arm64-debug.apk"
