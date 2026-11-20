from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class SessionSyncRequest(BaseModel):
    phone: str | None = None
    deviceId: str | None = None
    accountId: str | None = None
    nickname: str | None = None
    token: str | None = None
    refreshToken: str | None = None
    tokenExpiresAtMs: int | None = None
    tokenExpiresAtSec: int | None = None
    selectedVin: str | None = None
    carType: str | None = None
    appRegion: str | None = None
    appCenter: str | None = None
    carToken: str | None = None
    carRefreshToken: str | None = None
    carTokenExpiresAtMs: int | None = None
    signR2: str | None = None
    signR3: str | None = None
    hkdfKeyHex: str | None = None
    userId: str | None = None


class SmsRequest(BaseModel):
    phone: str = Field(min_length=11, max_length=11)


class LoginRequest(BaseModel):
    phone: str = Field(min_length=11, max_length=11)
    smsCode: str = Field(min_length=4, max_length=8)


class TripCreateRequest(BaseModel):
    startAt: int
    endAt: int
    distanceKm: float | None = None
    startOdo: float | None = None
    endOdo: float | None = None
    startSoc: float | None = None
    endSoc: float | None = None
    deltaSoc: float | None = None
    startBatteryTemp: float | None = None
    endBatteryTemp: float | None = None
    startInteriorTemp: float | None = None
    endInteriorTemp: float | None = None
    socSource: str = "UNKNOWN"
    note: str = ""
    tags: str = ""
    id: str | None = None


class TripPatchRequest(BaseModel):
    note: str | None = None
    tags: str | None = None
    startAt: int | None = None
    endAt: int | None = None
    distanceKm: float | None = None
    startOdo: float | None = None
    endOdo: float | None = None
    startSoc: float | None = None
    endSoc: float | None = None
    deltaSoc: float | None = None
    startBatteryTemp: float | None = None
    endBatteryTemp: float | None = None
    startInteriorTemp: float | None = None
    endInteriorTemp: float | None = None
    socSource: str | None = None


class SettingsUpdateRequest(BaseModel):
    batteryUsableKwh: float | None = None
    pollDrivingSeconds: int | None = None
    pollParkedSeconds: int | None = None
    parkTimeoutSeconds: int | None = None
    syncDays: int | None = None
    collectorEnabled: bool | None = None
    selectedVin: str | None = None


def settings_response(raw: dict[str, Any], vin_masked: str = "") -> dict[str, Any]:
    return {
        "batteryUsableKwh": float(raw.get("battery_usable_kwh") or 50.0),
        "pollDrivingSeconds": int(raw.get("poll_driving_seconds") or 6),
        "pollParkedSeconds": int(raw.get("poll_parked_seconds") or 60),
        "parkTimeoutSeconds": int(raw.get("park_timeout_seconds") or 60),
        "syncDays": int(raw.get("sync_days") or 7),
        "collectorEnabled": bool(int(raw.get("collector_enabled") or 0)),
        "selectedVinMasked": vin_masked,
    }
