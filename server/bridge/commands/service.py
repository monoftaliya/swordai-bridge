"""Жизненный цикл команды: проверка → подпись → доставка → ожидание → результат.

Гарантии:
* у каждой команды уникальный id и монотонный seq на устройство;
* подпись ECDSA делает подмену/внедрение команд в канале бесполезными;
* при переподключении телефона незавершённые команды досылаются с тем же
  id, а телефон отвечает сохранённым результатом вместо повторного выполнения;
* результат принимается только от того устройства, которому адресована команда.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any

from .. import PROTOCOL_VERSION
from ..config import Settings
from ..devices.hub import Hub
from ..security.signing import SigningKey
from ..security.tokens import new_id
from ..store import Store
from .schemas import COMMAND_ID, TOOLS, ArgumentError, validate_args

log = logging.getLogger("bridge.commands")

REJECT_CODES = {
    "rejected_by_user",
    "confirmation_timeout",
    "paused",
    "blocked_app",
    "password_field",
    "policy_denied",
}
FINAL_STATUSES = {"succeeded", "failed", "rejected", "expired", "timeout", "lost", "cancelled"}
RESULT_CACHE_TTL = 900.0
# Сколько ещё ждать результата после истечения срока команды (подтверждение + выполнение).
ABANDON_AFTER_EXPIRY = 240.0


class CommandError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class Pending:
    command_id: str
    device_id: str
    tool: str
    envelope: dict[str, Any]
    future: asyncio.Future[dict[str, Any]]
    expires_at: float
    state: str = "queued"
    created_at: float = field(default_factory=time.time)


def _summary(outcome: dict[str, Any]) -> str:
    """Короткая сводка результата для БД (без изображений), не более 4 КБ."""
    data = outcome.get("data")
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":"), default=str) if data is not None else ""
    return text[:4000]


class CommandService:
    def __init__(self, settings: Settings, store: Store, hub: Hub, signer: SigningKey) -> None:
        self.settings = settings
        self.store = store
        self.hub = hub
        self.signer = signer
        self._pending: dict[str, Pending] = {}
        self._results: dict[str, tuple[dict[str, Any], float]] = {}
        self.last_mcp_call: dict[str, Any] | None = None
        hub.on_online = self.on_device_online

    # --- выбор устройства ------------------------------------------------------
    async def pick_device(self, device_id: str | None) -> dict[str, Any]:
        if device_id:
            device = await self.store.device_get(device_id)
            if device is None or device.get("revoked_at"):
                raise CommandError("unknown_device", f"Устройство {device_id} не найдено или отозвано")
            return device
        devices = await self.store.devices_list()
        if not devices:
            raise CommandError("no_devices", "Нет сопряжённых устройств. Выполните сопряжение в приложении SwordAI Bridge.")
        online = [d for d in devices if self.hub.is_online(d["id"])]
        candidates = online or devices
        if len(online) > 1:
            raise CommandError(
                "ambiguous_device",
                "Онлайн несколько устройств — укажите device_id (см. list_devices): "
                + ", ".join(f"{d['id']} ({d['name']})" for d in online),
            )
        return max(candidates, key=lambda d: d.get("last_seen_at") or 0)

    # --- отправка --------------------------------------------------------------
    async def submit(
        self,
        tool: str,
        args: dict[str, Any] | None,
        *,
        device_id: str | None = None,
        source: str,
        client_id: str | None = None,
        client_name: str | None = None,
        wait: float | None = None,
    ) -> dict[str, Any]:
        spec = TOOLS.get(tool)
        if spec is None or not spec.device:
            raise CommandError("unknown_tool", f"Неизвестная команда: {tool}")
        try:
            normalized = validate_args(tool, args)
        except ArgumentError as exc:
            raise CommandError("bad_args", f"Неверные аргументы: {exc}") from exc

        device = await self.pick_device(device_id)
        seq = await self.store.device_next_seq(device["id"])
        if seq is None:
            raise CommandError("unknown_device", "Устройство отозвано")

        command_id = new_id("cmd", 16)
        now = time.time()
        expires_at = now + self.settings.command_ttl_seconds
        payload = {
            "v": PROTOCOL_VERSION,
            "command_id": command_id,
            "device_id": device["id"],
            "seq": seq,
            "tool": tool,
            "args": normalized,
            "issued_at": round(now, 3),
            "expires_at": round(expires_at, 3),
            "origin": {"source": source, "client": client_name or source},
        }
        payload_text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        envelope = {"type": "command", "payload": payload_text, "sig": self.signer.sign(payload_text.encode("utf-8"))}

        await self.store.command_insert(
            {
                "id": command_id,
                "device_id": device["id"],
                "seq": seq,
                "tool": tool,
                "args_json": json.dumps(normalized, ensure_ascii=False)[:8000],
                "source": source,
                "client_id": client_id,
                "status": "queued",
                "state": "queued",
                "created_at": now,
                "expires_at": expires_at,
            }
        )
        await self.store.audit(
            source,
            "command.created",
            device_id=device["id"],
            command_id=command_id,
            details={"tool": tool, "client": client_name or client_id, "seq": seq},
        )

        loop = asyncio.get_running_loop()
        pending = Pending(command_id, device["id"], tool, envelope, loop.create_future(), expires_at)
        self._pending[command_id] = pending

        if not self.hub.is_online(device["id"]):
            online = await self.hub.wait_online(device["id"], self.settings.offline_grace_seconds)
            if not online:
                outcome = await self._finish(
                    pending,
                    status="failed",
                    error={
                        "code": "device_offline",
                        "message": "Телефон не подключён к Cloud Bridge. Откройте SwordAI Bridge на телефоне "
                        "и проверьте интернет.",
                    },
                )
                return outcome

        await self._deliver(pending)
        return await self._await(pending, self.settings.command_wait_seconds if wait is None else wait)

    async def _deliver(self, pending: Pending) -> None:
        if pending.future.done():
            return
        sent = await self.hub.send(pending.device_id, pending.envelope)
        if sent and pending.state == "queued":
            pending.state = "sent"

    async def _await(self, pending: Pending, wait: float) -> dict[str, Any]:
        try:
            return await asyncio.wait_for(asyncio.shield(pending.future), timeout=max(0.0, wait))
        except TimeoutError:
            return self._pending_outcome(pending)

    def _pending_outcome(self, pending: Pending) -> dict[str, Any]:
        hint = {
            "awaiting_confirmation": "Ожидается подтверждение пользователя на телефоне.",
            "executing": "Команда выполняется на телефоне.",
        }.get(pending.state, "Команда ещё не завершена.")
        return {
            "status": "pending",
            "command_id": pending.command_id,
            "device_id": pending.device_id,
            "tool": pending.tool,
            "state": pending.state,
            "ok": None,
            "message": f"{hint} Вызовите get_command_result с этим command_id позже.",
        }

    async def on_device_online(self, device_id: str) -> None:
        """Досылаем незавершённые команды: телефон отбросит дубликаты по command_id."""
        for pending in list(self._pending.values()):
            if pending.device_id == device_id and not pending.future.done():
                await self._deliver(pending)

    # --- сообщения от устройства -----------------------------------------------
    async def handle_device_message(self, device_id: str, message: dict[str, Any]) -> None:
        kind = message.get("type")
        command_id = message.get("command_id")
        if not isinstance(command_id, str) or not _matches(COMMAND_ID, command_id):
            log.info("device %s sent %s with invalid command_id", device_id, kind)
            return
        pending = self._pending.get(command_id)
        if pending is None:
            # Поздний или повторный ответ на уже завершённую команду — игнорируем.
            return
        if pending.device_id != device_id:
            await self.store.audit(
                "server",
                "security.result_from_wrong_device",
                device_id=device_id,
                command_id=command_id,
            )
            return
        if kind == "ack":
            if pending.state in ("queued", "sent"):
                pending.state = "delivered"
            await self.store.command_update(command_id, status="delivered", state=pending.state, delivered_at=time.time())
        elif kind == "progress":
            state = message.get("state")
            if state in ("awaiting_confirmation", "executing", "confirmed"):
                pending.state = "executing" if state == "confirmed" else state
                await self.store.command_update(command_id, state=pending.state)
        elif kind == "result":
            await self._accept_result(pending, message)

    async def _accept_result(self, pending: Pending, message: dict[str, Any]) -> None:
        ok = bool(message.get("ok"))
        error = message.get("error") if not ok else None
        if error is not None and not isinstance(error, dict):
            error = {"code": "device_error", "message": str(error)[:500]}
        if error is not None:
            error = {"code": str(error.get("code", "device_error"))[:64], "message": str(error.get("message", ""))[:1000]}
        data = message.get("data") if isinstance(message.get("data"), dict) else None
        image = message.get("image") if isinstance(message.get("image"), dict) else None
        if ok:
            status = "succeeded"
        elif error and error["code"] in REJECT_CODES:
            status = "rejected"
        else:
            status = "failed"
        await self._finish(pending, status=status, data=data, error=error, image=image, actor="device")

    async def _finish(
        self,
        pending: Pending,
        *,
        status: str,
        data: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
        image: dict[str, Any] | None = None,
        actor: str = "server",
    ) -> dict[str, Any]:
        outcome: dict[str, Any] = {
            "status": status,
            "command_id": pending.command_id,
            "device_id": pending.device_id,
            "tool": pending.tool,
            "ok": status == "succeeded",
            "data": data,
            "error": error,
        }
        if image is not None:
            outcome["image"] = image
        self._pending.pop(pending.command_id, None)
        self._results[pending.command_id] = (outcome, time.time() + RESULT_CACHE_TTL)
        if not pending.future.done():
            pending.future.set_result(outcome)
        finished = await self.store.command_finish(
            pending.command_id,
            status=status,
            state="done",
            finished_at=time.time(),
            ok=1 if outcome["ok"] else 0,
            error_code=error["code"] if error else None,
            error_message=error["message"] if error else None,
            result_summary=_summary(outcome),
        )
        if finished:
            await self.store.audit(
                actor,
                f"command.{status}",
                device_id=pending.device_id,
                command_id=pending.command_id,
                details={"tool": pending.tool, "error": error["code"] if error else None},
            )
        return outcome

    # --- запрос результата -----------------------------------------------------
    async def get_result(self, command_id: str, wait: float = 0.0) -> dict[str, Any]:
        if not _matches(COMMAND_ID, command_id):
            raise CommandError("bad_args", "Неверный command_id")
        pending = self._pending.get(command_id)
        if pending is not None:
            return await self._await(pending, wait)
        cached = self._results.get(command_id)
        if cached is not None and cached[1] > time.time():
            return cached[0]
        row = await self.store.command_get(command_id)
        if row is None:
            raise CommandError("unknown_command", "Команда не найдена")
        status = row["status"]
        if status not in FINAL_STATUSES:
            status = "lost"
        summary = row.get("result_summary")
        data: Any = None
        if summary:
            try:
                data = json.loads(summary)
            except ValueError:
                data = {"summary": summary}
        return {
            "status": status,
            "command_id": command_id,
            "device_id": row["device_id"],
            "tool": row["tool"],
            "ok": bool(row.get("ok")),
            "data": data,
            "error": {"code": row["error_code"], "message": row["error_message"]} if row.get("error_code") else None,
            "note": "Полный результат больше не хранится; показана сокращённая сводка.",
        }

    async def cancel_device(self, device_id: str, code: str, message: str) -> None:
        for pending in [p for p in self._pending.values() if p.device_id == device_id]:
            await self._finish(pending, status="cancelled", error={"code": code, "message": message})

    # --- обслуживание ----------------------------------------------------------
    async def sweep(self) -> None:
        now = time.time()
        for pending in list(self._pending.values()):
            never_delivered = pending.state in ("queued", "sent") and now > pending.expires_at
            abandoned = now > pending.expires_at + ABANDON_AFTER_EXPIRY
            if never_delivered:
                await self._finish(
                    pending,
                    status="expired",
                    error={"code": "expired", "message": "Телефон не принял команду до истечения срока"},
                )
            elif abandoned:
                await self._finish(
                    pending,
                    status="timeout",
                    error={"code": "no_result", "message": "Телефон не прислал результат"},
                )
        for command_id in [c for c, (_, exp) in self._results.items() if exp < now]:
            del self._results[command_id]

    def pending_count(self) -> int:
        return len(self._pending)


def _matches(pattern: str, value: str) -> bool:
    return re.fullmatch(pattern, value) is not None
