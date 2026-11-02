"""Запуск: python -m bridge  (порт из переменной PORT, по умолчанию 8000)."""

from __future__ import annotations

import os

import uvicorn


def main() -> None:
    port = int(os.environ.get("PORT", "8000"))
    host = os.environ.get("HOST", "0.0.0.0")
    uvicorn.run(
        "bridge.app:create_app",
        factory=True,
        host=host,
        port=port,
        proxy_headers=True,
        forwarded_allow_ips=os.environ.get("FORWARDED_ALLOW_IPS", "*"),
        ws_ping_interval=20.0,
        ws_ping_timeout=30.0,
        ws_max_size=int(os.environ.get("MAX_WS_MESSAGE_BYTES", str(8 * 1024 * 1024))),
        timeout_keep_alive=75,
        log_level=os.environ.get("LOG_LEVEL", "info").lower(),
    )


if __name__ == "__main__":
    main()
