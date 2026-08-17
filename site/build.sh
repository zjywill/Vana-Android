#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/site"

rm -rf "$OUT"
mkdir -p "$OUT/privacy" "$OUT/support"
cp "$ROOT/app/src/main/assets/PrivacyPolicy.html" "$OUT/privacy/index.html"
cp "$ROOT/site/support/index.html" "$OUT/support/index.html"

echo "Site: $OUT"
