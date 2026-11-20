from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse


def normalize_epoch_ms(value: int | float | str | None) -> int:
    try:
        raw = int(float(value))  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0
    if raw <= 0:
        return 0
    if raw < 10_000_000_000:
        return raw * 1000
    return raw


def is_moving(gear: int | None, speed: float | None) -> bool:
    if gear in (1, 3):
        return True
    if speed is not None and speed > 1.0:
        return True
    return False


def _as_number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        text = value.strip()
        if not text or text.lower() == "null":
            return None
        try:
            return float(text)
        except ValueError:
            return None
    return None


def _as_int(value: Any) -> int | None:
    num = _as_number(value)
    return int(num) if num is not None else None


def _first_number(mapping: dict[str, Any], *keys: str) -> float | None:
    for key in keys:
        if key in mapping:
            num = _as_number(mapping.get(key))
            if num is not None:
                return num
    return None


def _first_int(mapping: dict[str, Any], *keys: str) -> int | None:
    for key in keys:
        if key in mapping:
            num = _as_int(mapping.get(key))
            if num is not None:
                return num
    return None


def _first_long(mapping: dict[str, Any], *keys: str) -> int | None:
    num = _first_number(mapping, *keys)
    return int(num) if num is not None else None


@dataclass
class VehicleSnapshot:
    sts: int = 0
    collect_time: int = 0
    soc: float | None = None
    soc_precise: bool = False
    odo_km: float | None = None
    speed_kmh: float | None = None
    gear: int | None = None
    battery_min_temp: float | None = None
    interior_temp: float | None = None
    charge_code: int | None = None
    vehicle_state_code: int | None = None
    range_km: float | None = None
    moving: bool = False
    charging: bool = False
    plugged: bool = False

    def status_label(self) -> str:
        if self.charging:
            return "充电中"
        if self.plugged:
            return "已插枪"
        if self.moving:
            return "行驶中"
        if self.gear in (0, 2):
            return "驻车"
        return "静止或未知"

    def to_dict(self) -> dict[str, Any]:
        return {
            "sts": self.sts,
            "collectTime": self.collect_time,
            "soc": self.soc,
            "socPrecise": self.soc_precise,
            "odoKm": self.odo_km,
            "speedKmh": self.speed_kmh,
            "gear": self.gear,
            "batteryMinTemp": self.battery_min_temp,
            "interiorTemp": self.interior_temp,
            "chargeCode": self.charge_code,
            "vehicleStateCode": self.vehicle_state_code,
            "rangeKm": self.range_km,
            "moving": self.moving,
            "charging": self.charging,
            "plugged": self.plugged,
            "statusLabel": self.status_label(),
        }


@dataclass
class SignalParse:
    code: int | None
    message: str
    snapshot: VehicleSnapshot | None
    keys: set[str] = field(default_factory=set)


@dataclass
class VehicleSummary:
    vin: str
    car_id: str = ""
    car_type: str = ""
    nick_name: str = ""
    plate_number: str = ""
    series_name: str = ""
    source: str = ""
    right_list: str = ""

    def display_name(self) -> str:
        return self.nick_name or self.series_name or self.car_type or self.vin

    def to_dict(self) -> dict[str, Any]:
        return {
            "vin": self.vin,
            "carId": self.car_id,
            "carType": self.car_type,
            "nickName": self.nick_name,
            "plateNumber": self.plate_number,
            "seriesName": self.series_name,
            "source": self.source,
            "rightList": self.right_list,
            "displayName": self.display_name(),
        }


@dataclass
class RouteInfo:
    code: int | None
    message: str
    app_region: str
    app_center: str
    region_ok: bool
    region_note: str


@dataclass
class EcParse:
    code: int | None
    message: str
    driver: float | None
    ac: float | None
    other: float | None


@dataclass
class MileageDay:
    day: str
    km: float | None


@dataclass
class MileageParse:
    code: int | None
    message: str
    total_mileage: float | None
    days: list[MileageDay]


