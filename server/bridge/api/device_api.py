"""REST API для приложения на телефоне: сопряжение и проверка статуса."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.requests import Request
from starlette.responses import JSONResponse

from .. import PROTOCOL_VERSION, __version__
from ..security.tokens import hash_token, new_id, new_token, normalize_pairing_code


class DeviceInfo(BaseModel):
    model_config = ConfigDict(extra="ignore")
    name: str = Field("Android", min_length=1, max_length=80)
    manufacturer: str | None = Field(None, max_length=80)
    model: str | None = Field(None, max_length=80)
    android_release: str | None = Field(None, max_length=20)
    sdk_int: int | None = Field(None, ge=1, le=100)


class PairRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(..., min_length=10, max_length=20)
    device: DeviceInfo
    app_version: str = Field(..., min_length=1, max_length=40)
    protocol: int = Field(..., ge=1, le=100)


def _err(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status)


def server_block(state: Any) -> dict[str, Any]:
    s = state.settings
    return {
        "name": s.server_name,
        "version": __version__,
        "protocol": PROTOCOL_VERSION,
        "public_url": s.public_url,
        "ws_url": s.ws_url,
        "mcp_url": s.mcp_url if state.mcp is not None else None,
        "signing_public_key": state.signer.public_key_b64,
        "signing_key_fingerprint": state.signer.fingerprint,
    }


async def pair(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    ip = state.client_ip(request)
    if not state.limiter.allow(f"pair:{ip}", limit=10, window=600) or not state.limiter.allow(
        "pair:global", limit=60, window=600
    ):
        await state.store.audit("server", "security.pair_rate_limited", ip=ip)
        return _err(429, "rate_limited", "Слишком много попыток сопряжения. Подождите 10 минут.")
    try:
        body = PairRequest.model_validate(await request.json())
    except (ValidationError, ValueError) as exc:
        return _err(400, "bad_request", f"Неверный запрос: {exc}"[:500])
    if body.protocol != PROTOCOL_VERSION:
        return _err(400, "protocol_mismatch", f"Сервер поддерживает протокол {PROTOCOL_VERSION}. Обновите приложение.")
    code = normalize_pairing_code(body.code)
    if not code:
        return _err(400, "bad_code", "Код сопряжения имеет вид XXXXX-XXXXX")
    device_id = new_id("dev", 12)
    token = new_token("sdt", 32)
    info = {"device": body.device.model_dump(), "app_version": body.app_version}
    device = await state.store.pairing_consume_and_create_device(
        hash_token(code),
        device_id,
        hash_token(token),
        body.device.name,
        body.device.model,
        body.device.android_release,
        body.app_version,
        info,
    )
    if device is None:
        await state.store.audit("server", "security.pair_bad_code", ip=ip)
        return _err(403, "invalid_code", "Код неверный, истёк или уже использован. Создайте новый в админ-панели.")
    await state.store.audit(
        "device",
        "device.paired",
        device_id=device_id,
        ip=ip,
        details={"name": body.device.name, "model": body.device.model, "app_version": body.app_version},
    )
    return JSONResponse(
        {
            "device_id": device_id,
            "device_token": token,
            "device_name": device["name"],
            "server": server_block(state),
        },
        headers={"Cache-Control": "no-store"},
    )


async def _device_from_request(request: Request) -> dict[str, Any] | None:
    state = request.app.state.bridge
    header = request.headers.get("authorization", "")
    if not header.lower().startswith("bearer "):
        return None
    token = header[7:].strip()
    if not token.startswith("sdt_") or len(token) > 200:
        return None
    device = await state.store.device_by_token_hash(hash_token(token))
    if device is None or device.get("revoked_at"):
        return None
    return device


async def device_me(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    device = await _device_from_request(request)
    if device is None:
        return _err(401, "unauthorized", "Токен устройства недействителен или отозван")
    return JSONResponse(
        {
            "device_id": device["id"],
            "device_name": device["name"],
            "online": state.hub.is_online(device["id"]),
            "server": server_block(state),
            "integration": await state.integration_status(),
        }
    )


async def device_unpair(request: Request) -> JSONResponse:
    state = request.app.state.bridge
    device = await _device_from_request(request)
    if device is None:
        return _err(401, "unauthorized", "Токен устройства недействителен или отозван")
    await state.revoke_device(device["id"], actor="device")
    return JSONResponse({"ok": True})
