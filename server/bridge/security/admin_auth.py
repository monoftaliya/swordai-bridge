"""Пароль администратора и сессии админ-панели."""

from __future__ import annotations

import hashlib
import hmac
import logging
import secrets

from ..config import Settings
from ..store import Store
from .tokens import hash_password, hash_token, new_token, verify_password

log = logging.getLogger("bridge.admin")
_KV_PASSWORD = "admin_password_hash"


class AdminAuth:
    def __init__(self, settings: Settings, store: Store) -> None:
        self.settings = settings
        self.store = store
        self._stored_hash: str | None = None
        self.generated_password: str | None = None

    async def init(self) -> None:
        if self.settings.admin_password:
            if len(self.settings.admin_password) < 10:
                log.warning("ADMIN_PASSWORD is shorter than 10 characters — use a long random password")
            return
        stored = await self.store.kv_get(_KV_PASSWORD)
        if stored is None:
            password = "-".join(secrets.token_urlsafe(6) for _ in range(3))
            stored = await self.store.kv_set_if_absent(_KV_PASSWORD, hash_password(password))
            if verify_password(password, stored):
                self.generated_password = password
                log.warning(
                    "ADMIN_PASSWORD is not set. Generated admin password (shown once): %s  "
                    "— set ADMIN_PASSWORD in the environment to choose your own.",
                    password,
                )
        self._stored_hash = stored

    def verify(self, password: str) -> bool:
        if not password or len(password) > 512:
            return False
        if self.settings.admin_password:
            expected = hashlib.sha256(self.settings.admin_password.encode("utf-8")).digest()
            given = hashlib.sha256(password.encode("utf-8")).digest()
            return hmac.compare_digest(expected, given)
        if self._stored_hash is None:
            return False
        return verify_password(password, self._stored_hash)

    async def create_session(self, ip: str | None) -> tuple[str, float]:
        token = new_token("sas", 32)
        expires_at = await self.store.admin_session_create(hash_token(token), self.settings.admin_session_ttl, ip)
        return token, expires_at

    async def check_session(self, token: str | None) -> bool:
        if not token or not token.startswith("sas_") or len(token) > 200:
            return False
        return await self.store.admin_session_valid(hash_token(token))

    async def end_session(self, token: str) -> None:
        await self.store.admin_session_delete(hash_token(token))
