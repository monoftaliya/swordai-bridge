"""Минимальный клиент WebSocket поверх ASGI для тестов в одном цикле событий."""

from __future__ import annotations

import asyncio
import json
from typing import Any


class WebSocketRejected(Exception):
    def __init__(self, code: int | None) -> None:
        super().__init__(f"websocket rejected with code {code}")
        self.code = code


class WebSocketClosed(Exception):
    def __init__(self, code: int | None, reason: str = "") -> None:
        super().__init__(f"websocket closed with code {code} {reason}")
        self.code = code


class AsgiWebSocket:
    def __init__(self, app: Any, path: str, headers: dict[str, str] | None = None, client_ip: str = "127.0.0.1") -> None:
        self.app = app
        self.path = path
        self.headers = headers or {}
        self.client_ip = client_ip
        self._to_app: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._from_app: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._task: asyncio.Task[None] | None = None
        self.closed_code: int | None = None

    async def connect(self) -> None:
        scope = {
            "type": "websocket",
            "asgi": {"version": "3.0", "spec_version": "2.3"},
            "http_version": "1.1",
            "scheme": "ws",
            "server": ("localhost", 8000),
            "client": (self.client_ip, 50000),
            "root_path": "",
            "path": self.path,
            "raw_path": self.path.encode(),
            "query_string": b"",
            "headers": [(b"host", b"localhost:8000")]
            + [(k.lower().encode(), v.encode()) for k, v in self.headers.items()],
            "subprotocols": [],
            "state": {},
        }
        self._task = asyncio.create_task(self.app(scope, self._to_app.get, self._from_app.put))
        await self._to_app.put({"type": "websocket.connect"})
        message = await asyncio.wait_for(self._from_app.get(), 5)
        if message["type"] == "websocket.close":
            await self._finish()
            raise WebSocketRejected(message.get("code"))
        if message["type"] != "websocket.accept":
            raise AssertionError(f"unexpected {message}")

    async def send_json(self, value: Any) -> None:
        await self._to_app.put({"type": "websocket.receive", "text": json.dumps(value, ensure_ascii=False)})

    async def send_text(self, text: str) -> None:
        await self._to_app.put({"type": "websocket.receive", "text": text})

    async def receive_json(self, timeout: float = 5.0) -> dict[str, Any]:
        message = await asyncio.wait_for(self._from_app.get(), timeout)
        if message["type"] == "websocket.close":
            self.closed_code = message.get("code")
            raise WebSocketClosed(message.get("code"), message.get("reason", ""))
        assert message["type"] == "websocket.send", message
        return json.loads(message["text"])

    async def close(self) -> None:
        await self._to_app.put({"type": "websocket.disconnect", "code": 1000})
        await self._finish()

    async def _finish(self) -> None:
        if self._task is not None:
            try:
                await asyncio.wait_for(self._task, 5)
            except (TimeoutError, asyncio.CancelledError):
                self._task.cancel()
