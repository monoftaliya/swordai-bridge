"""Сборка ASGI-приложения Cloud Bridge."""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.requests import HTTPConnection, Request
from starlette.responses import FileResponse, HTMLResponse, JSONResponse, Response
from starlette.routing import BaseRoute, Mount, Route, WebSocketRoute

from . import __version__
from .api import admin_api, device_api
from .commands.schemas import ALL_SCOPES
from .commands.service import CommandService
from .config import Settings
from .db import Database
from .devices.hub import Hub
from .devices.ws import CLOSE_REVOKED, device_ws
from .mcp_bridge.consent import consent_get, consent_post
from .mcp_bridge.oauth import BridgeOAuthProvider
from .mcp_bridge.tools import BridgeMCP
from .screenshots import ScreenshotStore
from .security.admin_auth import AdminAuth
from .security.ratelimit import RateLimiter
from .security.signing import SigningKey
from .store import Store
from .web.pages import SECURITY_HEADERS, landing_page

log = logging.getLogger("bridge")
WEB_DIR = Path(__file__).parent / "web"
_KV_SIGNING_KEY = "server_signing_key"
_LOOPBACK = {"localhost", "127.0.0.1", "::1"}


def mcp_supported(settings: Settings) -> bool:
    """OAuth требует HTTPS (или localhost для разработки)."""
    host = (urlparse(settings.public_url).hostname or "").lower()
    return settings.is_https or host in _LOOPBACK


class BridgeState:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.db = Database(settings.database_url)
        self.store = Store(self.db)
        self.hub = Hub()
        self.limiter = RateLimiter()
        self.admin = AdminAuth(settings, self.store)
        self.screenshots = ScreenshotStore(settings.screenshot_link_ttl)
        self.signer: SigningKey = SigningKey.generate()  # заменяется в startup()
        self.commands = CommandService(settings, self.store, self.hub, self.signer)
        self.oauth: BridgeOAuthProvider | None = None
        self.mcp: BridgeMCP | None = None
        if mcp_supported(settings):
            self.oauth = BridgeOAuthProvider(settings, self.store)
            self.mcp = BridgeMCP(settings, self.store, self.commands, self.oauth, self.screenshots)
        self.started_at = time.time()
        self._tasks: list[asyncio.Task[Any]] = []
        self._integration_cache: tuple[float, dict[str, Any]] | None = None
        if self.oauth is not None:
            self.oauth.on_change = self.invalidate_integration

    async def startup(self) -> None:
        await self.db.connect()
        self.signer = await self._load_signing_key()
        self.commands.signer = self.signer
        await self.admin.init()
        lost = await self.store.commands_abandon_unfinished()
        if lost:
            log.info("Marked %s unfinished commands from the previous run as lost", lost)
        await self.store.audit("server", "server.started", details={"version": __version__})
        self._tasks.append(asyncio.create_task(self._maintenance(), name="bridge-maintenance"))
        log.info("SwordAI Cloud Bridge %s started at %s", __version__, self.settings.public_url)
        if self.mcp is not None:
            log.info("MCP endpoint for ChatGPT: %s", self.settings.mcp_url)
        else:
            log.warning("MCP/OAuth disabled: PUBLIC_URL must be https:// (or localhost). Device API still works.")
        log.info("Command signing key fingerprint: %s", self.signer.fingerprint)

    async def shutdown(self) -> None:
        for task in self._tasks:
            task.cancel()
        for task in self._tasks:
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task
        await self.hub.close_all()
        await self.db.close()

    async def _load_signing_key(self) -> SigningKey:
        if self.settings.signing_key_pem:
            return SigningKey.from_config(self.settings.signing_key_pem)
        stored = await self.store.kv_get(_KV_SIGNING_KEY)
        if stored is None:
            stored = await self.store.kv_set_if_absent(_KV_SIGNING_KEY, SigningKey.generate().to_pem())
            log.warning(
                "Generated a new command signing key and stored it in the database. If the database is "
                "ephemeral (free hosting), set SIGNING_KEY so phones do not need to re-pair after restarts."
            )
        return SigningKey.from_pem(stored)

    async def _maintenance(self) -> None:
        last_cleanup = 0.0
        while True:
            await asyncio.sleep(15)
            try:
                await self.commands.sweep()
                self.screenshots.purge()
                # Уборка редко: бесплатные БД (Neon) засыпают без запросов и экономят лимит.
                if time.time() - last_cleanup > 6 * 3600:
                    last_cleanup = time.time()
                    await self.store.cleanup(self.settings.audit_retention_days)
            except Exception:  # noqa: BLE001 - фоновая задача не должна падать
                log.exception("maintenance failed")

    def client_ip(self, conn: HTTPConnection) -> str | None:
        if self.settings.trust_proxy_headers:
            forwarded = conn.headers.get("x-forwarded-for")
            if forwarded:
                return forwarded.split(",")[0].strip()[:64]
        return conn.client.host if conn.client else None

    def invalidate_integration(self) -> None:
        self._integration_cache = None

    async def integration_status(self) -> dict[str, Any]:
        """Статус интеграции для телефона и админ-панели.

        Кэшируется надолго и сбрасывается при событиях OAuth, чтобы постоянные пинги
        телефона не будили базу данных (важно для бесплатного Neon).
        """
        now = time.time()
        if self._integration_cache is None or self._integration_cache[0] < now:
            base: dict[str, Any] = {"mcp_enabled": self.mcp is not None}
            if self.mcp is not None:
                base["authorized_clients"] = await self.store.oauth_active_grants()
                if self.commands.last_mcp_call is None:
                    row = await self.store.oauth_last_use()
                    if row:
                        self.commands.last_mcp_call = {"at": row["last_used_at"], "client": row["client_id"], "tool": None}
            self._integration_cache = (now + 6 * 3600, base)
        status = dict(self._integration_cache[1])
        if self.mcp is not None:
            status["last_call"] = self.commands.last_mcp_call
        return status

    async def revoke_device(self, device_id: str, actor: str, ip: str | None = None) -> bool:
        ok = await self.store.device_revoke(device_id)
        if ok:
            await self.commands.cancel_device(device_id, "revoked", "Доступ устройства отозван")
            await self.hub.disconnect(device_id, CLOSE_REVOKED, "device revoked")
            await self.store.audit(actor, "device.revoked", device_id=device_id, ip=ip)
        return ok


