#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
javac -d "$TEMP" \
  "$ROOT/android-host/app/src/main/java/pl/remotevpn/host/OvpnProfilePolicy.java" \
  "$ROOT/tests/TestOvpnProfilePolicy.java"
java -cp "$TEMP" TestOvpnProfilePolicy
