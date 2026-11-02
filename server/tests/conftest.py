from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import os
import secrets
import sys
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx2
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bridge.app import create_app  # noqa: E402
from bridge.config import Settings  # noqa: E402
from testkit.asgi_ws import AsgiWebSocket, WebSocketClosed  # noqa: E402
from testkit.device_sim import DeviceSimulator  # noqa: E402

ADMIN_PASSWORD = "correct-horse-battery-staple"
BASE = "http://localhost:8000"
CHATGPT_REDIRECT = "https://chatgpt.com/connector/oauth/test-callback"


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@dataclass
class DeviceRunner:
    ws: AsgiWebSocket
    sim: DeviceSimulator
    task: asyncio.Task[None] | None = None
    respond: bool = True
    inbox: list[dict[str, Any]] = field(default_factory=list)

    async def _loop(self) -> None:
        try:
            while True:
                message = await self.ws.receive_json(timeout=60)
                self.inbox.append(message)
                if not self.respond and message.get("type") == "command":
                    continue
                for out in self.sim.on_message(message):
                    await self.ws.send_json(out)
        except (WebSocketClosed, asyncio.CancelledError, TimeoutError):
            return

    def start(self) -> None:
        self.task = asyncio.create_task(self._loop())

    async def send(self, messages: list[dict[str, Any]]) -> None:
        for out in messages:
            await self.ws.send_json(out)

    async def stop(self) -> None:
        if self.task is not None:
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self.task
        with contextlib.suppress(Exception):
            await self.ws.close()


@dataclass
class Harness:
    app: Any
    client: httpx2.AsyncClient
    settings: Settings
    runners: list[DeviceRunner] = field(default_factory=list)
    _admin: str | None = None

    @property
    def state(self) -> Any:
        return self.app.state.bridge

    async def admin_token(self) -> str:
        if self._admin is None:
            r = await self.client.post("/api/admin/login", json={"password": ADMIN_PASSWORD})
            assert r.status_code == 200, r.text
            self._admin = r.json()["token"]
        return self._admin

    async def admin(self, method: str, url: str, **kwargs: Any) -> httpx2.Response:
        headers = {"Authorization": f"Bearer {await self.admin_token()}"}
        return await self.client.request(method, url, headers=headers, **kwargs)

    async def pairing_code(self) -> str:
        r = await self.admin("POST", "/api/admin/pairing-codes", json={"label": "test"})
        assert r.status_code == 200, r.text
        return r.json()["code"]

    async def pair(self, name: str = "Pixel Test") -> dict[str, Any]:
        code = await self.pairing_code()
        r = await self.client.post(
            "/api/v1/pair",
            json={
                "code": code,
                "device": {"name": name, "manufacturer": "Google", "model": "Pixel 9", "android_release": "16", "sdk_int": 36},
                "app_version": "1.0.0",
                "protocol": 1,
            },
        )
        assert r.status_code == 200, r.text
        return r.json()

    async def connect(self, paired: dict[str, Any], start: bool = True, **sim_kwargs: Any) -> DeviceRunner:
        ws = AsgiWebSocket(self.app, "/ws/device", {"Authorization": f"Bearer {paired['device_token']}"})
        await ws.connect()
        sim = DeviceSimulator(paired["device_id"], paired["server"]["signing_public_key"], **sim_kwargs)
        runner = DeviceRunner(ws, sim)
        self.runners.append(runner)
        if start:
            runner.start()
            for _ in range(100):  # дождаться hello
                await asyncio.sleep(0.01)
                conn = self.state.hub.get(paired["device_id"])
                if conn is not None and conn.hello is not None:
                    break
        return runner

    async def oauth(self, *, control: bool = True, name: str = "ChatGPT") -> dict[str, Any]:
        """Полный OAuth-поток, как у ChatGPT: DCR → authorize → согласие → token (PKCE)."""
        r = await self.client.post(
            "/register",
            json={
                "client_name": name,
                "redirect_uris": [CHATGPT_REDIRECT],
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "client_secret_post",
            },
        )
        assert r.status_code == 201, r.text
        client = r.json()
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        r = await self.client.get(
            "/authorize",
            params={
                "response_type": "code",
                "client_id": client["client_id"],
                "redirect_uri": CHATGPT_REDIRECT,
                "code_challenge": challenge,
                "code_challenge_method": "S256",
                "state": "st-123",
                "scope": "device.read device.control",
                "resource": f"{BASE}/mcp",
            },
        )
        assert r.status_code == 302, r.text
        consent_url = r.headers["location"]
        req = parse_qs(urlparse(consent_url).query)["req"][0]
        page = await self.client.get(f"/oauth/consent?req={req}")
        assert page.status_code == 200
        csrf = page.text.split('name="csrf" value="')[1].split('"')[0]
        form = {"req": req, "csrf": csrf, "password": ADMIN_PASSWORD, "action": "allow"}
        if control:
            form["control"] = "1"
        r = await self.client.post("/oauth/consent", data=form)
        assert r.status_code == 303, r.text
        redirect = urlparse(r.headers["location"])
        query = parse_qs(redirect.query)
        assert query["state"] == ["st-123"]
        code = query["code"][0]
        r = await self.client.post(
            "/token",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": CHATGPT_REDIRECT,
                "client_id": client["client_id"],
                "client_secret": client["client_secret"],
                "code_verifier": verifier,
                "resource": f"{BASE}/mcp",
            },
        )
        assert r.status_code == 200, r.text
        tokens = r.json()
        return {"client": client, "tokens": tokens, "verifier": verifier}

    def mcp_http(self, access_token: str | None) -> httpx2.AsyncClient:
        headers = {"Authorization": f"Bearer {access_token}"} if access_token else {}
        return httpx2.AsyncClient(
            transport=httpx2.ASGITransport(app=self.app), base_url=BASE, headers=headers, timeout=30
        )


