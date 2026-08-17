#!/usr/bin/env bash
# GitHub / 侧载用的签名 APK。Play 上传请用 scripts/build-aab.sh。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-common.sh"

./gradlew :app:assembleRelease

SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
OUT="/tmp/Vana-${VERSION}.apk"
cp "$SRC" "$OUT"

if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/build-tools/36.1.0/apksigner" ]]; then
  "$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --print-certs "$OUT"
elif command -v apksigner >/dev/null 2>&1; then
  apksigner verify --print-certs "$OUT"
fi

echo "APK: $SRC"
echo "Copy: $OUT"
