"""Тонкий слой доступа к БД: SQLite (по умолчанию) или PostgreSQL.

Все запросы пишутся с плейсхолдером `?`; для PostgreSQL он автоматически
заменяется на `%s`. Операции выполняются в одном выделенном потоке, поэтому
событийный цикл не блокируется, а соединение не используется конкурентно.
"""

from __future__ import annotations

import asyncio
import logging
import os
import sqlite3
import threading
from collections.abc import Callable, Sequence
from concurrent.futures import ThreadPoolExecutor
from typing import Any, TypeVar

from .schema import migrations_for

log = logging.getLogger("bridge.db")
T = TypeVar("T")
Params = Sequence[Any]


class Tx:
    """Курсор внутри транзакции. Используется только в потоке БД."""

    def __init__(self, conn: Any, dialect: str) -> None:
        self._conn = conn
        self.dialect = dialect

    def _sql(self, sql: str) -> str:
        return sql.replace("?", "%s") if self.dialect == "postgres" else sql

    def execute(self, sql: str, params: Params = ()) -> int:
        cur = self._conn.execute(self._sql(sql), tuple(params))
        return cur.rowcount if cur.rowcount is not None else 0

    def fetchone(self, sql: str, params: Params = ()) -> dict[str, Any] | None:
        # fetchall() гарантированно завершает выполнение оператора (важно для
        # UPDATE ... RETURNING в SQLite перед COMMIT).
        cur = self._conn.execute(self._sql(sql), tuple(params))
        rows = cur.fetchall()
        return dict(rows[0]) if rows else None

    def fetchall(self, sql: str, params: Params = ()) -> list[dict[str, Any]]:
        cur = self._conn.execute(self._sql(sql), tuple(params))
        return [dict(r) for r in cur.fetchall()]


class Database:
    def __init__(self, url: str) -> None:
        self.url = url
        if url.startswith("sqlite:///"):
            self.dialect = "sqlite"
            self._path = url[len("sqlite:///"):]
        elif url.startswith(("postgres://", "postgresql://")):
            self.dialect = "postgres"
            self._path = ""
        else:
            raise ValueError("DATABASE_URL must start with sqlite:/// or postgresql://")
        self._conn: Any = None
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="bridge-db")

    # --- подключение ---------------------------------------------------------
    def _open(self) -> Any:
        if self.dialect == "sqlite":
            if self._path not in (":memory:", ""):
                directory = os.path.dirname(os.path.abspath(self._path))
                os.makedirs(directory, exist_ok=True)
            conn = sqlite3.connect(self._path or ":memory:", check_same_thread=False, isolation_level=None)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA busy_timeout = 5000")
            if self._path not in (":memory:", ""):
                conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("PRAGMA foreign_keys = ON")
            return conn
        try:
            import psycopg  # type: ignore[import-not-found]
            from psycopg.rows import dict_row  # type: ignore[import-not-found]
        except ImportError as exc:  # pragma: no cover - зависит от окружения
            raise RuntimeError("Для PostgreSQL установите пакет psycopg[binary]") from exc
        url = self.url.replace("postgres://", "postgresql://", 1)
        return psycopg.connect(url, autocommit=True, row_factory=dict_row, connect_timeout=10)

    async def connect(self) -> None:
        await self._submit(self._connect_sync)

    def _connect_sync(self) -> None:
        with self._lock:
            self._conn = self._open()
        self._migrate_sync()

    async def close(self) -> None:
        def _close() -> None:
            with self._lock:
                if self._conn is not None:
                    self._conn.close()
                    self._conn = None

        await self._submit(_close)
        self._executor.shutdown(wait=True)

    # --- выполнение ----------------------------------------------------------
    async def _submit(self, fn: Callable[[], T]) -> T:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._executor, fn)

    def _run_sync(self, fn: Callable[[Tx], T]) -> T:
        with self._lock:
            if self._conn is None:
                raise RuntimeError("database is not connected")
            try:
                return self._transaction(fn)
            except Exception as exc:
                if self.dialect == "postgres" and _is_connection_error(exc):
                    log.warning("PostgreSQL connection lost, reconnecting: %s", exc)
                    try:
                        self._conn.close()
                    except Exception:  # noqa: BLE001
                        pass
                    self._conn = self._open()
                    return self._transaction(fn)
                raise

    def _transaction(self, fn: Callable[[Tx], T]) -> T:
        conn = self._conn
        tx = Tx(conn, self.dialect)
        if self.dialect == "sqlite":
            conn.execute("BEGIN IMMEDIATE")
            try:
                result = fn(tx)
            except BaseException:
                conn.execute("ROLLBACK")
                raise
            conn.execute("COMMIT")
            return result
        with conn.transaction():
            return fn(tx)

    async def run(self, fn: Callable[[Tx], T]) -> T:
        """Выполнить функцию атомарно (в одной транзакции)."""
        return await self._submit(lambda: self._run_sync(fn))

    async def execute(self, sql: str, params: Params = ()) -> int:
        return await self.run(lambda tx: tx.execute(sql, params))

    async def fetchone(self, sql: str, params: Params = ()) -> dict[str, Any] | None:
        return await self.run(lambda tx: tx.fetchone(sql, params))

    async def fetchall(self, sql: str, params: Params = ()) -> list[dict[str, Any]]:
        return await self.run(lambda tx: tx.fetchall(sql, params))

    # --- миграции ------------------------------------------------------------
    def _migrate_sync(self) -> None:
        def apply(tx: Tx) -> None:
            tx.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
            row = tx.fetchone("SELECT MAX(version) AS v FROM schema_version")
            current = int(row["v"]) if row and row["v"] is not None else 0
            for version, statements in migrations_for(self.dialect):
                if version <= current:
                    continue
                for statement in statements:
                    tx.execute(statement)
                tx.execute("INSERT INTO schema_version (version) VALUES (?)", (version,))
                log.info("Applied database migration %s", version)

        with self._lock:
            self._transaction(apply)


def _is_connection_error(exc: Exception) -> bool:
    name = type(exc).__name__
    return name in {"OperationalError", "InterfaceError"}
