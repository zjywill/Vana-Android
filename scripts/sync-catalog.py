#!/usr/bin/env python3
"""把 provider catalog 从 AIKit(aikitswift)同步进 assets。

上游从 2026-08-29 起改由 models.dev 生成(aikitswift 的 `Scripts/sync-catalog.sh`),
provider 数从 49 涨到 185、单文件几千行——全量打进 APK 是 6.5MB 的 JSON,而
`CloudCatalog.bootstrap` 启动时要把它们逐个解析一遍。所以这里在落盘前做两件事,
两件都是**机械规则**,不是手工挑名单(设置页只做选择、不维护名单,这条原则不变):

1. 只留 Android 发得出请求的:adapter ∈ {openai, anthropic, gemini} 且有可连的托管
   API(排除 localhost)。被运行时过滤器注定扔掉的文件没有理由占安装包。
2. 只留 Android 读的字段(`CloudCatalog.ProviderJson` / `ModelJson` 声明的那些):
   provider 的 id/name/api/adapter,model 的 id/name/tool_call/reasoning.supported/
   limit.context/limit.output/modalities.input。cost、description、release_date 这些
   一个字节都用不上,却占掉体积的大头。

用法:
    python3 scripts/sync-catalog.py /path/to/aikitswift
    python3 scripts/sync-catalog.py            # 临时 clone 上游 main

跑完把 assets/catalog/ 的 diff 连同本文件打出的 PROVENANCE.md 一起提交。
"""

import json
import pathlib
import subprocess
import sys
import tempfile

ADAPTERS = {"openai", "anthropic", "gemini"}
LOOPBACK = {"localhost", "127.0.0.1", "::1", "0.0.0.0"}
REPO = "https://github.com/zjywill/aikitswift.git"

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEST = ROOT / "app/src/main/assets/catalog/providers"


def hosted(api: str | None) -> bool:
    if not api:
        return False
    host = api.split("//")[-1].split("/")[0].split(":")[0].lower()
    return host not in LOOPBACK and not host.endswith(".local")


def slim_model(m: dict) -> dict:
    out = {"id": m["id"]}
    if m.get("name") is not None:
        out["name"] = m["name"]
    if m.get("tool_call") is not None:
        out["tool_call"] = m["tool_call"]
    reasoning = m.get("reasoning")
    if isinstance(reasoning, dict) and reasoning.get("supported") is not None:
        out["reasoning"] = {"supported": reasoning["supported"]}
    elif isinstance(reasoning, bool):  # 老格式里 reasoning 是布尔
        out["reasoning"] = {"supported": reasoning}
    limit = m.get("limit") or {}
    slim_limit = {k: limit[k] for k in ("context", "output") if limit.get(k) is not None}
    if slim_limit:
        out["limit"] = slim_limit
    modalities = m.get("modalities") or {}
    if modalities.get("input"):
        out["modalities"] = {"input": modalities["input"]}
    return out


def main() -> int:
    if len(sys.argv) > 1:
        src_root = pathlib.Path(sys.argv[1]).expanduser()
        cleanup = None
    else:
        cleanup = tempfile.TemporaryDirectory()
        src_root = pathlib.Path(cleanup.name)
        subprocess.run(
            ["git", "clone", "--quiet", "--depth", "1", REPO, str(src_root)],
            check=True,
        )
    src = src_root / "Sources/AIKit/Catalog/providers"
    if not src.is_dir():
        print(f"找不到 {src}", file=sys.stderr)
        return 1
    commit = subprocess.run(
        ["git", "-C", str(src_root), "rev-parse", "HEAD"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()

    kept, dropped = [], []
    for path in sorted(src.glob("*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("adapter") not in ADAPTERS or not hosted(data.get("api")):
            dropped.append(data.get("id", path.stem))
            continue
        models = data.get("models") or []
        if isinstance(models, dict):
            models = list(models.values())
        slim = {
            "id": data["id"],
            **({"name": data["name"]} if data.get("name") else {}),
            "api": data["api"],
            "adapter": data["adapter"],
            "models": [slim_model(m) for m in models],
        }
        kept.append((path.name, slim))

    if DEST.exists():
        for old in DEST.glob("*.json"):
            old.unlink()
    DEST.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, slim in kept:
        text = json.dumps(slim, ensure_ascii=False, indent=2) + "\n"
        (DEST / name).write_text(text, encoding="utf-8")
        total += len(text.encode())

    (DEST.parent / "PROVENANCE.md").write_text(
        "# provider catalog 的来历\n\n"
        f"- 来源:{REPO} @ `{commit}`(上游由 models.dev 生成)\n"
        "- 同步:`python3 scripts/sync-catalog.py`,只留 Android 实现了协议且可连的\n"
        "  托管 provider,并剥掉 `CloudCatalog` 不读的字段。**不要手工编辑这些 JSON**,\n"
        "  要改就改脚本重跑。\n"
        f"- 本次:保留 {len(kept)} 个,过滤 {len(dropped)} 个,合计 {total / 1024:.0f}KB\n",
        encoding="utf-8",
    )
    print(f"保留 {len(kept)},过滤 {len(dropped)},共 {total / 1024:.0f}KB → {DEST}")
    if cleanup:
        cleanup.cleanup()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
