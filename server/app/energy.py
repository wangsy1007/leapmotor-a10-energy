from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum
from typing import Any
from zoneinfo import ZoneInfo

from .trip_engine import SocSource, TripRecord, TripStatus, flags_to_source

SHANGHAI = ZoneInfo("Asia/Shanghai")


def distance_km(trip: TripRecord) -> float | None:
    if trip.distance_override_km is not None:
        value = trip.distance_override_km
        return value if 0.0 <= value < 1500.0 else None
    if trip.start_odo is None or trip.end_odo is None:
        return None
    delta = trip.end_odo - trip.start_odo
    if delta < 0.0 or delta >= 1500.0:
        return None
    return delta


def delta_soc(trip: TripRecord) -> float | None:
    if trip.delta_soc_override is not None:
        return trip.delta_soc_override
    if trip.start_soc is None or trip.end_soc is None:
        return None
    return trip.start_soc - trip.end_soc


def energy_kwh(trip: TripRecord, capacity_kwh: float) -> float | None:
    if trip.energy_override_kwh is not None:
        return trip.energy_override_kwh
    delta = delta_soc(trip)
    if delta is None or capacity_kwh <= 0:
        return None
    return delta / 100.0 * capacity_kwh


def kwh_per_100km(trip: TripRecord, capacity_kwh: float) -> float | None:
    km = distance_km(trip)
    energy = energy_kwh(trip, capacity_kwh)
    if km is None or energy is None:
        return None
    if km < 0.5 or energy < 0.0:
        return None
    return energy / km * 100.0


def coldest_battery(trip: TripRecord) -> float | None:
    values = [v for v in (trip.start_battery_temp, trip.end_battery_temp) if v is not None]
    return min(values) if values else None


def band_temp(trip: TripRecord) -> float | None:
    start = trip.start_battery_temp
    end = trip.end_battery_temp
    if start is not None and end is not None:
        return (start + end) / 2.0
    return start if start is not None else end


@dataclass(frozen=True)
class TempBand:
    label: str
    min_inclusive: float | None
    max_exclusive: float | None


TEMP_BANDS = [
    TempBand("低于 -20℃", None, -20.0),
    TempBand("-20 ~ -10℃", -20.0, -10.0),
    TempBand("-10 ~ 0℃", -10.0, 0.0),
    TempBand("0 ~ 10℃", 0.0, 10.0),
    TempBand("10℃ 及以上", 10.0, None),
]


def band_of(temp: float) -> TempBand:
    for band in TEMP_BANDS:
        lower_ok = band.min_inclusive is None or temp >= band.min_inclusive
        upper_ok = band.max_exclusive is None or temp < band.max_exclusive
        if lower_ok and upper_ok:
            return band
    return TEMP_BANDS[-1]


@dataclass
class BandStat:
    label: str
    trips: int
    km: float
    kwh: float
    kwh_per_100km: float | None


@dataclass
class AggregateStat:
    trips: int
    total_km: float
    total_kwh: float
    weighted_kwh_per_100km: float | None
    bands: list[BandStat]


class DatePreset(str, Enum):
    TODAY = "TODAY"
    WEEK = "WEEK"
    MONTH = "MONTH"
    ALL = "ALL"
    D7 = "D7"
    D30 = "D30"


def range_start(preset: DatePreset, now_ms: int, zone: ZoneInfo = SHANGHAI) -> int | None:
    zdt = datetime.fromtimestamp(now_ms / 1000, tz=zone)
    if preset == DatePreset.ALL:
        return None
    if preset == DatePreset.TODAY:
        start = datetime(zdt.year, zdt.month, zdt.day, tzinfo=zone)
        return int(start.timestamp() * 1000)
    if preset == DatePreset.WEEK:
        monday = zdt.date() - timedelta(days=zdt.weekday())
        start = datetime(monday.year, monday.month, monday.day, tzinfo=zone)
        return int(start.timestamp() * 1000)
    if preset == DatePreset.MONTH:
        start = datetime(zdt.year, zdt.month, 1, tzinfo=zone)
        return int(start.timestamp() * 1000)
    if preset == DatePreset.D7:
        return now_ms - 7 * 24 * 3600 * 1000
    if preset == DatePreset.D30:
        return now_ms - 30 * 24 * 3600 * 1000
    return None


