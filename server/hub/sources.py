"""WatchHub 采集器：把任意「会变的东西」变成一条 Notification。

四种源类型：
  rss     - RSS/Atom，按条目去重
  json    - 任意 JSON API，取列表 + 字段映射
  regex   - 网页抓取，用正则从 HTML 里抠条目
  hash    - 整页/整文件指纹，变了就报（适合「某个页面更新了」这种无结构场景）

所有源都遵守同一个契约：check() -> list[Notification]，不抛异常（失败写 last_error）。
"""
from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from typing import Any

TZ = timezone(timedelta(hours=8))
UA = "WatchHub/1.0 (+self-hosted; notification bridge)"
TIMEOUT = 20


def now_iso() -> str:
    return datetime.now(TZ).isoformat(timespec="seconds")


def http_get(url: str, timeout: int = TIMEOUT) -> bytes:
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "application/rss+xml, application/atom+xml, application/json, text/html;q=0.8, */*;q=0.5",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def clip(s: Any, n: int) -> str:
    if s is None:
        return ""
    s = re.sub(r"<[^>]+>", "", str(s))          # 去 HTML 标签
    s = re.sub(r"\s+", " ", s).strip()
    return s if len(s) <= n else s[: n - 1] + "…"


def _fmt(template: str, row: Any) -> str:
    """把 `{a.b}` 占位符替换成 row 里的值；缺失字段替换成空串。"""
    def repl(m: re.Match) -> str:
        path = m.group(1)
        cur = row
        for part in path.split("."):
            if isinstance(cur, dict):
                cur = cur.get(part)
            else:
                return ""
            if cur is None:
                return ""
        return str(cur)
    return re.sub(r"\{([\w.]+)\}", repl, template)


