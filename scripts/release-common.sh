#!/usr/bin/env bash
# 签名发版共用检查。由 build-apk.sh / build-aab.sh source。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f keystore.properties || ! -f key/vana-release.keystore ]]; then
  echo "缺少签名文件。需要仓库根目录的 keystore.properties 和 key/vana-release.keystore。" >&2
  echo "从私有仓库拷：cp ../Vana-Android-keys/vana-release.keystore key/ && cp ../Vana-Android-keys/keystore.properties ." >&2
  exit 1
fi

version_name() {
  python3 - <<'PY'
import re
from pathlib import Path
text = Path("app/build.gradle.kts").read_text()
m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
if not m:
    raise SystemExit("app/build.gradle.kts 里找不到 versionName")
print(m.group(1))
PY
}

VERSION="$(version_name)"
export ROOT VERSION