def filter_by_preset(trips: list[TripRecord], preset: DatePreset, now_ms: int) -> list[TripRecord]:
    start = range_start(preset, now_ms)
    if start is None:
        return trips
    return [t for t in trips if start <= t.start_at <= now_ms]


def aggregate(trips: list[TripRecord], capacity_kwh: float) -> AggregateStat:
    closed = [t for t in trips if t.status == TripStatus.CLOSED]
    total_km = sum(distance_km(t) or 0.0 for t in closed)
    total_kwh = 0.0
    for trip in closed:
        energy = energy_kwh(trip, capacity_kwh)
        if energy is not None and energy > 0:
            total_kwh += energy
    qualified = []
    for trip in closed:
        km = distance_km(trip)
        energy = energy_kwh(trip, capacity_kwh)
        if km is not None and energy is not None and km >= 0.5 and energy >= 0.0:
            qualified.append(trip)
    q_km = sum(distance_km(t) or 0.0 for t in qualified)
    q_kwh = sum(energy_kwh(t, capacity_kwh) or 0.0 for t in qualified)
    weighted = (q_kwh / q_km * 100.0) if q_km > 0 else None
    grouped: dict[str, list[TripRecord]] = {b.label: [] for b in TEMP_BANDS}
    grouped["温度未知"] = []
    for trip in closed:
        temp = band_temp(trip)
        key = "温度未知" if temp is None else band_of(temp).label
        grouped[key].append(trip)
    bands: list[BandStat] = []
    for label, rows in grouped.items():
        km = sum(distance_km(t) or 0.0 for t in rows)
        kwh = 0.0
        for trip in rows:
            energy = energy_kwh(trip, capacity_kwh)
            if energy is not None and energy > 0:
                kwh += energy
        q = [
            t
            for t in rows
            if (distance_km(t) or 0.0) >= 0.5 and (energy_kwh(t, capacity_kwh) or -1.0) >= 0.0
        ]
        dk = sum(distance_km(t) or 0.0 for t in q)
        de = sum(energy_kwh(t, capacity_kwh) or 0.0 for t in q)
        rate = (de / dk * 100.0) if dk > 0 else None
        bands.append(BandStat(label, len(rows), km, kwh, rate))
    return AggregateStat(len(closed), total_km, total_kwh, weighted, bands)


@dataclass
class DailyEcDecision:
    driver: float | None
    ac: float | None
    other: float | None
    pending: bool
    note: str


def day_window_seconds(date, now_epoch_sec: int, zone: ZoneInfo = SHANGHAI) -> tuple[int, int]:
    start = int(datetime(date.year, date.month, date.day, tzinfo=zone).timestamp())
    end_of_day = int(
        (datetime(date.year, date.month, date.day, tzinfo=zone) + timedelta(days=1)).timestamp()
    ) - 1
    end = min(end_of_day, now_epoch_sec)
    return start, end


def decide_ec(
    code: int | None,
    driver: float | None,
    ac: float | None,
    other: float | None,
    is_today: bool,
) -> DailyEcDecision:
    if code in (0, 200) or (code is None and driver is not None):
        return DailyEcDecision(driver or 0.0, ac or 0.0, other or 0.0, False, "")
    if code == 100:
        if is_today:
            return DailyEcDecision(None, None, None, True, "今天云端可能还没记账，先看本地分段")
        return DailyEcDecision(0.0, 0.0, 0.0, False, "当日无行驶能耗")
    if code == 2:
        return DailyEcDecision(None, None, None, True, "时间窗无效，已尽量把终点收敛到现在")
    return DailyEcDecision(None, None, None, True, f"官方能耗暂不可用（code={code}）")


def format_ms(ms: int | None, zone: ZoneInfo = SHANGHAI) -> str:
    if ms is None or ms <= 0:
        return ""
    return datetime.fromtimestamp(ms / 1000, tz=zone).strftime("%Y-%m-%d %H:%M:%S")


