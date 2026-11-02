"""Сопряжение, WebSocket-канал, подпись команд, защита от повтора, отзыв, пауза."""

from __future__ import annotations

import asyncio
import json
import time

import pytest

from testkit.asgi_ws import AsgiWebSocket, WebSocketClosed, WebSocketRejected
from testkit.device_sim import Deferred, DeviceSimulator, SimError

from .conftest import Harness

pytestmark = pytest.mark.anyio


async def test_health_and_landing(bridge: Harness) -> None:
    r = await bridge.client.get("/healthz")
    assert r.status_code == 200 and r.json()["ok"] is True
    r = await bridge.client.get("/")
    assert r.status_code == 200 and "/mcp" in r.text
    assert r.headers["x-frame-options"] == "DENY"


async def test_admin_login(bridge: Harness) -> None:
    r = await bridge.client.post("/api/admin/login", json={"password": "wrong"})
    assert r.status_code == 401
    r = await bridge.client.get("/api/admin/overview")
    assert r.status_code == 401
    r = await bridge.admin("GET", "/api/admin/overview")
    assert r.status_code == 200
    body = r.json()
    assert body["server"]["mcp_enabled"] is True
    assert len(body["server"]["signing_key_fingerprint"].split()) == 8


async def test_pairing_code_is_single_use(bridge: Harness) -> None:
    code = await bridge.pairing_code()
    payload = {
        "code": code.lower().replace("-", " "),  # нормализация ввода пользователя
        "device": {"name": "Phone"},
        "app_version": "1.0.0",
        "protocol": 1,
    }
    r = await bridge.client.post("/api/v1/pair", json=payload)
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["device_id"].startswith("dev_") and data["device_token"].startswith("sdt_")
    assert data["server"]["ws_url"] == "ws://localhost:8000/ws/device"
    r = await bridge.client.post("/api/v1/pair", json=payload)
    assert r.status_code == 403
    r = await bridge.client.post("/api/v1/pair", json={**payload, "code": "short"})
    assert r.status_code == 400
    r = await bridge.client.post("/api/v1/pair", json={**payload, "unexpected": True})
    assert r.status_code == 400


async def test_pairing_rate_limit(bridge: Harness) -> None:
    payload = {"code": "AAAAA-BBBBB", "device": {"name": "x"}, "app_version": "1", "protocol": 1}
    statuses = [(await bridge.client.post("/api/v1/pair", json=payload)).status_code for _ in range(12)]
    assert statuses[:10] == [403] * 10
    assert statuses[10:] == [429, 429]


async def test_ws_rejects_bad_tokens(bridge: Harness) -> None:
    for headers in ({}, {"Authorization": "Bearer sdt_nope"}, {"Authorization": "Basic abc"}):
        ws = AsgiWebSocket(bridge.app, "/ws/device", headers)
        with pytest.raises(WebSocketRejected) as info:
            await ws.connect()
        assert info.value.code == 4401


async def test_command_roundtrip_and_audit(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "get_device_status", "args": {}})
    assert r.status_code == 200, r.text
    outcome = r.json()
    assert outcome["status"] == "succeeded" and outcome["ok"] is True
    assert outcome["data"]["device"]["model"] == "Simulator"
    assert len(runner.sim.executed) == 1
    events = (await bridge.admin("GET", "/api/admin/audit")).json()["events"]
    names = {e["event"] for e in events}
    assert {"device.paired", "device.connected", "command.created", "command.succeeded"} <= names
    devices = (await bridge.admin("GET", "/api/admin/devices")).json()["devices"]
    assert devices[0]["online"] is True and devices[0]["model"] == "Simulator"


