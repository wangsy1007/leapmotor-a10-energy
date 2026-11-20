from __future__ import annotations

import base64
import hashlib
import hmac
import uuid
from typing import Mapping

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.serialization import load_der_public_key

APP_VERSION = "1.22.98"
APP_SUBVERSION = "3.21.3-2"
SIGNATURE_VERSION = "2.0"

# 官方 App / 商城 H5 共用的 RSA-1024 公钥（X.509 DER，Base64）。这是公钥，不是账号密钥。
RSA_PUBLIC_KEY_B64 = (
    "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDHUIQKhkwNqJFTZPe98mC1lmpbY9r/+7PEWZg8ebqY"
    "XT3sumKRaQ0zcoTx42x0iybmCRXy4CcZrgGAbwKzwqwNw0rFquJ6c7mgQA6k3lZU3p96qBlzK7DSko"
    "FR6mO9pjcd2hlJ8wH+IwI5b8IWWZhwVN/4cM7npG0S0zeRn3soEwIDAQAB"
)

SIGNED_HEADER_NAMES = (
    "acceptLanguage",
    "channel",
    "deviceType",
    "source",
    "timestamp",
    "version",
    "deviceId",
)


def new_device_id() -> str:
    return uuid.uuid4().hex


def _public_key():
    der = base64.b64decode(RSA_PUBLIC_KEY_B64)
    return load_der_public_key(der)


def encrypt_rsa_pkcs1_base64url(text: str) -> str:
    key = _public_key()
    encrypted = key.encrypt(text.encode("utf-8"), padding.PKCS1v15())
    return base64.urlsafe_b64encode(encrypted).decode("ascii").rstrip("=")


def b64url_decode(text: str) -> bytes:
    normalized = text.replace("-", "+").replace("_", "/")
    padded = normalized + "=" * ((4 - len(normalized) % 4) % 4)
    return base64.b64decode(padded)


def derive_hkdf_hex(car_jwt: str, r2: str, r3: str) -> str:
    parts = car_jwt.split(".")
    if len(parts) != 3:
        raise ValueError("车端 token 不是 JWT")
    a = b64url_decode(r2)
    b = b64url_decode(r3)
    c = b64url_decode(parts[2])
    n = min(len(a), len(b), len(c))
    out = bytes(a[i] ^ b[i] ^ c[i] for i in range(n))
    return out.hex()


def sort_and_concat(values: Mapping[str, str]) -> str:
    return "".join(values[k] for k in sorted(values.keys()))


def md5_hex(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def md5_mid16(text: str) -> str:
    return md5_hex(text)[8:24]


def sha256_hex_upper(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest().upper()


def hmac_sha256_hex(key_hex: str, material: str) -> str:
    key = bytes.fromhex(key_hex)
    return hmac.new(key, material.encode("utf-8"), hashlib.sha256).hexdigest()


def account_refresh_sign(params_including_refresh_token: Mapping[str, str]) -> str:
    return md5_mid16(sort_and_concat(params_including_refresh_token))


def exchange_sign(headers_and_body: Mapping[str, str]) -> str:
    return sha256_hex_upper(sort_and_concat(headers_and_body))


def business_sign(hkdf_key_hex: str, payload_and_signed_headers: Mapping[str, str]) -> str:
    return hmac_sha256_hex(hkdf_key_hex, sort_and_concat(payload_and_signed_headers))


def signed_headers(device_id: str, timestamp_ms: int, version: str = APP_VERSION) -> dict[str, str]:
    return {
        "acceptLanguage": "zh-CN",
        "channel": "1",
        "deviceType": "android",
        "source": "leapmotor",
        "timestamp": str(timestamp_ms),
        "version": version,
        "deviceId": device_id,
    }
