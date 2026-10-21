"""录制真实服务端的 SSE 字节流 → 生成 Kotlin 单测用的 fixture。

这是「协议一致性」的守门员：
Python 服务端改了 SSE 格式，这里录下来的 fixture 就变，
Kotlin 的 SseParserFixtureTest 会立刻红灯。

    python server/tests/record_sse_fixture.py
"""
from __future__ import annotations

import http.client
import json
import os
import socket
import sys
import tempfile
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

OUT = os.path.join(
    os.path.dirname(ROOT), "android", "app", "src", "test", "resources", "sse_capture.txt"
)


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def main() -> None:
    from hub.server import serve

    tmp = tempfile.mkdtemp()
    token = "fixture-token"
    port = free_port()
    threading.Thread(
        target=serve, args=("127.0.0.1", port, tmp, token, None), daemon=True
    ).start()

    for _ in range(100):
        try:
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
            c.request("GET", "/health")
            if c.getresponse().status == 200:
                break
        except Exception:
            time.sleep(0.1)
    else:
        raise SystemExit("server did not start")

    # 先发一条历史通知，用于验证「连接时补发」
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    c.request("POST", "/api/v1/notify",
              body=json.dumps({"title": "历史通知", "body": "连接时补发", "key": "f:1"},
                              ensure_ascii=False).encode("utf-8"),
              headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    c.getresponse().read()

    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    conn.request("GET", f"/api/v1/events?token={token}&last_id=0")
    resp = conn.getresponse()
    assert resp.status == 200, resp.status

    raw = bytearray()

    def pump():
        while True:
            chunk = resp.fp.read(1)
            if not chunk:
                return
            raw.extend(chunk)
            text = raw.decode("utf-8", "ignore")
            # 等到最后一条通知的完整 data 行出现，避免截断在半个事件上
            if "第3话" in text and text.rstrip().endswith("") and "\n\n" in text:
                if text.count("event: notification") >= 3 and text.endswith("\n\n"):
                    return

    t = threading.Thread(target=pump, daemon=True)
    t.start()

    time.sleep(0.6)
    for i in (2, 3):
        c2 = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
        c2.request("POST", "/api/v1/notify",
                   body=json.dumps(
                       {"title": f"番剧更新 第{i}话", "body": "已更新 · bangumi",
                        "priority": "high", "tags": ["anime"], "key": f"f:{i}"},
                       ensure_ascii=False).encode("utf-8"),
                   headers={"Authorization": "Bearer " + token,
                            "Content-Type": "application/json"})
        c2.getresponse().read()
        time.sleep(0.4)

    t.join(timeout=8)
    conn.close()

    text = raw.decode("utf-8")
    # 心跳会随时间变化，去掉注释行让 fixture 稳定
    stable = "\n".join(l for l in text.split("\n") if not l.startswith(":"))
    # 最后一条事件可能被读到一半就停了；补上事件结束的空行保证 fixture 合法
    stable = stable.rstrip("\n") + "\n\n"
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(stable)

    assert "event: hello" in stable
    assert stable.count("event: notification") >= 2
    assert "补发" in stable
    print(f"wrote {OUT} ({len(stable)} bytes)")
    print("-----")
    print(stable[:400])


if __name__ == "__main__":
    main()
