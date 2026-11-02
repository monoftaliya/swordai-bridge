"""Реестр живых WebSocket-соединений устройств."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Awaitable, Callable
from typing import Any

log = logging.getLogger("bridge.hub")

SendText = Callable[[str], Awaitable[None]]
Close = Callable[[int, str], Awaitable[None]]


class DeviceConnection:
    def __init__(self, device_id: str, session_id: str, send_text: SendText, close: Close, ip: str | None) -> None:
        self.device_id = device_id
        self.session_id = session_id
        self._send_text = send_text
        self._close = close
        self.ip = ip
        self.connected_at = time.time()
        self.last_message_at = self.connected_at
        self.hello: dict[str, Any] | None = None
        self.state: dict[str, Any] = {}
        self._lock = asyncio.Lock()
        self.closed = False

    async def send(self, message: dict[str, Any]) -> bool:
        if self.closed:
            return False
        data = json.dumps(message, ensure_ascii=False, separators=(",", ":"))
        async with self._lock:
            try:
                await self._send_text(data)
                return True
            except Exception as exc:  # noqa: BLE001 - соединение могло оборваться
                log.info("send to %s failed: %s", self.device_id, exc)
                self.closed = True
                return False

    async def close(self, code: int, reason: str) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            await self._close(code, reason)
        except Exception:  # noqa: BLE001
            pass


class Hub:
    def __init__(self) -> None:
        self._connections: dict[str, DeviceConnection] = {}
        self._waiters: dict[str, list[asyncio.Event]] = {}
        self.on_online: Callable[[str], Awaitable[None]] | None = None

    def get(self, device_id: str) -> DeviceConnection | None:
        conn = self._connections.get(device_id)
        return conn if conn is not None and not conn.closed else None

    def is_online(self, device_id: str) -> bool:
        return self.get(device_id) is not None

    def online_ids(self) -> list[str]:
        return [device_id for device_id, conn in self._connections.items() if not conn.closed]

    async def attach(self, conn: DeviceConnection) -> None:
        previous = self._connections.get(conn.device_id)
        self._connections[conn.device_id] = conn
        if previous is not None and previous is not conn:
            await previous.close(4000, "replaced by a newer connection")
        for event in self._waiters.pop(conn.device_id, []):
            event.set()

    async def announce_online(self, device_id: str) -> None:
        if self.on_online is not None:
            await self.on_online(device_id)

    def detach(self, conn: DeviceConnection) -> bool:
        conn.closed = True
        if self._connections.get(conn.device_id) is conn:
            del self._connections[conn.device_id]
            return True
        return False

    async def wait_online(self, device_id: str, timeout: float) -> bool:
        if self.is_online(device_id):
            return True
        if timeout <= 0:
            return False
        event = asyncio.Event()
        self._waiters.setdefault(device_id, []).append(event)
        try:
            await asyncio.wait_for(event.wait(), timeout)
            return self.is_online(device_id)
        except TimeoutError:
            return False
        finally:
            waiters = self._waiters.get(device_id)
            if waiters and event in waiters:
                waiters.remove(event)
                if not waiters:
                    self._waiters.pop(device_id, None)

    async def send(self, device_id: str, message: dict[str, Any]) -> bool:
        conn = self.get(device_id)
        if conn is None:
            return False
        return await conn.send(message)

    async def disconnect(self, device_id: str, code: int, reason: str) -> None:
        conn = self._connections.pop(device_id, None)
        if conn is not None:
            await conn.close(code, reason)

    async def close_all(self) -> None:
        for device_id in list(self._connections):
            await self.disconnect(device_id, 1001, "server shutting down")
