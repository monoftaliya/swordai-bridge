"""Схема БД. Одинаковый SQL для SQLite и PostgreSQL, кроме типов-плейсхолдеров."""

from __future__ import annotations

_V1 = [
    """CREATE TABLE IF NOT EXISTS kv (
        key TEXT PRIMARY KEY,
        value TEXT NOT NULL,
        updated_at {FLOAT} NOT NULL
    )""",
    """CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        token_hash TEXT NOT NULL UNIQUE,
        model TEXT,
        android_version TEXT,
        app_version TEXT,
        info_json TEXT,
        created_at {FLOAT} NOT NULL,
        last_seen_at {FLOAT},
        revoked_at {FLOAT},
        next_seq BIGINT NOT NULL DEFAULT 1
    )""",
    """CREATE TABLE IF NOT EXISTS pairing_codes (
        code_hash TEXT PRIMARY KEY,
        label TEXT,
        created_at {FLOAT} NOT NULL,
        expires_at {FLOAT} NOT NULL,
        used_at {FLOAT},
        device_id TEXT
    )""",
    """CREATE TABLE IF NOT EXISTS commands (
        id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL,
        seq BIGINT NOT NULL,
        tool TEXT NOT NULL,
        args_json TEXT NOT NULL,
        source TEXT NOT NULL,
        client_id TEXT,
        status TEXT NOT NULL,
        state TEXT,
        created_at {FLOAT} NOT NULL,
        expires_at {FLOAT} NOT NULL,
        delivered_at {FLOAT},
        finished_at {FLOAT},
        ok INTEGER,
        error_code TEXT,
        error_message TEXT,
        result_summary TEXT
    )""",
    "CREATE INDEX IF NOT EXISTS idx_commands_device ON commands (device_id, created_at)",
    "CREATE INDEX IF NOT EXISTS idx_commands_created ON commands (created_at)",
    """CREATE TABLE IF NOT EXISTS audit_log (
        id {AUTOID},
        ts {FLOAT} NOT NULL,
        actor TEXT NOT NULL,
        event TEXT NOT NULL,
        device_id TEXT,
        command_id TEXT,
        ip TEXT,
        details_json TEXT
    )""",
    "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit_log (ts)",
    """CREATE TABLE IF NOT EXISTS oauth_clients (
        client_id TEXT PRIMARY KEY,
        info_json TEXT NOT NULL,
        created_at {FLOAT} NOT NULL,
        last_used_at {FLOAT},
        revoked_at {FLOAT}
    )""",
    """CREATE TABLE IF NOT EXISTS oauth_pending (
        id TEXT PRIMARY KEY,
        client_id TEXT NOT NULL,
        params_json TEXT NOT NULL,
        csrf TEXT NOT NULL,
        created_at {FLOAT} NOT NULL,
        expires_at {FLOAT} NOT NULL,
        used_at {FLOAT}
    )""",
    """CREATE TABLE IF NOT EXISTS oauth_codes (
        code_hash TEXT PRIMARY KEY,
        client_id TEXT NOT NULL,
        data_json TEXT NOT NULL,
        expires_at {FLOAT} NOT NULL,
        used_at {FLOAT}
    )""",
    """CREATE TABLE IF NOT EXISTS oauth_tokens (
        token_hash TEXT PRIMARY KEY,
        kind TEXT NOT NULL,
        client_id TEXT NOT NULL,
        grant_id TEXT NOT NULL,
        scopes TEXT NOT NULL,
        resource TEXT,
        subject TEXT,
        created_at {FLOAT} NOT NULL,
        expires_at {FLOAT},
        revoked_at {FLOAT},
        last_used_at {FLOAT}
    )""",
    "CREATE INDEX IF NOT EXISTS idx_oauth_tokens_grant ON oauth_tokens (grant_id)",
    "CREATE INDEX IF NOT EXISTS idx_oauth_tokens_client ON oauth_tokens (client_id)",
    """CREATE TABLE IF NOT EXISTS admin_sessions (
        token_hash TEXT PRIMARY KEY,
        created_at {FLOAT} NOT NULL,
        expires_at {FLOAT} NOT NULL,
        ip TEXT
    )""",
]

_TYPES = {
    "sqlite": {"FLOAT": "REAL", "AUTOID": "INTEGER PRIMARY KEY AUTOINCREMENT"},
    "postgres": {"FLOAT": "DOUBLE PRECISION", "AUTOID": "BIGSERIAL PRIMARY KEY"},
}


def migrations_for(dialect: str) -> list[tuple[int, list[str]]]:
    types = _TYPES[dialect]
    return [(1, [stmt.format(**types) for stmt in _V1])]
