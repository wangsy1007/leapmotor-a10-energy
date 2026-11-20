from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import PlainTextResponse

from .auth import require_api_token
from .energy import ManualInput, build_manual_trip, export_csv, trip_to_api
from .leap_client import friendly_message
from .redactor import mask_vin, redact_secrets
from .schemas import (
    LoginRequest,
    SessionSyncRequest,
    SettingsUpdateRequest,
    SmsRequest,
    TripCreateRequest,
    TripPatchRequest,
    settings_response,
)
from .trip_engine import SocSource

router = APIRouter(prefix="/api/v1", dependencies=[Depends(require_api_token)])


def _collector(request: Request):
    return request.app.state.collector


def _db(request: Request):
    return request.app.state.db


@router.get("/status")
async def status(request: Request) -> dict[str, Any]:
    return _collector(request).status()


@router.post("/poll/now")
async def poll_now(request: Request) -> dict[str, Any]:
    collector = _collector(request)
    outcome = await collector.poll_once(include_energy=True)
    return {"ok": True, **outcome, "status": collector.status()}


@router.post("/session/sync")
async def session_sync(body: SessionSyncRequest, request: Request) -> dict[str, Any]:
    if not body.refreshToken and not body.token:
        raise HTTPException(status_code=400, detail="至少需要 token 或 refreshToken")
    return await _collector(request).sync_session(body.model_dump(exclude_none=True))


@router.delete("/session")
async def session_clear(request: Request) -> dict[str, Any]:
    return await _collector(request).clear_session()


@router.post("/leapmotor/send-sms")
async def send_sms(body: SmsRequest, request: Request) -> dict[str, Any]:
    result = await _collector(request).client.request_sms(body.phone)
    return {
        "ok": result.ok,
        "code": result.code,
        "message": friendly_message(result) if not result.ok else "验证码已发送。60 秒内不要重复获取。",
        "rateLimited": result.rate_limited,
    }


@router.post("/leapmotor/login")
async def leapmotor_login(body: LoginRequest, request: Request) -> dict[str, Any]:
    collector = _collector(request)
    result = await collector.client.login(body.phone, body.smsCode)
    if not result.ok:
        return {
            "ok": False,
            "code": result.code,
            "message": friendly_message(result),
            "rateLimited": result.rate_limited,
        }
    gate = await collector.client.ensure_session()
    if gate is not None and not gate.ok:
        return {
            "ok": False,
            "code": gate.code,
            "message": f"账号已登录，但车端交换失败：{friendly_message(gate)}",
            "rateLimited": gate.rate_limited,
        }
    collector.db.update_settings(collector_enabled=1)
    collector.wake()
    poll = await collector.poll_once(include_energy=True)
    return {"ok": True, "message": "登录成功，服务端开始采集", "poll": poll, "status": collector.status()}


@router.get("/trips")
async def list_trips(request: Request) -> dict[str, Any]:
    db = _db(request)
    settings = db.get_settings()
    capacity = float(settings.get("battery_usable_kwh") or 50.0)
    trips = [trip_to_api(t, capacity) for t in db.list_trips()]
    return {"trips": trips, "capacityKwh": capacity}


@router.post("/trips")
async def create_trip(body: TripCreateRequest, request: Request) -> dict[str, Any]:
    db = _db(request)
    try:
        soc_source = SocSource(body.socSource)
    except Exception:
        soc_source = SocSource.UNKNOWN
    built = build_manual_trip(
        ManualInput(
            id=body.id,
            start_at=body.startAt,
            end_at=body.endAt,
            distance_km=body.distanceKm,
            start_odo=body.startOdo,
            end_odo=body.endOdo,
            start_soc=body.startSoc,
            end_soc=body.endSoc,
            delta_soc=body.deltaSoc,
            start_battery=body.startBatteryTemp,
            end_battery=body.endBatteryTemp,
            start_interior=body.startInteriorTemp,
            end_interior=body.endInteriorTemp,
            soc_source=soc_source,
            note=body.note,
            tags=body.tags,
        ),
        db.new_id(),
    )
    if built.error or built.trip is None:
        raise HTTPException(status_code=400, detail=built.error or "无法保存")
    now = _collector(request).client.clock()
    db.upsert_trip(built.trip, now)
    capacity = float(db.get_settings().get("battery_usable_kwh") or 50.0)
    return {"ok": True, "trip": trip_to_api(built.trip, capacity)}


