from __future__ import annotations

from app.config import Settings, clear_settings_cache


def test_api_token_optional_by_default(monkeypatch):
    monkeypatch.setenv("API_TOKEN", "")
    clear_settings_cache()
    settings = Settings()
    assert settings.api_token == ""
    assert settings.token_required is False


def test_api_token_required_when_configured(monkeypatch):
    monkeypatch.setenv("API_TOKEN", "configured-token")
    clear_settings_cache()
    settings = Settings()
    assert settings.token_required is True
    assert settings.api_token == "configured-token"


def test_ipv4_transport_local_address():
    from app.leap_client import ipv4_transport

    transport = ipv4_transport()
    pool = getattr(transport, "_pool", None)
    local = getattr(pool, "_local_address", None) or getattr(transport, "_local_address", None)
    assert local in ("0.0.0.0", None) or str(local) == "0.0.0.0"
    assert "HTTPTransport" in type(transport).__name__