async def test_bad_args_are_rejected_before_device(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    bad = [
        ("tap_element", {"element_id": "e1", "text": "OK"}),  # две цели
        ("tap_element", {"x": 5}),  # нет y
        ("tap_element", {"element_id": "1; rm -rf"}),  # шаблон id
        ("input_text", {"text": "a\x00b"}),  # управляющий символ
        ("open_app", {"package_name": "com.example", "evil": 1}),  # лишнее поле
        ("swipe_screen", {"direction": "diagonal"}),
        ("run_sequence", {"steps": [{"tool": "capture_screen"}]}),  # запрещённый шаг
        ("run_sequence", {"steps": [{"tool": "tap_element", "args": {}}]}),  # неверные аргументы шага
    ]
    for tool, args in bad:
        r = await bridge.admin("POST", "/api/admin/commands", json={"tool": tool, "args": args})
        assert r.status_code == 400, (tool, args, r.text)
        assert r.json()["error"]["code"] == "bad_args"
    assert runner.sim.executed == []


async def test_tampered_command_is_rejected_by_device(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    state = bridge.state
    payload = json.dumps(
        {
            "v": 1,
            "command_id": "cmd_ATTACKER0001",
            "device_id": paired["device_id"],
            "seq": 999,
            "tool": "tap_element",
            "args": {"text": "Оплатить"},
            "issued_at": time.time(),
            "expires_at": time.time() + 60,
        }
    )
    good_sig = state.signer.sign(b"something else")  # подпись не от этого payload
    out = runner.sim.on_command({"type": "command", "payload": payload, "sig": good_sig})
    assert out[0]["event"] == "command_rejected" and runner.sim.rejected == ["invalid_signature"]
    # Подпись верная, но устройство чужое
    other = json.dumps({**json.loads(payload), "device_id": "dev_someoneelse12"})
    out = runner.sim.on_command({"type": "command", "payload": other, "sig": state.signer.sign(other.encode())})
    assert runner.sim.rejected[-1] == "wrong_device"
    assert runner.sim.executed == []


async def test_replay_returns_cached_result_without_reexecution(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "press_back", "args": {}})
    assert r.json()["status"] == "succeeded"
    envelope = next(m for m in runner.inbox if m["type"] == "command")
    again = runner.sim.on_command(envelope)
    assert again[0]["type"] == "result" and again[0]["ok"] is True
    assert [tool for _, tool in runner.sim.executed] == ["press_back"]
    # Новая команда с тем же или меньшим seq (подписана сервером) не выполняется
    payload = json.loads(envelope["payload"])
    forged = json.dumps({**payload, "command_id": "cmd_REPLAYOLDSEQ1"}, separators=(",", ":"))
    out = runner.sim.on_command({"type": "command", "payload": forged, "sig": bridge.state.signer.sign(forged.encode())})
    assert out[0]["error"]["code"] == "replayed"
    # Просроченная команда
    expired = json.dumps({**payload, "command_id": "cmd_EXPIREDCMD01", "seq": 10_000, "expires_at": time.time() - 5})
    out = runner.sim.on_command({"type": "command", "payload": expired, "sig": bridge.state.signer.sign(expired.encode())})
    assert out[0]["error"]["code"] == "expired"
    assert len(runner.sim.executed) == 1


async def test_sequence_numbers_are_monotonic(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    for _ in range(3):
        r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "press_home", "args": {}})
        assert r.json()["status"] == "succeeded"
    seqs = [json.loads(m["payload"])["seq"] for m in runner.inbox if m["type"] == "command"]
    assert seqs == [1, 2, 3]


async def test_offline_device_fails_fast(bridge: Harness) -> None:
    await bridge.pair()
    started = time.monotonic()
    r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "get_device_status", "args": {}})
    assert time.monotonic() - started < 3
    assert r.json()["status"] == "failed" and r.json()["error"]["code"] == "device_offline"


async def test_redelivery_after_reconnect_executes_once(bridge: Harness) -> None:
    paired = await bridge.pair()
    first = await bridge.connect(paired)
    first.respond = False  # телефон «потерял связь», не ответив
    task = asyncio.create_task(
        bridge.admin("POST", "/api/admin/commands", json={"tool": "get_screen_info", "args": {}, "wait": 10})
    )
    for _ in range(100):
        await asyncio.sleep(0.02)
        if any(m["type"] == "command" for m in first.inbox):
            break
    await first.stop()
    second = await bridge.connect(paired)
    outcome = (await asyncio.wait_for(task, 10)).json()
    assert outcome["status"] == "succeeded"
    assert outcome["data"]["snapshot_id"] == "s_sim1"
    assert len(second.sim.executed) == 1


async def test_confirmation_flow_with_pending_and_result(bridge: Harness) -> None:
    def handler(tool: str, args: dict) -> object:
        if tool == "tap_element":
            return Deferred()
        return {"ok": True}

    paired = await bridge.pair()
    runner = await bridge.connect(paired, handler=handler)
    r = await bridge.admin(
        "POST", "/api/admin/commands", json={"tool": "tap_element", "args": {"text": "Отправить"}, "wait": 0.5}
    )
    pending = r.json()
    assert pending["status"] == "pending" and pending["state"] == "awaiting_confirmation"
    command_id = pending["command_id"]
    await runner.send(runner.sim.confirm(command_id, approve=True, data={"tapped": "Отправить"}))
    r = await bridge.admin("GET", f"/api/admin/commands/{command_id}?wait=5")
    final = r.json()
    assert final["status"] == "succeeded" and final["data"] == {"tapped": "Отправить"}

    r = await bridge.admin(
        "POST", "/api/admin/commands", json={"tool": "tap_element", "args": {"text": "Удалить"}, "wait": 0.5}
    )
    command_id = r.json()["command_id"]
    await runner.send(runner.sim.confirm(command_id, approve=False))
    final = (await bridge.admin("GET", f"/api/admin/commands/{command_id}?wait=5")).json()
    assert final["status"] == "rejected" and final["error"]["code"] == "rejected_by_user"


