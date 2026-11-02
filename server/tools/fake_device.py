#!/usr/bin/env python3
"""Имитатор телефона: подключается к Cloud Bridge как настоящее устройство.

Нужен, чтобы проверить сервер и подключение ChatGPT ещё до установки APK.
Требует пакет websockets (pip install -r requirements-dev.txt).

    python tools/fake_device.py --server https://your-bridge.onrender.com --pair ABCDE-FGHIJ
    python tools/fake_device.py            # повторный запуск с сохранёнными данными
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from testkit.device_sim import DeviceSimulator  # noqa: E402

STATE = Path.home() / ".swordai-fake-device.json"


def pair(server: str, code: str) -> dict:
    body = json.dumps(
        {
            "code": code,
            "device": {"name": "Имитатор", "manufacturer": "SwordAI", "model": "Simulator", "android_release": "16", "sdk_int": 36},
            "app_version": "sim-1.0",
            "protocol": 1,
        }
    ).encode()
    request = urllib.request.Request(server.rstrip("/") + "/api/v1/pair", data=body, method="POST",
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = json.loads(response.read())
    STATE.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"Сопряжено: {data['device_id']}. Отпечаток ключа сервера: {data['server']['signing_key_fingerprint']}")
    return data


async def run(data: dict) -> None:
    import websockets  # type: ignore[import-not-found]

    sim = DeviceSimulator(data["device_id"], data["server"]["signing_public_key"])
    headers = {"Authorization": f"Bearer {data['device_token']}"}
    url = data["server"]["ws_url"]
    delay = 1.0
    while True:
        try:
            try:
                connection = websockets.connect(url, additional_headers=headers, max_size=8 * 1024 * 1024)
            except TypeError:  # websockets < 14
                connection = websockets.connect(url, extra_headers=headers, max_size=8 * 1024 * 1024)
            async with connection as ws:
                print("Подключено к", url)
                delay = 1.0

                async def heartbeat() -> None:
                    while True:
                        await asyncio.sleep(20)
                        await ws.send(json.dumps({"type": "ping", "t": int(time.time() * 1000)}))

                beat = asyncio.create_task(heartbeat())
                try:
                    async for raw in ws:
                        message = json.loads(raw)
                        if message.get("type") == "command":
                            payload = json.loads(message["payload"])
                            print(f"← {payload['tool']} {json.dumps(payload['args'], ensure_ascii=False)}")
                        for out in sim.on_message(message):
                            await ws.send(json.dumps(out, ensure_ascii=False))
                finally:
                    beat.cancel()
        except Exception as exc:  # noqa: BLE001
            print(f"Соединение потеряно: {exc}. Повтор через {delay:.0f} с")
            await asyncio.sleep(delay)
            delay = min(60.0, delay * 2)


def main() -> int:
    parser = argparse.ArgumentParser(description="Имитатор устройства SwordAI")
    parser.add_argument("--server")
    parser.add_argument("--pair", metavar="CODE")
    args = parser.parse_args()
    if args.pair:
        if not args.server:
            parser.error("--pair требует --server")
        data = pair(args.server, args.pair)
    elif STATE.exists():
        data = json.loads(STATE.read_text(encoding="utf-8"))
    else:
        parser.error("сначала выполните сопряжение: --server URL --pair CODE")
    try:
        asyncio.run(run(data))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
