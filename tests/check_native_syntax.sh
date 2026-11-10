#!/usr/bin/env bash
# This checks JNI syntax ONLY, against local signature stubs, not OpenVPN3 itself.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}" 
g++ -std=c++20 -fsyntax-only -Wall -Wextra \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/linux" -I "$ROOT/tests/stubs" \
  "$ROOT/android-host/app/src/main/cpp/native_openvpn.cpp"
echo "JNI bridge: C++ syntax check passed (OpenVPN3 API linking not tested)"
