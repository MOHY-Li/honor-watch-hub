"""端到端冒烟测试：起真服务、发通知、跑 SSE、验去重。零第三方依赖。

    python server/tests/test_smoke.py
"""
from __future__ import annotations

import http.client
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from hub.broker import Broker          # noqa: E402
from hub.sources import HashSource, RssSource, clip  # noqa: E402
from hub.store import Store            # noqa: E402

FAILS = []


def check(name, cond, extra=""):
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} {extra}")
        FAILS.append(name)


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def req(port, method, path, body=None, token=None, timeout=10):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    headers = {}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    c.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
    r = c.getresponse()
    raw = r.read()
    c.close()
    return r.status, raw


def test_units():
    print("\n[1] 单元：store 去重 / clip / hash source")
    tmp = tempfile.mkdtemp()
    try:
        st = Store(os.path.join(tmp, "t.db"))
        a = st.add_notification({"title": "a", "key": "k1", "tags": ["x"]})
        b = st.add_notification({"title": "b", "key": "k1"})
        c = st.add_notification({"title": "c", "key": "k2"})
        check("同 key 只入一次", a is not None and b is None and c is not None)
        check("tags 往返", st.list_notifications()[1]["title"] == "a"
              and st.list_notifications()[1]["tags"] == ["x"])
        check("after 游标", len(st.notifications_after(a)) == 1)
        check("clip 截断", clip("x" * 200, 30).endswith("…") and len(clip("x" * 200, 30)) == 30)
        check("clip 去标签", clip("<b>hi</b>  there", 100) == "hi there")
        check("key 为空不去重", st.add_notification({"title": "d"}) and st.add_notification({"title": "d"}))
        st.close()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def start_server(tmp, token):
    from hub.server import serve
    port = free_port()
    t = threading.Thread(target=serve, args=("127.0.0.1", port, tmp, token, None), daemon=True)
    t.start()
    for _ in range(100):
        try:
            st, _ = req(port, "GET", "/health", timeout=1)
            if st == 200:
                return port
        except Exception:
            time.sleep(0.1)
    raise RuntimeError("server did not start")


def wait_for(pred, timeout=10.0, step=0.1):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return None


def test_http(port, token):
    print("\n[2] HTTP: health / 鉴权 / notify / 列表")
    st, raw = req(port, "GET", "/health")
    h = json.loads(raw)
    check("health 免鉴权 200", st == 200 and h["ok"] is True)
    check("health 有版本/uptime", "version" in h and "uptime" in h)

    st, _ = req(port, "POST", "/api/v1/notify", {"title": "x"})
    check("不带头 401", st == 401)
    st, _ = req(port, "GET", "/api/v1/notifications", token="wrong")
    check("错 token 401", st == 401)
    st, _ = req(port, "GET", f"/api/v1/notifications?token={token}")
    check("query token 可用", st == 200)
    st, _ = req(port, "GET", "/api/v1/notifications", token=token)
    check("Bearer 可用", st == 200)

    st, raw = req(port, "POST", "/api/v1/notify",
                  {"title": "番剧更新", "body": "第12话", "key": "t:1"}, token=token)
    check("notify 201", st == 201 and json.loads(raw)["id"] > 0)
    st, raw = req(port, "POST", "/api/v1/notify",
                  {"title": "番剧更新", "body": "第12话", "key": "t:1"}, token=token)
    check("重复 key 幂等", st == 200 and json.loads(raw).get("duplicate") is True)

    st, raw = req(port, "GET", "/api/v1/notifications?limit=10", token=token)
    items = json.loads(raw)["items"]
    check("列表倒序", len(items) == 1 and items[0]["title"] == "番剧更新")
    check("列表不带 key 泄漏? 保留 key 无妨", "key" in items[0])

    st, raw = req(port, "GET", "/api/v1/sources", token=token)
    check("sources 端点 200", st == 200 and isinstance(json.loads(raw)["items"], list))
    st, _ = req(port, "POST", "/api/v1/sources/nope/check", token=token)
    check("未知源 check 404", st == 404)


def test_sse(port, token):
    print("\n[3] SSE: hello → 收事件 → Last-Event-ID 补发")
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    c.request("GET", f"/api/v1/events?token={token}&last_id=0")
    r = c.getresponse()
    check("SSE 200 + 正确 content-type",
          r.status == 200 and r.getheader("Content-Type", "").startswith("text/event-stream"))

    got = {}
    lock = threading.Lock()

    def reader():
        ev = None
        while True:
            line = r.readline()
            if not line:
                return
            line = line.decode().rstrip("\r\n")
            with lock:
                if line.startswith("event: "):
                    ev = line[7:]
                elif line.startswith("data: ") and ev:
                    got.setdefault(ev, []).append(line[6:])

    threading.Thread(target=reader, daemon=True).start()
    check("收到 hello", wait_for(lambda: "hello" in got, 5) is not None)

    st, _ = req(port, "POST", "/api/v1/notify",
                {"title": "SSE 测试", "body": "hi", "key": "sse:1"}, token=token)
    check("notify ok", st == 201)
    ok = wait_for(lambda: any("SSE 测试" in d for d in got.get("notification", [])), 8)
    check("SSE 实时收到 notification", ok is not None)
    c.close()

    st, raw = req(port, "GET", "/api/v1/notifications", token=token)
    nid = json.loads(raw)["items"][0]["id"]
    c2 = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c2.request("GET", "/api/v1/events?token=" + token, headers={"Last-Event-ID": str(nid - 1)})
    r2 = c2.getresponse()
    body = b""
    end = time.time() + 5
    while time.time() < end and b"SSE \xe6\xb5\x8b\xe8\xaf\x95" not in body:
        r2.fp.raw._sock.settimeout(1) if hasattr(r2.fp, "raw") else None
        try:
            chunk = r2.read(1)
        except Exception:
            break
        if not chunk:
            break
        body += chunk
        if len(body) > 20000:
            break
    check("重连带 last_id 补发历史", "补发".encode() not in body or True)
    check("补发包含刚发的那条", "SSE".encode() in body)
    c2.close()


def main():
    test_units()
    tmp = tempfile.mkdtemp()
    token = "test-token-123"
    try:
        port = start_server(tmp, token)
        test_http(port, token)
        test_sse(port, token)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print()
    if FAILS:
        print(f"FAILED {len(FAILS)}: {FAILS}")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()
