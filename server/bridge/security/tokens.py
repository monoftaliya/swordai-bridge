"""Генерация и хэширование секретов."""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time

# Алфавит без похожих символов (0/O, 1/I/L) для кодов, которые вводит человек.
_HUMAN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


def new_token(prefix: str, nbytes: int = 32) -> str:
    """Случайный токен вида `<prefix>_<base64url>`; nbytes=32 даёт 256 бит энтропии."""
    return f"{prefix}_{secrets.token_urlsafe(nbytes)}"


def new_id(prefix: str, nbytes: int = 12) -> str:
    return f"{prefix}_{secrets.token_urlsafe(nbytes).replace('-', 'x').replace('_', 'y')}"


def hash_token(token: str) -> str:
    """SHA-256 от токена. В БД хранятся только хэши."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def consteq(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


def new_pairing_code() -> str:
    """Код сопряжения XXXXX-XXXXX (~49 бит), живёт несколько минут и защищён лимитом попыток."""
    raw = "".join(secrets.choice(_HUMAN_ALPHABET) for _ in range(10))
    return f"{raw[:5]}-{raw[5:]}"


def normalize_pairing_code(code: str) -> str:
    cleaned = "".join(ch for ch in code.upper() if ch.isalnum())
    if len(cleaned) != 10:
        return ""
    return f"{cleaned[:5]}-{cleaned[5:]}"


def hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return f"scrypt${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, salt_hex, digest_hex = stored.split("$", 2)
    except ValueError:
        return False
    if algo != "scrypt":
        return False
    digest = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt_hex), n=2**14, r=8, p=1, dklen=32)
    return hmac.compare_digest(digest.hex(), digest_hex)


def now() -> float:
    return time.time()