@router.patch("/trips/{trip_id}")
async def patch_trip(trip_id: str, body: TripPatchRequest, request: Request) -> dict[str, Any]:
    db = _db(request)
    trip = db.get_trip(trip_id)
    if trip is None:
        raise HTTPException(status_code=404, detail="行程不存在")
    now = _collector(request).client.clock()
    if body.note is not None or body.tags is not None:
        db.update_trip_note(trip_id, body.note if body.note is not None else trip.note, body.tags if body.tags is not None else trip.tags, now)
    # 允许对手动行程做较完整修正
    data = body.model_dump(exclude_none=True)
    if any(k not in ("note", "tags") for k in data):
        try:
            soc_source = SocSource(body.socSource) if body.socSource else trip.soc_source
        except Exception:
            soc_source = trip.soc_source
        built = build_manual_trip(
            ManualInput(
                id=trip_id,
                start_at=body.startAt or trip.start_at,
                end_at=body.endAt or trip.end_at or trip.start_at,
                distance_km=body.distanceKm if body.distanceKm is not None else trip.distance_override_km,
                start_odo=body.startOdo if body.startOdo is not None else trip.start_odo,
                end_odo=body.endOdo if body.endOdo is not None else trip.end_odo,
                start_soc=body.startSoc if body.startSoc is not None else trip.start_soc,
                end_soc=body.endSoc if body.endSoc is not None else trip.end_soc,
                delta_soc=body.deltaSoc if body.deltaSoc is not None else trip.delta_soc_override,
                start_battery=body.startBatteryTemp if body.startBatteryTemp is not None else trip.start_battery_temp,
                end_battery=body.endBatteryTemp if body.endBatteryTemp is not None else trip.end_battery_temp,
                start_interior=body.startInteriorTemp if body.startInteriorTemp is not None else trip.start_interior_temp,
                end_interior=body.endInteriorTemp if body.endInteriorTemp is not None else trip.end_interior_temp,
                soc_source=soc_source,
                note=body.note if body.note is not None else trip.note,
                tags=body.tags if body.tags is not None else trip.tags,
            ),
            trip_id,
        )
        if built.error or built.trip is None:
            raise HTTPException(status_code=400, detail=built.error or "无法更新")
        db.upsert_trip(built.trip, now)
    capacity = float(db.get_settings().get("battery_usable_kwh") or 50.0)
    updated = db.get_trip(trip_id)
    return {"ok": True, "trip": trip_to_api(updated, capacity) if updated else None}


@router.delete("/trips/{trip_id}")
async def delete_trip(trip_id: str, request: Request) -> dict[str, Any]:
    db = _db(request)
    if db.get_trip(trip_id) is None:
        raise HTTPException(status_code=404, detail="行程不存在")
    db.delete_trip(trip_id)
    return {"ok": True}


@router.post("/trips/manual-start")
async def manual_start(request: Request) -> dict[str, Any]:
    return await _collector(request).manual_start()


@router.post("/trips/manual-stop")
async def manual_stop(request: Request) -> dict[str, Any]:
    return await _collector(request).manual_stop()


@router.get("/trips/export.csv")
async def export_trips_csv(request: Request) -> Response:
    db = _db(request)
    capacity = float(db.get_settings().get("battery_usable_kwh") or 50.0)
    csv_text = export_csv(db.list_trips(), capacity)
    return PlainTextResponse(
        csv_text,
        media_type="text/csv; charset=utf-8",
        headers={"Content-Disposition": 'attachment; filename="trips.csv"'},
    )


@router.get("/stats")
async def stats(request: Request) -> dict[str, Any]:
    return _collector(request).stats()


@router.get("/daily-energy")
async def daily_energy(request: Request) -> dict[str, Any]:
    rows = _db(request).list_official_days()
    return {
        "days": [
            {
                "date": r["date"],
                "driverKwh": r["driver_kwh"],
                "acKwh": r["ac_kwh"],
                "otherKwh": r["other_kwh"],
                "mileageKm": r["mileage_km"],
                "pending": bool(r["pending"]),
                "note": r["note"],
                "syncedAt": r["synced_at"],
            }
            for r in rows
        ]
    }


@router.get("/diagnostics")
async def diagnostics_get(request: Request) -> dict[str, Any]:
    saved = {k: redact_secrets(v) for k, v in _db(request).read_all_diagnostics().items()}
    return {"saved": saved}


@router.post("/diagnostics/run")
async def diagnostics_run(request: Request) -> dict[str, Any]:
    return await _collector(request).run_diagnostics()


@router.get("/settings")
async def get_settings(request: Request) -> dict[str, Any]:
    db = _db(request)
    settings = db.get_settings()
    sess = db.get_session()
    vin = settings.get("selected_vin") or sess.get("selected_vin") or ""
    return settings_response(settings, mask_vin(str(vin)))


@router.put("/settings")
async def put_settings(body: SettingsUpdateRequest, request: Request) -> dict[str, Any]:
    db = _db(request)
    kwargs: dict[str, Any] = {}
    if body.batteryUsableKwh is not None:
        kwargs["battery_usable_kwh"] = max(5.0, min(200.0, float(body.batteryUsableKwh)))
    if body.pollDrivingSeconds is not None:
        kwargs["poll_driving_seconds"] = max(3, min(120, int(body.pollDrivingSeconds)))
    if body.pollParkedSeconds is not None:
        kwargs["poll_parked_seconds"] = max(15, min(600, int(body.pollParkedSeconds)))
    if body.parkTimeoutSeconds is not None:
        kwargs["park_timeout_seconds"] = max(30, min(600, int(body.parkTimeoutSeconds)))
    if body.syncDays is not None:
        kwargs["sync_days"] = max(1, min(14, int(body.syncDays)))
    if body.collectorEnabled is not None:
        kwargs["collector_enabled"] = 1 if body.collectorEnabled else 0
    if body.selectedVin is not None:
        kwargs["selected_vin"] = body.selectedVin.strip()
        sess = dict(db.get_session())
        sess["selected_vin"] = kwargs["selected_vin"]
        db.save_session(sess, _collector(request).client.clock())
    updated = db.update_settings(**kwargs)
    _collector(request).wake()
    vin = updated.get("selected_vin") or db.get_session().get("selected_vin") or ""
    return {"ok": True, "settings": settings_response(updated, mask_vin(str(vin)))}
