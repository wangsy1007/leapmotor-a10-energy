from __future__ import annotations

import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

# 默认无 Token 直连；个别用例可再设 API_TOKEN。
os.environ["API_TOKEN"] = ""
os.environ["DATABASE_PATH"] = str(Path("/tmp/leapmotor-test.db"))
os.environ["COLLECTOR_ENABLED"] = "0"

from app.config import clear_settings_cache  # noqa: E402


@pytest.fixture(autouse=True)
def _reset_env(tmp_path, monkeypatch):
    db_path = tmp_path / "leapmotor.db"
    monkeypatch.setenv("API_TOKEN", "")
    monkeypatch.setenv("DATABASE_PATH", str(db_path))
    monkeypatch.setenv("COLLECTOR_ENABLED", "0")
    clear_settings_cache()
    yield
    clear_settings_cache()


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("API_TOKEN", "")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "api.db"))
    monkeypatch.setenv("COLLECTOR_ENABLED", "0")
    clear_settings_cache()
    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def user_headers():
    return {"X-User-Id": "test-user"}


@pytest.fixture
def token_client(tmp_path, monkeypatch):
    monkeypatch.setenv("API_TOKEN", "test-api-token-for-unit-tests-only")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "token-api.db"))
    monkeypatch.setenv("COLLECTOR_ENABLED", "0")
    clear_settings_cache()
    from app.main import app

    with TestClient(app) as test_client:
        yield test_client
