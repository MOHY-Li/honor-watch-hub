"""SSE 广播：每个客户端一个 Queue，新通知 push 给所有人。"""
from __future__ import annotations

import queue
import threading
from typing import Any


class Broker:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._clients: set[queue.Queue] = set()

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=256)
        with self._lock:
            self._clients.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            self._clients.discard(q)

    def publish(self, item: Any) -> None:
        with self._lock:
            targets = list(self._clients)
        for q in targets:
            try:
                q.put_nowait(item)
            except queue.Full:
                pass  # 慢客户端丢事件，重连时会用 last_id 补发

    @property
    def count(self) -> int:
        with self._lock:
            return len(self._clients)
