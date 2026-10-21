"""SQLite 存储层。单文件、无外部依赖、进程内加锁。"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from typing import Any, Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS notifications (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    ts       TEXT NOT NULL,
    title    TEXT NOT NULL,
    body     TEXT NOT NULL DEFAULT '',
    detail   TEXT NOT NULL DEFAULT '',
    priority TEXT NOT NULL DEFAULT 'default',
    tags     TEXT NOT NULL DEFAULT '[]',
    url      TEXT NOT NULL DEFAULT '',
    source   TEXT NOT NULL DEFAULT '',
    key      TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_notif_key ON notifications(key);
CREATE INDEX IF NOT EXISTS idx_notif_ts  ON notifications(ts);

CREATE TABLE IF NOT EXISTS seen (
    key TEXT PRIMARY KEY,
    ts  REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS sources (
    name   TEXT PRIMARY KEY,
    config TEXT NOT NULL,
    state  TEXT NOT NULL DEFAULT '{}'
);
"""


class Store:
    def __init__(self, path: str):
        self.path = path
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        self._lock = threading.RLock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._lock:
            self._db.executescript(SCHEMA)
            self._db.commit()

    # ---------- 通知 ----------
    def add_notification(self, n: dict) -> int | None:
        """按 key 去重；重复返回 None。"""
        with self._lock:
            if n.get("key"):
                row = self._db.execute("SELECT 1 FROM seen WHERE key=?", (n["key"],)).fetchone()
                if row:
                    return None
            cur = self._db.execute(
                "INSERT INTO notifications (ts,title,body,detail,priority,tags,url,source,key)"
                " VALUES (?,?,?,?,?,?,?,?,?)",
                (
                    n.get("ts") or _now(),
                    n.get("title", "")[:200],
                    (n.get("body") or "")[:500],
                    (n.get("detail") or "")[:8000],
                    n.get("priority", "default"),
                    json.dumps(n.get("tags") or [], ensure_ascii=False),
                    (n.get("url") or "")[:2000],
                    (n.get("source") or "")[:64],
                    (n.get("key") or "")[:256],
                ),
            )
            if n.get("key"):
                self._db.execute("INSERT OR REPLACE INTO seen (key, ts) VALUES (?,?)",
                                 (n["key"], time.time()))
            self._db.commit()
            return int(cur.lastrowid)

    def list_notifications(self, limit: int = 50, before_id: int = 0) -> list[dict]:
        with self._lock:
            if before_id:
                rows = self._db.execute(
                    "SELECT * FROM notifications WHERE id < ? ORDER BY id DESC LIMIT ?",
                    (before_id, limit)).fetchall()
            else:
                rows = self._db.execute(
                    "SELECT * FROM notifications ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [_row_to_notif(r) for r in rows]

    def notifications_after(self, last_id: int, limit: int = 200) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM notifications WHERE id > ? ORDER BY id ASC LIMIT ?",
                (last_id, limit)).fetchall()
        return [_row_to_notif(r) for r in rows]

    def max_id(self) -> int:
        with self._lock:
            r = self._db.execute("SELECT COALESCE(MAX(id),0) AS m FROM notifications").fetchone()
        return int(r["m"])

    def last_notification_ts(self) -> str | None:
        with self._lock:
            r = self._db.execute("SELECT ts FROM notifications ORDER BY id DESC LIMIT 1").fetchone()
        return r["ts"] if r else None

    # ---------- 源 ----------
    def upsert_source(self, name: str, config: dict, state: dict | None = None) -> None:
        with self._lock:
            self._db.execute(
                "INSERT INTO sources (name, config, state) VALUES (?,?,?)"
                " ON CONFLICT(name) DO UPDATE SET config=excluded.config"
                + (", state=excluded.state" if state is not None else ""),
                (name, json.dumps(config, ensure_ascii=False),
                 json.dumps(state or {}, ensure_ascii=False)),
            )
            self._db.commit()

    def sources(self) -> list[tuple[str, dict, dict]]:
        with self._lock:
            rows = self._db.execute("SELECT name, config, state FROM sources ORDER BY name").fetchall()
        return [(r["name"], json.loads(r["config"]), json.loads(r["state"])) for r in rows]

    def update_state(self, name: str, state: dict) -> None:
        with self._lock:
            self._db.execute("UPDATE sources SET state=? WHERE name=?",
                             (json.dumps(state, ensure_ascii=False), name))
            self._db.commit()

    def set_enabled(self, name: str, enabled: bool) -> bool:
        with self._lock:
            row = self._db.execute("SELECT config FROM sources WHERE name=?", (name,)).fetchone()
            if not row:
                return False
            cfg = json.loads(row["config"])
            cfg["enabled"] = enabled
            self._db.execute("UPDATE sources SET config=? WHERE name=?",
                             (json.dumps(cfg, ensure_ascii=False), name))
            self._db.commit()
        return True

    def prune(self, keep_ids: int = 2000) -> None:
        with self._lock:
            cutoff = self._db.execute(
                "SELECT id FROM notifications ORDER BY id DESC LIMIT 1 OFFSET ?",
                (keep_ids,)).fetchone()
            if cutoff:
                self._db.execute("DELETE FROM notifications WHERE id <= ?", (cutoff["id"],))
                self._db.commit()

    def close(self) -> None:
        with self._lock:
            self._db.close()


def _now() -> str:
    from datetime import datetime, timezone, timedelta
    return datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds")


def _row_to_notif(r: Any) -> dict:
    try:
        tags = json.loads(r["tags"])
    except Exception:
        tags = []
    return {
        "id": r["id"], "ts": r["ts"], "title": r["title"], "body": r["body"],
        "detail": r["detail"], "priority": r["priority"], "tags": tags,
        "url": r["url"], "source": r["source"], "key": r["key"],
    }
