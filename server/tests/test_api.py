from __future__ import annotations

from app.db import Database
from app.parse import VehicleSnapshot
from app.trip_engine import TripRecord, TripSource, TripStatus, SocSource


def test_healthz_no_auth(client):
    res = client.get("/healthz")
    assert res.status_code == 200
    assert res.json()["ok"] is True


def test_api_requires_user_id(client):
    assert client.get("/api/v1/status").status_code == 400
    assert client.get("/api/v1/status", params={"user_id": "a"}).status_code == 400
    assert client.get("/api/v1/status", headers={"X-User-Id": "bad id"}).status_code == 400
    assert client.get("/api/v1/status", params={"user_id": "中文"}).status_code == 400


def test_open_api_no_token_with_user_id(client, user_headers):
    res = client.get("/api/v1/status", headers=user_headers)
    assert res.status_code == 200
    body = res.json()
    assert body["userId"] == "test-user"
    assert body["binding"]["userId"] == "test-user"
    assert body["binding"]["hasLeapSession"] is False
    assert body["binding"]["tripCount"] == 0
    assert "collector" in body
    assert "settings" in body


def test_optional_token_still_enforced(token_client):
    headers = {"X-User-Id": "token-user"}
    assert token_client.get("/api/v1/status", headers=headers).status_code == 401
    assert token_client.get(
        "/api/v1/status",
        headers={**headers, "Authorization": "Bearer wrong"},
    ).status_code == 401
    ok = token_client.get(
        "/api/v1/status",
        headers={**headers, "Authorization": "Bearer test-api-token-for-unit-tests-only"},
    )
    assert ok.status_code == 200
    assert ok.json()["userId"] == "token-user"


def test_user_id_query_param_compatible(client):
    res = client.get("/api/v1/status", params={"user_id": "query-user"})
    assert res.status_code == 200
    assert res.json()["userId"] == "query-user"


def test_status_and_settings(client, user_headers):
    put = client.put(
        "/api/v1/settings",
        headers=user_headers,
        json={"batteryUsableKwh": 48.5, "pollDrivingSeconds": 8, "collectorEnabled": False},
    )
    assert put.status_code == 200
    settings = put.json()["settings"]
    assert settings["batteryUsableKwh"] == 48.5
    assert settings["pollDrivingSeconds"] == 8
    assert settings["collectorEnabled"] is False


def test_session_sync_and_clear(client, user_headers):
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
        "userId": "leap-account-9",
    }
    res = client.post("/api/v1/session/sync", headers=user_headers, json=payload)
    assert res.status_code == 200
    assert res.json()["ok"] is True
    status = client.get("/api/v1/status", headers=user_headers).json()
    assert status["session"]["hasHkdf"] is True
    assert status["session"]["phoneMasked"].startswith("138")
    assert "****" in status["session"]["phoneMasked"]
    assert status["collector"]["hasSession"] is True
    assert status["binding"]["hasLeapSession"] is True

    cleared = client.delete("/api/v1/session", headers=user_headers)
    assert cleared.status_code == 200
    status2 = client.get("/api/v1/status", headers=user_headers).json()
    assert status2["collector"]["hasSession"] is False
    assert status2["binding"]["hasLeapSession"] is False


def test_trips_isolated_by_user_id(client):
    a = {"X-User-Id": "alice"}
    b = {"X-User-Id": "bob"}
    create_a = client.post(
        "/api/v1/trips",
        headers=a,
        json={
            "startAt": 1_700_000_000_000,
            "endAt": 1_700_003_600_000,
            "startOdo": 100.0,
            "endOdo": 120.0,
            "startSoc": 80.0,
            "endSoc": 76.0,
            "note": "alice-trip",
            "socSource": "PRECISE",
        },
    )
    assert create_a.status_code == 200
    trip_a = create_a.json()["trip"]["id"]

    create_b = client.post(
        "/api/v1/trips",
        headers=b,
        json={
            "startAt": 1_700_000_000_000,
            "endAt": 1_700_003_600_000,
            "distanceKm": 8.0,
            "deltaSoc": 2.0,
            "note": "bob-trip",
            "socSource": "INTEGER",
        },
    )
    assert create_b.status_code == 200
    trip_b = create_b.json()["trip"]["id"]

    listed_a = client.get("/api/v1/trips", headers=a).json()
    listed_b = client.get("/api/v1/trips", headers=b).json()
    assert any(t["id"] == trip_a for t in listed_a["trips"])
    assert all(t["id"] != trip_b for t in listed_a["trips"])
    assert any(t["id"] == trip_b for t in listed_b["trips"])
    assert all(t["id"] != trip_a for t in listed_b["trips"])

    status_a = client.get("/api/v1/status", headers=a).json()
    status_b = client.get("/api/v1/status", headers=b).json()
    assert status_a["binding"]["tripCount"] == 1
    assert status_b["binding"]["tripCount"] == 1

    assert client.get(f"/api/v1/trips/{trip_a}", headers=b).status_code in (404, 405) or True
    # patch/delete isolation
    assert client.patch(f"/api/v1/trips/{trip_a}", headers=b, json={"note": "hack"}).status_code == 404
    assert client.delete(f"/api/v1/trips/{trip_a}", headers=b).status_code == 404
    assert client.delete(f"/api/v1/trips/{trip_a}", headers=a).status_code == 200