def parse_signal_response(raw: dict[str, Any] | str) -> SignalParse:
    import json

    if isinstance(raw, str):
        try:
            root = json.loads(raw)
        except Exception:
            return SignalParse(None, "无法解析车况", None, set())
    else:
        root = raw
    if not isinstance(root, dict):
        return SignalParse(None, "无法解析车况", None, set())
    code = _as_int(root.get("code"))
    if code is None:
        code = _as_int(root.get("result"))
    message = str(root.get("message") or root.get("msg") or "")
    data = root.get("data") if isinstance(root.get("data"), dict) else {}
    mapping = data.get("signalMap") if isinstance(data.get("signalMap"), dict) else {}
    if not mapping and isinstance(data.get("signal"), dict):
        mapping = data["signal"]
    keys = set(mapping.keys())
    if code is not None and code not in (0, 200):
        return SignalParse(code, message, None, keys)
    sts = normalize_epoch_ms(_first_long(mapping, "sts") or _first_long(data, "sts") or 0)
    collect = normalize_epoch_ms(
        _first_long(data, "collectTime", "collectTimeMs")
        or _first_long(mapping, "collectTime", "collectTimeMs")
        or 0
    )
    precise = _first_number(mapping, "100003", "socPrecise")
    integer = _first_number(mapping, "1204", "soc")
    soc = precise if precise is not None else integer
    gear = _first_int(mapping, "1010", "gearStatus", "gear")
    speed = _first_number(mapping, "1319", "speed")
    charge = _first_int(mapping, "1149", "chargeState")
    moving = is_moving(gear, speed)
    snapshot = VehicleSnapshot(
        sts=sts,
        collect_time=collect,
        soc=soc,
        soc_precise=precise is not None,
        odo_km=_first_number(mapping, "1318", "totalMileage", "odometer"),
        speed_kmh=speed,
        gear=gear,
        battery_min_temp=_first_number(mapping, "1182", "minBatteryTemp", "minSingleTemp"),
        interior_temp=_first_number(mapping, "1349", "indoorTemp", "interiorTemp"),
        charge_code=charge,
        vehicle_state_code=_first_int(mapping, "1944", "vehicleState"),
        range_km=_first_number(mapping, "2188", "liveRemainingRange", "3257", "3260"),
        moving=moving,
        charging=charge == 1 and not moving,
        plugged=charge in {1, 2, 3, 4} and not moving,
    )
    useful = bool(keys) or snapshot.soc is not None or snapshot.odo_km is not None or snapshot.sts > 0
    return SignalParse(code, message, snapshot if useful else None, keys)


def validate_app_region(url: str) -> str | None:
    cleaned = url.strip().rstrip("/")
    if not cleaned:
        return "空路由"
    try:
        uri = urlparse(cleaned)
    except Exception:
        return "无法解析"
    if (uri.scheme or "").lower() != "https":
        return "不是 https"
    host = uri.hostname or ""
    if not host:
        return "没有主机名"
    if not (host.endswith(".leapmotor.com") or host.endswith(".leapmotor.cn")):
        return "主机不在零跑域名"
    if uri.username or uri.password:
        return "含用户信息"
    if uri.port not in (None, 443):
        return "端口不是 443"
    if uri.path not in ("", "/"):
        return "不应带路径"
    if uri.query:
        return "不应带查询"
    return None


def parse_car_route(raw: dict[str, Any] | str) -> RouteInfo:
    import json

    if isinstance(raw, str):
        try:
            root = json.loads(raw)
        except Exception:
            return RouteInfo(None, "无法解析路由", "", "", False, "无法解析")
    else:
        root = raw
    if not isinstance(root, dict):
        return RouteInfo(None, "无法解析路由", "", "", False, "无法解析")
    code = _as_int(root.get("code"))
    if code is None:
        code = _as_int(root.get("result"))
    message = str(root.get("message") or root.get("msg") or "")
    data = root.get("data") if isinstance(root.get("data"), dict) else {}
    region = str(data.get("appRegion") or "").strip().rstrip("/")
    center = str(data.get("appCenter") or "").strip().rstrip("/")
    note = "响应里没有 appRegion" if not region else (validate_app_region(region) or "可用")
    return RouteInfo(code, message, region, center, note == "可用", note)