# Для прогона на PostgreSQL (например, в CI): TEST_DATABASE_URL=postgresql://user:pass@host/db
TEST_DATABASE_URL = os.environ.get("TEST_DATABASE_URL")
_TABLES = ["kv", "devices", "pairing_codes", "commands", "audit_log", "oauth_clients", "oauth_pending",
           "oauth_codes", "oauth_tokens", "admin_sessions", "schema_version"]


def _reset_postgres(url: str) -> None:
    import psycopg  # type: ignore[import-not-found]

    with psycopg.connect(url, autocommit=True) as conn:
        for table in _TABLES:
            conn.execute(f"DROP TABLE IF EXISTS {table} CASCADE")


def make_settings(tmp_path: Path, **overrides: Any) -> Settings:
    if TEST_DATABASE_URL:
        _reset_postgres(TEST_DATABASE_URL)
    values: dict[str, Any] = {
        "public_url": BASE,
        "database_url": TEST_DATABASE_URL or f"sqlite:///{tmp_path / 'bridge.db'}",
        "admin_password": ADMIN_PASSWORD,
        "command_wait_seconds": 5.0,
        "offline_grace_seconds": 0.3,
        "command_ttl_seconds": 60,
        "trust_proxy_headers": False,
    }
    values.update(overrides)
    return Settings(**values)


@contextlib.asynccontextmanager
async def running(settings: Settings) -> AsyncIterator[Harness]:
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        transport = httpx2.ASGITransport(app=app)
        async with httpx2.AsyncClient(transport=transport, base_url=BASE, timeout=30) as client:
            harness = Harness(app, client, settings)
            try:
                yield harness
            finally:
                for runner in harness.runners:
                    await runner.stop()


@pytest.fixture
async def bridge(tmp_path: Path) -> AsyncIterator[Harness]:
    async with running(make_settings(tmp_path)) as harness:
        yield harness
