"""Настройки сервера. Все значения задаются переменными окружения."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from urllib.parse import urlparse


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int, minimum: int | None = None, maximum: int | None = None) -> int:
    raw = os.environ.get(name)
    value = default if raw is None or raw.strip() == "" else int(raw)
    if minimum is not None and value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    if maximum is not None and value > maximum:
        raise ValueError(f"{name} must be <= {maximum}")
    return value


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    return default if raw is None or raw.strip() == "" else float(raw)


def _env_list(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return tuple(item.strip().lower() for item in raw.split(",") if item.strip())


@dataclass(frozen=True)
class Settings:
    # Публичный адрес сервера без завершающего слеша, например https://bridge.onrender.com
    public_url: str = "http://localhost:8000"
    # sqlite:///data/bridge.db  или  postgresql://user:pass@host/db
    database_url: str = "sqlite:///data/bridge.db"
    # Пароль администратора (страница согласия OAuth и админ-панель).
    admin_password: str | None = None
    # Ключ подписи команд (PEM ECDSA P-256). Если не задан — генерируется и хранится в БД.
    signing_key_pem: str | None = None
    # Разрешённые хосты redirect_uri при динамической регистрации OAuth-клиентов.
    oauth_redirect_hosts: tuple[str, ...] = ("chatgpt.com", "chat.openai.com")
    # Разрешить redirect на localhost (MCP Inspector и локальные тесты).
    allow_loopback_redirects: bool = True
    access_token_ttl: int = 3600
    refresh_token_ttl: int = 90 * 24 * 3600
    # Сколько секунд MCP-вызов ждёт результат от телефона, прежде чем вернуть "pending".
    command_wait_seconds: float = 50.0
    # Срок, в течение которого телефон может начать выполнение команды.
    command_ttl_seconds: int = 120
    # Сколько ждать, пока офлайн-устройство переподключится.
    offline_grace_seconds: float = 12.0
    # Временные ссылки на скриншоты (для просмотра человеком).
    screenshot_links: bool = True
    screenshot_link_ttl: int = 300
    audit_retention_days: int = 30
    pairing_code_ttl: int = 600
    admin_session_ttl: int = 12 * 3600
    trust_proxy_headers: bool = True
    max_ws_message_bytes: int = 8 * 1024 * 1024
    heartbeat_seconds: int = 20
    log_level: str = "INFO"
    server_name: str = "SwordAI Bridge"
    extra: dict[str, str] = field(default_factory=dict)

    @property
    def mcp_url(self) -> str:
        return f"{self.public_url}/mcp"

    @property
    def ws_url(self) -> str:
        parsed = urlparse(self.public_url)
        scheme = "wss" if parsed.scheme == "https" else "ws"
        return f"{scheme}://{parsed.netloc}{parsed.path}/ws/device"

    @property
    def is_https(self) -> bool:
        return self.public_url.startswith("https://")

    @classmethod
    def from_env(cls) -> Settings:
        public_url = (
            os.environ.get("PUBLIC_URL")
            or os.environ.get("RENDER_EXTERNAL_URL")
            or f"http://localhost:{os.environ.get('PORT', '8000')}"
        ).strip().rstrip("/")
        parsed = urlparse(public_url)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("PUBLIC_URL must look like https://example.com")
        if parsed.query or parsed.fragment:
            raise ValueError("PUBLIC_URL must not contain query or fragment")
        signing_key = os.environ.get("SIGNING_KEY")
        return cls(
            public_url=public_url,
            database_url=(os.environ.get("DATABASE_URL") or "sqlite:///data/bridge.db").strip(),
            admin_password=os.environ.get("ADMIN_PASSWORD") or None,
            signing_key_pem=signing_key.strip() if signing_key else None,
            oauth_redirect_hosts=_env_list("OAUTH_REDIRECT_HOSTS", ("chatgpt.com", "chat.openai.com")),
            allow_loopback_redirects=_env_bool("ALLOW_LOOPBACK_REDIRECTS", True),
            access_token_ttl=_env_int("ACCESS_TOKEN_TTL", 3600, 300, 24 * 3600),
            refresh_token_ttl=_env_int("REFRESH_TOKEN_TTL", 90 * 24 * 3600, 3600, 365 * 24 * 3600),
            command_wait_seconds=_env_float("COMMAND_WAIT_SECONDS", 50.0),
            command_ttl_seconds=_env_int("COMMAND_TTL_SECONDS", 120, 15, 600),
            offline_grace_seconds=_env_float("OFFLINE_GRACE_SECONDS", 12.0),
            screenshot_links=_env_bool("SCREENSHOT_LINKS", True),
            screenshot_link_ttl=_env_int("SCREENSHOT_LINK_TTL", 300, 30, 3600),
            audit_retention_days=_env_int("AUDIT_RETENTION_DAYS", 30, 1, 3650),
            pairing_code_ttl=_env_int("PAIRING_CODE_TTL", 600, 60, 3600),
            admin_session_ttl=_env_int("ADMIN_SESSION_TTL", 12 * 3600, 600, 7 * 24 * 3600),
            trust_proxy_headers=_env_bool("TRUST_PROXY_HEADERS", True),
            max_ws_message_bytes=_env_int("MAX_WS_MESSAGE_BYTES", 8 * 1024 * 1024, 64 * 1024, 32 * 1024 * 1024),
            heartbeat_seconds=_env_int("HEARTBEAT_SECONDS", 20, 5, 120),
            log_level=os.environ.get("LOG_LEVEL", "INFO").upper(),
            server_name=os.environ.get("SERVER_NAME", "SwordAI Bridge"),
        )
