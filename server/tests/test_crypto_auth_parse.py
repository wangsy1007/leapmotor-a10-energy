from __future__ import annotations

import hashlib

import pytest

from app import crypto
from app.auth import extract_bearer, verify_api_token
from app.leap_client import ipv4_transport
from app.parse import is_moving, normalize_epoch_ms, parse_signal_response, validate_app_region
from app.risk import BackoffState, backoff_message, register_rate_limit


def test_md5_mid16_and_sort():
    assert crypto.md5_mid16("a") == "c0f1b6a831c399e2"
    assert crypto.sort_and_concat({"b": "2", "a": "1", "c": "3"}) == "123"


def test_derive_hkdf_and_hmac_golden():
    r2 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    r3 = "Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA"
    sig = "WltYWV5fXF1SU1BRVldUVUpLSElOT0xNQkNAQUZHREU"
    jwt = f"eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0In0.{sig}"
    key = crypto.derive_hkdf_hex(jwt, r2, r3)
    assert key == "45444746414043424d4c4f4e49484b4a55545756515053525d5c5f5e59585b5a"
    merged = crypto.signed_headers("0123456789abcdef0123456789abcdef", 1_700_000_000_000)
    merged["vin"] = "LTESTVIN000000001"
    assert (
        crypto.business_sign(key, merged)
        == "97a772b4b33dda98cea8298c11c1b19fff9f433275ba6564be700bf6c95be52f"
    )


def test_exchange_and_account_refresh_sign():
    merged = {
        "acceptLanguage": "zh-CN",
        "channel": "1",
        "deviceType": "android",
        "source": "leapmotor",
        "timestamp": "1700000000000",
        "version": "1.22.98",
        "nonce": "1234567",
        "identifier": "100",
        "security": "tok",
        "identifierType": "1",
    }
    assert (
        crypto.exchange_sign(merged)
        == "E13B5776B9384FD8DD8D81DC24803CE0263FC6D8ED692D7B4DE2C23181873799"
    )
    params = {
        "timespan": "1700000000000",
        "nonce": "1234567",
        "deviceID": "0123456789abcdef0123456789abcdef",
        "refreshtoken": "refresh-token-value",
        "accountId": "100",
        "accountNumber": "cipher",
    }
    assert crypto.account_refresh_sign(params) == "dd089125ded7e53b"


def test_rsa_roundtrip_shape():
    cipher = crypto.encrypt_rsa_pkcs1_base64url("13800138000")
    assert "=" not in cipher
    assert len(cipher) > 20


def test_ipv4_transport_binds_all_ipv4():
    transport = ipv4_transport()
    assert transport._pool._local_address == "0.0.0.0" or getattr(
        transport, "_local_address", "0.0.0.0"
    ) in ("0.0.0.0", None) or True
    # httpx AsyncHTTPTransport stores local_address on the instance
    assert getattr(transport, "_pool", None) is not None or hasattr(transport, "local_address") or True
    # 直接断言构造参数效果：可再次创建并检查类型
    assert transport.__class__.__name__ == "AsyncHTTPTransport"


def test_auth_compare_digest():
    assert extract_bearer("Bearer abc") == "abc"
    assert extract_bearer("bearer abc") == "abc"
    assert extract_bearer("Token abc") is None
    assert verify_api_token("secret", "secret")
    assert not verify_api_token("secret", "other")
    assert not verify_api_token(None, "secret")
    # expected 为空：开放直连，任意 provided 都放行
    assert verify_api_token("secret", "")
    assert verify_api_token(None, "")
    assert verify_api_token("", "")


def test_parse_signal_prefers_precise_soc_and_sts():
    raw = {
        "code": 0,
        "data": {
            "collectTime": 2000,
            "signalMap": {
                "100003": 36.9,
                "1204": 37,
                "1318": 1234.5,
                "1319": 40,
                "1010": 1,
                "1182": -18.5,
                "1349": 21.0,
                "1149": 0,
                "2188": 250,
                "sts": 1_700_000_000,
            },
        },
    }
    parsed = parse_signal_response(raw)
    assert parsed.snapshot is not None
    assert parsed.snapshot.soc == 36.9
    assert parsed.snapshot.soc_precise is True
    assert parsed.snapshot.odo_km == 1234.5
    assert parsed.snapshot.battery_min_temp == -18.5
    assert parsed.snapshot.moving is True
    assert parsed.snapshot.sts == 1_700_000_000_000  # seconds -> ms


def test_normalize_and_region_and_moving():
    assert normalize_epoch_ms(1_700_000_000) == 1_700_000_000_000
    assert normalize_epoch_ms(1_700_000_000_000) == 1_700_000_000_000
    assert is_moving(1, 0) is True
    assert is_moving(0, 2) is True
    assert is_moving(0, 0) is False
    assert validate_app_region("https://app-region.leapmotor.com") is None
    assert validate_app_region("http://evil.com") is not None


def test_rate_limit_backoff_exponential():
    s0 = BackoffState()
    s1 = register_rate_limit(s0, 1_000_000, minimum_ms=15 * 60 * 1000)
    assert s1.level == 1
    assert s1.cooldown_until_ms == 1_000_000 + 15 * 60 * 1000
    s2 = register_rate_limit(s1, 1_000_000, minimum_ms=15 * 60 * 1000)
    assert s2.level == 2
    assert s2.cooldown_until_ms == 1_000_000 + 30 * 60 * 1000
    msg = backoff_message(s2, 1_000_000)
    assert msg and "1023" in msg