def strip_ns(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


@dataclass
class Item:
    key: str
    title: str
    body: str = ""
    detail: str = ""
    url: str = ""
    priority: str = "default"
    tags: list[str] = field(default_factory=list)

    def to_notification(self, source: str) -> dict:
        return {
            "ts": now_iso(),
            "title": clip(self.title, 30),
            "body": clip(self.body, 120),
            "detail": clip(self.detail, 4000),
            "priority": self.priority if self.priority in ("low", "default", "high") else "default",
            "tags": self.tags or [source],
            "url": self.url,
            "source": source,
            "key": self.key,
        }


class Source:
    kind = "base"

    def __init__(self, name: str, cfg: dict):
        self.name = name
        self.cfg = cfg

    @property
    def interval(self) -> int:
        return int(self.cfg.get("interval", 600))

    @property
    def enabled(self) -> bool:
        return bool(self.cfg.get("enabled", True))

    def fetch(self) -> list[Item]:
        raise NotImplementedError

    @property
    def notify_on_first_run(self) -> bool:
        """首次运行是否推送已有条目。

        默认 **False**：第一次检查只「播种」去重表，不推送历史。
        否则一个 20 条的 RSS 会在第一次启动时把手表炸屏。
        需要「一启动就推最新一条」的源可以设 notify_on_first_run: true。
        """
        return bool(self.cfg.get("notify_on_first_run", False))

    def check(self) -> list[Item]:
        """返回本次新增的 Item（去重由 store 负责）。"""
        return self.fetch()


class RssSource(Source):
    kind = "rss"

    def fetch(self) -> list[Item]:
        raw = http_get(self.cfg["url"])
        root = ET.fromstring(raw)
        items: list[Item] = []
        entries = [e for e in root.iter() if strip_ns(e.tag) in ("item", "entry")]
        limit = int(self.cfg.get("limit", 20))
        for e in entries[:limit]:
            d: dict[str, str] = {}
            link = ""
            for child in e:
                t = strip_ns(child.tag)
                if t == "link":
                    link = (child.text or "").strip() or child.attrib.get("href", "")
                    continue
                if t not in d and child.text:
                    d[t] = child.text.strip()
            title = d.get("title", "") or link
            key = d.get("guid") or d.get("id") or link or title
            body = d.get("description") or d.get("summary") or d.get("content") or ""
            items.append(Item(
                key=f"{self.name}:{hashlib.sha1(key.encode()).hexdigest()[:16]}",
                title=title,
                body=body,
                detail=body,
                url=link,
                priority=self.cfg.get("priority", "default"),
                tags=self.cfg.get("tags", [self.name]),
            ))
        return items


class JsonSource(Source):
    kind = "json"

    @staticmethod
    def dig(obj: Any, path: str) -> Any:
        cur = obj
        for part in [p for p in path.split(".") if p]:
            if isinstance(cur, list):
                cur = cur[int(part)]
            elif isinstance(cur, dict):
                cur = cur.get(part)
            else:
                return None
            if cur is None:
                return None
        return cur

    def fetch(self) -> list[Item]:
        raw = http_get(self.cfg["url"])
        data = json.loads(raw)
        cfg = self.cfg
        arr = self.dig(data, cfg.get("list_path", "")) if cfg.get("list_path") else data
        if isinstance(arr, dict):
            arr = list(arr.values())
        if not isinstance(arr, list):
            raise ValueError(f"list_path {cfg.get('list_path')!r} 不是数组")
        fields = cfg.get("fields", {})
        items: list[Item] = []
        for row in arr[: int(cfg.get("limit", 20))]:
            def g(key: str, default: str = "") -> str:
                p = fields.get(key)
                v = self.dig(row, p) if p else None
                return default if v is None else str(v)
            title = g("title")
            key = g("key") or g("url") or title
            body = g("body")
            # 可选模板：用 {字段名} 组合多个字段，例如
            #   body_format: "{airdate} · 第{ep}话"
            if cfg.get("title_format"):
                title = _fmt(cfg["title_format"], row)
            if cfg.get("body_format"):
                body = _fmt(cfg["body_format"], row)
            items.append(Item(
                key=f"{self.name}:{hashlib.sha1(key.encode()).hexdigest()[:16]}",
                title=title, body=body, detail=body or title,
                url=g("url"), priority=cfg.get("priority", "default"),
                tags=cfg.get("tags", [self.name]),
            ))
        return items


class RegexSource(Source):
    kind = "regex"

    def fetch(self) -> list[Item]:
        cfg = self.cfg
        html = http_get(cfg["url"]).decode(cfg.get("encoding", "utf-8"), "replace")
        rx = re.compile(cfg["pattern"], re.S | (re.M if cfg.get("multiline") else 0))
        items: list[Item] = []
        for m in rx.finditer(html):
            gd = m.groupdict()
            title = (gd.get("title") or m.group(0)).strip()
            key = gd.get("key") or title
            items.append(Item(
                key=f"{self.name}:{hashlib.sha1(key.encode()).hexdigest()[:16]}",
                title=title, body=gd.get("body", ""), detail=gd.get("body", "") or title,
                url=gd.get("url", ""), priority=cfg.get("priority", "default"),
                tags=cfg.get("tags", [self.name]),
            ))
            if len(items) >= int(cfg.get("limit", 20)):
                break
        return items


class HashSource(Source):
    kind = "hash"

    def fetch(self) -> list[Item]:
        cfg = self.cfg
        raw = http_get(cfg["url"])
        h = hashlib.sha256(raw).hexdigest()[:16]
        marker = cfg.get("marker", "")
        return [Item(
            key=f"{self.name}:{h}",
            title=cfg.get("title", f"{self.name} 有更新"),
            body=cfg.get("body", marker or "页面内容发生变化"),
            detail=cfg.get("detail", ""),
            url=cfg["url"], priority=cfg.get("priority", "default"),
            tags=cfg.get("tags", [self.name]),
        )]


class CronSource(Source):
    """定时提醒：检查「现在是不是该响」。调度器每 30s 调一次。

    cfg:
      times: ["08:00", "22:30"]     # 24h HH:MM，可多个
      days:  [1,2,3,4,5]            # 0=周一 … 6=周日；缺省=每天
      title: "起床"
      body:  "记得喝水"
      priority: high
      once: false                   # true = 每个 times 每天只响一次（默认就是一次）
    key 用 名字+日期+时刻 生成，所以同一分钟内的重复检查会自动去重。
    """
    kind = "cron"

    @property
    def notify_on_first_run(self) -> bool:
        # 定时提醒没有「历史」概念：命中了现在就该响，不能被首轮播种吞掉
        return True

    def _matches_now(self, now) -> list[str]:
        times = self.cfg.get("times") or []
        if isinstance(times, str):
            times = [times]
        days = self.cfg.get("days")
        if days is not None:
            if isinstance(days, int):
                days = [days]
            if now.weekday() not in [int(d) for d in days]:
                return []
        hm = now.strftime("%H:%M")
        return [t for t in times if str(t).strip()[:5] == hm]

    def fetch(self) -> list[Item]:
        from datetime import datetime
        now = datetime.now(TZ)
        hits = self._matches_now(now)
        if not hits:
            return []
        stamp = now.strftime("%Y%m%d%H%M")
        return [Item(
            key=f"{self.name}:{stamp}",
            title=self.cfg.get("title", self.name),
            body=self.cfg.get("body", ""),
            detail=self.cfg.get("detail", ""),
            url=self.cfg.get("url", ""),
            priority=self.cfg.get("priority", "high"),
            tags=self.cfg.get("tags", ["reminder"]),
        ) for _ in hits]


KINDS = {"rss": RssSource, "json": JsonSource, "regex": RegexSource, "hash": HashSource, "cron": CronSource}



def build(name: str, cfg: dict) -> Source:
    kind = cfg.get("kind", "rss")
    if kind not in KINDS:
        raise ValueError(f"未知 kind={kind!r}，可选 {list(KINDS)}")
    return KINDS[kind](name, cfg)
