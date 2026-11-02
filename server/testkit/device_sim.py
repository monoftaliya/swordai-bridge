"""Эталонная реализация протокола устройства (как в Android Agent).

Используется в автотестах и в tools/fake_device.py для проверки сервера без
телефона. Проверки идентичны приложению: подпись ECDSA P-256 над строкой
payload, совпадение device_id, монотонный seq, срок действия, повторная
доставка возвращает сохранённый результат без повторного выполнения.
"""

from __future__ import annotations

import base64
import binascii
import json
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

PROTOCOL = 1


class SimError(Exception):
    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code
        self.message = message or code


@dataclass
class Deferred:
    """Обработчик вернул Deferred — команда ждёт подтверждения пользователя."""

    reason: str = "Нужно подтверждение"


Handler = Callable[[str, dict[str, Any]], Any]


def default_handler(tool: str, args: dict[str, Any]) -> dict[str, Any]:
    if tool == "get_device_status":
        return {"device": {"model": "Simulator", "android_release": "16", "sdk_int": 36}, "state": {"locked": False}}
    if tool == "get_screen_info":
        return {
            "snapshot_id": "s_sim1",
            "package": "com.example.notes",
            "app_label": "Notes",
            "screen": {"w": 1080, "h": 2400, "rotation": 0},
            "keyboard": False,
            "elements": [
                {"id": "e1", "cls": "TextView", "text": "Заметки", "b": [40, 120, 600, 200], "f": []},
                {"id": "e2", "cls": "Button", "text": "Новая", "rid": "fab", "b": [880, 2100, 1040, 2260], "f": ["click"]},
            ],
            "element_count": 2,
            "truncated": False,
        }
    if tool == "capture_screen":
        # 1×1 прозрачный PNG
        png = base64.b64encode(
            bytes.fromhex(
                "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                "1f15c4890000000d49444154789c6360000002000100e221bc330000000049454e44ae426082"
            )
        ).decode()
        return {"__image__": {"mime": "image/png", "data": png}, "width": 1, "height": 1, "package": "com.example.notes"}
    return {"done": tool, "args": args}


@dataclass
class DeviceSimulator:
    device_id: str
    server_public_key_b64: str
    handler: Handler = default_handler
    last_seq: int = 0
    clock_offset: float = 0.0
    paused: bool = False
    results: dict[str, dict[str, Any]] = field(default_factory=dict)
    waiting: dict[str, dict[str, Any]] = field(default_factory=dict)
    executed: list[tuple[str, str]] = field(default_factory=list)
    rejected: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        key = serialization.load_der_public_key(base64.b64decode(self.server_public_key_b64))
        if not isinstance(key, ec.EllipticCurvePublicKey):
            raise ValueError("server key must be EC")
        self._key = key

    # --- входящие сообщения ----------------------------------------------------------
    def on_message(self, message: dict[str, Any]) -> list[dict[str, Any]]:
        kind = message.get("type")
        if kind == "welcome":
            self.clock_offset = float(message.get("server_time", time.time())) - time.time()
            return [self.hello()]
        if kind == "command":
            return self.on_command(message)
        if kind == "control" and message.get("action") == "pause":
            self.paused = True
            return [{"type": "event", "event": "control_paused", "detail": {"by": "server"}}]
        return []

    def hello(self) -> dict[str, Any]:
        return {
            "type": "hello",
            "protocol": PROTOCOL,
            "app_version": "sim-1.0",
            "device": {"manufacturer": "SwordAI", "model": "Simulator", "android_release": "16", "sdk_int": 36},
            "capabilities": ["get_device_status", "get_screen_info", "tap_element"],
            "state": {"control_enabled": not self.paused, "accessibility": True},
        }

    def verify(self, envelope: dict[str, Any]) -> tuple[dict[str, Any] | None, str | None]:
        payload_text = envelope.get("payload")
        signature = envelope.get("sig")
        if not isinstance(payload_text, str) or not isinstance(signature, str) or len(payload_text) > 1_000_000:
            return None, "malformed"
        try:
            self._key.verify(base64.b64decode(signature, validate=True), payload_text.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, binascii.Error, ValueError):
            return None, "invalid_signature"
        try:
            payload = json.loads(payload_text)
        except ValueError:
            return None, "malformed"
        required = {"v": int, "command_id": str, "device_id": str, "seq": int, "tool": str, "args": dict,
                    "issued_at": (int, float), "expires_at": (int, float)}
        for name, kind in required.items():
            if not isinstance(payload.get(name), kind):
                return None, "malformed"
        if payload["v"] != PROTOCOL:
            return None, "unsupported_version"
        if payload["device_id"] != self.device_id:
            return None, "wrong_device"
        return payload, None

    def on_command(self, envelope: dict[str, Any]) -> list[dict[str, Any]]:
        payload, error = self.verify(envelope)
        if payload is None:
            self.rejected.append(error or "invalid")
            return [{"type": "event", "event": "command_rejected", "detail": {"reason": error}}]
        command_id = payload["command_id"]
        if command_id in self.results:
            return [self.results[command_id]]  # повторная доставка — без повторного выполнения
        if command_id in self.waiting:
            return [{"type": "progress", "command_id": command_id, "state": "awaiting_confirmation"}]
        if payload["seq"] <= self.last_seq:
            self.rejected.append("replayed")
            return [self._result(command_id, error=("replayed", "Повтор команды отклонён"))]
        now = time.time() + self.clock_offset
        if now > payload["expires_at"] or payload["issued_at"] > now + 300:
            self.rejected.append("expired")
            return [self._result(command_id, error=("expired", "Срок действия команды истёк"))]
        self.last_seq = payload["seq"]
        out: list[dict[str, Any]] = [{"type": "ack", "command_id": command_id}]
        if self.paused:
            out.append(self._result(command_id, error=("paused", "Управление остановлено пользователем")))
            return out
        try:
            value = self.handler(payload["tool"], payload["args"])
        except SimError as exc:
            out.append(self._result(command_id, error=(exc.code, exc.message)))
            return out
        if isinstance(value, Deferred):
            self.waiting[command_id] = payload
            out.append({"type": "progress", "command_id": command_id, "state": "awaiting_confirmation"})
            return out
        self.executed.append((command_id, payload["tool"]))
        out.append(self._result(command_id, data=value))
        return out

    def confirm(self, command_id: str, approve: bool, data: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        payload = self.waiting.pop(command_id)
        if not approve:
            return [self._result(command_id, error=("rejected_by_user", "Пользователь отклонил действие"))]
        self.executed.append((command_id, payload["tool"]))
        return [
            {"type": "progress", "command_id": command_id, "state": "executing"},
            self._result(command_id, data=data or {"done": payload["tool"]}),
        ]

    def _result(
        self, command_id: str, data: dict[str, Any] | None = None, error: tuple[str, str] | None = None
    ) -> dict[str, Any]:
        message: dict[str, Any] = {"type": "result", "command_id": command_id, "ok": error is None}
        if data is not None:
            data = dict(data)
            image = data.pop("__image__", None)
            message["data"] = data
            if image:
                message["image"] = image
        if error is not None:
            message["error"] = {"code": error[0], "message": error[1]}
        self.results[command_id] = message
        return message
