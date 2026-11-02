"""WebSocket-канал устройства: /ws/device (токен в заголовке Authorization)."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

from starlette.websockets import WebSocket, WebSocketDisconnect

from .. import PROTOCOL_VERSION, __version__
from ..security.tokens import hash_token, new_id
from .hub import DeviceConnection

log = logging.getLogger("bridge.ws")

# Коды закрытия (4xxx — прикладные)
CLOSE_UNAUTHORIZED = 4401
CLOSE_REVOKED = 4403
CLOSE_TOO_MANY = 4429
CLOSE_PROTOCOL = 4400
CLOSE_IDLE = 4408

_DEVICE_EVENTS = {
    "emergency_stop",
    "control_paused",
    "control_resumed",
    "projection_started",
    "projection_stopped",
    "permissions_changed",
    "command_rejected",
}


def _bearer(websocket: WebSocket) -> str | None:
    header = websocket.headers.get("authorization", "")
    if header.lower().startswith("bearer "):
        token = header[7:].strip()
        return token or None
    return None


async def device_ws(websocket: WebSocket) -> None:
    state = websocket.app.state.bridge
    settings = state.settings
    ip = state.client_ip(websocket)

    token = _bearer(websocket)
    if not token or not token.startswith("sdt_") or len(token) > 200:
        await websocket.close(code=CLOSE_UNAUTHORIZED)
        return
    device = await state.store.device_by_token_hash(hash_token(token))
    if device is None:
        if state.limiter.allow(f"ws-bad:{ip}", limit=20, window=600):
            await state.store.audit("server", "security.ws_bad_token", ip=ip)
        await websocket.close(code=CLOSE_UNAUTHORIZED)
        return
    device_id = device["id"]
    if device.get("revoked_at"):
        await websocket.close(code=CLOSE_REVOKED)
        return
    if not state.limiter.allow(f"ws-connect:{device_id}", limit=30, window=60):
        await websocket.close(code=CLOSE_TOO_MANY)
        return

    await websocket.accept()

    async def close(code: int, reason: str) -> None:
        await websocket.close(code=code, reason=reason)

    conn = DeviceConnection(device_id, new_id("wss", 8), websocket.send_text, close, ip)
    await state.hub.attach(conn)
    await conn.send(
        {
            "type": "welcome",
            "protocol": PROTOCOL_VERSION,
            "server_version": __version__,
            "server_time": time.time(),
            "device_id": device_id,
            "device_name": device["name"],
            "heartbeat_sec": settings.heartbeat_seconds,
            "key_fingerprint": state.signer.fingerprint,
            "integration": await state.integration_status(),
        }
    )
    await state.store.device_touch(device_id)
    await state.hub.announce_online(device_id)

    idle_timeout = settings.heartbeat_seconds * 3 + 15
    bad_messages = 0
    last_touch = time.time()
    reason = "closed"
    try:
        while True:
            try:
                text = await asyncio.wait_for(websocket.receive_text(), timeout=idle_timeout)
            except TimeoutError:
                reason = "idle timeout"
                await conn.close(CLOSE_IDLE, "no heartbeat")
                break
            if len(text) > settings.max_ws_message_bytes:
                reason = "message too large"
                await conn.close(1009, "message too large")
                break
            conn.last_message_at = time.time()
            try:
                message = json.loads(text)
                if not isinstance(message, dict) or not isinstance(message.get("type"), str):
                    raise ValueError("message must be an object with a type")
            except ValueError:
                bad_messages += 1
                if bad_messages > 20:
                    reason = "protocol errors"
                    await conn.close(CLOSE_PROTOCOL, "too many malformed messages")
                    break
                await conn.send({"type": "error", "code": "bad_message", "message": "Malformed message"})
                continue
            await _handle(state, conn, message)
            # last_seen в БД обновляем редко: живой статус берётся из памяти (hub)
            if time.time() - last_touch > 1800:
                last_touch = time.time()
                await state.store.device_touch(device_id)
    except WebSocketDisconnect:
        reason = "disconnected"
    except RuntimeError as exc:  # отправка в закрытый сокет
        reason = f"runtime: {exc}"
    finally:
        current = state.hub.detach(conn)
        if current:
            await state.store.device_touch(device_id)
            await state.store.audit("device", "device.disconnected", device_id=device_id, ip=ip, details={"reason": reason})


async def _handle(state: Any, conn: DeviceConnection, message: dict[str, Any]) -> None:
    kind = message["type"]
    if kind == "ping":
        t = message.get("t")
        await conn.send(
            {
                "type": "pong",
                "t": t if isinstance(t, (int, float)) else None,
                "server_time": time.time(),
                "integration": await state.integration_status(),
            }
        )
    elif kind == "hello":
        info = {
            "app_version": str(message.get("app_version", ""))[:40],
            "protocol": message.get("protocol"),
            "device": _clip(message.get("device")),
            "capabilities": [str(c)[:40] for c in (message.get("capabilities") or [])][:40],
            "state": _clip(message.get("state")),
        }
        conn.hello = info
        conn.state = info["state"] if isinstance(info["state"], dict) else {}
        await state.store.device_touch(conn.device_id, info)
        await state.store.audit(
            "device",
            "device.connected",
            device_id=conn.device_id,
            ip=conn.ip,
            details={"app_version": info["app_version"], "protocol": info["protocol"]},
        )
    elif kind == "status":
        new_state = _clip(message.get("state"))
        if isinstance(new_state, dict):
            conn.state = new_state
    elif kind in ("ack", "progress", "result"):
        await state.commands.handle_device_message(conn.device_id, message)
    elif kind == "event":
        event = message.get("event")
        if event in _DEVICE_EVENTS:
            await state.store.audit(
                "device", f"device.{event}", device_id=conn.device_id, details=_clip(message.get("detail"))
            )
            if event in ("emergency_stop", "control_paused"):
                await state.commands.cancel_device(
                    conn.device_id, "paused", "Пользователь остановил управление на телефоне"
                )
    else:
        await conn.send({"type": "error", "code": "unknown_type", "message": f"Unknown message type {kind[:40]}"})


def _clip(value: Any, depth: int = 0) -> Any:
    """Ограничить размер произвольных данных от устройства перед записью в журнал/БД."""
    if depth > 4:
        return None
    if isinstance(value, dict):
        return {str(k)[:60]: _clip(v, depth + 1) for k, v in list(value.items())[:60]}
    if isinstance(value, list):
        return [_clip(v, depth + 1) for v in value[:60]]
    if isinstance(value, str):
        return value[:300]
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return str(value)[:300]