def parse_vehicles(raw: dict[str, Any] | str) -> list[VehicleSummary]:
    import json

    if isinstance(raw, str):
        try:
            root = json.loads(raw)
        except Exception:
            return []
    else:
        root = raw
    if not isinstance(root, dict):
        return []
    data = root.get("data") if isinstance(root.get("data"), dict) else root
    found: dict[str, VehicleSummary] = {}

    def add(source: str, element: dict[str, Any]) -> None:
        vin = str(element.get("vin") or element.get("carVin") or element.get("carvin") or "").strip()
        if not vin or vin in found:
            return
        rights = element.get("rightList")
        if isinstance(rights, list):
            right_text = ",".join(str(x) for x in rights)
        else:
            right_text = str(rights or "")
        found[vin] = VehicleSummary(
            vin=vin,
            car_id=str(element.get("carId") or element.get("id") or ""),
            car_type=str(element.get("carType") or element.get("carTypeCode") or element.get("series") or ""),
            nick_name=str(element.get("nickName") or element.get("vinNickname") or element.get("name") or ""),
            plate_number=str(element.get("plateNumber") or element.get("plateNo") or ""),
            series_name=str(element.get("seriesName") or ""),
            source=source,
            right_list=right_text,
        )

    for key, source in (
        ("sharedcars", "shared"),
        ("bindcars", "owner"),
        ("cars", "unknown"),
        ("list", "unknown"),
        ("vehicleList", "unknown"),
    ):
        arr = data.get(key)
        if isinstance(arr, list):
            for item in arr:
                if isinstance(item, dict):
                    add(source, item)
    return list(found.values())


def parse_lastweek_ec(raw: dict[str, Any] | str) -> EcParse:
    import json

    if isinstance(raw, str):
        try:
            root = json.loads(raw)
        except Exception:
            return EcParse(None, "无法解析能耗", None, None, None)
    else:
        root = raw
    if not isinstance(root, dict):
        return EcParse(None, "无法解析能耗", None, None, None)
    code = _as_int(root.get("code"))
    if code is None:
        code = _as_int(root.get("result"))
    message = str(root.get("message") or root.get("msg") or "")
    data = root.get("data") if isinstance(root.get("data"), dict) else root
    return EcParse(
        code=code,
        message=message,
        driver=_as_number(data.get("driverEC")),
        ac=_as_number(data.get("acEC")),
        other=_as_number(data.get("otherEC")),
    )


def parse_mileage(raw: dict[str, Any] | str) -> MileageParse:
    import json

    if isinstance(raw, str):
        try:
            root = json.loads(raw)
        except Exception:
            return MileageParse(None, "无法解析里程", None, [])
    else:
        root = raw
    if not isinstance(root, dict):
        return MileageParse(None, "无法解析里程", None, [])
    code = _as_int(root.get("code"))
    if code is None:
        code = _as_int(root.get("result"))
    message = str(root.get("message") or root.get("msg") or "")
    data = root.get("data") if isinstance(root.get("data"), dict) else {}
    total = _as_number(data.get("totalmileage"))
    if total is None:
        total = _as_number(data.get("totalMileage"))
    days: list[MileageDay] = []
    detail = data.get("detail")
    if isinstance(detail, list):
        for item in detail:
            if not isinstance(item, dict):
                continue
            day = str(item.get("day") or "").strip()
            if not day:
                continue
            days.append(MileageDay(day=day, km=_as_number(item.get("accumulatedMileage"))))
    return MileageParse(code, message, total, days)
