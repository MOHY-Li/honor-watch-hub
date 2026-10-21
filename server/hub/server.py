"""零依赖 HTTP 服务（仅标准库）。

设计取舍：这个服务的唯一职责是「把变化变成通知」，需要长期跑在一台小机器/路由器/NAS 上，
所以不引入任何第三方包。sqlite3 + http.server 足够。
"""
from __future__ import annotations

import json
import os
import queue
import re
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from .broker import Broker
from .scheduler import Scheduler
from .sources import now_iso
from .store import Store

VERSION = "1.0.0"
START = time.time()
HEARTBEAT = 15


class App:
    def __init__(self, data_dir: str, token: str, config_path: str | None = None):
        self.store = Store(os.path.join(data_dir, "watchhub.db"))
        self.broker = Broker()
        self.scheduler = Scheduler(self.store, self.broker)
        self.token = token
        self.config_path = config_path
        if config_path and os.path.exists(config_path):
            self._load_yaml_ish(config_path)
        self.scheduler.load()

    def _load_yaml_ish(self, path: str) -> None:
        """极简 sources.yaml 解析：两级缩进 + 可选嵌套块。无 PyYAML 依赖。"""
        cur = None
        name = None
        nest_key = None
        nest_indent = 0
        for raw in open(path, encoding="utf-8"):
            line = raw.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            indent = len(line) - len(line.lstrip())
            t = line.strip()
            if indent == 2 and t.endswith(":"):
                if name and cur is not None:
                    self.store.upsert_source(name, cur)
                name, cur, nest_key = t[:-1].strip(), {}, None
                continue
            if cur is None or indent <= 2 or ":" not in t:
                continue
            k, v = t.split(":", 1)
            k, v = k.strip(), v.strip()
            if nest_key is not None and indent > nest_indent:
                cur.setdefault(nest_key, {})[k] = _coerce(v)
                continue
            if v == "":
                nest_key, nest_indent = k, indent
                cur[k] = {}
                continue
            nest_key = None
            cur[k] = _coerce(v)
        if name and cur is not None:
            self.store.upsert_source(name, cur)

    def health(self) -> dict:
        st = self.scheduler.status()
        ok = sum(1 for s in st if s["ok"])
        return {
            "ok": True, "version": VERSION, "uptime": int(time.time() - START),
            "now": now_iso(), "sources_total": len(st), "sources_ok": ok,
            "sse_clients": self.broker.count,
            "last_notification": self.store.last_notification_ts(),
        }


def _coerce(v: str):
    if v.lower() in ("true", "yes", "on"):
        return True
    if v.lower() in ("false", "no", "off"):
        return False
    if re.fullmatch(r"-?\d+", v):
        return int(v)
    if v.lower() in ("null", "none", "~", ""):
        return None
    if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
        return v[1:-1]
    if v.startswith("[") and v.endswith("]"):
        return [x.strip().strip("\"'") for x in v[1:-1].split(",") if x.strip()]
    return v