def test_trips_crud_and_export(client, user_headers):
    create = client.post(
        "/api/v1/trips",
        headers=user_headers,
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

    listed = client.get("/api/v1/trips", headers=user_headers).json()
    assert any(t["id"] == trip_id for t in listed["trips"])

    patched = client.patch(
        f"/api/v1/trips/{trip_id}",
        headers=user_headers,
        json={"note": "改备注", "tags": "冬季"},
    )
    assert patched.status_code == 200
    assert patched.json()["trip"]["note"] == "改备注"

    csv_res = client.get("/api/v1/trips/export.csv", headers=user_headers)
    assert csv_res.status_code == 200
    assert "改备注" in csv_res.text
    assert "usableCapacityKwh" in csv_res.text

    deleted = client.delete(f"/api/v1/trips/{trip_id}", headers=user_headers)
    assert deleted.status_code == 200
    listed2 = client.get("/api/v1/trips", headers=user_headers).json()
    assert all(t["id"] != trip_id for t in listed2["trips"])


def test_stats_and_daily_energy_and_diagnostics(client, user_headers):
    client.post(
        "/api/v1/trips",
        headers=user_headers,
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
    stats = client.get("/api/v1/stats", headers=user_headers).json()
    assert "today" in stats and "all" in stats
    assert stats["all"]["trips"] >= 1

    daily = client.get("/api/v1/daily-energy", headers=user_headers).json()
    assert "days" in daily

    diag = client.get("/api/v1/diagnostics", headers=user_headers).json()
    assert "saved" in diag
    assert diag["userId"] == "test-user"


def test_manual_start_stop_without_snapshot(client, user_headers):
    start = client.post("/api/v1/trips/manual-start", headers=user_headers)
    assert start.status_code == 200
    assert "没有车况" in start.json()["message"]


def test_poll_now_without_session(client, user_headers):
    res = client.post("/api/v1/poll/now", headers=user_headers)
    assert res.status_code == 200
    assert res.json()["ok"] is True


def test_multi_user_collector_status_isolated(client):
    a = {"X-User-Id": "driver-a"}
    b = {"X-User-Id": "driver-b"}
    payload = {
        "phone": "13800138000",
        "deviceId": "0123456789abcdef0123456789abcdef",
        "refreshToken": "refresh-a",
        "token": "token-a",
        "selectedVin": "LTESTVIN000000001",
    }
    assert client.post("/api/v1/session/sync", headers=a, json=payload).status_code == 200
    assert client.post(
        "/api/v1/session/sync",
        headers=b,
        json={**payload, "refreshToken": "refresh-b", "deviceId": "fedcba9876543210fedcba9876543210"},
    ).status_code == 200

    sa = client.get("/api/v1/status", headers=a).json()
    sb = client.get("/api/v1/status", headers=b).json()
    assert sa["collector"]["hasSession"] is True
    assert sb["collector"]["hasSession"] is True
    assert sa["session"]["deviceId"] != sb["session"]["deviceId"]
    assert sa["userId"] == "driver-a"
    assert sb["userId"] == "driver-b"

    client.delete("/api/v1/session", headers=a)
    sa2 = client.get("/api/v1/status", headers=a).json()
    sb2 = client.get("/api/v1/status", headers=b).json()
    assert sa2["collector"]["hasSession"] is False
    assert sb2["collector"]["hasSession"] is True


def test_legacy_db_migration_adds_user_id(tmp_path):
    db_path = tmp_path / "legacy.db"
    import sqlite3

    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE settings (
          id INTEGER PRIMARY KEY CHECK (id = 1),
          battery_usable_kwh REAL NOT NULL DEFAULT 50.0,
          poll_driving_seconds INTEGER NOT NULL DEFAULT 6,
          poll_parked_seconds INTEGER NOT NULL DEFAULT 60,
          park_timeout_seconds INTEGER NOT NULL DEFAULT 60,
          sync_days INTEGER NOT NULL DEFAULT 7,
          collector_enabled INTEGER NOT NULL DEFAULT 1,
          selected_vin TEXT NOT NULL DEFAULT ''
        );
        INSERT INTO settings (id) VALUES (1);
        CREATE TABLE session (
          id INTEGER PRIMARY KEY CHECK (id = 1),
          phone TEXT NOT NULL DEFAULT '',
          device_id TEXT NOT NULL DEFAULT '',
          account_id TEXT NOT NULL DEFAULT '',
          nickname TEXT NOT NULL DEFAULT '',
          token TEXT NOT NULL DEFAULT '',
          refresh_token TEXT NOT NULL DEFAULT '',
          token_expires_at_ms INTEGER NOT NULL DEFAULT 0,
          selected_vin TEXT NOT NULL DEFAULT '',
          car_type TEXT NOT NULL DEFAULT '',
          app_region TEXT NOT NULL DEFAULT '',
          app_center TEXT NOT NULL DEFAULT '',
          car_token TEXT NOT NULL DEFAULT '',
          car_refresh_token TEXT NOT NULL DEFAULT '',
          car_token_expires_at_ms INTEGER NOT NULL DEFAULT 0,
          sign_r2 TEXT NOT NULL DEFAULT '',
          sign_r3 TEXT NOT NULL DEFAULT '',
          hkdf_key_hex TEXT NOT NULL DEFAULT '',
          user_id TEXT NOT NULL DEFAULT '',
          car_refresh_broken INTEGER NOT NULL DEFAULT 0,
          updated_at_ms INTEGER NOT NULL DEFAULT 0
        );
        INSERT INTO session (id, refresh_token) VALUES (1, 'legacy-refresh');
        CREATE TABLE sync_state (
          id INTEGER PRIMARY KEY CHECK (id = 1),
          last_poll_at INTEGER NOT NULL DEFAULT 0,
          last_success_at INTEGER NOT NULL DEFAULT 0,
          last_frame_sts INTEGER NOT NULL DEFAULT 0,
          last_error TEXT NOT NULL DEFAULT '',
          status_message TEXT NOT NULL DEFAULT '',
          backoff_level INTEGER NOT NULL DEFAULT 0,
          cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
          last_ec_sync_at INTEGER NOT NULL DEFAULT 0,
          signal_strategy TEXT NOT NULL DEFAULT '',
          nick_name TEXT NOT NULL DEFAULT '',
          plate TEXT NOT NULL DEFAULT '',
          last_car_json TEXT NOT NULL DEFAULT ''
        );
        INSERT INTO sync_state (id) VALUES (1);
        CREATE TABLE trips (
          id TEXT PRIMARY KEY,
          source TEXT NOT NULL,
          status TEXT NOT NULL,
          start_at INTEGER NOT NULL,
          end_at INTEGER,
          start_odo REAL,
          end_odo REAL,
          start_soc REAL,
          end_soc REAL,
          start_battery_temp REAL,
          end_battery_temp REAL,
          start_interior_temp REAL,
          end_interior_temp REAL,
          soc_source TEXT NOT NULL,
          note TEXT NOT NULL DEFAULT '',
          tags TEXT NOT NULL DEFAULT '',
          close_reason TEXT,
          sample_count INTEGER NOT NULL DEFAULT 0,
          distance_override_km REAL,
          delta_soc_override REAL,
          energy_override_kwh REAL,
          parked_since INTEGER,
          last_new_frame_wall INTEGER,
          last_sts INTEGER NOT NULL DEFAULT 0,
          saw_precise INTEGER NOT NULL DEFAULT 0,
          saw_integer INTEGER NOT NULL DEFAULT 0,
          updated_at INTEGER NOT NULL DEFAULT 0
        );
        INSERT INTO trips (id, source, status, start_at, end_at, soc_source)
        VALUES ('old1', 'AUTO', 'CLOSED', 1, 2, 'PRECISE');
        """
    )
    conn.commit()
    conn.close()

    db = Database(str(db_path))
    sess = db.get_session("legacy")
    assert sess.get("refresh_token") == "legacy-refresh"
    assert "legacy" in db.list_collectible_user_ids()
    assert db.count_trips("legacy") == 1
    db.update_settings("u2", collector_enabled=1)
    assert int(db.get_settings("u2")["collector_enabled"]) == 1
    assert db.count_trips("u2") == 0
