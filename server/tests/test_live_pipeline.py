"""真实链路集成测试：本地起一个会变的 RSS 站点 → WatchHub 轮询 → SSE 收到通知。

覆盖了除 Android 通知栏以外的全部环节：
    RSS 变化 → 解析 → 去重 → 入库 → broker 广播 → SSE 字节流

    python server/tests/test_live_pipeline.py
"""
from __future__ import annotations

import http.client
import http.server
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

STATE = {"episode": 1, "hits": 0}


class FeedHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/feed.xml":
            STATE["hits"] += 1
            # 第二次之后才出现新一集
            ep = STATE["episode"]
            items = "".join(
                f"""<item><title>番剧 第{i}话</title><link>http://x/{i}</link>
                     <guid>ep-{i}</guid><description>第{i}话已更新</description></item>"""
                for i in range(1, ep + 1)
            )
            body = f"""<?xml version="1.0"?><rss version="2.0"><channel>
                <title>test</title>{items}</channel></rss>""".encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/rss+xml")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait(pred, timeout=25.0, step=0.2):
    end = time.time() + timeout
    while time.time() < end:
        v = pred()
        if v:
            return v
        time.sleep(step)
    return None


def main():
    fails = []

    def check(name, cond, extra=""):
        print(("  PASS  " if cond else "  FAIL  ") + name + ("" if cond else f"  {extra}"))
        if not cond:
            fails.append(name)

    feed_port = free_port()
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", feed_port), FeedHandler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    tmp = tempfile.mkdtemp()
    # 往 store 里写一个只轮询本地的 rss 源，interval 用最小值 30s 太慢，
    # 所以直接调 scheduler.trigger 手动触发第一轮，第二轮再 trigger。
    from hub.server import App, make_handler
    from http.server import ThreadingHTTPServer

    app = App(tmp, "live-token", None)
    app.store.upsert_source("local_feed", {
        "kind": "rss",
        "url": f"http://127.0.0.1:{feed_port}/feed.xml",
        "interval": 600,
        "limit": 10,
        "priority": "high",
        "tags": ["anime"],
    })
    app.scheduler.register("local_feed", {
        "kind": "rss",
        "url": f"http://127.0.0.1:{feed_port}/feed.xml",
        "interval": 600,
        "limit": 10,
        "priority": "high",
        "tags": ["anime"],
    })

    port = free_port()
    srv = ThreadingHTTPServer(("127.0.0.1", port), make_handler(app))
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    print("[1] 首轮抓取（1 集）")
    app.scheduler.trigger("local_feed")
    got = wait(lambda: len(app.store.list_notifications()) >= 1)
    check("解析出 1 条通知", bool(got), f"hits={STATE['hits']}")
    first = app.store.list_notifications()
    check("标题来自 RSS", first[0]["title"] == "番剧 第1话", first[0]["title"])
    check("priority 继承配置", first[0]["priority"] == "high")
    check("tag 来自配置", first[0]["tags"] == ["anime"])

    print("[2] 第二轮抓取（内容不变 → 不应重复）")
    app.scheduler.trigger("local_feed")
    time.sleep(2)
    check("去重生效，仍为 1 条", len(app.store.list_notifications()) == 1,
          str(len(app.store.list_notifications())))

    print("[3] 第三轮（站点新增第 2 集 → 只推新的那条）")
    STATE["episode"] = 2
    app.scheduler.trigger("local_feed")
    got = wait(lambda: len(app.store.list_notifications()) >= 2, timeout=15)
    check("新增 1 条", bool(got))
    items = app.store.list_notifications(limit=5)
    check("总数恰好 2", len(items) == 2, str([i["title"] for i in items]))
    check("最新一条是第 2 话", items[0]["title"] == "番剧 第2话", items[0]["title"])

    print("[4] SSE 断线补发：从 last_id=0 重连应拿到 2 条")
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    conn.request("GET", "/api/v1/events?token=live-token&last_id=0")
    resp = conn.getresponse()
    # SSE 是长连接：逐行读，直到收到 2 个完整事件块（以空行结束）
    lines = []
    deadline = time.time() + 10
    blocks = 0
    while time.time() < deadline:
        try:
            line = resp.readline()
        except Exception:
            break
        if not line:
            break
        decoded = line.decode("utf-8", "ignore")
        lines.append(decoded)
        if decoded.strip() == "":
            if any(l.startswith("event: notification") for l in lines):
                blocks += 1
                if blocks >= 2:
                    break
    text = "".join(lines)
    check("SSE 补发 2 条", text.count("event: notification") >= 2, str(text.count("event: notification")))
    check("补发内容含第2话", "第2话" in text, text[-120:])
    conn.close()

    print("[5] 源状态可见")
    st = app.scheduler.status()
    check("status 有 last_ok", any(s["last_ok"] for s in st))
    check("status 无错误", all(s["last_error"] is None for s in st), str(st))

    srv.shutdown()
    httpd.shutdown()
    shutil.rmtree(tmp, ignore_errors=True)

    print()
    if fails:
        print(f"FAILED {len(fails)}: {fails}")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()