async def healthz(request: Request) -> JSONResponse:
    state: BridgeState = request.app.state.bridge
    return JSONResponse(
        {
            "ok": True,
            "version": __version__,
            "uptime": round(time.time() - state.started_at),
            "devices_online": len(state.hub.online_ids()),
            "mcp": state.mcp is not None,
        }
    )


async def landing(request: Request) -> HTMLResponse:
    state: BridgeState = request.app.state.bridge
    return HTMLResponse(landing_page(__version__, state.settings.mcp_url), headers=SECURITY_HEADERS)


async def admin_page(request: Request) -> Response:
    headers = dict(SECURITY_HEADERS)
    headers["Content-Security-Policy"] = (
        "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; "
        "connect-src 'self'; frame-ancestors 'none'"
    )
    return FileResponse(WEB_DIR / "admin.html", media_type="text/html", headers=headers)


async def screenshot(request: Request) -> Response:
    state: BridgeState = request.app.state.bridge
    shot = state.screenshots.get(request.path_params["token"])
    if shot is None:
        return Response("Ссылка устарела", status_code=404, media_type="text/plain; charset=utf-8")
    return Response(
        shot.data,
        media_type=shot.mime,
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer"},
    )


async def protected_resource_root(request: Request) -> JSONResponse:
    """Метаданные защищённого ресурса и по «короткому» пути (RFC 9728 + совместимость)."""
    state: BridgeState = request.app.state.bridge
    s = state.settings
    return JSONResponse(
        {
            "resource": s.mcp_url,
            "authorization_servers": [s.public_url],
            "scopes_supported": ALL_SCOPES,
            "bearer_methods_supported": ["header"],
            "resource_name": "SwordAI Bridge",
        },
        headers={"Access-Control-Allow-Origin": "*"},
    )


def create_app(settings: Settings | None = None) -> Starlette:
    settings = settings or Settings.from_env()
    logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    state = BridgeState(settings)

    routes: list[BaseRoute] = [
        Route("/", landing, methods=["GET"]),
        Route("/healthz", healthz, methods=["GET", "HEAD"]),
        Route("/admin", admin_page, methods=["GET"]),
        Route("/s/{token}", screenshot, methods=["GET"]),
        Route("/api/v1/pair", device_api.pair, methods=["POST"]),
        Route("/api/v1/device/me", device_api.device_me, methods=["GET"]),
        Route("/api/v1/device/unpair", device_api.device_unpair, methods=["POST"]),
        Route("/api/admin/login", admin_api.login, methods=["POST"]),
        Route("/api/admin/logout", admin_api.logout, methods=["POST"]),
        Route("/api/admin/overview", admin_api.overview, methods=["GET"]),
        Route("/api/admin/pairing-codes", admin_api.create_pairing_code, methods=["POST"]),
        Route("/api/admin/devices", admin_api.list_devices, methods=["GET"]),
        Route("/api/admin/devices/{device_id}/revoke", admin_api.revoke_device, methods=["POST"]),
        Route("/api/admin/devices/{device_id}/rename", admin_api.rename_device, methods=["POST"]),
        Route("/api/admin/devices/{device_id}/pause", admin_api.pause_device, methods=["POST"]),
        Route("/api/admin/commands", admin_api.send_command, methods=["POST"]),
        Route("/api/admin/commands", admin_api.list_commands, methods=["GET"]),
        Route("/api/admin/commands/{command_id}", admin_api.get_command, methods=["GET"]),
        Route("/api/admin/audit", admin_api.audit_log, methods=["GET"]),
        Route("/api/admin/oauth/clients", admin_api.oauth_clients, methods=["GET"]),
        Route("/api/admin/oauth/clients/{client_id}/revoke", admin_api.revoke_oauth_client, methods=["POST"]),
        Route("/api/admin/oauth/revoke-all", admin_api.revoke_all_oauth, methods=["POST"]),
        Route("/oauth/consent", consent_get, methods=["GET"]),
        Route("/oauth/consent", consent_post, methods=["POST"]),
        WebSocketRoute("/ws/device", device_ws),
    ]

    mcp_app = None
    if state.mcp is not None:
        routes.append(Route("/.well-known/oauth-protected-resource", protected_resource_root, methods=["GET"]))
        mcp_app = state.mcp.server.streamable_http_app(
            streamable_http_path="/mcp",
            stateless_http=True,
            json_response=True,
            transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
            host="0.0.0.0",
        )
        # Монтируем последним: /mcp, /authorize, /token, /register, /revoke, /.well-known/*
        routes.append(Mount("/", app=mcp_app))

    @contextlib.asynccontextmanager
    async def lifespan(app: Starlette) -> AsyncIterator[None]:
        await state.startup()
        try:
            if state.mcp is not None:
                async with state.mcp.server.session_manager.run():
                    yield
            else:
                yield
        finally:
            await state.shutdown()

    app = Starlette(routes=routes, lifespan=lifespan)
    app.state.bridge = state
    return app
