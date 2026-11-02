"""Простой ограничитель частоты запросов (скользящее окно, в памяти процесса)."""

from __future__ import annotations

import time
from collections import deque


class RateLimiter:
    def __init__(self, max_keys: int = 10_000) -> None:
        self._hits: dict[str, deque[float]] = {}
        self._max_keys = max_keys

    def allow(self, key: str, limit: int, window: float) -> bool:
        now = time.monotonic()
        bucket = self._hits.get(key)
        if bucket is None:
            if len(self._hits) >= self._max_keys:
                self._evict(now, window)
            bucket = deque()
            self._hits[key] = bucket
        while bucket and now - bucket[0] > window:
            bucket.popleft()
        if len(bucket) >= limit:
            return False
        bucket.append(now)
        return True

    def _evict(self, now: float, window: float) -> None:
        for key in list(self._hits):
            bucket = self._hits[key]
            while bucket and now - bucket[0] > window:
                bucket.popleft()
            if not bucket:
                del self._hits[key]
        if len(self._hits) >= self._max_keys:
            # Крайний случай: сбрасываем всё, чтобы не расти бесконечно.
            self._hits.clear()
