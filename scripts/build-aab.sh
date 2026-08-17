#!/usr/bin/env bash
# Play Console 用的签名 Android App Bundle。侧载请用 scripts/build-apk.sh。
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-common.sh"

./gradlew :app:bundleRelease

SRC="$ROOT/app/build/outputs/bundle/release/app-release.aab"
OUT="/tmp/Vana-${VERSION}.aab"
cp "$SRC" "$OUT"

if command -v jarsigner >/dev/null 2>&1; then
  jarsigner -verify "$OUT" >/dev/null
fi

echo "AAB: $SRC"
echo "Copy: $OUT"
echo "把这个文件上传到 Play Console（内部测试 / 正式版）。"
