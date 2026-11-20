from __future__ import annotations

import uuid
from dataclasses import dataclass, replace
from enum import Enum
from typing import Callable

from .parse import VehicleSnapshot


class TripSource(str, Enum):
    AUTO = "AUTO"
    MANUAL = "MANUAL"
    GAP = "GAP"


class TripStatus(str, Enum):
    OPEN = "OPEN"
    CLOSED = "CLOSED"


class SocSource(str, Enum):
    PRECISE = "PRECISE"
    INTEGER = "INTEGER"
    MIXED = "MIXED"
    UNKNOWN = "UNKNOWN"


class ManualCommand(str, Enum):
    NONE = "NONE"
    START = "START"
    END = "END"


def flags_to_source(precise: bool, integer: bool) -> SocSource:
    if precise and integer:
        return SocSource.MIXED
    if precise:
        return SocSource.PRECISE
    if integer:
        return SocSource.INTEGER
    return SocSource.UNKNOWN


@dataclass
class TripRecord:
    id: str
    source: TripSource
    status: TripStatus
    start_at: int
    end_at: int | None = None
    start_odo: float | None = None
    end_odo: float | None = None
    start_soc: float | None = None
    end_soc: float | None = None
    start_battery_temp: float | None = None
    end_battery_temp: float | None = None
    start_interior_temp: float | None = None
    end_interior_temp: float | None = None
    soc_source: SocSource = SocSource.UNKNOWN
    note: str = ""
    tags: str = ""
    close_reason: str | None = None
    sample_count: int = 0
    distance_override_km: float | None = None
    delta_soc_override: float | None = None
    energy_override_kwh: float | None = None
    parked_since: int | None = None
    last_new_frame_wall: int | None = None
    last_sts: int = 0
    saw_precise: bool = False
    saw_integer: bool = False


@dataclass
class Anchor:
    at: int | None = None
    sts: int = 0
    odo: float | None = None
    soc: float | None = None
    battery_temp: float | None = None
    interior_temp: float | None = None
    soc_precise: bool = False
    charging: bool = False


@dataclass
class TrackerState:
    open: TripRecord | None = None
    last_frame_sts: int = 0
    anchor: Anchor | None = None

    def __post_init__(self) -> None:
        if self.anchor is None:
            self.anchor = Anchor()


@dataclass
class EngineConfig:
    park_timeout_ms: int = 60_000
    lost_contact_ms: int = 30 * 60 * 1000
    min_keep_km: float = 0.2
    gap_fill_km: float = 1.0
    max_jump_km: float = 1500.0


class TripEffect:
    @dataclass
    class Save:
        trip: TripRecord

    @dataclass
    class Delete:
        id: str


@dataclass
class ReduceResult:
    state: TrackerState
    effects: list
    messages: list[str]


def _distance_km(trip: TripRecord) -> float | None:
    if trip.distance_override_km is not None:
        value = trip.distance_override_km
        return value if 0.0 <= value < 1500.0 else None
    if trip.start_odo is None or trip.end_odo is None:
        return None
    delta = trip.end_odo - trip.start_odo
    if delta < 0.0 or delta >= 1500.0:
        return None
    return delta


def _start_record(snap: VehicleSnapshot, trip_id: str, source: TripSource, now: int) -> TripRecord:
    precise = snap.soc is not None and snap.soc_precise
    integer = snap.soc is not None and not snap.soc_precise
    return TripRecord(
        id=trip_id,
        source=source,
        status=TripStatus.OPEN,
        start_at=now,
        start_odo=snap.odo_km,
        end_odo=snap.odo_km,
        start_soc=snap.soc,
        end_soc=snap.soc,
        start_battery_temp=snap.battery_min_temp,
        end_battery_temp=snap.battery_min_temp,
        start_interior_temp=snap.interior_temp,
        end_interior_temp=snap.interior_temp,
        soc_source=flags_to_source(precise, integer),
        sample_count=1,
        parked_since=None if snap.moving else now,
        last_new_frame_wall=now,
        last_sts=snap.sts,
        saw_precise=precise,
        saw_integer=integer,
    )


