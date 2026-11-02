#!/usr/bin/env python3
"""Генерирует фикстуру для теста совместимости подписи Python (сервер) ↔ Kotlin (телефон).

    python tools/make_interop_fixture.py ../android/app/src/test/resources/interop/signed_command.json
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from bridge.security.signing import SigningKey  # noqa: E402

SEED = "interop-test-seed-do-not-use-in-production-0123456789"


def build() -> dict:
    key = SigningKey.from_seed(SEED)
    payload = {
        "v": 1,
        "command_id": "cmd_InteropTest0001",
        "device_id": "dev_InteropDevice01",
        "seq": 7,
        "tool": "tap_element",
        "args": {"text": "Отправить «сообщение» ✓", "exact": False, "index": 0, "long_press": False},
        "issued_at": 1767225600.123,
        "expires_at": 1767225720.123,
        "origin": {"source": "mcp", "client": "ChatGPT"},
    }
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    return {
        "generated_at": int(time.time()),
        "seed": SEED,
        "public_key": key.public_key_b64,
        "fingerprint": key.fingerprint,
        "device_id": payload["device_id"],
        "envelope": {"type": "command", "payload": text, "sig": key.sign(text.encode("utf-8"))},
    }


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(build(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"written {out}")
