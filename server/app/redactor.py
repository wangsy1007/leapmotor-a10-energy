from __future__ import annotations

import re

_SECRET_KEYS = (
    "token",
    "refreshToken",
    "accessToken",
    "security",
    "password",
    "smsCode",
    "phone",
    "phoneNo",
    "phoneNoCiphertext",
    "accountNumber",
    "sign",
    "digest",
    "hkdfKey",
    "hkdfKeyHex",
    "r2",
    "r3",
    "carToken",
    "apiToken",
    "Authorization",
)


def redact_secrets(raw: str) -> str:
    text = raw
    for key in _SECRET_KEYS:
        text = re.sub(rf'"{key}"\s*:\s*"[^"]*"', f'"{key}":"***"', text)
    text = re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "1**********", text)
    return text


def mask_phone(phone: str) -> str:
    if len(phone) >= 7:
        return phone[:3] + "****" + phone[-4:]
    return "***" if phone else ""


def mask_vin(vin: str) -> str:
    if len(vin) >= 8:
        return vin[:4] + "****" + vin[-4:]
    return "***" if vin else ""


def mask_token(token: str) -> str:
    if not token:
        return ""
    if len(token) <= 8:
        return "***"
    return token[:4] + "…" + token[-4:]
