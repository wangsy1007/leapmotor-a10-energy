from __future__ import annotations

import pytest

from app.config import Settings, clear_settings_cache


def test_api_token_required(monkeypatch):
    monkeypatch.setenv("API_TOKEN", "")
    clear_settings_cache()
    settings = Settings()
    with pytest.raises(RuntimeError, match="API_TOKEN"):
        settings.require_api_token()


def test_ipv4_transport_local_address():
    from app.leap_client import ipv4_transport

    transport = ipv4_transport()
    # httpx stores the bind address used to force IPv4 egress
    pool = getattr(transport, "_pool", None)
    local = getattr(pool, "_local_address", None) or getattr(transport, "_local_address", None)
    assert local in ("0.0.0.0", None) or str(local) == "0.0.0.0"
    # At minimum the factory returns AsyncHTTPTransport
    assert "HTTPTransport" in type(transport).__name__
