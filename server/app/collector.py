from __future__ import annotations

import asyncio
import json
import logging
import time
from datetime import datetime
from typing import Any

from .db import Database
from .energy import DatePreset, aggregate, day_window_seconds, decide_ec, filter_by_preset, trip_to_api
from .energy import SHANGHAI
from .leap_client import GATEWAY_BASE, LeapClient, friendly_message, signal_attempts
from .parse import (
    parse_car_route,
    parse_lastweek_ec,
    parse_mileage,
    parse_signal_response,
    parse_vehicles,
    validate_app_region,
)
from .redactor import mask_phone, mask_token, mask_vin, redact_secrets
from .risk import BackoffState, backoff_message, register_rate_limit
from .trip_engine import EngineConfig, ManualCommand, TripEngine

logger = logging.getLogger(__name__)


class UserWorker:
    """单个 user_id 的采集与行程状态机。"""

    def __init__(self, db: Database, user_id: str, shared_http=None) -> None:
        self.db = db
        self.user_id = user_id
        self.client = LeapClient(db, user_id, client=shared_http, owns_client=False)
        self.engine = TripEngine()
        self._lock = asyncio.Lock()
        self.last_poll_at = 0

    async def poll_once(self, include_energy: bool = True) -> dict[str, Any]:
        async with self._lock:
            result = await self._poll_locked()
            self.last_poll_at = int(time.time() * 1000)
            if include_energy and not result.get("rateLimited"):
                try:
                    await self.sync_official_days()
                except Exception as exc:
                    logger.warning("energy sync failed: %s", exc)
            return result

    async def _poll_locked(self) -> dict[str, Any]:
        now = self.client.clock()
        sync, tracker = self.db.load_tracker(self.user_id)
        settings = self.db.get_settings(self.user_id)
        sess = self.db.get_session(self.user_id)
        blocked = backoff_message(
            BackoffState(int(sync.get("backoff_level") or 0), int(sync.get("cooldown_until_ms") or 0)),
            now,
        )
        if blocked:
            sync = dict(sync)
            sync["status_message"] = blocked
            sync["last_poll_at"] = now
            self.db.save_sync_state(self.user_id, sync)
            return {
                "message": blocked,
                "moving": False,
                "waitMs": max(5_000, int(sync.get("cooldown_until_ms") or 0) - now),
                "rateLimited": True,
            }

        vin = settings.get("selected_vin") or sess.get("selected_vin") or ""
        car_type = sess.get("car_type") or ""
        self.client.hint_vin = vin
        self.client.hint_car_type = car_type

        if not vin:
            listed = await self._load_vehicles(sync)
            sync = listed["sync"]
            if listed.get("outcome"):
                return listed["outcome"]
            vin = settings.get("selected_vin") or self.db.get_session(self.user_id).get("selected_vin") or ""
            if not vin:
                message = "账号下没有车辆。请确认子账号已经接受车辆共享。"
                sync = dict(sync)
                sync["last_error"] = message
                sync["status_message"] = message
                sync["last_poll_at"] = now
                self.db.save_sync_state(self.user_id, sync)
                return {"message": message, "moving": False}

        self.client.hint_vin = vin
        self.client.hint_car_type = self.db.get_session(self.user_id).get("car_type") or car_type
        sess = self.db.get_session(self.user_id)
        region = sess.get("app_region") or ""
        if not region or validate_app_region(region) is not None:
            sync = await self._refresh_route(sync, vin)

        fetched = await self._fetch_signal(sync, vin)
        sync = fetched["sync"]
        http = fetched["http"]
        strategy = fetched["strategy"]
        sync = dict(sync)
        sync["last_poll_at"] = now
        if http is None:
            message = sync.get("status_message") or "没有拿到车况"
            self.db.save_sync_state(self.user_id, sync)
            return {"message": message, "moving": False}
        if http.rate_limited:
            await self._note_rate_limit(sync, now)
            latest = self.db.get_sync_state(self.user_id)
            message = backoff_message(
                BackoffState(int(latest.get("backoff_level") or 0), int(latest.get("cooldown_until_ms") or 0)),
                self.client.clock(),
            ) or friendly_message(http)
            return {
                "message": message,
                "moving": False,
                "waitMs": max(5_000, int(latest.get("cooldown_until_ms") or 0) - self.client.clock()),
                "rateLimited": True,
            }
        self.db.write_diagnostic(self.user_id, "signal", redact_secrets(http.raw or http.message), now)
        if not http.ok and http.code not in (0, None):
            message = friendly_message(http)
            sync["last_error"] = message
            sync["status_message"] = message
            self.db.save_sync_state(self.user_id, sync)
            return {"message": message, "moving": False}

        parsed = parse_signal_response(http.raw or "{}")
        snap = parsed.snapshot
        if snap is None:
            message = parsed.message or "车况里没有 signalMap。可到诊断页查看原始 JSON，并确认 appRegion。"
            sync["last_error"] = message
            sync["status_message"] = message
            sync["signal_strategy"] = ""
            self.db.save_sync_state(self.user_id, sync)
            return {"message": message, "moving": False}

        config = EngineConfig(park_timeout_ms=int(settings.get("park_timeout_seconds") or 60) * 1000)
        reduced = self.engine.reduce(tracker, snap, now, ManualCommand.NONE, config)
        stale = ""
        if snap.sts > 0 and now - snap.sts > 10 * 60 * 1000:
            stale = "车端 sts 已超过 10 分钟，车辆可能在休眠，这不是新的行驶帧。"
        message = " ".join([m for m in (reduced.messages + [stale]) if m]).strip() or f"车况已更新"
        sync["last_error"] = ""
        sync["last_success_at"] = now
        sync["last_car_json"] = json.dumps(snap.to_dict(), ensure_ascii=False)
        sync["status_message"] = message
        sync["signal_strategy"] = strategy
        self.db.apply_tracker(self.user_id, sync, reduced.state, now, reduced.effects)
        return {"message": message, "moving": snap.moving, "snapshot": snap.to_dict()}

    async def manual_start(self) -> dict[str, Any]:
        async with self._lock:
            return await self._manual(ManualCommand.START)

    async def manual_stop(self) -> dict[str, Any]:
        async with self._lock:
            return await self._manual(ManualCommand.END)

    async def _manual(self, command: ManualCommand) -> dict[str, Any]:
        now = self.client.clock()
        sync, tracker = self.db.load_tracker(self.user_id)
        snap = self.db.snapshot_from_sync(self.user_id)
        if snap is None:
            return {"message": "没有车况，请先刷新"}
        settings = self.db.get_settings(self.user_id)
        config = EngineConfig(park_timeout_ms=int(settings.get("park_timeout_seconds") or 60) * 1000)
        result = self.engine.reduce(tracker, snap, now, command, config)
        sync = dict(sync)
        sync["status_message"] = " ".join(result.messages)
        self.db.apply_tracker(self.user_id, sync, result.state, now, result.effects)
        return {"message": sync["status_message"] or "已处理", "openTrip": result.state.open is not None}

    async def sync_official_days(self) -> str:
        now = self.client.clock()
        sync, _ = self.db.load_tracker(self.user_id)
        blocked = backoff_message(
            BackoffState(int(sync.get("backoff_level") or 0), int(sync.get("cooldown_until_ms") or 0)),
            now,
        )
        if blocked:
            return blocked
        sess = self.db.get_session(self.user_id)
        settings = self.db.get_settings(self.user_id)
        vin = settings.get("selected_vin") or sess.get("selected_vin") or ""
        if not vin:
            return "还没有车辆，请先同步会话或刷新"
        self.client.hint_vin = vin
        self.client.hint_car_type = sess.get("car_type") or ""
        zone_now = datetime.fromtimestamp(now / 1000, tz=SHANGHAI).date()
        sync_days = int(settings.get("sync_days") or 7)
        begin_day = zone_now.fromordinal(zone_now.toordinal() - (sync_days - 1))
        begin_ms = int(datetime(begin_day.year, begin_day.month, begin_day.day, tzinfo=SHANGHAI).timestamp() * 1000)
        mileage = await self.client.mileage_detail(vin, begin_ms, now)
        self.db.write_diagnostic(self.user_id, "mileage", redact_secrets(mileage.raw or mileage.message), now)
        if mileage.rate_limited:
            await self._note_rate_limit(sync, now)
            return friendly_message(mileage)
        mileage_days = {}
        if mileage.ok:
            mileage_days = {d.day: d.km for d in parse_mileage(mileage.raw).days}
        done = 0
        stopped = ""
        for offset in range(sync_days):
            date = begin_day.fromordinal(begin_day.toordinal() + offset)
            begin_sec, end_sec = day_window_seconds(date, now // 1000)
            if end_sec < begin_sec:
                continue
            ec = await self.client.get_lastweek_ec(vin, begin_sec, end_sec)
            self.db.write_diagnostic(self.user_id, "energy", redact_secrets(ec.raw or ec.message), now)
            if ec.rate_limited:
                await self._note_rate_limit(dict(self.db.get_sync_state(self.user_id)), self.client.clock())
                stopped = friendly_message(ec)
                break
            parsed = parse_lastweek_ec(ec.raw or f'{{"code":{ec.code or -1},"message":"{ec.message}"}}')
            decision = decide_ec(parsed.code if parsed.code is not None else ec.code, parsed.driver, parsed.ac, parsed.other, date == zone_now)
            day_key = date.isoformat()
            self.db.save_official_day(
                self.user_id,
                {
                    "date": day_key,
                    "driver_kwh": decision.driver,
                    "ac_kwh": decision.ac,
                    "other_kwh": decision.other,
                    "mileage_km": mileage_days.get(day_key),
                    "pending": decision.pending,
                    "note": decision.note,
                    "raw_ec": (ec.raw or "")[:4000],
                    "synced_at": self.client.clock(),
                }
            )
            done += 1
        sync = dict(self.db.get_sync_state(self.user_id))
        sync["last_ec_sync_at"] = self.client.clock()
        self.db.save_sync_state(self.user_id, sync)
        if stopped:
            return f"已同步 {done} 天，随后触发风控。{stopped}"
        return f"已同步近 {sync_days} 天官方能耗"

    async def sync_session(self, payload: dict[str, Any]) -> dict[str, Any]:
        now = self.client.clock()
        current = dict(self.db.get_session(self.user_id))
        mapping = {
            "phone": "phone",
            "deviceId": "device_id",
            "accountId": "account_id",
            "nickname": "nickname",
            "token": "token",
            "refreshToken": "refresh_token",
            "tokenExpiresAtMs": "token_expires_at_ms",
            "selectedVin": "selected_vin",
            "carType": "car_type",
            "appRegion": "app_region",
            "appCenter": "app_center",
            "carToken": "car_token",
            "carRefreshToken": "car_refresh_token",
            "carTokenExpiresAtMs": "car_token_expires_at_ms",
            "signR2": "sign_r2",
            "signR3": "sign_r3",
            "hkdfKeyHex": "hkdf_key_hex",
            "userId": "leap_user_id",
        }
        for src, dst in mapping.items():
            if src in payload and payload[src] is not None:
                current[dst] = payload[src]
        # 兼容账号过期秒数
        if "tokenExpiresAtSec" in payload and payload["tokenExpiresAtSec"]:
            current["token_expires_at_ms"] = int(payload["tokenExpiresAtSec"]) * 1000
        if current.get("sign_r2") and current.get("sign_r3") and current.get("car_token") and not current.get("hkdf_key_hex"):
            try:
                from . import crypto

                current["hkdf_key_hex"] = crypto.derive_hkdf_hex(
                    str(current["car_token"]), str(current["sign_r2"]), str(current["sign_r3"])
                )
            except Exception:
                pass
        if not current.get("device_id"):
            from . import crypto

            current["device_id"] = crypto.new_device_id()
        self.db.save_session(self.user_id, current, now)
        if current.get("selected_vin"):
            self.db.update_settings(self.user_id, selected_vin=current["selected_vin"], collector_enabled=1)
        # 立刻拉一次
        poll = await self.poll_once(include_energy=True)
        return {"ok": True, "message": "会话已同步，服务端开始 24 小时采集", "poll": poll}

    async def clear_session(self) -> dict[str, Any]:
        self.db.clear_session(self.user_id)
        self.db.update_settings(self.user_id, collector_enabled=0, selected_vin="")
        sync = dict(self.db.get_sync_state(self.user_id))
        sync["status_message"] = "会话已清空，采集已暂停"
        self.db.save_sync_state(self.user_id, sync)
        return {"ok": True, "message": sync["status_message"]}

    def status(self) -> dict[str, Any]:
        now = self.client.clock()
        settings = self.db.get_settings(self.user_id)
        sess = self.db.get_session(self.user_id)
        sync = self.db.get_sync_state(self.user_id)
        snap = self.db.snapshot_from_sync(self.user_id)
        open_trip = self.db.get_open_trip(self.user_id)
        capacity = float(settings.get("battery_usable_kwh") or 50.0)
        cooldown = int(sync.get("cooldown_until_ms") or 0)
        rate_limited = cooldown > now
        trip_count = self.db.count_trips(self.user_id)
        has_session = bool(sess.get("refresh_token"))
        return {
            "version": "1.2.0",
            "userId": self.user_id,
            "binding": {
                "userId": self.user_id,
                "hasLeapSession": has_session,
                "tripCount": trip_count,
                "selectedVinMasked": mask_vin(str(sess.get("selected_vin") or settings.get("selected_vin") or "")),
            },
            "collector": {
                "enabled": bool(int(settings.get("collector_enabled") or 0)),
                "running": bool(int(settings.get("collector_enabled") or 0)) and has_session,
                "hasSession": has_session,
                "rateLimited": rate_limited,
                "backoffLevel": int(sync.get("backoff_level") or 0),
                "cooldownUntilMs": cooldown,
                "cooldownRemainingMs": max(0, cooldown - now) if rate_limited else 0,
                "rateLimitMessage": backoff_message(
                    BackoffState(int(sync.get("backoff_level") or 0), cooldown), now
                ),
                "lastPollAt": int(sync.get("last_poll_at") or 0),
                "lastSuccessAt": int(sync.get("last_success_at") or 0),
                "lastFrameSts": int(sync.get("last_frame_sts") or 0),
                "statusMessage": sync.get("status_message") or "",
                "lastError": sync.get("last_error") or "",
                "signalStrategy": sync.get("signal_strategy") or "",
                "lastLoopAt": self.last_poll_at,
            },
            "session": {
                "phoneMasked": mask_phone(str(sess.get("phone") or "")),
                "nickname": sess.get("nickname") or "",
                "accountIdMasked": mask_token(str(sess.get("account_id") or "")),
                "deviceId": sess.get("device_id") or "",
                "selectedVinMasked": mask_vin(str(sess.get("selected_vin") or settings.get("selected_vin") or "")),
                "carType": sess.get("car_type") or "",
                "appRegion": sess.get("app_region") or "",
                "tokenExpiresAtMs": int(sess.get("token_expires_at_ms") or 0),
                "carTokenExpiresAtMs": int(sess.get("car_token_expires_at_ms") or 0),
                "hasHkdf": bool(sess.get("hkdf_key_hex")),
            },
            "vehicle": snap.to_dict() if snap else None,
            "openTrip": trip_to_api(open_trip, capacity) if open_trip else None,
            "settings": {
                "batteryUsableKwh": capacity,
                "pollDrivingSeconds": int(settings.get("poll_driving_seconds") or 6),
                "pollParkedSeconds": int(settings.get("poll_parked_seconds") or 60),
                "parkTimeoutSeconds": int(settings.get("park_timeout_seconds") or 60),
                "syncDays": int(settings.get("sync_days") or 7),
                "collectorEnabled": bool(int(settings.get("collector_enabled") or 0)),
                "selectedVinMasked": mask_vin(str(settings.get("selected_vin") or sess.get("selected_vin") or "")),
            },
        }

    def stats(self) -> dict[str, Any]:
        settings = self.db.get_settings(self.user_id)
        capacity = float(settings.get("battery_usable_kwh") or 50.0)
        trips = self.db.list_trips(self.user_id)
        now = self.client.clock()
        out = {}
        for preset in (DatePreset.TODAY, DatePreset.WEEK, DatePreset.MONTH, DatePreset.ALL):
            filtered = filter_by_preset(trips, preset, now)
            stat = aggregate(filtered, capacity)
            out[preset.value.lower()] = {
                "trips": stat.trips,
                "totalKm": stat.total_km,
                "totalKwh": stat.total_kwh,
                "weightedKwhPer100km": stat.weighted_kwh_per_100km,
                "bands": [
                    {
                        "label": b.label,
                        "trips": b.trips,
                        "km": b.km,
                        "kwh": b.kwh,
                        "kwhPer100km": b.kwh_per_100km,
                    }
                    for b in stat.bands
                ],
            }
        return out

    async def run_diagnostics(self) -> dict[str, Any]:
        now = self.client.clock()
        sess = self.db.get_session(self.user_id)
        settings = self.db.get_settings(self.user_id)
        vin = settings.get("selected_vin") or sess.get("selected_vin") or ""
        result: dict[str, Any] = {"carType": sess.get("car_type") or "", "selectedVinMasked": mask_vin(vin)}
        vehicles = await self.client.vehicle_list()
        self.db.write_diagnostic(self.user_id, "vehicles", redact_secrets(vehicles.raw or vehicles.message), now)
        result["vehicles"] = redact_secrets(vehicles.raw or vehicles.message)
        if vehicles.ok:
            parsed = parse_vehicles(vehicles.raw)
            result["vehicleSummaries"] = [
                {
                    "carType": v.car_type,
                    "nickName": v.nick_name,
                    "source": v.source,
                    "vinMasked": mask_vin(v.vin),
                }
                for v in parsed
            ]
            if not vin and parsed:
                vin = parsed[0].vin
                self.db.update_settings(self.user_id, selected_vin=vin)
                current = dict(sess)
                current["selected_vin"] = vin
                current["car_type"] = parsed[0].car_type
                self.db.save_session(self.user_id, current, now)
                result["carType"] = parsed[0].car_type
        if vin:
            self.client.hint_vin = vin
            self.client.hint_car_type = self.db.get_session(self.user_id).get("car_type") or ""
            route = await self.client.car_route(vin)
            self.db.write_diagnostic(self.user_id, "route", redact_secrets(route.raw or route.message), now)
            result["route"] = redact_secrets(route.raw or route.message)
            info = parse_car_route(route.raw) if route.raw else None
            if info and info.region_ok:
                current = dict(self.db.get_session(self.user_id))
                current["app_region"] = info.app_region
                current["app_center"] = info.app_center
                self.db.save_session(self.user_id, current, now)
                result["appRegion"] = info.app_region
            sync = self.db.get_sync_state(self.user_id)
            fetched = await self._fetch_signal(sync, vin)
            http = fetched["http"]
            signal_raw = redact_secrets((http.raw if http else "") or (http.message if http else "没有响应"))
            self.db.write_diagnostic(self.user_id, "signal", signal_raw, now)
            result["signalMap"] = signal_raw
            begin_sec, end_sec = day_window_seconds(
                datetime.fromtimestamp(now / 1000, tz=SHANGHAI).date(), now // 1000
            )
            ec = await self.client.get_lastweek_ec(vin, begin_sec, end_sec)
            energy_raw = redact_secrets(ec.raw or ec.message)
            self.db.write_diagnostic(self.user_id, "energy", energy_raw, now)
            result["getLastweekEC"] = energy_raw
        result["saved"] = {k: redact_secrets(v) for k, v in self.db.read_all_diagnostics(self.user_id).items()}
        return result

    async def _load_vehicles(self, sync: dict[str, Any]) -> dict[str, Any]:
        result = await self.client.vehicle_list()
        now = self.client.clock()
        self.db.write_diagnostic(self.user_id, "vehicles", redact_secrets(result.raw or result.message), now)
        if result.rate_limited:
            await self._note_rate_limit(sync, now)
            message = friendly_message(result)
            return {
                "sync": sync,
                "outcome": {"message": message, "moving": False, "rateLimited": True, "waitMs": 15 * 60 * 1000},
            }
        if not result.ok:
            message = friendly_message(result)
            sync = dict(sync)
            sync["last_error"] = message
            sync["status_message"] = message
            self.db.save_sync_state(self.user_id, sync)
            return {"sync": sync, "outcome": {"message": message, "moving": False}}
        vehicles = parse_vehicles(result.raw)
        chosen = next((v for v in vehicles if v.source == "shared"), None) or (vehicles[0] if vehicles else None)
        if chosen is None:
            return {"sync": sync, "outcome": None}
        sess = dict(self.db.get_session(self.user_id))
        sess["selected_vin"] = chosen.vin
        sess["car_type"] = chosen.car_type
        self.db.save_session(self.user_id, sess, now)
        self.db.update_settings(self.user_id, selected_vin=chosen.vin)
        sync = dict(sync)
        sync["nick_name"] = chosen.nick_name
        sync["plate"] = chosen.plate_number
        self.db.save_sync_state(self.user_id, sync)
        return {"sync": sync, "outcome": None}

    async def _refresh_route(self, sync: dict[str, Any], vin: str) -> dict[str, Any]:
        result = await self.client.car_route(vin)
        now = self.client.clock()
        self.db.write_diagnostic(self.user_id, "route", redact_secrets(result.raw or result.message), now)
        if result.rate_limited:
            return sync
        if not result.raw:
            return sync
        route = parse_car_route(result.raw)
        region = route.app_region if route.region_ok else ""
        sess = dict(self.db.get_session(self.user_id))
        sess["app_region"] = region
        sess["app_center"] = route.app_center
        self.db.save_session(self.user_id, sess, now)
        return sync

    async def _fetch_signal(self, sync: dict[str, Any], vin: str) -> dict[str, Any]:
        sess = self.db.get_session(self.user_id)
        region = sess.get("app_region") or ""
        if validate_app_region(region) is not None:
            region = ""
        attempts = signal_attempts(region, sync.get("signal_strategy") or "", GATEWAY_BASE)
        last = None
        last_strategy = sync.get("signal_strategy") or ""
        for name, base, as_json in attempts:
            result = await self.client.query_signal(vin, base, as_json)
            last = result
            last_strategy = name
            if result.rate_limited:
                return {"sync": sync, "http": result, "strategy": name}
            parsed = parse_signal_response(result.raw) if result.ok and result.raw else None
            if parsed and parsed.snapshot and (
                parsed.snapshot.soc is not None or parsed.snapshot.odo_km is not None or parsed.keys
            ):
                return {"sync": sync, "http": result, "strategy": name}
            if result.signature_error or result.auth_error:
                continue
        return {"sync": sync, "http": last, "strategy": last_strategy}

    async def _note_rate_limit(self, sync: dict[str, Any], now: int, minimum_ms: int = 15 * 60 * 1000) -> None:
        next_state = register_rate_limit(
            BackoffState(int(sync.get("backoff_level") or 0), int(sync.get("cooldown_until_ms") or 0)),
            now,
            minimum_ms,
        )
        message = backoff_message(next_state, now) or ""
        sync = dict(sync)
        sync["backoff_level"] = next_state.level
        sync["cooldown_until_ms"] = next_state.cooldown_until_ms
        sync["last_error"] = message
        sync["status_message"] = message
        self.db.save_sync_state(self.user_id, sync)


class CollectorService:
    """多 user_id 调度：每个启用采集且有有效会话的用户独立轮询。"""

    def __init__(self, db: Database) -> None:
        self.db = db
        self.version = "1.2.0"
        self.running = False
        self.last_loop_at = 0
        self._task: asyncio.Task | None = None
        self._wake = asyncio.Event()
        self._workers: dict[str, UserWorker] = {}
        self._shared_http = None

    def _http(self):
        if self._shared_http is None:
            import httpx
            from .leap_client import ipv4_transport
            self._shared_http = httpx.AsyncClient(
                transport=ipv4_transport(),
                timeout=httpx.Timeout(30.0, connect=20.0),
                follow_redirects=True,
            )
        return self._shared_http

    def worker(self, user_id: str) -> UserWorker:
        self.db.ensure_user(user_id)
        w = self._workers.get(user_id)
        if w is None:
            w = UserWorker(self.db, user_id, shared_http=self._http())
            self._workers[user_id] = w
        return w

    async def start(self) -> None:
        if self._task and not self._task.done():
            return
        self.running = True
        self._task = asyncio.create_task(self._loop(), name="collector-loop")

    async def stop(self) -> None:
        self.running = False
        self._wake.set()
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._shared_http is not None:
            await self._shared_http.aclose()
            self._shared_http = None
        self._workers.clear()

    def wake(self, user_id: str | None = None) -> None:
        self._wake.set()

    async def _loop(self) -> None:
        while self.running:
            wait_ms = 5_000
            try:
                user_ids = self.db.list_collectible_user_ids()
                if not user_ids:
                    wait_ms = 5_000
                else:
                    waits = []
                    for uid in user_ids:
                        w = self.worker(uid)
                        try:
                            outcome = await w.poll_once(include_energy=False)
                        except Exception as exc:
                            logger.exception("poll failed for %s: %s", uid, exc)
                            continue
                        moving = bool(outcome.get("moving"))
                        settings = self.db.get_settings(uid)
                        custom = outcome.get("waitMs")
                        base = int(
                            (settings.get("poll_driving_seconds") if moving else settings.get("poll_parked_seconds"))
                            or (6 if moving else 60)
                        ) * 1000
                        waits.append(int(custom if custom is not None else base))
                        sync = self.db.get_sync_state(uid)
                        now = int(time.time() * 1000)
                        if (not moving) and now - int(sync.get("last_ec_sync_at") or 0) >= 600_000:
                            try:
                                await w.sync_official_days()
                            except Exception as exc:
                                logger.warning("energy sync %s: %s", uid, exc)
                    wait_ms = max(3_000, min(min(waits) if waits else 5_000, 6 * 3_600_000))
            except Exception as exc:
                logger.exception("collector loop error: %s", exc)
                wait_ms = 15_000
            self.last_loop_at = int(time.time() * 1000)
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=wait_ms / 1000)
            except asyncio.TimeoutError:
                pass

    # 代理到具体用户
    async def poll_once(self, user_id: str, include_energy: bool = True) -> dict[str, Any]:
        return await self.worker(user_id).poll_once(include_energy=include_energy)

    async def manual_start(self, user_id: str) -> dict[str, Any]:
        return await self.worker(user_id).manual_start()

    async def manual_stop(self, user_id: str) -> dict[str, Any]:
        return await self.worker(user_id).manual_stop()

    async def sync_official_days(self, user_id: str) -> str:
        return await self.worker(user_id).sync_official_days()

    async def sync_session(self, user_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        result = await self.worker(user_id).sync_session(payload)
        self.wake(user_id)
        return result

    async def clear_session(self, user_id: str) -> dict[str, Any]:
        return await self.worker(user_id).clear_session()

    def status(self, user_id: str) -> dict[str, Any]:
        return self.worker(user_id).status()

    def stats(self, user_id: str) -> dict[str, Any]:
        return self.worker(user_id).stats()

    async def run_diagnostics(self, user_id: str) -> dict[str, Any]:
        return await self.worker(user_id).run_diagnostics()