def _apply_sample(trip: TripRecord, snap: VehicleSnapshot, now: int, max_jump_km: float) -> TripRecord:
    end_odo = trip.end_odo
    if snap.odo_km is not None and end_odo is not None:
        delta = snap.odo_km - end_odo
        if 0.0 <= delta <= max_jump_km:
            end_odo = snap.odo_km
    elif snap.odo_km is not None and end_odo is None:
        end_odo = snap.odo_km
    end_soc = snap.soc if snap.soc is not None and 0.0 <= snap.soc <= 100.0 else trip.end_soc
    precise = trip.saw_precise or (snap.soc is not None and snap.soc_precise)
    integer = trip.saw_integer or (snap.soc is not None and not snap.soc_precise)
    return replace(
        trip,
        end_odo=end_odo,
        end_soc=end_soc,
        end_battery_temp=snap.battery_min_temp if snap.battery_min_temp is not None else trip.end_battery_temp,
        end_interior_temp=snap.interior_temp if snap.interior_temp is not None else trip.end_interior_temp,
        soc_source=flags_to_source(precise, integer),
        sample_count=trip.sample_count + 1,
        last_sts=snap.sts,
        last_new_frame_wall=now,
        saw_precise=precise,
        saw_integer=integer,
    )


def _gap_record(anchor: Anchor, snap: VehicleSnapshot, now: int, trip_id: str) -> TripRecord:
    saw_precise = (anchor.soc is not None and anchor.soc_precise) or (
        snap.soc is not None and snap.soc_precise
    )
    saw_integer = (anchor.soc is not None and not anchor.soc_precise) or (
        snap.soc is not None and not snap.soc_precise
    )
    return TripRecord(
        id=trip_id,
        source=TripSource.GAP,
        status=TripStatus.CLOSED,
        start_at=anchor.at or now,
        end_at=now,
        start_odo=anchor.odo,
        end_odo=snap.odo_km,
        start_soc=anchor.soc,
        end_soc=snap.soc,
        start_battery_temp=anchor.battery_temp,
        end_battery_temp=snap.battery_min_temp,
        start_interior_temp=anchor.interior_temp,
        end_interior_temp=snap.interior_temp,
        soc_source=flags_to_source(saw_precise, saw_integer),
        close_reason="GAP",
        sample_count=2,
        last_sts=snap.sts,
        last_new_frame_wall=now,
        saw_precise=saw_precise,
        saw_integer=saw_integer,
    )


def _anchor_from(trip: TripRecord, charging: bool) -> Anchor:
    return Anchor(
        at=trip.end_at or trip.start_at,
        sts=trip.last_sts,
        odo=trip.end_odo,
        soc=trip.end_soc,
        battery_temp=trip.end_battery_temp,
        interior_temp=trip.end_interior_temp,
        soc_precise=trip.saw_precise and not trip.saw_integer,
        charging=charging,
    )


def _carry(anchor: Anchor, snap: VehicleSnapshot, now: int, charging: bool) -> Anchor:
    return Anchor(
        at=now,
        sts=snap.sts,
        odo=snap.odo_km if snap.odo_km is not None else anchor.odo,
        soc=snap.soc if snap.soc is not None else anchor.soc,
        battery_temp=snap.battery_min_temp if snap.battery_min_temp is not None else anchor.battery_temp,
        interior_temp=snap.interior_temp if snap.interior_temp is not None else anchor.interior_temp,
        soc_precise=snap.soc_precise if snap.soc is not None else anchor.soc_precise,
        charging=charging,
    )


