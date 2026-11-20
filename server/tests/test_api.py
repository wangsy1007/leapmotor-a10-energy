from __future__ import annotations

import json
from dataclasses import replace

from app.db import Database
from app.parse import VehicleSnapshot
from app.trip_engine import TripRecord, TripSource, TripStatus, SocSource


def test_healthz_no_auth(client):
    res = client.get("/healthz")
    assert res.status_code == 200
    assert res.json()["ok"] is True


def test_api_requires_bearer(client):
    assert client.get("/api/v1/status").status_code == 401
    assert client.get("/api/v1/status", headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_status_and_settings(client, auth_headers):
    res = client.get("/api/v1/status", headers=auth_headers)
    assert res.status_code == 200
    body = res.json()
    assert "collector" in body
    assert "settings" in body
    assert body["version"]

    put = client.put(
        "/api/v1/settings",
        headers=auth_headers,
        json={"batteryUsableKwh": 48.5, "pollDrivingSeconds": 8, "collectorEnabled": False},
    )
    assert put.status_code == 200
    settings = put.json()["settings"]
    assert settings["batteryUsableKwh"] == 48.5
    assert settings["pollDrivingSeconds"] == 8
    assert settings["collectorEnabled"] is False


def test_session_sync_and_clear(client, auth_headers):
    payload = {
        "phone": "13800138000",
        "deviceId": "0123456789abcdef0123456789abcdef",
        "accountId": "acc-1",
        "nickname": "LP_test",
        "token": "account-token-value",
        "refreshToken": "refresh-token-value",
        "tokenExpiresAtMs": 9_999_999_999_000,
        "selectedVin": "LTESTVIN000000001",
        "carType": "A10",
        "appRegion": "https://app-region.leapmotor.com",
        "carToken": "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0In0.WltYWV5fXF1SU1BRVldUVUpLSElOT0xNQkNAQUZHREU",
        "carTokenExpiresAtMs": 9_999_999_999_000,
        "signR2": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
        "signR3": "Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA",
    }
    res = client.post("/api/v1/session/sync", headers=auth_headers, json=payload)
    assert res.status_code == 200
    assert res.json()["ok"] is True
    status = client.get("/api/v1/status", headers=auth_headers).json()
    assert status["session"]["hasHkdf"] is True
    assert status["session"]["phoneMasked"].startswith("138")
    assert "****" in status["session"]["phoneMasked"]
    assert status["collector"]["hasSession"] is True

    cleared = client.delete("/api/v1/session", headers=auth_headers)
    assert cleared.status_code == 200
    status2 = client.get("/api/v1/status", headers=auth_headers).json()
    assert status2["collector"]["hasSession"] is False


def test_trips_crud_and_export(client, auth_headers):
    create = client.post(
        "/api/v1/trips",
        headers=auth_headers,
        json={
            "startAt": 1_700_000_000_000,
            "endAt": 1_700_003_600_000,
            "startOdo": 100.0,
            "endOdo": 120.0,
            "startSoc": 80.0,
            "endSoc": 76.0,
            "startBatteryTemp": -12.0,
            "endBatteryTemp": -10.0,
            "note": "补录",
            "tags": "通勤",
            "socSource": "PRECISE",
        },
    )
    assert create.status_code == 200
    trip_id = create.json()["trip"]["id"]
    assert create.json()["trip"]["distanceKm"] == 20.0

    listed = client.get("/api/v1/trips", headers=auth_headers).json()
    assert any(t["id"] == trip_id for t in listed["trips"])

    patched = client.patch(
        f"/api/v1/trips/{trip_id}",
        headers=auth_headers,
        json={"note": "改备注", "tags": "冬季"},
    )
    assert patched.status_code == 200
    assert patched.json()["trip"]["note"] == "改备注"

    csv_res = client.get("/api/v1/trips/export.csv", headers=auth_headers)
    assert csv_res.status_code == 200
    assert "改备注" in csv_res.text
    assert "usableCapacityKwh" in csv_res.text

    deleted = client.delete(f"/api/v1/trips/{trip_id}", headers=auth_headers)
    assert deleted.status_code == 200
    listed2 = client.get("/api/v1/trips", headers=auth_headers).json()
    assert all(t["id"] != trip_id for t in listed2["trips"])


def test_stats_and_daily_energy_and_diagnostics(client, auth_headers, tmp_path):
    # seed a closed trip via create API
    client.post(
        "/api/v1/trips",
        headers=auth_headers,
        json={
            "startAt": 1_700_000_000_000,
            "endAt": 1_700_003_600_000,
            "distanceKm": 30.0,
            "deltaSoc": 6.0,
            "startBatteryTemp": -15.0,
            "endBatteryTemp": -14.0,
            "socSource": "INTEGER",
        },
    )
    stats = client.get("/api/v1/stats", headers=auth_headers).json()
    assert "today" in stats and "all" in stats
    assert stats["all"]["trips"] >= 1

    daily = client.get("/api/v1/daily-energy", headers=auth_headers).json()
    assert "days" in daily

    diag = client.get("/api/v1/diagnostics", headers=auth_headers).json()
    assert "saved" in diag


def test_manual_start_stop_without_snapshot(client, auth_headers):
    start = client.post("/api/v1/trips/manual-start", headers=auth_headers)
    assert start.status_code == 200
    assert "没有车况" in start.json()["message"]


def test_poll_now_without_session(client, auth_headers):
    res = client.post("/api/v1/poll/now", headers=auth_headers)
    assert res.status_code == 200
    assert res.json()["ok"] is True
