"""调度器：每个源一个线程，按 interval 轮询；支持立即触发。"""
from __future__ import annotations

import threading
import traceback
from datetime import datetime, timedelta

from .broker import Broker
from .sources import Source, build, now_iso
from .store import Store

TZ_OFFSET_SECONDS = 8 * 3600


class Scheduler:
    def __init__(self, store: Store, broker: Broker) -> None:
        self.store = store
        self.broker = broker
        self._sources: dict[str, Source] = {}
        self._state: dict[str, dict] = {}
        self._wake: dict[str, threading.Event] = {}
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._threads: dict[str, threading.Thread] = {}
        self.on_new = None  # callback(notif_dict, id)

    # ---------- 配置 ----------
    def load(self) -> None:
        for name, cfg, state in self.store.sources():
            self.register(name, cfg, state)

    def register(self, name: str, cfg: dict, state: dict | None = None) -> None:
        with self._lock:
            self._sources[name] = build(name, cfg)
            self._state[name] = dict(state or {})
            self._wake.setdefault(name, threading.Event())
            if name not in self._threads or not self._threads[name].is_alive():
                t = threading.Thread(target=self._run, args=(name,), name=f"src-{name}", daemon=True)
                self._threads[name] = t
                t.start()

    def get(self, name: str) -> Source | None:
        return self._sources.get(name)

    def trigger(self, name: str) -> bool:
        ev = self._wake.get(name)
        if not ev:
            return False
        ev.set()
        return True

    def status(self) -> list[dict]:
        out = []
        with self._lock:
            items = list(self._sources.items())
        for name, src in items:
            st = self._state.get(name, {})
            out.append({
                "name": name,
                "kind": src.kind,
                "enabled": src.enabled,
                "interval": src.interval,
                "last_check": st.get("last_check"),
                "last_ok": st.get("last_ok"),
                "last_error": st.get("last_error"),
                "ok": st.get("last_error") is None and st.get("last_ok") is not None,
                "items_seen": st.get("items_seen", 0),
                "new_last_run": st.get("new_last_run", 0),
                "next_check": st.get("next_check"),
                "url": src.cfg.get("url", ""),
            })
        return sorted(out, key=lambda x: x["name"])

    def stop(self) -> None:
        self._stop.set()
        for ev in self._wake.values():
            ev.set()

    # ---------- 主循环 ----------
    def _run(self, name: str) -> None:
        ev = self._wake[name]
        while not self._stop.is_set():
            src = self._sources.get(name)
            if src is None:
                return
            # cron 源必须高频轮询才能命中整分钟
            interval = 30 if src.kind == "cron" else max(30, src.interval)
            if src.enabled:
                self._check_once(name)
            nxt = datetime.now().astimezone() + timedelta(seconds=interval)
            self._state.setdefault(name, {})["next_check"] = nxt.isoformat(timespec="seconds")
            self._persist(name)
            ev.wait(timeout=interval)
            ev.clear()

    def _check_once(self, name: str) -> None:
        src = self._sources.get(name)
        if src is None:
            return
        st = self._state.setdefault(name, {})
        st["last_check"] = now_iso()
        new_count = 0
        try:
            items = src.check()
            st["last_ok"] = now_iso()
            st["last_error"] = None
            st["items_seen"] = st.get("items_seen", 0) + len(items)
            for item in items:
                nid = self.store.add_notification(item.to_notification(name))
                if nid is None:
                    continue
                new_count += 1
                n = self.store.list_notifications(limit=1)[0]
                self.broker.publish({"event": "notification", "id": nid, "data": n})
                if self.on_new:
                    try:
                        self.on_new(n, nid)
                    except Exception:
                        traceback.print_exc()
        except Exception as e:
            st["last_error"] = f"{type(e).__name__}: {e}"[:300]
            print(f"[{name}] check failed: {st['last_error']}")
        st["new_last_run"] = new_count
        self._persist(name)

    def _persist(self, name: str) -> None:
        try:
            self.store.update_state(name, self._state.get(name, {}))
        except Exception:
            pass