def day_of(ms: int, zone: ZoneInfo = SHANGHAI) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=zone).strftime("%Y-%m-%d")


def source_label(source: str) -> str:
    return {"AUTO": "自动", "MANUAL": "手动", "GAP": "补记"}.get(source, source)


def soc_label(source: str) -> str:
    return {
        "PRECISE": "精确",
        "INTEGER": "整数",
        "MIXED": "混合",
        "UNKNOWN": "未知",
    }.get(source, source)


def reason_label(reason: str | None) -> str:
    if not reason:
        return ""
    return {
        "PARK": "停车",
        "CHARGING": "充电",
        "PLUGGED": "插枪",
        "LOST": "失联",
        "MANUAL": "手动",
        "GAP": "里程补记",
    }.get(reason, reason)


def export_csv(trips: list[TripRecord], capacity_kwh: float) -> str:
    header = [
        "开始时间",
        "结束时间",
        "来源",
        "状态",
        "里程km",
        "百公里电耗",
        "耗电kWh",
        "掉电%",
        "起始SOC",
        "结束SOC",
        "电池最低温起",
        "电池最低温止",
        "车内温起",
        "车内温止",
        "SOC来源",
        "标签",
        "备注",
        "结束原因",
    ]

    def num(value: float | None, digits: int) -> str:
        if value is None:
            return ""
        return f"{value:.{digits}f}"

    def cell(value: str) -> str:
        escaped = value.replace('"', '""')
        if any(ch in escaped for ch in ",\n\""):
            return f'"{escaped}"'
        return escaped

    lines = [",".join(header)]
    for trip in sorted(trips, key=lambda t: t.start_at):
        row = [
            format_ms(trip.start_at),
            format_ms(trip.end_at),
            source_label(trip.source.value if hasattr(trip.source, "value") else str(trip.source)),
            "进行中" if trip.status == TripStatus.OPEN else "已结束",
            num(distance_km(trip), 3),
            num(kwh_per_100km(trip, capacity_kwh), 2),
            num(energy_kwh(trip, capacity_kwh), 3),
            num(delta_soc(trip), 2),
            num(trip.start_soc, 1),
            num(trip.end_soc, 1),
            num(trip.start_battery_temp, 1),
            num(trip.end_battery_temp, 1),
            num(trip.start_interior_temp, 1),
            num(trip.end_interior_temp, 1),
            soc_label(trip.soc_source.value if hasattr(trip.soc_source, "value") else str(trip.soc_source)),
            trip.tags,
            trip.note,
            reason_label(trip.close_reason),
        ]
        lines.append(",".join(cell(x) for x in row))
    return "\ufeff# usableCapacityKwh=" + str(capacity_kwh) + "\n" + "\n".join(lines)


@dataclass
class ManualInput:
    id: str | None = None
    start_at: int = 0
    end_at: int = 0
    distance_km: float | None = None
    start_odo: float | None = None
    end_odo: float | None = None
    start_soc: float | None = None
    end_soc: float | None = None
    delta_soc: float | None = None
    start_battery: float | None = None
    end_battery: float | None = None
    start_interior: float | None = None
    end_interior: float | None = None
    soc_source: SocSource = SocSource.UNKNOWN
    note: str = ""
    tags: str = ""


@dataclass
class ManualBuild:
    error: str | None = None
    trip: TripRecord | None = None


