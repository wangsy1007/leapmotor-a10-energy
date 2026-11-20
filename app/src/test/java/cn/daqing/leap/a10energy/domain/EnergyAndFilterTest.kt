package cn.daqing.leap.a10energy.domain

import cn.daqing.leap.a10energy.data.signalAttempts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class EnergyAndFilterTest {
    @Test
    fun capacityInference() {
        assertEquals(50.0, inferUsableCapacity(10.0, 20.0)!!, 0.001)
        assertNull(inferUsableCapacity(0.0, 20.0))
        assertNull(inferUsableCapacity(10.0, 0.0))
        assertNull(inferUsableCapacity(-1.0, 10.0))
    }

    @Test
    fun temperatureBoundaries_andWeightedAverage() {
        assertEquals("低于 -20℃", bandOf(-20.0001).label)
        assertEquals("-20 ~ -10℃", bandOf(-20.0).label)
        assertEquals("-10 ~ 0℃", bandOf(-10.0).label)
        assertEquals("0 ~ 10℃", bandOf(0.0).label)
        assertEquals("10℃ 及以上", bandOf(10.0).label)
        val cold = trip(km = 100.0, startSoc = 80.0, endSoc = 40.0, temp = -20.0)
        val mild = trip(km = 50.0, startSoc = 50.0, endSoc = 40.0, temp = 0.0)
        val stat = aggregate(listOf(cold, mild), 50.0)
        assertEquals(150.0, stat.totalKm, 0.001)
        assertEquals(25.0, stat.totalKwh, 0.001)
        assertEquals(16.666, stat.weightedKwhPer100km!!, 0.01)
        val coldBand = stat.bands.first { it.label == "-20 ~ -10℃" }
        assertEquals(1, coldBand.trips)
        assertEquals(20.0, coldBand.kwhPer100km!!, 0.01)
    }

    @Test
    fun lowTempFilter_usesColderReading() {
        val now = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, Shanghai.zone).toInstant().toEpochMilli()
        val cold = trip(km = 5.0, startSoc = 50.0, endSoc = 48.0, temp = -12.0).copy(startAt = now - 3_600_000)
        val warm = trip(km = 5.0, startSoc = 50.0, endSoc = 48.0, temp = 8.0).copy(startAt = now - 1_000)
        val shown = applyTripFilter(listOf(cold, warm), TripFilter(tempMax = -10.0, tag = ""), now)
        assertEquals(listOf(cold.id), shown.map { it.id })
        val tagged = applyTripFilter(listOf(warm.copy(tags = "极寒")), TripFilter(tag = "极寒"), now)
        assertEquals(1, tagged.size)
    }

    @Test
    fun weekStartsMondayInShanghai() {
        val now = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, Shanghai.zone).toInstant().toEpochMilli()
        val start = rangeStart(DatePreset.WEEK, now)!!
        val monday = ZonedDateTime.of(2026, 11, 16, 0, 0, 0, 0, Shanghai.zone).toInstant().toEpochMilli()
        assertEquals(monday, start)
    }

    @Test
    fun dayWindow_clampsEndToNow() {
        val now = ZonedDateTime.of(2026, 11, 20, 8, 0, 0, 0, Shanghai.zone)
        val (begin, end) = dayWindowSeconds(LocalDate.of(2026, 11, 20), now.toEpochSecond())
        assertEquals(now.toLocalDate().atStartOfDay(Shanghai.zone).toEpochSecond(), begin)
        assertEquals(now.toEpochSecond(), end)
        assertTrue(end < begin + 24 * 3600)
    }

    @Test
    fun manualBuilder_rejectsBadInput_andComputesFromOdo() {
        val bad = buildManualTrip(
            ManualInput(startAt = 2_000, endAt = 1_000, distanceKm = 1.0, deltaSoc = 1.0),
            "n",
        )
        assertTrue(bad.error!!.contains("结束时间"))
        val ok = buildManualTrip(
            ManualInput(
                startAt = 1_000,
                endAt = 2_000,
                startOdo = 10.0,
                endOdo = 15.0,
                startSoc = 80.0,
                endSoc = 70.0,
                startBattery = -15.0,
                endBattery = -14.0,
                socSource = SocSource.PRECISE,
            ),
            "m1",
        )
        assertEquals(5.0, ok.trip!!.distanceKm()!!, 0.001)
        assertEquals(5.0, ok.trip.energyKwh(50.0)!!, 0.001)
    }

    @Test
    fun backoff_growsAndBlocks() {
        val first = registerRateLimit(BackoffState(), nowMs = 0, minimumMs = 15 * 60_000)
        assertEquals(15 * 60_000L, first.cooldownUntilMs)
        val second = registerRateLimit(first, nowMs = first.cooldownUntilMs, minimumMs = 15 * 60_000)
        assertEquals(first.cooldownUntilMs + 30 * 60_000L, second.cooldownUntilMs)
        assertTrue(backoffMessage(second, second.cooldownUntilMs - 1_000)!!.contains("1023"))
        assertNull(backoffMessage(second, second.cooldownUntilMs))
    }

    @Test
    fun redact_hidesTokensButKeepsVinAndSignals() {
        val raw = """{"token":"abcdef","vin":"LTESTVIN000000001","signalMap":{"100003":36.9},"phone":"13800138000"}"""
        val cleaned = redactSecrets(raw)
        assertTrue(cleaned.contains("LTESTVIN000000001"))
        assertTrue(cleaned.contains("36.9"))
        assertTrue(cleaned.contains(""""token":"***""""))
        assertTrue(!cleaned.contains("13800138000"))
    }

    @Test
    fun signalAttemptOrder_prefersRememberedStrategy() {
        val attempts = signalAttempts("https://r.leapmotor.com", "REGION_JSON", "https://appgateway.leapmotor.com")
        assertEquals(listOf("REGION_JSON", "REGION_FORM", "GATEWAY_FORM"), attempts.map { it.name })
    }

    @Test
    fun csvContainsCapacityAndChineseHeader() {
        val csv = exportCsv(listOf(trip(km = 2.0, startSoc = 50.0, endSoc = 48.0, temp = -5.0)), 50.0)
        assertTrue(csv.startsWith("\uFEFF# usableCapacityKwh=50.0"))
        assertTrue(csv.contains("百公里电耗"))
    }

    private fun trip(km: Double, startSoc: Double, endSoc: Double, temp: Double) = TripRecord(
        id = "t$km$temp",
        source = TripSource.AUTO,
        status = TripStatus.CLOSED,
        startAt = 1_000,
        endAt = 2_000,
        startOdo = 0.0,
        endOdo = km,
        startSoc = startSoc,
        endSoc = endSoc,
        startBatteryTemp = temp,
        endBatteryTemp = temp,
        socSource = SocSource.PRECISE,
    )
}
