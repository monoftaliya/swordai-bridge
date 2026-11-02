"""Ключ сервера (ECDSA P-256) для подписи команд.

Телефон получает открытый ключ при сопряжении и принимает только команды,
подписанные этим ключом. Подписывается точная строка `payload` (JSON), поэтому
каноникализация JSON не нужна: устройство проверяет подпись байтов строки и
лишь затем разбирает её.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import logging

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

log = logging.getLogger("bridge.signing")

# Порядок группы кривой P-256
_P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


class SigningKey:
    def __init__(self, private_key: ec.EllipticCurvePrivateKey) -> None:
        if not isinstance(private_key.curve, ec.SECP256R1):
            raise ValueError("Signing key must be ECDSA P-256 (secp256r1)")
        self._key = private_key
        self.public_der = private_key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )

    @classmethod
    def generate(cls) -> SigningKey:
        return cls(ec.generate_private_key(ec.SECP256R1()))

    @classmethod
    def from_config(cls, value: str) -> SigningKey:
        """SIGNING_KEY: PEM, base64(PEM) или длинная случайная строка-«зерно» (≥ 32 символов).

        Зерно удобно задавать прямо в панели хостинга с телефона: ключ детерминированно
        выводится через HKDF-SHA256, поэтому переживает перезапуски без хранения в БД.
        """
        text = value.strip()
        if "BEGIN" in text:
            return cls.from_pem(text)
        try:
            decoded = base64.b64decode(text, validate=True).decode("ascii")
            if "BEGIN" in decoded:
                return cls.from_pem(decoded)
        except (binascii.Error, UnicodeDecodeError, ValueError):
            pass
        if len(text) < 32:
            raise ValueError("SIGNING_KEY must be a PEM key or a random string of at least 32 characters")
        return cls.from_seed(text)

    @classmethod
    def from_seed(cls, seed: str) -> SigningKey:
        material = HKDF(
            algorithm=hashes.SHA256(), length=48, salt=b"swordai-bridge/signing-key/v1", info=b"ecdsa-p256"
        ).derive(seed.encode("utf-8"))
        scalar = int.from_bytes(material, "big") % (_P256_ORDER - 1) + 1
        return cls(ec.derive_private_key(scalar, ec.SECP256R1()))

    @classmethod
    def from_pem(cls, pem: str) -> SigningKey:
        text = pem.strip()
        if "BEGIN" not in text:
            # Допускаем PEM, закодированный в base64 (удобно для переменных окружения).
            try:
                text = base64.b64decode(text).decode("ascii")
            except (binascii.Error, UnicodeDecodeError) as exc:
                raise ValueError("SIGNING_KEY must be PEM or base64(PEM)") from exc
        key = serialization.load_pem_private_key(text.encode("ascii"), password=None)
        if not isinstance(key, ec.EllipticCurvePrivateKey):
            raise ValueError("SIGNING_KEY must be an EC private key")
        return cls(key)

    def to_pem(self) -> str:
        return self._key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ).decode("ascii")

    @property
    def public_key_b64(self) -> str:
        return base64.b64encode(self.public_der).decode("ascii")

    @property
    def fingerprint(self) -> str:
        """SHA-256 от SubjectPublicKeyInfo: первые 16 байт группами по 2."""
        digest = hashlib.sha256(self.public_der).hexdigest().upper()[:32]
        return " ".join(digest[i : i + 4] for i in range(0, 32, 4))

    def sign(self, payload: bytes) -> str:
        signature = self._key.sign(payload, ec.ECDSA(hashes.SHA256()))
        return base64.b64encode(signature).decode("ascii")

    def verify(self, payload: bytes, signature_b64: str) -> bool:
        from cryptography.exceptions import InvalidSignature

        try:
            self._key.public_key().verify(base64.b64decode(signature_b64), payload, ec.ECDSA(hashes.SHA256()))
            return True
        except (InvalidSignature, binascii.Error, ValueError):
            return False