def make_handler(app: App):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = f"WatchHub/{VERSION}"

        # ---------- 工具 ----------
        def log_message(self, fmt, *args):
            if os.environ.get("WATCHHUB_QUIET") != "1":
                sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

        def _auth_ok(self, q: dict) -> bool:
            if not app.token:
                return True
            h = self.headers.get("Authorization", "")
            if h.startswith("Bearer ") and h[7:].strip() == app.token:
                return True
            if self.headers.get("X-Token", "") == app.token:
                return True
            return q.get("token", [None])[0] == app.token

        def _json(self, code: int, obj) -> None:
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if not n:
                return {}
            try:
                return json.loads(self.rfile.read(n) or b"{}")
            except Exception:
                return {}

        # ---------- 路由 ----------
        def do_GET(self):
            u = urlparse(self.path)
            q = parse_qs(u.query)
            p = u.path.rstrip("/") or "/"
            if p in ("/", "/health"):
                return self._json(200, app.health())
            if p == "/api/v1/health":
                return self._json(200, app.health())
            if not self._auth_ok(q):
                return self._json(401, {"error": "unauthorized"})

            if p == "/api/v1/events":
                return self._sse(q)
            if p == "/api/v1/notifications":
                limit = min(int(q.get("limit", ["50"])[0]), 200)
                before = int(q.get("before_id", ["0"])[0])
                return self._json(200, {"items": app.store.list_notifications(limit, before)})
            if p == "/api/v1/sources":
                return self._json(200, {"items": app.scheduler.status()})
            return self._json(404, {"error": "not found", "path": p})

        def do_POST(self):
            u = urlparse(self.path)
            q = parse_qs(u.query)
            p = u.path.rstrip("/") or "/"
            if not self._auth_ok(q):
                return self._json(401, {"error": "unauthorized"})
            if p == "/api/v1/notify":
                d = self._body()
                nid = app.store.add_notification({
                    "ts": now_iso(),
                    "title": d.get("title", "测试通知"),
                    "body": d.get("body", ""),
                    "detail": d.get("detail", ""),
                    "priority": d.get("priority", "default"),
                    "tags": d.get("tags", []),
                    "url": d.get("url", ""),
                    "source": d.get("source", "manual"),
                    "key": d.get("key", ""),
                })
                if nid is None:
                    return self._json(200, {"duplicate": True})
                n = app.store.list_notifications(limit=1)[0]
                app.broker.publish({"event": "notification", "id": nid, "data": n})
                return self._json(201, {"id": nid})
            m = re.fullmatch(r"/api/v1/sources/([^/]+)/check", p)
            if m:
                ok = app.scheduler.trigger(m.group(1))
                return self._json(202 if ok else 404, {"queued": ok})
            m = re.fullmatch(r"/api/v1/sources/([^/]+)", p)
            if m:
                d = self._body()
                if "enabled" in d:
                    ok = app.store.set_enabled(m.group(1), bool(d["enabled"]))
                    if not ok:
                        return self._json(404, {"error": "no such source"})
                    return self._json(200, {"name": m.group(1), "enabled": bool(d["enabled"])})
                return self._json(400, {"error": "only 'enabled' can be updated"})
            return self._json(404, {"error": "not found", "path": p})

        # ---------- SSE ----------
        def _sse(self, q: dict):
            last_id = 0
            hdr = self.headers.get("Last-Event-ID")
            raw = hdr or q.get("last_id", ["0"])[0]
            try:
                last_id = int(raw)
            except Exception:
                last_id = 0

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache, no-transform")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()

            def send(line: str) -> None:
                self.wfile.write((line + "\n").encode())
                self.wfile.flush()

            send("retry: 3000")
            send("event: hello")
            send("data: " + json.dumps(
                {"server": VERSION, "now": now_iso(), "cursor": app.store.max_id()},
                ensure_ascii=False))
            send("")

            client = app.broker.subscribe()
            try:
                # 断线补发
                for n in app.store.notifications_after(last_id):
                    send(f"id: {n['id']}")
                    send("event: notification")
                    send("data: " + json.dumps(n, ensure_ascii=False))
                    send("")
                last_ping = time.time()
                while True:
                    try:
                        item = client.get(timeout=1)
                    except queue.Empty:
                        if time.time() - last_ping >= HEARTBEAT:
                            send(": ping")
                            last_ping = time.time()
                        continue
                    send(f"id: {item['id']}")
                    send(f"event: {item['event']}")
                    send("data: " + json.dumps(item["data"], ensure_ascii=False))
                    send("")
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                app.broker.unsubscribe(client)
                self.close_connection = True

    return Handler


def serve(host: str, port: int, data_dir: str, token: str, config_path: str | None = None):
    app = App(data_dir, token, config_path)
    httpd = ThreadingHTTPServer((host, port), make_handler(app))
    httpd.daemon_threads = True
    print(f"WatchHub {VERSION} listening on http://{host}:{port}")
    print(f"  token: {'(disabled)' if not token else token[:4] + '***'}")
    print(f"  data:  {os.path.abspath(data_dir)}")
    for s in app.scheduler.status():
        print(f"  source {s['name']:<16} {s['kind']:<6} interval={s['interval']}s enabled={s['enabled']}")

    def shutdown(*_):
        print("\nshutting down…")
        app.scheduler.stop()
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    if threading.current_thread() is threading.main_thread():
        signal.signal(signal.SIGINT, shutdown)
    try:
        httpd.serve_forever()
    finally:
        app.scheduler.stop()
        app.store.close()

