"""Модульные тесты: ключи подписи, схемы аргументов, токены, ограничитель частоты."""

from __future__ import annotations

import base64

import pytest

from bridge.commands.schemas import ArgumentError, required_scope, validate_args
from bridge.security.ratelimit import RateLimiter
from bridge.security.signing import SigningKey
from bridge.security.tokens import (
    hash_password,
    new_pairing_code,
    normalize_pairing_code,
    verify_password,
)
from testkit.device_sim import DeviceSimulator


def test_seed_key_is_deterministic_and_verifiable() -> None:
    seed = "x" * 16 + "random-seed-from-hosting-panel"
    a = SigningKey.from_config(seed)
    b = SigningKey.from_config(seed)
    assert a.public_der == b.public_der
    assert a.fingerprint == b.fingerprint
    assert SigningKey.from_config(seed + "!").public_der != a.public_der
    sig = a.sign(b"payload")
    assert b.verify(b"payload", sig) and not b.verify(b"payload2", sig)


def test_pem_and_base64_pem_config() -> None:
    key = SigningKey.generate()
    pem = key.to_pem()
    assert SigningKey.from_config(pem).public_der == key.public_der
    b64 = base64.b64encode(pem.encode()).decode()
    assert SigningKey.from_config(b64).public_der == key.public_der
    with pytest.raises(ValueError):
        SigningKey.from_config("too-short")


def test_simulator_accepts_server_signature() -> None:
    key = SigningKey.generate()
    sim = DeviceSimulator("dev_abcdefgh1234", key.public_key_b64)
    import json
    import time

    payload = json.dumps({"v": 1, "command_id": "cmd_ABCDEFGH1234", "device_id": "dev_abcdefgh1234", "seq": 1,
                          "tool": "press_back", "args": {}, "issued_at": time.time(), "expires_at": time.time() + 30})
    parsed, error = sim.verify({"payload": payload, "sig": key.sign(payload.encode())})
    assert error is None and parsed is not None and parsed["tool"] == "press_back"


@pytest.mark.parametrize(
    ("tool", "args", "expected"),
    [
        ("tap_element", {"element_id": "e12", "snapshot_id": "s_abc123"}, {"element_id": "e12", "snapshot_id": "s_abc123", "exact": False, "index": 0, "long_press": False}),
        ("tap_element", {"x": 10, "y": 20}, {"x": 10, "y": 20, "exact": False, "index": 0, "long_press": False}),
        ("open_app", {"package_name": "org.telegram.messenger"}, {"package_name": "org.telegram.messenger"}),
        ("swipe_screen", {"direction": "up"}, {"direction": "up", "distance": 0.5, "duration_ms": 300}),
        ("input_text", {"text": "строка 1\nстрока 2"}, {"text": "строка 1\nстрока 2", "mode": "replace", "submit": False}),
        ("search_files", {"query": "*.pdf", "mime_prefix": "application/pdf"}, {"query": "*.pdf", "mime_prefix": "application/pdf", "max_results": 30}),
    ],
)
def test_valid_args_are_normalized(tool: str, args: dict, expected: dict) -> None:
    assert validate_args(tool, args) == expected


@pytest.mark.parametrize(
    ("tool", "args"),
    [
        ("tap_element", {}),
        ("tap_element", {"snapshot_id": "s_abc123", "text": "x"}),
        ("tap_element", {"x": -1, "y": 2}),
        ("open_app", {}),
        ("open_app", {"package_name": "notapackage"}),
        ("open_app", {"package_name": "a.b", "app_name": "B"}),
        ("input_text", {"text": "x" * 2001}),
        ("input_text", {"text": "a\x07b"}),
        ("open_file", {"file_id": "../etc/passwd"}),
        ("get_notifications", {"limit": 0}),
        ("capture_screen", {"quality": 100}),
        ("run_sequence", {"steps": []}),
        ("run_sequence", {"steps": [{"tool": "press_back"}] * 11}),
        ("run_sequence", {"steps": [{"tool": "run_sequence", "args": {}}]}),
        ("unknown_tool", {}),
        ("press_back", {"extra": 1}),
    ],
)
def test_invalid_args_raise(tool: str, args: dict) -> None:
    with pytest.raises(ArgumentError):
        validate_args(tool, args)


def test_sequence_steps_are_validated_and_normalized() -> None:
    data = validate_args(
        "run_sequence",
        {"steps": [{"tool": "open_app", "args": {"app_name": "Камера"}, "wait_ms": 500}, {"tool": "press_back"}]},
    )
    assert data == {
        "steps": [
            {"tool": "open_app", "args": {"app_name": "Камера"}, "wait_ms": 500},
            {"tool": "press_back", "args": {}, "wait_ms": 0},
        ],
        "stop_on_error": True,
    }


def test_scopes() -> None:
    assert required_scope("get_screen_info") == "device.read"
    assert required_scope("capture_screen") == "device.read"
    assert required_scope("tap_element") == "device.control"
    assert required_scope("run_sequence") == "device.control"


def test_pairing_codes() -> None:
    code = new_pairing_code()
    assert len(code) == 11 and code[5] == "-"
    assert normalize_pairing_code(code.lower().replace("-", " ")) == code
    assert normalize_pairing_code("short") == ""


def test_password_hashing() -> None:
    stored = hash_password("s3cret-password")
    assert verify_password("s3cret-password", stored)
    assert not verify_password("wrong", stored)
    assert not verify_password("s3cret-password", "garbage")


def test_rate_limiter_window() -> None:
    limiter = RateLimiter()
    assert all(limiter.allow("k", 3, 60) for _ in range(3))
    assert not limiter.allow("k", 3, 60)
    assert limiter.allow("other", 3, 60)


def test_interop_fixture_matches_current_signing_code() -> None:
    """Фикстура для Kotlin-теста должна соответствовать текущей реализации подписи."""
    import json
    from pathlib import Path

    path = Path(__file__).resolve().parents[2] / "android/app/src/test/resources/interop/signed_command.json"
    fixture = json.loads(path.read_text(encoding="utf-8"))
    key = SigningKey.from_seed(fixture["seed"])
    assert key.public_key_b64 == fixture["public_key"]
    assert key.fingerprint == fixture["fingerprint"]
    envelope = fixture["envelope"]
    assert key.verify(envelope["payload"].encode("utf-8"), envelope["sig"])
