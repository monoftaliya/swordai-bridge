"""REST API админ-панели (/api/admin/*). Авторизация — сессия по паролю администратора."""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.requests import Request
from starlette.responses import JSONResponse

from .. import __version__
from ..commands.schemas import DEVICE_ID, TOOLS
from ..commands.service import CommandError
from ..security.tokens import hash_token, new_pairing_code

Handler = Callable[[Request], Awaitable[JSONResponse]]


def _err(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status)


def _bearer(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    return header[7:].strip() if header.lower().startswith("bearer ") else None


def admin_required(handler: Handler) -> Handler:
    async def wrapper(request: Request) -> JSONResponse:
        state = request.app.state.bridge
        if not await state.admin.check_session(_bearer(request)):
            return _err(401, "unauthorized", "Требуется вход администратора")
        return await handler(request)

    wrapper.__name__ = handler.__name__
    return wrapper


class LoginBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    password: str = Field(..., min_length=1, max_length=512)


async def login(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    ip = state.client_ip(request)
    if not state.limiter.allow(f"login:{ip}", limit=10, window=600) or not state.limiter.allow(
        "login:global", limit=50, window=600
    ):
        await state.store.audit("admin", "security.login_rate_limited", ip=ip)
        return _err(429, "rate_limited", "Слишком много попыток. Подождите 10 минут.")
    try:
        body = LoginBody.model_validate(await request.json())
    except (ValidationError, ValueError):
        return _err(400, "bad_request", "Ожидается JSON {password}")
    if not state.admin.verify(body.password):
        await state.store.audit("admin", "security.login_failed", ip=ip)
        return _err(401, "bad_password", "Неверный пароль")
    token, expires_at = await state.admin.create_session(ip)
    await state.store.audit("admin", "admin.login", ip=ip)
    return JSONResponse({"token": token, "expires_at": expires_at}, headers={"Cache-Control": "no-store"})


@admin_required
async def logout(request: Request) -> JSONResponse:
    token = _bearer(request)
    if token:
        await request.app.state.bridge.admin.end_session(token)
    return JSONResponse({"ok": True})


def _device_view(state: Any, row: dict[str, Any]) -> dict[str, Any]:
    conn = state.hub.get(row["id"])
    info = json.loads(row["info_json"]) if row.get("info_json") else {}
    return {
        "device_id": row["id"],
        "name": row["name"],
        "model": row.get("model"),
        "android_version": row.get("android_version"),
        "app_version": row.get("app_version"),
        "created_at": row["created_at"],
        "last_seen_at": row.get("last_seen_at"),
        "revoked_at": row.get("revoked_at"),
        "online": conn is not None,
        "connected_at": conn.connected_at if conn else None,
        "state": conn.state if conn else (info.get("state") or {}),
    }


@admin_required
async def overview(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    s = state.settings
    devices = await state.store.devices_list()
    return JSONResponse(
        {
            "server": {
                "name": s.server_name,
                "version": __version__,
                "public_url": s.public_url,
                "mcp_url": s.mcp_url if state.mcp is not None else None,
                "mcp_enabled": state.mcp is not None,
                "ws_url": s.ws_url,
                "signing_key_fingerprint": state.signer.fingerprint,
                "database": state.db.dialect,
                "pending_commands": state.commands.pending_count(),
            },
            "integration": await state.integration_status(),
            "devices": [_device_view(state, d) for d in devices],
        }
    )


class PairingBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str | None = Field(None, max_length=80)


@admin_required
async def create_pairing_code(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    try:
        raw = await request.body()
        body = PairingBody.model_validate_json(raw or b"{}")
    except ValidationError:
        return _err(400, "bad_request", "Неверный запрос")
    code = new_pairing_code()
    expires_at = await state.store.pairing_create(hash_token(code), body.label, state.settings.pairing_code_ttl)
    await state.store.audit("admin", "pairing.code_created", ip=state.client_ip(request), details={"label": body.label})
    return JSONResponse(
        {"code": code, "expires_at": expires_at, "server_url": state.settings.public_url},
        headers={"Cache-Control": "no-store"},
    )


@admin_required
async def list_devices(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    include_revoked = request.query_params.get("include_revoked") == "1"
    devices = await state.store.devices_list(include_revoked=include_revoked)
    return JSONResponse({"devices": [_device_view(state, d) for d in devices]})


@admin_required
async def revoke_device(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    device_id = request.path_params["device_id"]
    ok = await state.revoke_device(device_id, actor="admin", ip=state.client_ip(request))
    if not ok:
        return _err(404, "not_found", "Устройство не найдено или уже отозвано")
    return JSONResponse({"ok": True})


class RenameBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(..., min_length=1, max_length=80)


@admin_required
async def rename_device(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    try:
        body = RenameBody.model_validate(await request.json())
    except (ValidationError, ValueError):
        return _err(400, "bad_request", "Ожидается JSON {name}")
    if not await state.store.device_rename(request.path_params["device_id"], body.name):
        return _err(404, "not_found", "Устройство не найдено")
    return JSONResponse({"ok": True})


@admin_required
async def pause_device(request: Request) -> JSONResponse:
    """Удалённая пауза (только остановка; возобновить можно лишь на самом телефоне)."""
    state = request.app.state.bridge
    device_id = request.path_params["device_id"]
    sent = await state.hub.send(device_id, {"type": "control", "action": "pause"})
    await state.commands.cancel_device(device_id, "paused", "Управление приостановлено администратором")
    await state.store.audit("admin", "device.remote_pause", device_id=device_id, details={"delivered": sent})
    return JSONResponse({"ok": True, "delivered": sent})


class CommandBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    tool: str = Field(..., max_length=40)
    args: dict[str, Any] = Field(default_factory=dict)
    device_id: str | None = Field(None, pattern=DEVICE_ID)
    wait: float = Field(30.0, ge=0, le=55)


@admin_required
async def send_command(request: Request) -> JSONResponse:
    """Отправка команды из админ-панели или bridgectl — этап 2 (локальный тест без ChatGPT)."""
    state = request.app.state.bridge
    try:
        body = CommandBody.model_validate(await request.json())
    except (ValidationError, ValueError) as exc:
        return _err(400, "bad_request", f"Неверный запрос: {exc}"[:500])
    if body.tool not in TOOLS:
        return _err(400, "unknown_tool", f"Неизвестная команда {body.tool}")
    try:
        outcome = await state.commands.submit(
            body.tool, body.args, device_id=body.device_id, source="admin", client_name="Админ-панель", wait=body.wait
        )
    except CommandError as exc:
        return _err(400, exc.code, exc.message)
    return JSONResponse(outcome)


@admin_required
async def get_command(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    try:
        wait = min(55.0, max(0.0, float(request.query_params.get("wait", "0"))))
        outcome = await state.commands.get_result(request.path_params["command_id"], wait=wait)
    except CommandError as exc:
        return _err(404 if exc.code == "unknown_command" else 400, exc.code, exc.message)
    except ValueError:
        return _err(400, "bad_request", "wait должно быть числом")
    return JSONResponse(outcome)


@admin_required
async def list_commands(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    device_id = request.query_params.get("device_id") or None
    try:
        limit = int(request.query_params.get("limit", "50"))
    except ValueError:
        limit = 50
    rows = await state.store.commands_list(device_id, limit)
    for row in rows:
        row["args"] = json.loads(row.pop("args_json") or "{}")
    return JSONResponse({"commands": rows})


@admin_required
async def audit_log(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    try:
        limit = int(request.query_params.get("limit", "100"))
        before = request.query_params.get("before_id")
        before_id = int(before) if before else None
    except ValueError:
        return _err(400, "bad_request", "limit/before_id должны быть числами")
    rows = await state.store.audit_list(limit, before_id, request.query_params.get("device_id") or None)
    return JSONResponse({"events": rows})


@admin_required
async def oauth_clients(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    rows = await state.store.oauth_clients_list()
    clients = []
    for row in rows:
        info = json.loads(row["info_json"])
        clients.append(
            {
                "client_id": row["client_id"],
                "name": info.get("client_name"),
                "redirect_uris": info.get("redirect_uris"),
                "created_at": row["created_at"],
                "last_used_at": row.get("last_used_at"),
                "revoked_at": row.get("revoked_at"),
                "active_grants": row.get("active_grants", 0),
            }
        )
    return JSONResponse({"clients": clients})


@admin_required
async def revoke_oauth_client(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    client_id = request.path_params["client_id"]
    ok = await state.store.oauth_client_revoke(client_id)
    state.invalidate_integration()
    await state.store.audit("admin", "oauth.client_revoked", details={"client_id": client_id})
    return JSONResponse({"ok": ok})


@admin_required
async def revoke_all_oauth(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    count = await state.store.oauth_revoke_all_tokens()
    state.invalidate_integration()
    await state.store.audit("admin", "oauth.all_tokens_revoked", details={"count": count})
    return JSONResponse({"ok": True, "revoked": count})
