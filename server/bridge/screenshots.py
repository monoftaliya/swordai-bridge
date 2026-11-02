"""Временные ссылки на скриншоты. Хранятся только в памяти и быстро истекают."""

from __future__ import annotations

import base64
import secrets
import time
from collections import OrderedDict
from dataclasses import dataclass


@dataclass
class Shot:
    data: bytes
    mime: str
    expires_at: float


class ScreenshotStore:
    def __init__(self, ttl: int, max_items: int = 20) -> None:
        self._ttl = ttl
        self._max = max_items
        self._items: OrderedDict[str, Shot] = OrderedDict()

    def put(self, data_b64: str, mime: str) -> str:
        self.purge()
        token = secrets.token_urlsafe(24)
        self._items[token] = Shot(base64.b64decode(data_b64), mime, time.time() + self._ttl)
        while len(self._items) > self._max:
            self._items.popitem(last=False)
        return token

    def get(self, token: str) -> Shot | None:
        shot = self._items.get(token)
        if shot is None or shot.expires_at < time.time():
            self._items.pop(token, None)
            return None
        return shot

    def purge(self) -> None:
        now = time.time()
        for token in [t for t, s in self._items.items() if s.expires_at < now]:
            del self._items[token]
