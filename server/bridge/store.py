"""Репозитории поверх Database: устройства, команды, журнал, OAuth, сессии."""

from __future__ import annotations

import json
import time
from typing import Any

from .db import Database, Tx


def _dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), default=str)


class Store:
    def __init__(self, db: Database) -> None:
        self.db = db

    # --- key/value -----------------------------------------------------------
    async def kv_get(self, key: str) -> str | None:
        row = await self.db.fetchone("SELECT value FROM kv WHERE key = ?", (key,))
        return row["value"] if row else None

    async def kv_set(self, key: str, value: str) -> None:
        await self.db.execute(
            "INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?) "
            "ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
            (key, value, time.time()),
        )

    async def kv_set_if_absent(self, key: str, value: str) -> str:
        """Записать значение, если его нет; вернуть то, что в итоге хранится."""

        def op(tx: Tx) -> str:
            tx.execute(
                "INSERT INTO kv (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT (key) DO NOTHING",
                (key, value, time.time()),
            )
            row = tx.fetchone("SELECT value FROM kv WHERE key = ?", (key,))
            assert row is not None
            return str(row["value"])

        return await self.db.run(op)

    # --- журнал --------------------------------------------------------------
    async def audit(
        self,
        actor: str,
        event: str,
        *,
        device_id: str | None = None,
        command_id: str | None = None,
        ip: str | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        await self.db.execute(
            "INSERT INTO audit_log (ts, actor, event, device_id, command_id, ip, details_json) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (time.time(), actor, event, device_id, command_id, ip, _dumps(details) if details else None),
        )

    async def audit_list(
        self, limit: int = 100, before_id: int | None = None, device_id: str | None = None
    ) -> list[dict[str, Any]]:
        clauses, params = [], []
        if before_id is not None:
            clauses.append("id < ?")
            params.append(before_id)
        if device_id:
            clauses.append("device_id = ?")
            params.append(device_id)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        rows = await self.db.fetchall(
            f"SELECT * FROM audit_log {where} ORDER BY id DESC LIMIT ?", (*params, max(1, min(limit, 500)))
        )
        for row in rows:
            row["details"] = json.loads(row.pop("details_json") or "null")
        return rows

    # --- сопряжение ----------------------------------------------------------
    async def pairing_create(self, code_hash: str, label: str | None, ttl: int) -> float:
        now = time.time()
        await self.db.execute(
            "INSERT INTO pairing_codes (code_hash, label, created_at, expires_at) VALUES (?, ?, ?, ?)",
            (code_hash, label, now, now + ttl),
        )
        return now + ttl

    async def pairing_consume_and_create_device(
        self,
        code_hash: str,
        device_id: str,
        token_hash: str,
        name: str,
        model: str | None,
        android_version: str | None,
        app_version: str | None,
        info: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Атомарно погасить код и создать устройство. None — код неверен/истёк/использован."""

        def op(tx: Tx) -> dict[str, Any] | None:
            now = time.time()
            changed = tx.execute(
                "UPDATE pairing_codes SET used_at = ?, device_id = ? "
                "WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
                (now, device_id, code_hash, now),
            )
            if changed != 1:
                return None
            tx.execute(
                "INSERT INTO devices (id, name, token_hash, model, android_version, app_version, info_json, "
                "created_at, last_seen_at, next_seq) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                (device_id, name, token_hash, model, android_version, app_version, _dumps(info), now, now),
            )
            return tx.fetchone("SELECT * FROM devices WHERE id = ?", (device_id,))

        return await self.db.run(op)

    # --- устройства ----------------------------------------------------------
    async def device_get(self, device_id: str) -> dict[str, Any] | None:
        return await self.db.fetchone("SELECT * FROM devices WHERE id = ?", (device_id,))

    async def device_by_token_hash(self, token_hash: str) -> dict[str, Any] | None:
        return await self.db.fetchone("SELECT * FROM devices WHERE token_hash = ?", (token_hash,))

    async def devices_list(self, include_revoked: bool = False) -> list[dict[str, Any]]:
        where = "" if include_revoked else "WHERE revoked_at IS NULL"
        return await self.db.fetchall(f"SELECT * FROM devices {where} ORDER BY created_at DESC")

    async def device_revoke(self, device_id: str) -> bool:
        changed = await self.db.execute(
            "UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", (time.time(), device_id)
        )
        return changed == 1

    async def device_rename(self, device_id: str, name: str) -> bool:
        return await self.db.execute("UPDATE devices SET name = ? WHERE id = ?", (name, device_id)) == 1

    async def device_touch(self, device_id: str, info: dict[str, Any] | None = None) -> None:
        if info is None:
            await self.db.execute("UPDATE devices SET last_seen_at = ? WHERE id = ?", (time.time(), device_id))
            return
        device = info.get("device") or {}
        await self.db.execute(
            "UPDATE devices SET last_seen_at = ?, info_json = ?, model = COALESCE(?, model), "
            "android_version = COALESCE(?, android_version), app_version = COALESCE(?, app_version) WHERE id = ?",
            (
                time.time(),
                _dumps(info),
                device.get("model"),
                device.get("android_release"),
                info.get("app_version"),
                device_id,
            ),
        )

    async def device_next_seq(self, device_id: str) -> int | None:
        row = await self.db.fetchone(
            "UPDATE devices SET next_seq = next_seq + 1 WHERE id = ? AND revoked_at IS NULL RETURNING next_seq",
            (device_id,),
        )
        return int(row["next_seq"]) - 1 if row else None

    # --- команды ---------------------------------------------------------------
    async def command_insert(self, row: dict[str, Any]) -> None:
        columns = ", ".join(row)
        marks = ", ".join("?" for _ in row)
        await self.db.execute(f"INSERT INTO commands ({columns}) VALUES ({marks})", tuple(row.values()))

    _COMMAND_COLUMNS = frozenset(
        {"status", "state", "delivered_at", "finished_at", "ok", "error_code", "error_message", "result_summary"}
    )

    async def command_update(self, command_id: str, **fields: Any) -> None:
        unknown = set(fields) - self._COMMAND_COLUMNS
        if unknown:
            raise ValueError(f"unknown command columns: {unknown}")
        assignments = ", ".join(f"{name} = ?" for name in fields)
        await self.db.execute(f"UPDATE commands SET {assignments} WHERE id = ?", (*fields.values(), command_id))

    async def command_finish(self, command_id: str, **fields: Any) -> bool:
        """Перевести команду в финальный статус ровно один раз."""
        unknown = set(fields) - self._COMMAND_COLUMNS
        if unknown:
            raise ValueError(f"unknown command columns: {unknown}")
        assignments = ", ".join(f"{name} = ?" for name in fields)
        changed = await self.db.execute(
            f"UPDATE commands SET {assignments} WHERE id = ? AND finished_at IS NULL",
            (*fields.values(), command_id),
        )
        return changed == 1

    async def command_get(self, command_id: str) -> dict[str, Any] | None:
        return await self.db.fetchone("SELECT * FROM commands WHERE id = ?", (command_id,))

    async def commands_list(self, device_id: str | None = None, limit: int = 50) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 500))
        if device_id:
            return await self.db.fetchall(
                "SELECT * FROM commands WHERE device_id = ? ORDER BY created_at DESC LIMIT ?", (device_id, limit)
            )
        return await self.db.fetchall("SELECT * FROM commands ORDER BY created_at DESC LIMIT ?", (limit,))

    async def commands_abandon_unfinished(self) -> int:
        """При старте сервера незавершённые команды прошлого процесса больше не отследить."""
        return await self.db.execute(
            "UPDATE commands SET status = 'lost', finished_at = ?, ok = 0, error_code = 'server_restarted', "
            "error_message = ? WHERE finished_at IS NULL",
            (time.time(), "Сервер был перезапущен до получения результата"),
        )

    # --- сессии администратора -------------------------------------------------
    async def admin_session_create(self, token_hash: str, ttl: int, ip: str | None) -> float:
        now = time.time()
        await self.db.execute(
            "INSERT INTO admin_sessions (token_hash, created_at, expires_at, ip) VALUES (?, ?, ?, ?)",
            (token_hash, now, now + ttl, ip),
        )
        return now + ttl

    async def admin_session_valid(self, token_hash: str) -> bool:
        row = await self.db.fetchone(
            "SELECT 1 AS ok FROM admin_sessions WHERE token_hash = ? AND expires_at > ?", (token_hash, time.time())
        )
        return row is not None

    async def admin_session_delete(self, token_hash: str) -> None:
        await self.db.execute("DELETE FROM admin_sessions WHERE token_hash = ?", (token_hash,))

    # --- OAuth -----------------------------------------------------------------
    async def oauth_client_get(self, client_id: str) -> dict[str, Any] | None:
        return await self.db.fetchone("SELECT * FROM oauth_clients WHERE client_id = ?", (client_id,))

    async def oauth_client_insert(self, client_id: str, info_json: str) -> None:
        await self.db.execute(
            "INSERT INTO oauth_clients (client_id, info_json, created_at) VALUES (?, ?, ?)",
            (client_id, info_json, time.time()),
        )

    async def oauth_clients_count(self) -> int:
        row = await self.db.fetchone("SELECT COUNT(*) AS n FROM oauth_clients WHERE revoked_at IS NULL")
        return int(row["n"]) if row else 0

    async def oauth_clients_list(self) -> list[dict[str, Any]]:
        return await self.db.fetchall(
            "SELECT c.client_id, c.info_json, c.created_at, c.last_used_at, c.revoked_at, "
            "(SELECT COUNT(*) FROM oauth_tokens t WHERE t.client_id = c.client_id AND t.kind = 'refresh' "
            " AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > ?)) AS active_grants "
            "FROM oauth_clients c ORDER BY c.created_at DESC",
            (time.time(),),
        )

    async def oauth_client_revoke(self, client_id: str) -> bool:
        def op(tx: Tx) -> bool:
            now = time.time()
            changed = tx.execute(
                "UPDATE oauth_clients SET revoked_at = ? WHERE client_id = ? AND revoked_at IS NULL", (now, client_id)
            )
            tx.execute(
                "UPDATE oauth_tokens SET revoked_at = ? WHERE client_id = ? AND revoked_at IS NULL", (now, client_id)
            )
            return changed == 1

        return await self.db.run(op)

    async def oauth_revoke_all_tokens(self) -> int:
        return await self.db.execute(
            "UPDATE oauth_tokens SET revoked_at = ? WHERE revoked_at IS NULL", (time.time(),)
        )

    async def oauth_client_touch(self, client_id: str) -> None:
        await self.db.execute("UPDATE oauth_clients SET last_used_at = ? WHERE client_id = ?", (time.time(), client_id))

    async def oauth_pending_create(self, pending_id: str, client_id: str, params_json: str, csrf: str, ttl: int) -> None:
        now = time.time()
        await self.db.execute(
            "INSERT INTO oauth_pending (id, client_id, params_json, csrf, created_at, expires_at) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (pending_id, client_id, params_json, csrf, now, now + ttl),
        )

    async def oauth_pending_get(self, pending_id: str) -> dict[str, Any] | None:
        return await self.db.fetchone(
            "SELECT * FROM oauth_pending WHERE id = ? AND used_at IS NULL AND expires_at > ?",
            (pending_id, time.time()),
        )

    async def oauth_pending_consume(self, pending_id: str) -> bool:
        changed = await self.db.execute(
            "UPDATE oauth_pending SET used_at = ? WHERE id = ? AND used_at IS NULL AND expires_at > ?",
            (time.time(), pending_id, time.time()),
        )
        return changed == 1

    async def oauth_code_insert(self, code_hash: str, client_id: str, data_json: str, expires_at: float) -> None:
        await self.db.execute(
            "INSERT INTO oauth_codes (code_hash, client_id, data_json, expires_at) VALUES (?, ?, ?, ?)",
            (code_hash, client_id, data_json, expires_at),
        )

    async def oauth_code_get(self, code_hash: str) -> dict[str, Any] | None:
        return await self.db.fetchone(
            "SELECT * FROM oauth_codes WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
            (code_hash, time.time()),
        )

    async def oauth_code_consume(self, code_hash: str) -> bool:
        changed = await self.db.execute(
            "UPDATE oauth_codes SET used_at = ? WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?",
            (time.time(), code_hash, time.time()),
        )
        return changed == 1

    async def oauth_tokens_insert(self, rows: list[dict[str, Any]]) -> None:
        def op(tx: Tx) -> None:
            for row in rows:
                columns = ", ".join(row)
                marks = ", ".join("?" for _ in row)
                tx.execute(f"INSERT INTO oauth_tokens ({columns}) VALUES ({marks})", tuple(row.values()))

        await self.db.run(op)

    async def oauth_token_get(self, token_hash: str) -> dict[str, Any] | None:
        return await self.db.fetchone("SELECT * FROM oauth_tokens WHERE token_hash = ?", (token_hash,))

    async def oauth_rotate_refresh(self, old_hash: str, new_rows: list[dict[str, Any]]) -> bool:
        """Атомарно погасить refresh-токен и выдать новую пару. False — токен уже использован."""

        def op(tx: Tx) -> bool:
            now = time.time()
            changed = tx.execute(
                "UPDATE oauth_tokens SET revoked_at = ? WHERE token_hash = ? AND kind = 'refresh' "
                "AND revoked_at IS NULL",
                (now, old_hash),
            )
            if changed != 1:
                return False
            row = tx.fetchone("SELECT grant_id FROM oauth_tokens WHERE token_hash = ?", (old_hash,))
            if row is not None:
                # Старые access-токены этой выдачи больше не нужны.
                tx.execute(
                    "UPDATE oauth_tokens SET revoked_at = ? WHERE grant_id = ? AND kind = 'access' "
                    "AND revoked_at IS NULL",
                    (now, row["grant_id"]),
                )
            for new in new_rows:
                columns = ", ".join(new)
                marks = ", ".join("?" for _ in new)
                tx.execute(f"INSERT INTO oauth_tokens ({columns}) VALUES ({marks})", tuple(new.values()))
            return True

        return await self.db.run(op)

    async def oauth_revoke_grant(self, grant_id: str) -> int:
        return await self.db.execute(
            "UPDATE oauth_tokens SET revoked_at = ? WHERE grant_id = ? AND revoked_at IS NULL",
            (time.time(), grant_id),
        )

    async def oauth_token_touch(self, token_hash: str) -> None:
        await self.db.execute("UPDATE oauth_tokens SET last_used_at = ? WHERE token_hash = ?", (time.time(), token_hash))

    async def oauth_last_use(self) -> dict[str, Any] | None:
        return await self.db.fetchone(
            "SELECT client_id, last_used_at FROM oauth_tokens WHERE kind = 'access' AND last_used_at IS NOT NULL "
            "ORDER BY last_used_at DESC LIMIT 1"
        )

    async def oauth_active_grants(self) -> int:
        row = await self.db.fetchone(
            "SELECT COUNT(*) AS n FROM oauth_tokens WHERE kind = 'refresh' AND revoked_at IS NULL "
            "AND (expires_at IS NULL OR expires_at > ?)",
            (time.time(),),
        )
        return int(row["n"]) if row else 0

    # --- уборка ----------------------------------------------------------------
    async def cleanup(self, audit_retention_days: int) -> None:
        def op(tx: Tx) -> None:
            now = time.time()
            tx.execute("DELETE FROM audit_log WHERE ts < ?", (now - audit_retention_days * 86400,))
            tx.execute("DELETE FROM commands WHERE created_at < ?", (now - audit_retention_days * 86400,))
            tx.execute("DELETE FROM pairing_codes WHERE expires_at < ?", (now - 86400,))
            tx.execute("DELETE FROM oauth_pending WHERE expires_at < ?", (now - 3600,))
            tx.execute("DELETE FROM oauth_codes WHERE expires_at < ?", (now - 3600,))
            tx.execute("DELETE FROM admin_sessions WHERE expires_at < ?", (now,))
            tx.execute(
                "DELETE FROM oauth_tokens WHERE (expires_at IS NOT NULL AND expires_at < ?) "
                "OR (revoked_at IS NOT NULL AND revoked_at < ?)",
                (now - 86400, now - 30 * 86400),
            )

        await self.db.run(op)