async def test_device_errors_are_reported(bridge: Harness) -> None:
    def handler(tool: str, args: dict) -> object:
        raise SimError("blocked_app", "Приложение защищено")

    paired = await bridge.pair()
    await bridge.connect(paired, handler=handler)
    r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "open_app", "args": {"app_name": "Банк"}})
    outcome = r.json()
    assert outcome["status"] == "rejected" and outcome["error"]["code"] == "blocked_app"


async def test_result_from_other_device_is_ignored(bridge: Harness) -> None:
    def slow(tool: str, args: dict) -> object:
        return Deferred()

    first_pair = await bridge.pair("A")
    second_pair = await bridge.pair("B")
    first = await bridge.connect(first_pair, handler=slow)
    second = await bridge.connect(second_pair)
    r = await bridge.admin(
        "POST",
        "/api/admin/commands",
        json={"tool": "press_back", "args": {}, "device_id": first_pair["device_id"], "wait": 0.3},
    )
    command_id = r.json()["command_id"]
    await second.send([{"type": "result", "command_id": command_id, "ok": True, "data": {"evil": True}}])
    await asyncio.sleep(0.1)
    outcome = (await bridge.admin("GET", f"/api/admin/commands/{command_id}")).json()
    assert outcome["status"] == "pending"
    events = (await bridge.admin("GET", "/api/admin/audit")).json()["events"]
    assert any(e["event"] == "security.result_from_wrong_device" for e in events)
    await first.send(first.sim.confirm(command_id, approve=True))
    outcome = (await bridge.admin("GET", f"/api/admin/commands/{command_id}?wait=5")).json()
    assert outcome["status"] == "succeeded" and "evil" not in (outcome["data"] or {})


async def test_two_online_devices_require_device_id(bridge: Harness) -> None:
    await bridge.connect(await bridge.pair("A"))
    await bridge.connect(await bridge.pair("B"))
    r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "press_back", "args": {}})
    assert r.status_code == 400 and r.json()["error"]["code"] == "ambiguous_device"


async def test_revocation_disconnects_and_blocks(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired, start=False)
    welcome = await runner.ws.receive_json()
    assert welcome["type"] == "welcome"
    r = await bridge.admin("POST", f"/api/admin/devices/{paired['device_id']}/revoke")
    assert r.status_code == 200
    with pytest.raises(WebSocketClosed) as info:
        await runner.ws.receive_json()
    assert info.value.code == 4403
    ws = AsgiWebSocket(bridge.app, "/ws/device", {"Authorization": f"Bearer {paired['device_token']}"})
    with pytest.raises(WebSocketRejected) as rejected:
        await ws.connect()
    assert rejected.value.code == 4403
    r = await bridge.client.get("/api/v1/device/me", headers={"Authorization": f"Bearer {paired['device_token']}"})
    assert r.status_code == 401


async def test_remote_pause_only_pauses(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired)
    r = await bridge.admin("POST", f"/api/admin/devices/{paired['device_id']}/pause")
    assert r.json()["delivered"] is True
    await asyncio.sleep(0.05)
    assert runner.sim.paused is True
    r = await bridge.admin("POST", "/api/admin/commands", json={"tool": "press_back", "args": {}})
    assert r.json()["status"] == "rejected" and r.json()["error"]["code"] == "paused"


async def test_device_self_unpair(bridge: Harness) -> None:
    paired = await bridge.pair()
    headers = {"Authorization": f"Bearer {paired['device_token']}"}
    r = await bridge.client.get("/api/v1/device/me", headers=headers)
    assert r.status_code == 200 and r.json()["device_id"] == paired["device_id"]
    r = await bridge.client.post("/api/v1/device/unpair", headers=headers)
    assert r.status_code == 200
    r = await bridge.client.get("/api/v1/device/me", headers=headers)
    assert r.status_code == 401


async def test_malformed_messages_do_not_break_channel(bridge: Harness) -> None:
    paired = await bridge.pair()
    runner = await bridge.connect(paired, start=False)
    await runner.ws.receive_json()  # welcome
    await runner.ws.send_text("not json")
    err = await runner.ws.receive_json()
    assert err["type"] == "error"
    await runner.ws.send_json({"type": "ping", "t": 123})
    pong = await runner.ws.receive_json()
    assert pong["type"] == "pong" and pong["t"] == 123 and "integration" in pong
    await runner.ws.send_json({"type": "result", "command_id": "../../etc", "ok": True})
    await runner.ws.send_json({"type": "ping", "t": 5})
    assert (await runner.ws.receive_json())["t"] == 5


async def test_signature_verifies_with_published_key(bridge: Harness) -> None:
    paired = await bridge.pair()
    sim = DeviceSimulator(paired["device_id"], paired["server"]["signing_public_key"])
    text = '{"a":1}'
    assert sim.verify({"payload": text, "sig": bridge.state.signer.sign(text.encode())})[1] == "malformed"
    assert sim.verify({"payload": text, "sig": "AAAA"})[1] == "invalid_signature"
