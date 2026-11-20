package cn.daqing.leap.a10energy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {
    private var seq = 0
    private val engine = TripEngine { "id-${seq++}" }

    @Test
    fun duplicateSts_doesNotAccumulateEvenIfValuesChange() {
        val started = engine.reduce(TrackerState(), frame(sts = 100, odo = 10.0, soc = 80.0, moving = true), now = 1_000)
        val duplicate = engine.reduce(
            started.state,
            frame(sts = 100, odo = 99.0, soc = 10.0, moving = true),
            now = 2_000,
        )
        assertEquals(10.0, duplicate.state.open!!.endOdo!!, 0.001)
        assertEquals(80.0, duplicate.state.open!!.endSoc!!, 0.001)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun redLightUnderOneMinute_doesNotSplit() {
        val start = engine.reduce(TrackerState(), frame(1, odo = 10.0, soc = 80.0, moving = true), 0)
        val parked = engine.reduce(start.state, frame(2, odo = 10.2, soc = 79.8, moving = false), 10_000)
        val moving = engine.reduce(parked.state, frame(3, odo = 10.4, soc = 79.6, moving = true), 40_000)
        assertEquals(start.state.open!!.id, moving.state.open!!.id)
        assertNull(moving.state.open!!.parkedSince)
        assertEquals(0.4, moving.state.open!!.distanceKm()!!, 0.001)
    }

    @Test
    fun parkTimeout_usesWallClockOnDuplicateFrame_andDropsShortTrip() {
        var state = engine.reduce(TrackerState(), frame(1, odo = 10.0, soc = 50.0, moving = true), 1_000).state
        state = engine.reduce(state, frame(2, odo = 10.1, soc = 50.0, moving = false), 2_000).state
        val closed = engine.reduce(state, frame(2, odo = 99.0, soc = 1.0, moving = false), 2_000 + 70_000)
        assertNull(closed.state.open)
        assertTrue(closed.effects.any { it is TripEffect.Delete })
        assertTrue(closed.messages.any { it.contains("0.2") })
    }

    @Test
    fun parkTimeout_keepsNormalDistance_andEndsAtParkMoment() {
        var state = engine.reduce(TrackerState(), frame(1, odo = 100.0, soc = 80.0, moving = true, batt = -12.0), 1_000).state
        state = engine.reduce(state, frame(2, odo = 120.0, soc = 76.0, moving = false, batt = -11.0), 10_000).state
        val closed = engine.reduce(state, frame(2, moving = false), 80_000)
        val trip = (closed.effects.last() as TripEffect.Save).trip
        assertEquals(TripStatus.CLOSED, trip.status)
        assertEquals("PARK", trip.closeReason)
        assertEquals(10_000L, trip.endAt)
        assertEquals(20.0, trip.distanceKm()!!, 0.001)
        assertEquals(4.0, trip.deltaSoc()!!, 0.001)
        assertEquals(2.0, trip.energyKwh(50.0)!!, 0.001)
        assertEquals(10.0, trip.kwhPer100km(50.0)!!, 0.001)
    }

    @Test
    fun chargingInterrupts_withoutApplyingChargedSoc_andSkipsLaterGap() {
        var state = engine.reduce(TrackerState(), frame(1, odo = 100.0, soc = 80.0, moving = true), 1_000).state
        state = engine.reduce(state, frame(2, odo = 101.0, soc = 79.0, moving = true), 2_000).state
        val charged = engine.reduce(state, frame(3, odo = 101.0, soc = 90.0, charging = true), 3_000)
        val trip = charged.effects.filterIsInstance<TripEffect.Save>().last().trip
        assertEquals(79.0, trip.endSoc!!, 0.001)
        assertEquals(1.0, trip.distanceKm()!!, 0.001)
        assertEquals("CHARGING", trip.closeReason)
        assertTrue(charged.state.anchor.charging)
        val after = engine.reduce(charged.state, frame(4, odo = 105.0, soc = 95.0, moving = false), 4_000)
        assertTrue(after.effects.none { it is TripEffect.Save && (it as TripEffect.Save).trip.source == TripSource.GAP })
        assertNull(after.state.open)
        assertEquals(false, after.state.anchor.charging)
    }

    @Test
    fun gapFill_whenParkedOdoJumps_butNotForSmallJump() {
        val first = engine.reduce(TrackerState(), frame(1, odo = 100.0, soc = 80.0, moving = false), 1_000)
        val gap = engine.reduce(first.state, frame(2, odo = 103.0, soc = 78.0, moving = false, batt = -21.0), 5_000)
        val trip = gap.effects.filterIsInstance<TripEffect.Save>().single().trip
        assertEquals(TripSource.GAP, trip.source)
        assertEquals(3.0, trip.distanceKm()!!, 0.001)
        val smallBase = engine.reduce(TrackerState(), frame(1, odo = 100.0, soc = 80.0, moving = false), 1_000)
        val small = engine.reduce(smallBase.state, frame(2, odo = 100.9, soc = 80.0, moving = false), 2_000)
        assertTrue(small.effects.isEmpty())
    }

    @Test
    fun hugeOdoJump_isIgnored() {
        val start = engine.reduce(TrackerState(), frame(1, odo = 100.0, soc = 50.0, moving = true), 1_000)
        val next = engine.reduce(start.state, frame(2, odo = 2000.0, soc = 49.0, moving = true), 2_000)
        assertEquals(100.0, next.state.open!!.endOdo!!, 0.001)
        assertEquals(49.0, next.state.open!!.endSoc!!, 0.001)
    }

    @Test
    fun lostContact_onDuplicateClocksOut_andNewFrameKeepsDistance() {
        var state = engine.reduce(TrackerState(), frame(1_000, odo = 10.0, soc = 80.0, moving = true), 1_000).state
        state = engine.reduce(state, frame(2_000, odo = 11.0, soc = 79.0, moving = true), 2_000).state
        val lost = engine.reduce(state, frame(2_000, odo = 50.0, soc = 10.0, moving = false), 2_000 + 31 * 60_000)
        val trip = lost.effects.filterIsInstance<TripEffect.Save>().single().trip
        assertEquals("LOST", trip.closeReason)
        assertEquals(1.0, trip.distanceKm()!!, 0.001)

        val restarted = engine.reduce(TrackerState(), frame(1_000, odo = 10.0, soc = 80.0, moving = true), 1_000)
        val resumed = engine.reduce(
            restarted.state,
            frame(1_000 + 31 * 60_000, odo = 25.0, soc = 70.0, moving = true),
            1_000 + 31 * 60_000,
        )
        val closed = resumed.effects.filterIsInstance<TripEffect.Save>().first().trip
        assertEquals(TripStatus.CLOSED, closed.status)
        assertEquals(15.0, closed.distanceKm()!!, 0.001)
        assertEquals(25.0, resumed.state.open!!.startOdo!!, 0.001)
    }

    @Test
    fun manualTrip_keepsShortDistance_andIgnoresParkTimeout() {
        val started = engine.reduce(
            TrackerState(),
            frame(1, odo = 10.0, soc = 40.0, moving = false),
            now = 1_000,
            manual = ManualCommand.START,
        )
        val ended = engine.reduce(started.state, frame(1, odo = 10.0, soc = 40.0), now = 2_000, manual = ManualCommand.END)
        val trip = ended.effects.filterIsInstance<TripEffect.Save>().last().trip
        assertEquals(TripStatus.CLOSED, trip.status)
        assertEquals(0.0, trip.distanceKm()!!, 0.001)
        assertEquals(TripSource.MANUAL, trip.source)

        val manual = engine.reduce(
            TrackerState(),
            frame(1, odo = 5.0, soc = 60.0, moving = true),
            now = 1_000,
            manual = ManualCommand.START,
        )
        val still = engine.reduce(manual.state, frame(2, odo = 5.2, soc = 59.0, moving = false), 10 * 60_000)
        assertEquals(TripStatus.OPEN, still.state.open!!.status)
        assertEquals(manual.state.open!!.id, still.state.open!!.id)
    }

    @Test
    fun negativeEnergy_hasNoConsumptionRate() {
        val trip = TripRecord(
            id = "x",
            source = TripSource.AUTO,
            status = TripStatus.CLOSED,
            startAt = 1,
            endAt = 2,
            startOdo = 0.0,
            endOdo = 10.0,
            startSoc = 70.0,
            endSoc = 72.0,
        )
        assertTrue(trip.energyKwh(50.0)!! < 0)
        assertNull(trip.kwhPer100km(50.0))
    }
}

private fun frame(
    sts: Long,
    odo: Double? = 100.0,
    soc: Double? = 80.0,
    moving: Boolean = false,
    charging: Boolean = false,
    plugged: Boolean = false,
    precise: Boolean = true,
    batt: Double? = -18.0,
    interior: Double? = 18.0,
) = VehicleSnapshot(
    sts = sts,
    collectTime = sts + 5,
    soc = soc,
    socPrecise = precise,
    odoKm = odo,
    speedKmh = if (moving) 20.0 else 0.0,
    gear = if (moving) 1 else 0,
    batteryMinTemp = batt,
    interiorTemp = interior,
    chargeCode = if (charging) 1 else if (plugged) 2 else 0,
    moving = moving,
    charging = charging,
    plugged = plugged || charging,
)
