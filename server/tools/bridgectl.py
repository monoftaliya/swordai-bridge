#!/usr/bin/env python3
"""bridgectl — консольный клиент админ-API SwordAI Cloud Bridge (только стандартная библиотека).

Этап 2 (локальный тестовый сервер): управлять телефоном без ChatGPT.

    python tools/bridgectl.py --server http://192.168.1.10:8000 login
    python tools/bridgectl.py pair-code
    python tools/bridgectl.py devices
    python tools/bridgectl.py send get_screen_info '{"max_elements": 60}'
    python tools/bridgectl.py send tap_element '{"text": "Поиск"}'
    python tools/bridgectl.py send capture_screen --save shot.jpg
    python tools/bridgectl.py audit --limit 20
"""

from __future__ import annotations

import argparse
import base64
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

CONFIG = Path(os.environ.get("BRIDGECTL_CONFIG", Path.home() / ".swordai-bridgectl.json"))


def load_config() -> dict[str, Any]:
    if CONFIG.exists():
        return json.loads(CONFIG.read_text(encoding="utf-8"))
    return {}


def save_config(data: dict[str, Any]) -> None:
    CONFIG.write_text(json.dumps(data, indent=2), encoding="utf-8")
    try:
        CONFIG.chmod(0o600)
    except OSError:
        pass


class ApiError(Exception):
    pass


def call(server: str, method: str, path: str, token: str | None = None, body: Any = None, timeout: float = 70) -> Any:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(server.rstrip("/") + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8") or "null")
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode("utf-8"))
            message = payload.get("error", {}).get("message") or payload
        except ValueError:
            message = exc.reason
        raise ApiError(f"HTTP {exc.code}: {message}") from None
    except urllib.error.URLError as exc:
        raise ApiError(f"Сервер недоступен: {exc.reason}") from None


def ago(ts: float | None) -> str:
    if not ts:
        return "никогда"
    delta = int(time.time() - ts)
    if delta < 60:
        return f"{delta} с назад"
    if delta < 3600:
        return f"{delta // 60} мин назад"
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(ts))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="bridgectl", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", help="Адрес сервера, например http://192.168.1.10:8000")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("login", help="Войти (пароль администратора)")
    sub.add_parser("logout")
    p = sub.add_parser("pair-code", help="Создать одноразовый код сопряжения")
    p.add_argument("--label")
    sub.add_parser("devices", help="Список устройств")
    sub.add_parser("status", help="Сводка сервера")
    p = sub.add_parser("send", help="Отправить команду телефону")
    p.add_argument("tool")
    p.add_argument("args", nargs="?", default="{}", help="JSON с аргументами")
    p.add_argument("--device")
    p.add_argument("--wait", type=float, default=40)
    p.add_argument("--save", help="Сохранить изображение (для capture_screen)")
    p = sub.add_parser("result", help="Результат команды")
    p.add_argument("command_id")
    p.add_argument("--wait", type=float, default=20)
    p = sub.add_parser("audit", help="Журнал операций")
    p.add_argument("--limit", type=int, default=30)
    for name in ("revoke", "pause"):
        p = sub.add_parser(name, help=f"{name} устройства")
        p.add_argument("device_id")
    args = parser.parse_args(argv)

    config = load_config()
    server = args.server or config.get("server") or "http://localhost:8000"
    token = config.get("token") if config.get("server") == server else None

    try:
        if args.cmd == "login":
            password = os.environ.get("BRIDGE_ADMIN_PASSWORD") or getpass.getpass("Пароль администратора: ")
            data = call(server, "POST", "/api/admin/login", body={"password": password})
            save_config({"server": server, "token": data["token"]})
            print(f"Вход выполнен, сессия до {time.strftime('%H:%M', time.localtime(data['expires_at']))}")
            return 0
        if not token:
            print("Сначала выполните: bridgectl --server URL login", file=sys.stderr)
            return 2
        if args.cmd == "logout":
            call(server, "POST", "/api/admin/logout", token)
            save_config({"server": server})
        elif args.cmd == "pair-code":
            data = call(server, "POST", "/api/admin/pairing-codes", token, {"label": args.label})
            print(f"Код сопряжения: {data['code']}\nАдрес сервера:  {data['server_url']}")
            print(f"Действует до:   {time.strftime('%H:%M:%S', time.localtime(data['expires_at']))}")
        elif args.cmd == "devices":
            data = call(server, "GET", "/api/admin/devices", token)
            if not data["devices"]:
                print("Нет устройств")
            for d in data["devices"]:
                mark = "●" if d["online"] else "○"
                print(f"{mark} {d['device_id']}  {d['name']}  {d.get('model') or ''}  Android {d.get('android_version') or '?'}"
                      f"  был в сети {ago(d.get('last_seen_at'))}")
        elif args.cmd == "status":
            print(json.dumps(call(server, "GET", "/api/admin/overview", token), ensure_ascii=False, indent=2))
        elif args.cmd == "send":
            try:
                command_args = json.loads(args.args)
            except ValueError:
                print("args должен быть JSON-объектом", file=sys.stderr)
                return 2
            body: dict[str, Any] = {"tool": args.tool, "args": command_args, "wait": min(args.wait, 55)}
            if args.device:
                body["device_id"] = args.device
            outcome = call(server, "POST", "/api/admin/commands", token, body)
            while outcome.get("status") == "pending":
                state = outcome.get("state")
                print("… подтвердите действие на телефоне" if state == "awaiting_confirmation" else f"… {state}", file=sys.stderr)
                outcome = call(server, "GET", f"/api/admin/commands/{outcome['command_id']}?wait=20", token)
            image = outcome.pop("image", None)
            if image and image.get("data"):
                if args.save:
                    Path(args.save).write_bytes(base64.b64decode(image["data"]))
                    print(f"Изображение сохранено: {args.save}", file=sys.stderr)
                outcome["image"] = f"<{image.get('mime')}, {len(image['data']) * 3 // 4} байт>"
            print(json.dumps(outcome, ensure_ascii=False, indent=2))
            return 0 if outcome.get("status") == "succeeded" else 1
        elif args.cmd == "result":
            print(json.dumps(call(server, "GET", f"/api/admin/commands/{args.command_id}?wait={args.wait}", token),
                             ensure_ascii=False, indent=2))
        elif args.cmd == "audit":
            data = call(server, "GET", f"/api/admin/audit?limit={args.limit}", token)
            for e in reversed(data["events"]):
                stamp = time.strftime("%m-%d %H:%M:%S", time.localtime(e["ts"]))
                details = json.dumps(e["details"], ensure_ascii=False) if e.get("details") else ""
                print(f"{stamp}  {e['event']:<32} {e['actor']:<7} {e.get('device_id') or ''} {details}")
        elif args.cmd in ("revoke", "pause"):
            print(json.dumps(call(server, "POST", f"/api/admin/devices/{args.device_id}/{args.cmd}", token), ensure_ascii=False))
    except ApiError as exc:
        print(f"Ошибка: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
