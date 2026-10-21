"""入口：python -m hub.main --port 8787 --data ./data --token SECRET --config sources.yaml"""
from __future__ import annotations

import argparse
import os
import secrets
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from hub.server import VERSION, serve  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser("watchhub")
    ap.add_argument("--host", default=os.environ.get("WATCHHUB_HOST", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("WATCHHUB_PORT", "8787")))
    ap.add_argument("--data", default=os.environ.get("WATCHHUB_DATA", "./data"))
    ap.add_argument("--token", default=os.environ.get("WATCHHUB_TOKEN", ""))
    ap.add_argument("--config", default=os.environ.get("WATCHHUB_CONFIG", "sources.yaml"))
    ap.add_argument("--print-token", action="store_true", help="生成并打印一个随机 token 后退出")
    a = ap.parse_args()

    if a.print_token:
        print(secrets.token_urlsafe(24))
        return

    token = a.token
    if not token:
        tf = os.path.join(a.data, "token.txt")
        if os.path.exists(tf):
            token = open(tf, encoding="utf-8").read().strip()
        else:
            os.makedirs(a.data, exist_ok=True)
            token = secrets.token_urlsafe(24)
            open(tf, "w", encoding="utf-8").write(token)
    serve(a.host, a.port, a.data, token, a.config if os.path.exists(a.config) else None)


if __name__ == "__main__":
    main()
