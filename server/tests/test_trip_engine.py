from __future__ import annotations

from app.parse import VehicleSnapshot
from app.trip_engine import ManualCommand, TrackerState, TripEffect, TripEngine, TripSource, TripStatus


def frame(
    sts: int,
    odo: float | None = 100.0,
    soc: float | None = 80.0,
    moving: bool = False,
    charging: bool = False,
    plugged: bool = False,
    precise: bool = True,
    batt: float | None = -18.0,
    interior: float | None = 18.0,
) -> VehicleSnapshot:
    return VehicleSnapshot(
        sts=sts,
        collect_time=sts + 5,
        soc=soc,
        soc_precise=precise,
        odo_km=odo,
        speed_kmh=20.0 if moving else 0.0,
        gear=1 if moving else 0,
        battery_min_temp=batt,
        interior_temp=interior,
        charge_code=1 if charging else (2 if plugged else 0),
        moving=moving,
        charging=charging,
        plugged=plugged or charging,
    )


def test_duplicate_sts_does_not_accumulate():
    engine = TripEngine(ids=lambda: "id-0")
    started = engine.reduce(TrackerState(), frame(100, odo=10.0, soc=80.0, moving=True), 1_000)
    duplicate = engine.reduce(started.state, frame(100, odo=99.0, soc=10.0, moving=True), 2_000)
    assert duplicate.state.open.end_odo == 10.0
    assert duplicate.state.open.end_soc == 80.0
    assert duplicate.effects == []


def test_park_timeout_keeps_distance():
    seq = {"n": 0}

    def ids():
        seq["n"] += 1
        return f"id-{seq['n']}"

    engine = TripEngine(ids=ids)
    state = engine.reduce(TrackerState(), frame(1, odo=100.0, soc=80.0, moving=True, batt=-12.0), 1_000).state
    state = engine.reduce(state, frame(2, odo=120.0, soc=76.0, moving=False, batt=-11.0), 10_000).state
    closed = engine.reduce(state, frame(2, moving=False), 80_000)
    trip = [e for e in closed.effects if isinstance(e, TripEffect.Save)][-1].trip
    assert trip.status == TripStatus.CLOSED
    assert trip.close_reason == "PARK"
    assert trip.end_at == 10_000
    assert (trip.end_odo or 0) - (trip.start_odo or 0) == 20.0


def test_charging_interrupts_without_charged_soc():
    engine = TripEngine(ids=lambda: "t1")
    state = engine.reduce(TrackerState(), frame(1, odo=100.0, soc=80.0, moving=True), 1_000).state
    state = engine.reduce(state, frame(2, odo=101.0, soc=79.0, moving=True), 2_000).state
    charged = engine.reduce(state, frame(3, odo=101.0, soc=90.0, charging=True), 3_000)
    trip = [e for e in charged.effects if isinstance(e, TripEffect.Save)][-1].trip
    assert trip.end_soc == 79.0
    assert trip.close_reason == "CHARGING"


def test_manual_trip_ignores_park_timeout():
    engine = TripEngine(ids=lambda: "m1")
    started = engine.reduce(
        TrackerState(),
        frame(1, odo=5.0, soc=60.0, moving=True),
        1_000,
        manual=ManualCommand.START,
    )
    still = engine.reduce(started.state, frame(2, odo=5.2, soc=59.0, moving=False), 10 * 60_000)
    assert still.state.open.status == TripStatus.OPEN
    assert still.state.open.source == TripSource.MANUAL
