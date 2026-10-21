#!/usr/bin/env python3
"""生成自更新清单 dist/latest.json。

CI 用它；本地也能用来自测（--apk 指向构建产物）。

    python scripts/make_manifest.py \
        --apk dist/WatchHub.apk \
        --tag v1.1.0 \
        --code 2 \
        --name 1.1.0 \
        --repo MOHY-Li/honor-watch-hub \
        --out dist/latest.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--tag", required=True, help="GitHub release tag，例如 v1.1.0")
    ap.add_argument("--code", required=True, type=int, help="versionCode，必须单调递增")
    ap.add_argument("--name", required=True, help="versionName，例如 1.1.0")
    ap.add_argument("--repo", required=True, help="OWNER/REPO")
    ap.add_argument("--out", default="dist/latest.json")
    ap.add_argument("--changelog", default="")
    ap.add_argument("--min-sdk", type=int, default=24)
    a = ap.parse_args()

    if not os.path.exists(a.apk):
        raise SystemExit(f"APK 不存在: {a.apk}")

    size = os.path.getsize(a.apk)
    if size < 100_000:
        raise SystemExit(f"APK 太小（{size} 字节），像是构建失败")

    manifest = {
        "versionCode": a.code,
        "versionName": a.name,
        "apkUrl": f"https://github.com/{a.repo}/releases/download/{a.tag}/WatchHub.apk",
        "sha256": sha256_of(a.apk),
        "sizeBytes": size,
        "changelog": a.changelog or f"WatchHub {a.name}",
        "minSdk": a.min_sdk,
        "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }

    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {a.out}: versionCode={a.code} sha={manifest['sha256'][:12]}… size={size}")


if __name__ == "__main__":
    main()
