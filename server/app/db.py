from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from .parse import VehicleSnapshot
from .trip_engine import Anchor, SocSource, TrackerState, TripRecord, TripSource, TripStatus

DEFAULT_SETTINGS = {
    "battery_usable_kwh": 50.0,
    "poll_driving_seconds": 6,
    "poll_parked_seconds": 60,
    "park_timeout_seconds": 60,
    "sync_days": 7,
    "collector_enabled": 1,
    "selected_vin": "",
}


class Database:
    def __init__(self, path: str) -> None:
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._init_schema()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            conn = sqlite3.connect(self.path, check_same_thread=False)
            conn.row_factory = sqlite3.Row
            try:
                yield conn
                conn.commit()
            except Exception:
                conn.rollback()
                raise
            finally:
                conn.close()

    def _table_columns(self, conn: sqlite3.Connection, table: str) -> set[str]:
        rows = conn.execute(f"PRAGMA table_info({table})").fetchall()
        return {r["name"] for r in rows}

    def _table_exists(self, conn: sqlite3.Connection, table: str) -> bool:
        row = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            (table,),
        ).fetchone()
        return row is not None

    def _init_schema(self) -> None:
        with self.connect() as conn:
            self._migrate_legacy(conn)
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                  user_id TEXT PRIMARY KEY,
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
                  leap_user_id TEXT NOT NULL DEFAULT '',
                  car_refresh_broken INTEGER NOT NULL DEFAULT 0,
                  updated_at_ms INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS user_settings (
                  user_id TEXT PRIMARY KEY,
                  battery_usable_kwh REAL NOT NULL DEFAULT 50.0,
                  poll_driving_seconds INTEGER NOT NULL DEFAULT 6,
                  poll_parked_seconds INTEGER NOT NULL DEFAULT 60,
                  park_timeout_seconds INTEGER NOT NULL DEFAULT 60,
                  sync_days INTEGER NOT NULL DEFAULT 7,
                  collector_enabled INTEGER NOT NULL DEFAULT 1,
                  selected_vin TEXT NOT NULL DEFAULT ''
                );

                CREATE TABLE IF NOT EXISTS user_sync_state (
                  user_id TEXT PRIMARY KEY,
                  last_frame_sts INTEGER NOT NULL DEFAULT 0,
                  anchor_at INTEGER,
                  anchor_sts INTEGER NOT NULL DEFAULT 0,
                  anchor_odo REAL,
                  anchor_soc REAL,
                  anchor_battery REAL,
                  anchor_interior REAL,
                  anchor_soc_precise INTEGER NOT NULL DEFAULT 0,
                  anchor_charging INTEGER NOT NULL DEFAULT 0,
                  backoff_level INTEGER NOT NULL DEFAULT 0,
                  cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
                  nick_name TEXT NOT NULL DEFAULT '',
                  plate TEXT NOT NULL DEFAULT '',
                  signal_strategy TEXT NOT NULL DEFAULT '',
                  last_error TEXT NOT NULL DEFAULT '',
                  last_success_at INTEGER NOT NULL DEFAULT 0,
                  last_poll_at INTEGER NOT NULL DEFAULT 0,
                  last_car_json TEXT NOT NULL DEFAULT '',
                  status_message TEXT NOT NULL DEFAULT '',
                  last_ec_sync_at INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS trips (
                  id TEXT NOT NULL,
                  user_id TEXT NOT NULL,
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
                  updated_at INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (user_id, id)
                );
                CREATE INDEX IF NOT EXISTS idx_trips_user_start ON trips(user_id, start_at);
                CREATE INDEX IF NOT EXISTS idx_trips_user_status ON trips(user_id, status);

                CREATE TABLE IF NOT EXISTS official_days (
                  user_id TEXT NOT NULL,
                  date TEXT NOT NULL,
                  driver_kwh REAL,
                  ac_kwh REAL,
                  other_kwh REAL,
                  mileage_km REAL,
                  pending INTEGER NOT NULL DEFAULT 0,
                  note TEXT NOT NULL DEFAULT '',
                  raw_ec TEXT NOT NULL DEFAULT '',
                  synced_at INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (user_id, date)
                );
                CREATE INDEX IF NOT EXISTS idx_official_user_date ON official_days(user_id, date);

                CREATE TABLE IF NOT EXISTS diagnostics (
                  user_id TEXT NOT NULL,
                  name TEXT NOT NULL,
                  content TEXT NOT NULL DEFAULT '',
                  updated_at INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (user_id, name)
                );
                """
            )

    def _migrate_legacy(self, conn: sqlite3.Connection) -> None:
        """把旧版单例表迁到按 user_id 隔离的新表，并兼容旧 trips 无 user_id 的情况。"""
        legacy_uid = "legacy"

        # 旧 session / settings / sync_state 单例
        if self._table_exists(conn, "session") and not self._table_exists(conn, "sessions"):
            cols = self._table_columns(conn, "session")
            row = conn.execute("SELECT * FROM session WHERE id = 1").fetchone()
            if row:
                data = dict(row)
                leap_uid = data.get("user_id") or ""
                conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                      user_id TEXT PRIMARY KEY,
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
                      leap_user_id TEXT NOT NULL DEFAULT '',
                      car_refresh_broken INTEGER NOT NULL DEFAULT 0,
                      updated_at_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """
                )
                if data.get("refresh_token") or data.get("token"):
                    conn.execute(
                        """
                        INSERT OR IGNORE INTO sessions (
                          user_id, phone, device_id, account_id, nickname, token, refresh_token,
                          token_expires_at_ms, selected_vin, car_type, app_region, app_center,
                          car_token, car_refresh_token, car_token_expires_at_ms, sign_r2, sign_r3,
                          hkdf_key_hex, leap_user_id, car_refresh_broken, updated_at_ms
                        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """,
                        (
                            legacy_uid,
                            data.get("phone") or "",
                            data.get("device_id") or "",
                            data.get("account_id") or "",
                            data.get("nickname") or "",
                            data.get("token") or "",
                            data.get("refresh_token") or "",
                            data.get("token_expires_at_ms") or 0,
                            data.get("selected_vin") or "",
                            data.get("car_type") or "",
                            data.get("app_region") or "",
                            data.get("app_center") or "",
                            data.get("car_token") or "",
                            data.get("car_refresh_token") or "",
                            data.get("car_token_expires_at_ms") or 0,
                            data.get("sign_r2") or "",
                            data.get("sign_r3") or "",
                            data.get("hkdf_key_hex") or "",
                            leap_uid,
                            data.get("car_refresh_broken") or 0,
                            data.get("updated_at_ms") or 0,
                        ),
                    )

        if self._table_exists(conn, "settings") and not self._table_exists(conn, "user_settings"):
            row = conn.execute("SELECT * FROM settings WHERE id = 1").fetchone()
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS user_settings (
                  user_id TEXT PRIMARY KEY,
                  battery_usable_kwh REAL NOT NULL DEFAULT 50.0,
                  poll_driving_seconds INTEGER NOT NULL DEFAULT 6,
                  poll_parked_seconds INTEGER NOT NULL DEFAULT 60,
                  park_timeout_seconds INTEGER NOT NULL DEFAULT 60,
                  sync_days INTEGER NOT NULL DEFAULT 7,
                  collector_enabled INTEGER NOT NULL DEFAULT 1,
                  selected_vin TEXT NOT NULL DEFAULT ''
                )
                """
            )
            if row:
                d = dict(row)
                conn.execute(
                    """
                    INSERT OR IGNORE INTO user_settings (
                      user_id, battery_usable_kwh, poll_driving_seconds, poll_parked_seconds,
                      park_timeout_seconds, sync_days, collector_enabled, selected_vin
                    ) VALUES (?,?,?,?,?,?,?,?)
                    """,
                    (
                        legacy_uid,
                        d.get("battery_usable_kwh") or 50.0,
                        d.get("poll_driving_seconds") or 6,
                        d.get("poll_parked_seconds") or 60,
                        d.get("park_timeout_seconds") or 60,
                        d.get("sync_days") or 7,
                        d.get("collector_enabled") if d.get("collector_enabled") is not None else 1,
                        d.get("selected_vin") or "",
                    ),
                )

        if self._table_exists(conn, "sync_state") and not self._table_exists(conn, "user_sync_state"):
            row = conn.execute("SELECT * FROM sync_state WHERE id = 1").fetchone()
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS user_sync_state (
                  user_id TEXT PRIMARY KEY,
                  last_frame_sts INTEGER NOT NULL DEFAULT 0,
                  anchor_at INTEGER,
                  anchor_sts INTEGER NOT NULL DEFAULT 0,
                  anchor_odo REAL,
                  anchor_soc REAL,
                  anchor_battery REAL,
                  anchor_interior REAL,
                  anchor_soc_precise INTEGER NOT NULL DEFAULT 0,
                  anchor_charging INTEGER NOT NULL DEFAULT 0,
                  backoff_level INTEGER NOT NULL DEFAULT 0,
                  cooldown_until_ms INTEGER NOT NULL DEFAULT 0,
                  nick_name TEXT NOT NULL DEFAULT '',
                  plate TEXT NOT NULL DEFAULT '',
                  signal_strategy TEXT NOT NULL DEFAULT '',
                  last_error TEXT NOT NULL DEFAULT '',
                  last_success_at INTEGER NOT NULL DEFAULT 0,
                  last_poll_at INTEGER NOT NULL DEFAULT 0,
                  last_car_json TEXT NOT NULL DEFAULT '',
                  status_message TEXT NOT NULL DEFAULT '',
                  last_ec_sync_at INTEGER NOT NULL DEFAULT 0
                )
                """
            )
            if row:
                d = dict(row)
                conn.execute(
                    """
                    INSERT OR IGNORE INTO user_sync_state (
                      user_id, last_frame_sts, anchor_at, anchor_sts, anchor_odo, anchor_soc,
                      anchor_battery, anchor_interior, anchor_soc_precise, anchor_charging,
                      backoff_level, cooldown_until_ms, nick_name, plate, signal_strategy,
                      last_error, last_success_at, last_poll_at, last_car_json, status_message,
                      last_ec_sync_at
                    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    (
                        legacy_uid,
                        d.get("last_frame_sts") or 0,
                        d.get("anchor_at"),
                        d.get("anchor_sts") or 0,
                        d.get("anchor_odo"),
                        d.get("anchor_soc"),
                        d.get("anchor_battery"),
                        d.get("anchor_interior"),
                        d.get("anchor_soc_precise") or 0,
                        d.get("anchor_charging") or 0,
                        d.get("backoff_level") or 0,
                        d.get("cooldown_until_ms") or 0,
                        d.get("nick_name") or "",
                        d.get("plate") or "",
                        d.get("signal_strategy") or "",
                        d.get("last_error") or "",
                        d.get("last_success_at") or 0,
                        d.get("last_poll_at") or 0,
                        d.get("last_car_json") or "",
                        d.get("status_message") or "",
                        d.get("last_ec_sync_at") or 0,
                    ),
                )

        # trips：旧表无 user_id 列时重建
        if self._table_exists(conn, "trips"):
            cols = self._table_columns(conn, "trips")
            if "user_id" not in cols:
                conn.execute("ALTER TABLE trips RENAME TO trips_legacy_tmp")
                conn.execute(
                    """
                    CREATE TABLE trips (
                      id TEXT NOT NULL,
                      user_id TEXT NOT NULL,
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
                      updated_at INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (user_id, id)
                    )
                    """
                )
                old_cols = self._table_columns(conn, "trips_legacy_tmp")
                common = [
                    c
                    for c in (
                        "id",
                        "source",
                        "status",
                        "start_at",
                        "end_at",
                        "start_odo",
                        "end_odo",
                        "start_soc",
                        "end_soc",
                        "start_battery_temp",
                        "end_battery_temp",
                        "start_interior_temp",
                        "end_interior_temp",
                        "soc_source",
                        "note",
                        "tags",
                        "close_reason",
                        "sample_count",
                        "distance_override_km",
                        "delta_soc_override",
                        "energy_override_kwh",
                        "parked_since",
                        "last_new_frame_wall",
                        "last_sts",
                        "saw_precise",
                        "saw_integer",
                        "updated_at",
                    )
                    if c in old_cols
                ]
                col_list = ", ".join(common)
                conn.execute(
                    f"INSERT INTO trips (user_id, {col_list}) SELECT ?, {col_list} FROM trips_legacy_tmp",
                    (legacy_uid,),
                )
                conn.execute("DROP TABLE trips_legacy_tmp")

        if self._table_exists(conn, "official_days"):
            cols = self._table_columns(conn, "official_days")
            if "user_id" not in cols:
                conn.execute("ALTER TABLE official_days RENAME TO official_days_legacy_tmp")
                conn.execute(
                    """
                    CREATE TABLE official_days (
                      user_id TEXT NOT NULL,
                      date TEXT NOT NULL,
                      driver_kwh REAL,
                      ac_kwh REAL,
                      other_kwh REAL,
                      mileage_km REAL,
                      pending INTEGER NOT NULL DEFAULT 0,
                      note TEXT NOT NULL DEFAULT '',
                      raw_ec TEXT NOT NULL DEFAULT '',
                      synced_at INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (user_id, date)
                    )
                    """
                )
                conn.execute(
                    """
                    INSERT INTO official_days (
                      user_id, date, driver_kwh, ac_kwh, other_kwh, mileage_km, pending, note, raw_ec, synced_at
                    )
                    SELECT ?, date, driver_kwh, ac_kwh, other_kwh, mileage_km, pending, note, raw_ec, synced_at
                    FROM official_days_legacy_tmp
                    """,
                    (legacy_uid,),
                )
                conn.execute("DROP TABLE official_days_legacy_tmp")

        if self._table_exists(conn, "diagnostics"):
            cols = self._table_columns(conn, "diagnostics")
            if "user_id" not in cols:
                conn.execute("ALTER TABLE diagnostics RENAME TO diagnostics_legacy_tmp")
                conn.execute(
                    """
                    CREATE TABLE diagnostics (
                      user_id TEXT NOT NULL,
                      name TEXT NOT NULL,
                      content TEXT NOT NULL DEFAULT '',
                      updated_at INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (user_id, name)
                    )
                    """
                )
                conn.execute(
                    """
                    INSERT INTO diagnostics (user_id, name, content, updated_at)
                    SELECT ?, name, content, updated_at FROM diagnostics_legacy_tmp
                    """,
                    (legacy_uid,),
                )
                conn.execute("DROP TABLE diagnostics_legacy_tmp")

    def ensure_user(self, user_id: str) -> None:
        with self.connect() as conn:
            conn.execute(
                "INSERT OR IGNORE INTO user_settings (user_id) VALUES (?)",
                (user_id,),
            )
            conn.execute(
                "INSERT OR IGNORE INTO user_sync_state (user_id) VALUES (?)",
                (user_id,),
            )
            conn.execute(
                "INSERT OR IGNORE INTO sessions (user_id) VALUES (?)",
                (user_id,),
            )

    def list_collectible_user_ids(self) -> list[str]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT s.user_id
                FROM sessions s
                JOIN user_settings u ON u.user_id = s.user_id
                WHERE u.collector_enabled = 1
                  AND s.refresh_token != ''
                """
            ).fetchall()
            return [r["user_id"] for r in rows]

    def get_session(self, user_id: str) -> dict[str, Any]:
        self.ensure_user(user_id)
        with self.connect() as conn:
            row = conn.execute("SELECT * FROM sessions WHERE user_id=?", (user_id,)).fetchone()
            return dict(row) if row else {"user_id": user_id}

    def save_session(self, user_id: str, data: dict[str, Any], updated_at_ms: int) -> None:
        self.ensure_user(user_id)
        fields = [
            "phone",
            "device_id",
            "account_id",
            "nickname",
            "token",
            "refresh_token",
            "token_expires_at_ms",
            "selected_vin",
            "car_type",
            "app_region",
            "app_center",
            "car_token",
            "car_refresh_token",
            "car_token_expires_at_ms",
            "sign_r2",
            "sign_r3",
            "hkdf_key_hex",
            "leap_user_id",
            "car_refresh_broken",
        ]
        cols = ", ".join(f"{f}=?" for f in fields)
        values = [data.get(f, "" if f != "car_refresh_broken" else 0) for f in fields]
        values.extend([updated_at_ms, user_id])
        with self.connect() as conn:
            conn.execute(
                f"UPDATE sessions SET {cols}, updated_at_ms=? WHERE user_id=?",
                values,
            )

    def clear_session(self, user_id: str) -> None:
        self.ensure_user(user_id)
        with self.connect() as conn:
            conn.execute(
                """
                UPDATE sessions SET
                  phone='', account_id='', nickname='', token='', refresh_token='',
                  token_expires_at_ms=0, selected_vin='', car_type='', app_region='',
                  app_center='', car_token='', car_refresh_token='', car_token_expires_at_ms=0,
                  sign_r2='', sign_r3='', hkdf_key_hex='', leap_user_id='', car_refresh_broken=0,
                  updated_at_ms=0
                WHERE user_id=?
                """,
                (user_id,),
            )

    def get_settings(self, user_id: str) -> dict[str, Any]:
        self.ensure_user(user_id)
        with self.connect() as conn:
            row = conn.execute("SELECT * FROM user_settings WHERE user_id=?", (user_id,)).fetchone()
            return dict(row) if row else {"user_id": user_id, **DEFAULT_SETTINGS}

    def update_settings(self, user_id: str, **kwargs: Any) -> dict[str, Any]:
        self.ensure_user(user_id)
        allowed = {
            "battery_usable_kwh",
            "poll_driving_seconds",
            "poll_parked_seconds",
            "park_timeout_seconds",
            "sync_days",
            "collector_enabled",
            "selected_vin",
        }
        updates = {k: v for k, v in kwargs.items() if k in allowed and v is not None}
        if updates:
            cols = ", ".join(f"{k}=?" for k in updates)
            with self.connect() as conn:
                conn.execute(
                    f"UPDATE user_settings SET {cols} WHERE user_id=?",
                    list(updates.values()) + [user_id],
                )
        return self.get_settings(user_id)

    def get_sync_state(self, user_id: str) -> dict[str, Any]:
        self.ensure_user(user_id)
        with self.connect() as conn:
            row = conn.execute("SELECT * FROM user_sync_state WHERE user_id=?", (user_id,)).fetchone()
            return dict(row) if row else {"user_id": user_id}

    def save_sync_state(self, user_id: str, data: dict[str, Any]) -> None:
        self.ensure_user(user_id)
        fields = [
            "last_frame_sts",
            "anchor_at",
            "anchor_sts",
            "anchor_odo",
            "anchor_soc",
            "anchor_battery",
            "anchor_interior",
            "anchor_soc_precise",
            "anchor_charging",
            "backoff_level",
            "cooldown_until_ms",
            "nick_name",
            "plate",
            "signal_strategy",
            "last_error",
            "last_success_at",
            "last_poll_at",
            "last_car_json",
            "status_message",
            "last_ec_sync_at",
        ]
        cols = ", ".join(f"{f}=?" for f in fields)
        values = [data.get(f) for f in fields] + [user_id]
        with self.connect() as conn:
            conn.execute(f"UPDATE user_sync_state SET {cols} WHERE user_id=?", values)

    def load_tracker(self, user_id: str) -> tuple[dict[str, Any], TrackerState]:
        sync = self.get_sync_state(user_id)
        open_trip = self.get_open_trip(user_id)
        anchor = Anchor(
            at=sync.get("anchor_at"),
            sts=int(sync.get("anchor_sts") or 0),
            odo=sync.get("anchor_odo"),
            soc=sync.get("anchor_soc"),
            battery_temp=sync.get("anchor_battery"),
            interior_temp=sync.get("anchor_interior"),
            soc_precise=bool(sync.get("anchor_soc_precise")),
            charging=bool(sync.get("anchor_charging")),
        )
        return sync, TrackerState(open=open_trip, last_frame_sts=int(sync.get("last_frame_sts") or 0), anchor=anchor)

    def apply_tracker(self, user_id: str, sync: dict[str, Any], state: TrackerState, now: int, effects: list) -> None:
        for effect in effects:
            if hasattr(effect, "trip"):
                self.upsert_trip(user_id, effect.trip, now)
            elif hasattr(effect, "id") and effect.__class__.__name__ == "Delete":
                self.delete_trip(user_id, effect.id)
        sync = dict(sync)
        sync["last_frame_sts"] = state.last_frame_sts
        anchor = state.anchor or Anchor()
        sync["anchor_at"] = anchor.at
        sync["anchor_sts"] = anchor.sts
        sync["anchor_odo"] = anchor.odo
        sync["anchor_soc"] = anchor.soc
        sync["anchor_battery"] = anchor.battery_temp
        sync["anchor_interior"] = anchor.interior_temp
        sync["anchor_soc_precise"] = 1 if anchor.soc_precise else 0
        sync["anchor_charging"] = 1 if anchor.charging else 0
        self.save_sync_state(user_id, sync)

    def _row_to_trip(self, row: sqlite3.Row) -> TripRecord:
        return TripRecord(
            id=row["id"],
            source=TripSource(row["source"]),
            status=TripStatus(row["status"]),
            start_at=row["start_at"],
            end_at=row["end_at"],
            start_odo=row["start_odo"],
            end_odo=row["end_odo"],
            start_soc=row["start_soc"],
            end_soc=row["end_soc"],
            start_battery_temp=row["start_battery_temp"],
            end_battery_temp=row["end_battery_temp"],
            start_interior_temp=row["start_interior_temp"],
            end_interior_temp=row["end_interior_temp"],
            soc_source=SocSource(row["soc_source"]),
            note=row["note"] or "",
            tags=row["tags"] or "",
            close_reason=row["close_reason"],
            sample_count=row["sample_count"] or 0,
            distance_override_km=row["distance_override_km"],
            delta_soc_override=row["delta_soc_override"],
            energy_override_kwh=row["energy_override_kwh"],
            parked_since=row["parked_since"],
            last_new_frame_wall=row["last_new_frame_wall"],
            last_sts=row["last_sts"] or 0,
            saw_precise=bool(row["saw_precise"]),
            saw_integer=bool(row["saw_integer"]),
        )

    def list_trips(self, user_id: str) -> list[TripRecord]:
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM trips WHERE user_id=? ORDER BY start_at DESC",
                (user_id,),
            ).fetchall()
            return [self._row_to_trip(r) for r in rows]

    def count_trips(self, user_id: str) -> int:
        with self.connect() as conn:
            row = conn.execute("SELECT COUNT(*) AS c FROM trips WHERE user_id=?", (user_id,)).fetchone()
            return int(row["c"] if row else 0)

    def get_trip(self, user_id: str, trip_id: str) -> TripRecord | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM trips WHERE user_id=? AND id=?",
                (user_id, trip_id),
            ).fetchone()
            return self._row_to_trip(row) if row else None

    def get_open_trip(self, user_id: str) -> TripRecord | None:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT * FROM trips WHERE user_id=? AND status='OPEN' ORDER BY start_at DESC LIMIT 1",
                (user_id,),
            ).fetchone()
            return self._row_to_trip(row) if row else None

    def upsert_trip(self, user_id: str, trip: TripRecord, updated_at: int) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO trips (
                  id, user_id, source, status, start_at, end_at, start_odo, end_odo, start_soc, end_soc,
                  start_battery_temp, end_battery_temp, start_interior_temp, end_interior_temp,
                  soc_source, note, tags, close_reason, sample_count, distance_override_km,
                  delta_soc_override, energy_override_kwh, parked_since, last_new_frame_wall,
                  last_sts, saw_precise, saw_integer, updated_at
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    trip.id,
                    user_id,
                    trip.source.value,
                    trip.status.value,
                    trip.start_at,
                    trip.end_at,
                    trip.start_odo,
                    trip.end_odo,
                    trip.start_soc,
                    trip.end_soc,
                    trip.start_battery_temp,
                    trip.end_battery_temp,
                    trip.start_interior_temp,
                    trip.end_interior_temp,
                    trip.soc_source.value,
                    trip.note,
                    trip.tags,
                    trip.close_reason,
                    trip.sample_count,
                    trip.distance_override_km,
                    trip.delta_soc_override,
                    trip.energy_override_kwh,
                    trip.parked_since,
                    trip.last_new_frame_wall,
                    trip.last_sts,
                    1 if trip.saw_precise else 0,
                    1 if trip.saw_integer else 0,
                    updated_at,
                ),
            )

    def update_trip_note(self, user_id: str, trip_id: str, note: str, tags: str, updated_at: int) -> None:
        with self.connect() as conn:
            conn.execute(
                "UPDATE trips SET note=?, tags=?, updated_at=? WHERE user_id=? AND id=?",
                (note.strip(), tags.strip(), updated_at, user_id, trip_id),
            )

    def delete_trip(self, user_id: str, trip_id: str) -> None:
        with self.connect() as conn:
            conn.execute("DELETE FROM trips WHERE user_id=? AND id=?", (user_id, trip_id))

    def save_official_day(self, user_id: str, data: dict[str, Any]) -> None:
        with self.connect() as conn:
            previous = conn.execute(
                "SELECT * FROM official_days WHERE user_id=? AND date=?",
                (user_id, data["date"]),
            ).fetchone()
            if (
                data.get("pending")
                and previous
                and previous["driver_kwh"] is not None
                and data.get("driver_kwh") is None
            ):
                conn.execute(
                    """
                    UPDATE official_days SET pending=1, note=?, synced_at=?, raw_ec=?
                    WHERE user_id=? AND date=?
                    """,
                    (data.get("note", ""), data.get("synced_at", 0), data.get("raw_ec", ""), user_id, data["date"]),
                )
                return
            conn.execute(
                """
                INSERT OR REPLACE INTO official_days
                (user_id, date, driver_kwh, ac_kwh, other_kwh, mileage_km, pending, note, raw_ec, synced_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                (
                    user_id,
                    data["date"],
                    data.get("driver_kwh"),
                    data.get("ac_kwh"),
                    data.get("other_kwh"),
                    data.get("mileage_km"),
                    1 if data.get("pending") else 0,
                    data.get("note", ""),
                    data.get("raw_ec", ""),
                    data.get("synced_at", 0),
                ),
            )

    def list_official_days(self, user_id: str) -> list[dict[str, Any]]:
        with self.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM official_days WHERE user_id=? ORDER BY date DESC",
                (user_id,),
            ).fetchall()
            return [dict(r) for r in rows]

    def write_diagnostic(self, user_id: str, name: str, content: str, updated_at: int) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT OR REPLACE INTO diagnostics(user_id, name, content, updated_at)
                VALUES (?,?,?,?)
                """,
                (user_id, name, content, updated_at),
            )

    def read_diagnostic(self, user_id: str, name: str) -> str:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT content FROM diagnostics WHERE user_id=? AND name=?",
                (user_id, name),
            ).fetchone()
            return row["content"] if row else ""

    def read_all_diagnostics(self, user_id: str) -> dict[str, str]:
        names = ["vehicles", "route", "signal", "energy", "mileage"]
        return {name: self.read_diagnostic(user_id, name) for name in names}

    @staticmethod
    def new_id() -> str:
        return str(uuid.uuid4())

    def snapshot_from_sync(self, user_id: str) -> VehicleSnapshot | None:
        sync = self.get_sync_state(user_id)
        raw = sync.get("last_car_json") or ""
        if not raw:
            return None
        try:
            data = json.loads(raw)
        except Exception:
            return None
        return VehicleSnapshot(
            sts=int(data.get("sts") or 0),
            collect_time=int(data.get("collectTime") or 0),
            soc=data.get("soc"),
            soc_precise=bool(data.get("socPrecise")),
            odo_km=data.get("odoKm"),
            speed_kmh=data.get("speedKmh"),
            gear=data.get("gear"),
            battery_min_temp=data.get("batteryMinTemp"),
            interior_temp=data.get("interiorTemp"),
            charge_code=data.get("chargeCode"),
            vehicle_state_code=data.get("vehicleStateCode"),
            range_km=data.get("rangeKm"),
            moving=bool(data.get("moving")),
            charging=bool(data.get("charging")),
            plugged=bool(data.get("plugged")),
        )
