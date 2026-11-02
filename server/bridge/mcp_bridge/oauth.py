"""OAuth 2.1 Authorization Server для подключения ChatGPT к MCP.

Поток: динамическая регистрация клиента (DCR) → /authorize → наша страница
согласия (пароль администратора + выбор прав) → код с PKCE → токены.
Access/refresh-токены хранятся только в виде SHA-256, refresh-токены
ротируются; повторное использование старого refresh-токена отзывает всю выдачу.
"""

from __future__ import annotations

import json
import logging
import time
from collections.abc import Callable
from typing import Any
from urllib.parse import urlparse

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    AuthorizeError,
    RefreshToken,
    RegistrationError,
    TokenError,
    construct_redirect_uri,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

from ..commands.schemas import ALL_SCOPES, SCOPE_CONTROL, SCOPE_READ
from ..config import Settings
from ..security.tokens import hash_token, new_id, new_token
from ..store import Store

log = logging.getLogger("bridge.oauth")

PENDING_TTL = 600
CODE_TTL = 300
MAX_CLIENTS = 100
LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1", "[::1]"}


class BridgeOAuthProvider:
    def __init__(self, settings: Settings, store: Store) -> None:
        self.settings = settings
        self.store = store
        self.allowed_resources = {
            settings.public_url,
            settings.public_url + "/",
            settings.mcp_url,
            settings.mcp_url + "/",
        }
        self._touched: dict[str, float] = {}
        self.on_change: Callable[[], None] | None = None

    def _changed(self) -> None:
        if self.on_change is not None:
            self.on_change()

    # --- клиенты ---------------------------------------------------------------
    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        row = await self.store.oauth_client_get(client_id)
        if row is None or row.get("revoked_at"):
            return None
        return OAuthClientInformationFull.model_validate_json(row["info_json"])

    def _redirect_allowed(self, uri: str) -> bool:
        parsed = urlparse(uri)
        host = (parsed.hostname or "").lower()
        if parsed.scheme == "https" and host in self.settings.oauth_redirect_hosts:
            return True
        if self.settings.allow_loopback_redirects and parsed.scheme in ("http", "https") and host in LOOPBACK_HOSTS:
            return True
        return False

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        uris = [str(u) for u in (client_info.redirect_uris or [])]
        if not uris:
            raise RegistrationError("invalid_redirect_uri", "redirect_uris is required")
        for uri in uris:
            if not self._redirect_allowed(uri):
                log.warning("Rejected OAuth client registration with redirect_uri %s", uri)
                raise RegistrationError(
                    "invalid_redirect_uri",
                    "redirect_uri host is not allowed by this server (see OAUTH_REDIRECT_HOSTS)",
                )
        if await self.store.oauth_clients_count() >= MAX_CLIENTS:
            raise RegistrationError("invalid_client_metadata", "too many registered clients; revoke unused ones")
        assert client_info.client_id is not None
        await self.store.oauth_client_insert(client_info.client_id, client_info.model_dump_json())
        await self.store.audit(
            "oauth",
            "oauth.client_registered",
            details={"client_id": client_info.client_id, "name": client_info.client_name, "redirect_uris": uris},
        )

    # --- авторизация -------------------------------------------------------------
    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        if params.resource is not None and params.resource not in self.allowed_resources:
            log.warning(
                "OAuth resource mismatch: got %r, expected %r (check PUBLIC_URL)", params.resource, self.settings.mcp_url
            )
            raise AuthorizeError("invalid_target", "Unknown resource; check the server PUBLIC_URL")
        if params.scopes is not None:
            unknown = [s for s in params.scopes if s not in ALL_SCOPES]
            if unknown:
                raise AuthorizeError("invalid_scope", f"Unknown scopes: {' '.join(unknown)}")
        pending_id = new_token("req", 24)
        csrf = new_token("csrf", 24)
        assert client.client_id is not None
        await self.store.oauth_pending_create(pending_id, client.client_id, params.model_dump_json(), csrf, PENDING_TTL)
        return f"{self.settings.public_url}/oauth/consent?req={pending_id}"

    async def load_pending(self, pending_id: str) -> tuple[dict[str, Any], AuthorizationParams, OAuthClientInformationFull] | None:
        if not pending_id.startswith("req_") or len(pending_id) > 100:
            return None
        row = await self.store.oauth_pending_get(pending_id)
        if row is None:
            return None
        client = await self.get_client(row["client_id"])
        if client is None:
            return None
        return row, AuthorizationParams.model_validate_json(row["params_json"]), client

    async def complete(self, pending_id: str, approve: bool, grant_control: bool) -> str | None:
        """Завершить согласие и вернуть URL редиректа к клиенту (None — запрос уже обработан)."""
        loaded = await self.load_pending(pending_id)
        if loaded is None:
            return None
        row, params, client = loaded
        if not await self.store.oauth_pending_consume(pending_id):
            return None
        redirect = str(params.redirect_uri)
        if not approve:
            await self.store.audit("owner", "oauth.consent_denied", details={"client_id": client.client_id})
            return construct_redirect_uri(
                redirect, error="access_denied", error_description="The owner denied access", state=params.state
            )
        requested = params.scopes or list(ALL_SCOPES)
        granted = [s for s in requested if s in ALL_SCOPES]
        if not grant_control:
            granted = [s for s in granted if s != SCOPE_CONTROL]
        if not granted:
            granted = [SCOPE_READ]
        code = new_token("sac", 32)
        data = {
            "scopes": granted,
            "expires_at": time.time() + CODE_TTL,
            "client_id": client.client_id,
            "code_challenge": params.code_challenge,
            "redirect_uri": redirect,
            "redirect_uri_provided_explicitly": params.redirect_uri_provided_explicitly,
            "resource": params.resource,
            "subject": "owner",
        }
        assert client.client_id is not None
        await self.store.oauth_code_insert(hash_token(code), client.client_id, json.dumps(data), data["expires_at"])
        await self.store.audit(
            "owner", "oauth.consent_granted", details={"client_id": client.client_id, "scopes": granted}
        )
        return construct_redirect_uri(redirect, code=code, state=params.state)

    async def load_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: str
    ) -> AuthorizationCode | None:
        row = await self.store.oauth_code_get(hash_token(authorization_code))
        if row is None or row["client_id"] != client.client_id:
            return None
        data = json.loads(row["data_json"])
        return AuthorizationCode(code=authorization_code, **data)

    async def exchange_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: AuthorizationCode
    ) -> OAuthToken:
        if not await self.store.oauth_code_consume(hash_token(authorization_code.code)):
            raise TokenError("invalid_grant", "authorization code is invalid, expired or already used")
        assert client.client_id is not None
        await self.store.oauth_client_touch(client.client_id)
        return await self._issue(
            client.client_id, authorization_code.scopes, authorization_code.resource, authorization_code.subject
        )

    async def _issue(
        self,
        client_id: str,
        scopes: list[str],
        resource: str | None,
        subject: str | None,
        grant_id: str | None = None,
        rotate_from: str | None = None,
    ) -> OAuthToken:
        now = time.time()
        grant_id = grant_id or new_id("grant", 12)
        access = new_token("sat", 32)
        refresh = new_token("srt", 32)
        rows = [
            {
                "token_hash": hash_token(access),
                "kind": "access",
                "client_id": client_id,
                "grant_id": grant_id,
                "scopes": " ".join(scopes),
                "resource": resource,
                "subject": subject,
                "created_at": now,
                "expires_at": now + self.settings.access_token_ttl,
            },
            {
                "token_hash": hash_token(refresh),
                "kind": "refresh",
                "client_id": client_id,
                "grant_id": grant_id,
                "scopes": " ".join(scopes),
                "resource": resource,
                "subject": subject,
                "created_at": now,
                "expires_at": now + self.settings.refresh_token_ttl,
            },
        ]
        if rotate_from is None:
            await self.store.oauth_tokens_insert(rows)
        elif not await self.store.oauth_rotate_refresh(rotate_from, rows):
            raise TokenError("invalid_grant", "refresh token was already used")
        self._changed()
        return OAuthToken(
            access_token=access,
            token_type="Bearer",
            expires_in=self.settings.access_token_ttl,
            scope=" ".join(scopes),
            refresh_token=refresh,
        )

    async def load_refresh_token(self, client: OAuthClientInformationFull, refresh_token: str) -> RefreshToken | None:
        row = await self.store.oauth_token_get(hash_token(refresh_token))
        if row is None or row["kind"] != "refresh" or row["client_id"] != client.client_id:
            return None
        if row.get("revoked_at"):
            # Повторное использование уже ротированного токена — признак кражи: отзываем выдачу.
            await self.store.oauth_revoke_grant(row["grant_id"])
            await self.store.audit("oauth", "security.refresh_token_reuse", details={"client_id": client.client_id})
            self._changed()
            return None
        if row.get("expires_at") and row["expires_at"] < time.time():
            return None
        return RefreshToken(
            token=refresh_token,
            client_id=row["client_id"],
            scopes=row["scopes"].split(),
            expires_at=int(row["expires_at"]) if row.get("expires_at") else None,
            resource=row.get("resource"),
            subject=row.get("subject"),
        )

    async def exchange_refresh_token(
        self, client: OAuthClientInformationFull, refresh_token: RefreshToken, scopes: list[str]
    ) -> OAuthToken:
        original = set(refresh_token.scopes)
        requested = scopes or list(refresh_token.scopes)
        if not set(requested).issubset(original):
            raise TokenError("invalid_scope", "requested scopes exceed the original grant")
        row = await self.store.oauth_token_get(hash_token(refresh_token.token))
        if row is None:
            raise TokenError("invalid_grant", "unknown refresh token")
        assert client.client_id is not None
        await self.store.oauth_client_touch(client.client_id)
        return await self._issue(
            client.client_id,
            requested,
            refresh_token.resource,
            refresh_token.subject,
            grant_id=row["grant_id"],
            rotate_from=row["token_hash"],
        )

    async def load_access_token(self, token: str) -> AccessToken | None:
        if not token.startswith("sat_") or len(token) > 200:
            return None
        token_hash = hash_token(token)
        row = await self.store.oauth_token_get(token_hash)
        if row is None or row["kind"] != "access" or row.get("revoked_at"):
            return None
        now = time.time()
        if row.get("expires_at") and row["expires_at"] < now:
            return None
        if row.get("resource") and row["resource"] not in self.allowed_resources:
            return None
        if now - self._touched.get(token_hash, 0) > 60:
            self._touched[token_hash] = now
            if len(self._touched) > 1000:
                self._touched.clear()
            await self.store.oauth_token_touch(token_hash)
        return AccessToken(
            token=token,
            client_id=row["client_id"],
            scopes=row["scopes"].split(),
            expires_at=int(row["expires_at"]) if row.get("expires_at") else None,
            resource=row.get("resource"),
            subject=row.get("subject"),
        )

    async def revoke_token(self, token: AccessToken | RefreshToken) -> None:
        row = await self.store.oauth_token_get(hash_token(token.token))
        if row is not None:
            await self.store.oauth_revoke_grant(row["grant_id"])
            await self.store.audit("oauth", "oauth.token_revoked", details={"client_id": row["client_id"]})
            self._changed()
