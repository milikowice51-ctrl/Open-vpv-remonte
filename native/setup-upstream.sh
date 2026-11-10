#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REF="${OPENVPN3_REF:-master}"
if [[ ! -e "$ROOT/openvpn3/.git" ]]; then
  git clone https://github.com/OpenVPN/openvpn3.git "$ROOT/openvpn3"
fi
git -C "$ROOT/openvpn3" fetch --tags origin
git -C "$ROOT/openvpn3" checkout "$REF"
git -C "$ROOT/openvpn3" rev-parse HEAD | tee "$ROOT/OPENVPN3_COMMIT_USED.txt"
echo 'Przejrzyj zmiany i zamroz commit przed publikacją APK.'