class TripEngine:
    def __init__(self, ids: Callable[[], str] | None = None) -> None:
        self._ids = ids or (lambda: str(uuid.uuid4()))

    def reduce(
        self,
        state: TrackerState,
        snap: VehicleSnapshot | None,
        now: int,
        manual: ManualCommand = ManualCommand.NONE,
        config: EngineConfig | None = None,
    ) -> ReduceResult:
        config = config or EngineConfig()
        effects: list = []
        messages: list[str] = []
        open_trip = state.open
        last_frame_sts = state.last_frame_sts
        anchor = state.anchor or Anchor()

        def finish(reason: str, keep_short: bool, end_at: int) -> None:
            nonlocal open_trip, anchor
            current = open_trip
            if current is None:
                return
            closed = replace(
                current,
                status=TripStatus.CLOSED,
                end_at=end_at,
                close_reason=reason,
                parked_since=None,
            )
            km = _distance_km(closed)
            drop = (
                not keep_short
                and closed.source != TripSource.MANUAL
                and (km is None or km < config.min_keep_km)
            )
            if drop:
                effects.append(TripEffect.Delete(closed.id))
                messages.append(f"本次不足 {config.min_keep_km} km，未保存")
            else:
                effects.append(TripEffect.Save(closed))
                messages.append(f"行程已结束（{reason}）")
            anchor = _anchor_from(closed, charging=False)
            open_trip = None

        def result() -> ReduceResult:
            return ReduceResult(
                TrackerState(open_trip, last_frame_sts, anchor),
                effects,
                messages,
            )

        if manual == ManualCommand.START:
            if open_trip is not None:
                return ReduceResult(state, [], ["已有进行中的行程"])
            if snap is None or snap.soc is None or snap.odo_km is None:
                return ReduceResult(state, [], ["没有可用车况，无法手动开始"])
            started = _start_record(snap, self._ids(), TripSource.MANUAL, now)
            effects.append(TripEffect.Save(started))
            if snap.sts > 0:
                last_frame_sts = max(last_frame_sts, snap.sts)
            return ReduceResult(
                TrackerState(
                    started,
                    last_frame_sts,
                    _carry(anchor, snap, now, charging=snap.charging or snap.plugged),
                ),
                effects,
                ["已按当前车况开始行程"],
            )

        if manual == ManualCommand.END:
            if open_trip is None:
                return ReduceResult(TrackerState(None, last_frame_sts, anchor), [], ["当前没有进行中的行程"])
            if snap is not None and snap.sts > last_frame_sts and snap.sts > 0:
                open_trip = _apply_sample(open_trip, snap, now, config.max_jump_km)
                last_frame_sts = snap.sts
            finish("MANUAL", keep_short=True, end_at=now)
            return result()

        if open_trip is not None and open_trip.source == TripSource.MANUAL:
            if snap is not None and snap.sts > last_frame_sts and snap.sts > 0:
                updated = _apply_sample(open_trip, snap, now, config.max_jump_km)
                effects.append(TripEffect.Save(updated))
                return ReduceResult(
                    TrackerState(updated, snap.sts, _carry(anchor, snap, now, charging=False)),
                    effects,
                    messages,
                )
            return result()

        new_frame = snap is not None and snap.sts > last_frame_sts and snap.sts > 0
        if not new_frame:
            if open_trip is not None:
                wall = open_trip.last_new_frame_wall or open_trip.start_at
                if now - wall >= config.lost_contact_ms:
                    finish("LOST", keep_short=False, end_at=wall)
                    return result()
                parked = open_trip.parked_since
                if parked is not None and now - parked >= config.park_timeout_ms:
                    finish("PARK", keep_short=False, end_at=parked)
            return result()

        assert snap is not None
        if snap.charging or snap.plugged:
            was_charging = anchor.charging
            if open_trip is not None:
                end_at = open_trip.last_new_frame_wall or now
                finish("CHARGING" if snap.charging else "PLUGGED", keep_short=False, end_at=end_at)
                messages.append(
                    "充电中，已切段并暂停记行程" if snap.charging else "已插枪，已结束行程并暂停记行程"
                )
            elif not was_charging:
                messages.append("充电中，暂停记行程" if snap.charging else "已插枪，暂停记行程")
            anchor = _carry(anchor, snap, now, charging=True)
            return ReduceResult(TrackerState(open_trip, snap.sts, anchor), effects, messages)

        if anchor.charging:
            anchor = _carry(anchor, snap, now, charging=False)
            if snap.moving and snap.soc is not None and snap.odo_km is not None:
                started = _start_record(snap, self._ids(), TripSource.AUTO, now)
                effects.append(TripEffect.Save(started))
                messages.append("充电结束，已开始新行程")
                return ReduceResult(TrackerState(started, snap.sts, anchor), effects, messages)
            messages.append("充电结束，已重新对齐里程，不把充电过程记成行程")
            return ReduceResult(TrackerState(None, snap.sts, anchor), effects, messages)

        if open_trip is not None and open_trip.last_sts > 0 and snap.sts - open_trip.last_sts >= config.lost_contact_ms:
            open_trip = _apply_sample(open_trip, snap, now, config.max_jump_km)
            still_moving = snap.moving
            finish("LOST", keep_short=False, end_at=now)
            if still_moving and snap.soc is not None and snap.odo_km is not None:
                started = _start_record(snap, self._ids(), TripSource.AUTO, now)
                effects.append(TripEffect.Save(started))
                messages.append("失联较久，上一段已收尾并开始新行程")
                return ReduceResult(
                    TrackerState(started, snap.sts, _carry(anchor, snap, now, charging=False)),
                    effects,
                    messages,
                )
            return ReduceResult(
                TrackerState(open_trip, snap.sts, _carry(anchor, snap, now, charging=False)),
                effects,
                messages,
            )

        if open_trip is None:
            if not snap.moving:
                previous = anchor.odo
                current = snap.odo_km
                if (
                    previous is not None
                    and current is not None
                    and anchor.at is not None
                    and anchor.soc is not None
                    and snap.soc is not None
                ):
                    delta = current - previous
                    if config.gap_fill_km <= delta < config.max_jump_km:
                        gap = _gap_record(anchor, snap, now, self._ids())
                        if (_distance_km(gap) or 0.0) >= config.min_keep_km:
                            effects.append(TripEffect.Save(gap))
                            messages.append(f"停车期间里程增加 {delta:.1f} km，已补记一段")
                return ReduceResult(
                    TrackerState(None, snap.sts, _carry(anchor, snap, now, charging=False)),
                    effects,
                    messages,
                )
            if snap.soc is None or snap.odo_km is None:
                messages.append("正在行驶，但这一帧缺少 SOC 或总里程")
                return ReduceResult(TrackerState(None, snap.sts, anchor), effects, messages)
            started = _start_record(snap, self._ids(), TripSource.AUTO, now)
            effects.append(TripEffect.Save(started))
            messages.append("检测到行驶，已开始行程")
            return ReduceResult(
                TrackerState(started, snap.sts, _carry(anchor, snap, now, charging=False)),
                effects,
                messages,
            )

        updated = _apply_sample(open_trip, snap, now, config.max_jump_km)
        if snap.moving:
            updated = replace(updated, parked_since=None)
        elif updated.parked_since is None:
            updated = replace(updated, parked_since=now)
        if updated.parked_since is not None and now - updated.parked_since >= config.park_timeout_ms:
            open_trip = updated
            finish("PARK", keep_short=False, end_at=updated.parked_since or now)
            return ReduceResult(
                TrackerState(open_trip, snap.sts, _carry(anchor, snap, now, charging=False)),
                effects,
                messages,
            )
        effects.append(TripEffect.Save(updated))
        return ReduceResult(
            TrackerState(updated, snap.sts, _carry(anchor, snap, now, charging=False)),
            effects,
            messages,
        )