def build_manual_trip(input_: ManualInput, new_id: str) -> ManualBuild:
    if input_.end_at <= input_.start_at:
        return ManualBuild(error="结束时间要晚于开始时间")
    has_odo = input_.start_odo is not None or input_.end_odo is not None
    if has_odo and (input_.start_odo is None or input_.end_odo is None):
        return ManualBuild(error="起始里程和结束里程需要一起填")
    if input_.start_odo is not None and input_.end_odo is not None:
        if input_.start_odo < 0 or input_.end_odo < input_.start_odo:
            return ManualBuild(error="结束里程不能小于起始里程")
        if input_.end_odo - input_.start_odo >= 1500:
            return ManualBuild(error="里程差过大，请核对总里程")
    distance_override = None if input_.start_odo is not None else input_.distance_km
    if distance_override is None and input_.start_odo is None:
        return ManualBuild(error="请填写行驶里程，或填写起止总里程")
    if distance_override is not None and (distance_override < 0 or distance_override >= 1500):
        return ManualBuild(error="行驶里程需要在 0 到 1500 km 之间")
    soc_pair = input_.start_soc is not None or input_.end_soc is not None
    if soc_pair and (input_.start_soc is None or input_.end_soc is None):
        return ManualBuild(error="起始 SOC 和结束 SOC 需要一起填")
    if input_.start_soc is not None and (
        not (0.0 <= input_.start_soc <= 100.0) or not (0.0 <= (input_.end_soc or -1) <= 100.0)
    ):
        return ManualBuild(error="SOC 需要在 0 到 100 之间")
    delta_override = None if input_.start_soc is not None else input_.delta_soc
    if delta_override is None and input_.start_soc is None:
        return ManualBuild(error="请填写起止 SOC，或填写掉电百分比")
    if delta_override is not None and not (-20.0 <= delta_override <= 100.0):
        return ManualBuild(error="掉电百分比不合理")
    precise = input_.soc_source in (SocSource.PRECISE, SocSource.MIXED)
    integer = input_.soc_source in (SocSource.INTEGER, SocSource.MIXED)
    from .trip_engine import TripSource

    trip = TripRecord(
        id=input_.id or new_id,
        source=TripSource.MANUAL,
        status=TripStatus.CLOSED,
        start_at=input_.start_at,
        end_at=input_.end_at,
        start_odo=input_.start_odo,
        end_odo=input_.end_odo,
        start_soc=input_.start_soc,
        end_soc=input_.end_soc,
        start_battery_temp=input_.start_battery,
        end_battery_temp=input_.end_battery,
        start_interior_temp=input_.start_interior,
        end_interior_temp=input_.end_interior,
        soc_source=input_.soc_source,
        note=input_.note.strip(),
        tags=input_.tags.strip(),
        close_reason="MANUAL",
        sample_count=0,
        distance_override_km=distance_override,
        delta_soc_override=delta_override,
        last_new_frame_wall=input_.end_at,
        saw_precise=precise,
        saw_integer=integer,
    )
    return ManualBuild(trip=trip)


def trip_to_api(trip: TripRecord, capacity_kwh: float) -> dict[str, Any]:
    return {
        "id": trip.id,
        "source": trip.source.value if hasattr(trip.source, "value") else str(trip.source),
        "status": trip.status.value if hasattr(trip.status, "value") else str(trip.status),
        "startAt": trip.start_at,
        "endAt": trip.end_at,
        "startOdo": trip.start_odo,
        "endOdo": trip.end_odo,
        "startSoc": trip.start_soc,
        "endSoc": trip.end_soc,
        "startBatteryTemp": trip.start_battery_temp,
        "endBatteryTemp": trip.end_battery_temp,
        "startInteriorTemp": trip.start_interior_temp,
        "endInteriorTemp": trip.end_interior_temp,
        "socSource": trip.soc_source.value if hasattr(trip.soc_source, "value") else str(trip.soc_source),
        "note": trip.note,
        "tags": trip.tags,
        "closeReason": trip.close_reason,
        "sampleCount": trip.sample_count,
        "distanceOverrideKm": trip.distance_override_km,
        "deltaSocOverride": trip.delta_soc_override,
        "energyOverrideKwh": trip.energy_override_kwh,
        "parkedSince": trip.parked_since,
        "lastNewFrameWall": trip.last_new_frame_wall,
        "lastSts": trip.last_sts,
        "sawPrecise": trip.saw_precise,
        "sawInteger": trip.saw_integer,
        "distanceKm": distance_km(trip),
        "deltaSoc": delta_soc(trip),
        "energyKwh": energy_kwh(trip, capacity_kwh),
        "kwhPer100km": kwh_per_100km(trip, capacity_kwh),
    }
