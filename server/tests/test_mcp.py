"""MCP + OAuth: метаданные, регистрация, согласие, PKCE, токены, вызовы через официальный MCP-клиент."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import secrets

import pytest
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from testkit.device_sim import Deferred

from .conftest import ADMIN_PASSWORD, BASE, CHATGPT_REDIRECT, Harness

pytestmark = pytest.mark.anyio

EXPECTED_TOOLS = {
    "get_device_status",
    "get_screen_info",
    "capture_screen",
    "open_app",
    "tap_element",
    "swipe_screen",
    "input_text",
    "press_back",
    "press_home",
    "search_files",
    "open_file",
    "get_notifications",
    "run_sequence",
    "list_devices",
    "get_command_result",
}


def mcp_client(bridge: Harness, token: str, mode: str = "legacy") -> Client:
    transport = streamable_http_client(f"{BASE}/mcp", http_client=bridge.mcp_http(token))
    return Client(transport, mode=mode)  # legacy = рукопожатие initialize, как у ChatGPT


async def test_unauthenticated_mcp_gets_401_with_metadata(bridge: Harness) -> None:
    r = await bridge.client.post(
        "/mcp",
        json={"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
        headers={"Accept": "application/json, text/event-stream"},
    )
    assert r.status_code == 401
    challenge = r.headers["www-authenticate"]
    assert "resource_metadata" in challenge and "/.well-known/oauth-protected-resource/mcp" in challenge


async def test_discovery_metadata(bridge: Harness) -> None:
    prm = (await bridge.client.get("/.well-known/oauth-protected-resource/mcp")).json()
    assert prm["resource"] == f"{BASE}/mcp" and prm["authorization_servers"] == [BASE]
    root = (await bridge.client.get("/.well-known/oauth-protected-resource")).json()
    assert root["resource"] == f"{BASE}/mcp" and root["scopes_supported"] == ["device.read", "device.control"]
    asm = (await bridge.client.get("/.well-known/oauth-authorization-server")).json()
    assert asm["issuer"] == BASE
    assert asm["code_challenge_methods_supported"] == ["S256"]
    assert asm["registration_endpoint"] == f"{BASE}/register"
    assert set(asm["scopes_supported"]) == {"device.read", "device.control"}


async def test_registration_rejects_foreign_redirects(bridge: Harness) -> None:
    r = await bridge.client.post(
        "/register",
        json={"client_name": "Evil", "redirect_uris": ["https://evil.example/cb"], "grant_types": ["authorization_code"]},
    )
    assert r.status_code == 400 and r.json()["error"] == "invalid_redirect_uri"


async def test_consent_requires_password_and_csrf(bridge: Harness) -> None:
    r = await bridge.client.post(
        "/register",
        json={"client_name": "ChatGPT", "redirect_uris": [CHATGPT_REDIRECT], "grant_types": ["authorization_code", "refresh_token"]},
    )
    client = r.json()
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    r = await bridge.client.get(
        "/authorize",
        params={
            "response_type": "code",
            "client_id": client["client_id"],
            "redirect_uri": CHATGPT_REDIRECT,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "state": "xyz",
        },
    )
    req = r.headers["location"].split("req=")[1]
    page = await bridge.client.get(f"/oauth/consent?req={req}")
    assert "ChatGPT" in page.text and "chatgpt.com" in page.text
    assert page.headers["x-frame-options"] == "DENY"
    csrf = page.text.split('name="csrf" value="')[1].split('"')[0]
    r = await bridge.client.post("/oauth/consent", data={"req": req, "csrf": "bad", "password": ADMIN_PASSWORD, "action": "allow"})
    assert r.status_code == 400
    r = await bridge.client.post("/oauth/consent", data={"req": req, "csrf": csrf, "password": "nope", "action": "allow"})
    assert r.status_code == 401 and "Неверный пароль" in r.text
    r = await bridge.client.post("/oauth/consent", data={"req": req, "csrf": csrf, "action": "deny"})
    assert r.status_code == 303 and "error=access_denied" in r.headers["location"] and "state=xyz" in r.headers["location"]
    # Повторно использовать тот же запрос нельзя
    r = await bridge.client.post("/oauth/consent", data={"req": req, "csrf": csrf, "password": ADMIN_PASSWORD, "action": "allow"})
    assert r.status_code == 400


async def test_resource_mismatch_is_rejected(bridge: Harness) -> None:
    r = await bridge.client.post(
        "/register", json={"client_name": "X", "redirect_uris": [CHATGPT_REDIRECT], "grant_types": ["authorization_code"]}
    )
    client = r.json()
    r = await bridge.client.get(
        "/authorize",
        params={
            "response_type": "code",
            "client_id": client["client_id"],
            "redirect_uri": CHATGPT_REDIRECT,
            "code_challenge": "a" * 43,
            "code_challenge_method": "S256",
            "resource": "https://other.example/mcp",
        },
    )
    assert r.status_code == 302 and "error=invalid_target" in r.headers["location"]


async def test_full_flow_tools_and_device_call(bridge: Harness) -> None:
    auth = await bridge.oauth()
    tokens = auth["tokens"]
    assert tokens["token_type"].lower() == "bearer" and tokens["scope"] == "device.read device.control"
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    async with mcp_client(bridge, tokens["access_token"]) as client:
        listed = await client.list_tools()
        names = {t.name for t in listed.tools}
        assert names == EXPECTED_TOOLS
        by_name = {t.name: t for t in listed.tools}
        assert by_name["get_screen_info"].annotations.read_only_hint is True
        assert by_name["tap_element"].annotations.read_only_hint is False
        assert by_name["tap_element"].annotations.destructive_hint is True
        assert "element_id" in by_name["tap_element"].input_schema["properties"]

        result = await client.call_tool("get_device_status", {})
        assert result.is_error is not True, result
        assert result.structured_content["data"]["device"]["model"] == "Simulator"

        screen = await client.call_tool("get_screen_info", {"max_elements": 50})
        text = screen.content[0].text
        assert "e2 Button \"Новая\"" in text and "snapshot_id=s_sim1" in text

        tap = await client.call_tool("tap_element", {"element_id": "e2", "snapshot_id": "s_sim1"})
        assert tap.is_error is not True
        assert ("tap_element" in [tool for _, tool in runner.sim.executed])

        bad = await client.call_tool("tap_element", {"element_id": "e2", "text": "both"})
        assert bad.is_error is True and "bad_args" in bad.content[0].text

        unknown = await client.call_tool("get_device_status", {"rm": "-rf"})
        assert unknown.is_error is True and "неизвестные аргументы" in unknown.content[0].text

        devices = await client.call_tool("list_devices", {})
        assert devices.structured_content["devices"][0]["online"] is True

    # Подключение видно в админ-панели и в статусе интеграции
    clients = (await bridge.admin("GET", "/api/admin/oauth/clients")).json()["clients"]
    assert clients[0]["name"] == "ChatGPT" and clients[0]["active_grants"] == 1
    status = await bridge.state.integration_status()
    assert status["authorized_clients"] == 1


async def test_modern_protocol_mode_also_works(bridge: Harness) -> None:
    auth = await bridge.oauth()
    await bridge.connect(await bridge.pair())
    async with mcp_client(bridge, auth["tokens"]["access_token"], mode="auto") as client:
        result = await client.call_tool("press_back", {})
        assert result.is_error is not True


async def test_read_only_grant_cannot_control(bridge: Harness) -> None:
    auth = await bridge.oauth(control=False)
    assert auth["tokens"]["scope"] == "device.read"
    runner = await bridge.connect(await bridge.pair())
    async with mcp_client(bridge, auth["tokens"]["access_token"]) as client:
        ok = await client.call_tool("get_screen_info", {})
        assert ok.is_error is not True
        denied = await client.call_tool("tap_element", {"text": "OK"})
        assert denied.is_error is True and "forbidden" in denied.content[0].text
    assert [tool for _, tool in runner.sim.executed] == ["get_screen_info"]


async def test_capture_returns_image_and_temporary_link(bridge: Harness) -> None:
    auth = await bridge.oauth()
    await bridge.connect(await bridge.pair())
    async with mcp_client(bridge, auth["tokens"]["access_token"]) as client:
        shot = await client.call_tool("capture_screen", {"format": "png"})
        assert shot.is_error is not True
        kinds = [c.type for c in shot.content]
        assert kinds == ["text", "image"]
        url = shot.structured_content["url"]
        assert url.startswith(f"{BASE}/s/") and "data" not in shot.structured_content
    r = await bridge.client.get(url.replace(BASE, ""))
    assert r.status_code == 200 and r.headers["content-type"] == "image/png" and r.content.startswith(b"\x89PNG")
    assert (await bridge.client.get("/s/unknown-token")).status_code == 404


async def test_pending_then_get_command_result(bridge: Harness) -> None:
    def handler(tool: str, args: dict) -> object:
        return Deferred() if tool == "input_text" else {"ok": True}

    # Settings — frozen dataclass; для теста сокращаем ожидание результата
    object.__setattr__(bridge.state.settings, "command_wait_seconds", 0.3)
    auth = await bridge.oauth()
    runner = await bridge.connect(await bridge.pair(), handler=handler)
    async with mcp_client(bridge, auth["tokens"]["access_token"]) as client:
        first = await client.call_tool("input_text", {"text": "Привет", "submit": True})
        assert first.is_error is not True
        assert first.structured_content["status"] == "pending"
        command_id = first.structured_content["command_id"]
        await runner.send(runner.sim.confirm(command_id, approve=True, data={"typed": 6}))
        final = await client.call_tool("get_command_result", {"command_id": command_id, "wait_seconds": 5})
        assert final.is_error is not True and final.structured_content["data"] == {"typed": 6}


async def test_offline_device_error_is_clear(bridge: Harness) -> None:
    auth = await bridge.oauth()
    await bridge.pair()
    async with mcp_client(bridge, auth["tokens"]["access_token"]) as client:
        result = await client.call_tool("get_device_status", {})
        assert result.is_error is True and "device_offline" in result.content[0].text


async def test_refresh_rotation_and_reuse_detection(bridge: Harness) -> None:
    auth = await bridge.oauth()
    client = auth["client"]
    old_refresh = auth["tokens"]["refresh_token"]
    data = {
        "grant_type": "refresh_token",
        "refresh_token": old_refresh,
        "client_id": client["client_id"],
        "client_secret": client["client_secret"],
    }
    r = await bridge.client.post("/token", data=data)
    assert r.status_code == 200, r.text
    fresh = r.json()
    assert fresh["refresh_token"] != old_refresh
    # Старый access-токен больше не действует
    async with bridge.mcp_http(auth["tokens"]["access_token"]) as http:
        r = await http.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "ping"},
                            headers={"Accept": "application/json, text/event-stream"})
        assert r.status_code == 401
    # Новый access-токен работает...
    async with bridge.mcp_http(fresh["access_token"]) as http:
        r = await http.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "ping"},
                            headers={"Accept": "application/json, text/event-stream"})
        assert r.status_code == 200
    # ...но повторное использование старого refresh-токена (признак кражи) отзывает всю выдачу
    r = await bridge.client.post("/token", data=data)
    assert r.status_code == 400
    async with bridge.mcp_http(fresh["access_token"]) as http:
        r = await http.post("/mcp", json={"jsonrpc": "2.0", "id": 2, "method": "ping"},
                            headers={"Accept": "application/json, text/event-stream"})
        assert r.status_code == 401
    r = await bridge.client.post("/token", data={**data, "refresh_token": fresh["refresh_token"]})
    assert r.status_code == 400
    events = (await bridge.admin("GET", "/api/admin/audit")).json()["events"]
    assert any(e["event"] == "security.refresh_token_reuse" for e in events)


async def test_code_reuse_and_wrong_verifier(bridge: Harness) -> None:
    r = await bridge.client.post(
        "/register",
        json={"client_name": "ChatGPT", "redirect_uris": [CHATGPT_REDIRECT], "grant_types": ["authorization_code"]},
    )
    client = r.json()
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    r = await bridge.client.get(
        "/authorize",
        params={"response_type": "code", "client_id": client["client_id"], "redirect_uri": CHATGPT_REDIRECT,
                "code_challenge": challenge, "code_challenge_method": "S256"},
    )
    req = r.headers["location"].split("req=")[1]
    page = await bridge.client.get(f"/oauth/consent?req={req}")
    csrf = page.text.split('name="csrf" value="')[1].split('"')[0]
    r = await bridge.client.post("/oauth/consent", data={"req": req, "csrf": csrf, "password": ADMIN_PASSWORD, "action": "allow", "control": "1"})
    code = r.headers["location"].split("code=")[1].split("&")[0]
    base = {"grant_type": "authorization_code", "code": code, "redirect_uri": CHATGPT_REDIRECT,
            "client_id": client["client_id"], "client_secret": client["client_secret"]}
    r = await bridge.client.post("/token", data={**base, "code_verifier": secrets.token_urlsafe(48)})
    assert r.status_code == 400
    r = await bridge.client.post("/token", data={**base, "code_verifier": verifier})
    assert r.status_code == 200
    r = await bridge.client.post("/token", data={**base, "code_verifier": verifier})
    assert r.status_code == 400


async def test_admin_revocation_cuts_access(bridge: Harness) -> None:
    auth = await bridge.oauth()
    r = await bridge.admin("POST", "/api/admin/oauth/revoke-all")
    assert r.json()["revoked"] >= 2
    async with bridge.mcp_http(auth["tokens"]["access_token"]) as http:
        r = await http.post("/mcp", json={"jsonrpc": "2.0", "id": 1, "method": "ping"},
                            headers={"Accept": "application/json, text/event-stream"})
        assert r.status_code == 401


async def test_concurrent_calls_are_independent(bridge: Harness) -> None:
    auth = await bridge.oauth()
    runner = await bridge.connect(await bridge.pair())
    async with mcp_client(bridge, auth["tokens"]["access_token"]) as client:
        results = await asyncio.gather(*[client.call_tool("press_home", {}) for _ in range(5)])
    assert all(r.is_error is not True for r in results)
    assert len(runner.sim.executed) == 5
