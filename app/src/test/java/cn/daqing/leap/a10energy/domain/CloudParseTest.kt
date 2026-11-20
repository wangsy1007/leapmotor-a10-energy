package cn.daqing.leap.a10energy.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudParseTest {
    @Test
    fun preciseSocBeatsInteger_andStsIsNotCollectTime() {
        val raw = """
            {"code":0,"data":{"collectTime":1790456795283,"signalMap":{
              "100003":36.9,"1204":37,"1318":12345,"1319":0,"1010":0,
              "1182":-18.5,"1349":12.0,"2188":247,"1149":0,"sts":1790456700000
            }}}
        """.trimIndent()
        val parsed = parseSignalResponse(raw)
        val snap = parsed.snapshot!!
        assertEquals(36.9, snap.soc!!, 0.001)
        assertTrue(snap.socPrecise)
        assertEquals(12345.0, snap.odoKm!!, 0.001)
        assertEquals(-18.5, snap.batteryMinTemp!!, 0.001)
        assertEquals(12.0, snap.interiorTemp!!, 0.001)
        assertEquals(247.0, snap.rangeKm!!, 0.001)
        assertEquals(1790456700000L, snap.sts)
        assertEquals(1790456795283L, snap.collectTime)
        assertFalse(snap.sts == snap.collectTime)
        assertFalse(snap.moving)
        assertFalse(snap.charging)
    }

    @Test
    fun integerSocFallback_andSecondsStsScaledToMillis() {
        val raw = """{"code":0,"data":{"signalMap":{"1204":"36","1318":10,"sts":1790456700}}}"""
        val snap = parseSignalResponse(raw).snapshot!!
        assertEquals(36.0, snap.soc!!, 0.001)
        assertFalse(snap.socPrecise)
        assertEquals(1_790_456_700_000L, snap.sts)
    }

    @Test
    fun namedSignalsAreAcceptedWhenNumericKeysMissing() {
        val raw = """{"code":0,"data":{"signalMap":{"soc":40,"totalMileage":8,"gearStatus":1,"sts":1700000000000}}}"""
        val snap = parseSignalResponse(raw).snapshot!!
        assertEquals(40.0, snap.soc!!, 0.001)
        assertEquals(8.0, snap.odoKm!!, 0.001)
        assertTrue(snap.moving)
    }

    @Test
    fun chargingCodes_andDrivingGuard() {
        val charging = parseSignalResponse("""{"code":0,"data":{"signalMap":{"1010":0,"1319":0,"1149":1,"sts":11}}}""").snapshot!!
        assertTrue(charging.charging)
        assertTrue(charging.plugged)
        val plugged = parseSignalResponse("""{"code":0,"data":{"signalMap":{"1010":2,"1149":2,"sts":12}}}""").snapshot!!
        assertFalse(plugged.charging)
        assertTrue(plugged.plugged)
        val notPlug = parseSignalResponse("""{"code":0,"data":{"signalMap":{"1010":0,"1149":5,"sts":13}}}""").snapshot!!
        assertFalse(notPlug.plugged)
        val drivingDirty = parseSignalResponse("""{"code":0,"data":{"signalMap":{"1010":1,"1319":30,"1149":1,"sts":14}}}""").snapshot!!
        assertTrue(drivingDirty.moving)
        assertFalse(drivingDirty.charging)
        assertFalse(drivingDirty.plugged)
    }

    @Test
    fun speedStartsMovingWhenGearMissing() {
        val snap = parseSignalResponse("""{"code":0,"data":{"signalMap":{"1319":6,"sts":15}}}""").snapshot!!
        assertTrue(snap.moving)
    }

    @Test
    fun rateLimitDoesNotInventSnapshot() {
        val parsed = parseSignalResponse("""{"code":1023,"message":"风控"}""")
        assertNull(parsed.snapshot)
        assertEquals(1023, parsed.code)
    }

    @Test
    fun sameStsDifferentCollectTime_staysSameFrameId() {
        val a = parseSignalResponse("""{"code":0,"data":{"collectTime":100,"signalMap":{"sts":50,"1318":1}}}""").snapshot!!
        val b = parseSignalResponse("""{"code":0,"data":{"collectTime":999,"signalMap":{"sts":50,"1318":9}}}""").snapshot!!
        assertEquals(a.sts, b.sts)
        assertTrue(a.collectTime != b.collectTime)
    }

    @Test
    fun batteryMinTemp_reads1182AndIgnoresHeatingAndCabin() {
        val snap = parseSignalResponse(
            """{"code":0,"data":{"signalMap":{"1182":-12,"1186":1,"1349":18,"2183":26,"sts":1700000000000}}}""",
        ).snapshot!!
        assertEquals(-12.0, snap.batteryMinTemp!!, 0.001)
        assertEquals(18.0, snap.interiorTemp!!, 0.001)
    }

    @Test
    fun batteryMinTemp_absentWithout1182() {
        val snap = parseSignalResponse(
            """{"code":0,"data":{"signalMap":{"1186":1,"1349":5,"sts":1700000000000}}}""",
        ).snapshot!!
        assertNull(snap.batteryMinTemp)
        assertEquals(5.0, snap.interiorTemp!!, 0.001)
    }

    @Test
    fun batteryMinTemp_keepsZeroAndPrefersNumericOverNamed() {
        val zero = parseSignalResponse(
            """{"code":0,"data":{"signalMap":{"1182":0,"sts":1700000000001}}}""",
        ).snapshot!!
        assertEquals(0.0, zero.batteryMinTemp!!, 0.001)
        val named = parseSignalResponse(
            """{"code":0,"data":{"signalMap":{"minBatteryTemp":"-10.5","sts":1700000000002}}}""",
        ).snapshot!!
        assertEquals(-10.5, named.batteryMinTemp!!, 0.001)
        val alias = parseSignalResponse(
            """{"code":0,"data":{"signalMap":{"minSingleTemp":"-7","sts":1700000000003}}}""",
        ).snapshot!!
        assertEquals(-7.0, alias.batteryMinTemp!!, 0.001)
        val numericWins = parseSignalResponse(
            """{"code":0,"data":{"signalMap":{"1182":-18,"minBatteryTemp":30,"minSingleTemp":4,"sts":1700000000004}}}""",
        ).snapshot!!
        assertEquals(-18.0, numericWins.batteryMinTemp!!, 0.001)
    }

    @Test
    fun checklist_marksMissingA10Keys() {
        val items = diagnoseSignalKeys(setOf("1204", "1318", "sts")).associateBy { it.id }
        assertFalse(items.getValue("100003").present)
        assertTrue(items.getValue("1204").present)
        assertTrue(items.getValue("soc-any").present)
        assertFalse(items.getValue("1182").present)
    }

    @Test
    fun vehicles_preferSharedAndOwnerLists() {
        val raw = """{"code":0,"data":{"sharedcars":[{"vin":"VINSHARED","carType":"A10","nickName":"大庆"}],"bindcars":[{"vin":"VINOWN"}]}}"""
        val cars = parseVehicles(raw)
        assertEquals(listOf("VINSHARED", "VINOWN"), cars.map { it.vin })
        assertEquals("shared", cars[0].source)
        assertEquals("A10", cars[0].carType)
    }

    @Test
    fun routeValidation_rejectsNonLeapHostsAndPaths() {
        assertNull(validateAppRegion("https://region.leapmotor.com"))
        assertEquals("可用", parseCarRoute("""{"code":0,"data":{"appRegion":"https://region.leapmotor.com/","appCenter":"https://c.leapmotor.cn"}}""").regionNote)
        assertFalse(parseCarRoute("""{"code":0,"data":{"appRegion":"https://evil.example/app"}}""").regionOk)
        assertTrue(validateAppRegion("http://a.leapmotor.com") != null)
        assertTrue(validateAppRegion("https://a.leapmotor.com/extra") != null)
    }

    @Test
    fun energyAndMileageParsers_ignoreAccumulatedEnergy() {
        val ec = parseLastWeekEc("""{"code":0,"data":{"driverEC":"7.0","acEC":"0.5","otherEC":"0.9"}}""")
        assertEquals(7.0, ec.driver!!, 0.001)
        assertEquals(0.5, ec.ac!!, 0.001)
        val mileage = parseMileage(
            """{"code":0,"data":{"totalmileage":9598,"detail":[{"day":"2026-11-20","accumulatedMileage":22,"accumulatedEnergyConsume":0}]}}""",
        )
        assertEquals(22.0, mileage.days.single().km!!, 0.001)
        assertEquals(9598.0, mileage.totalMileage!!, 0.001)
    }

    @Test
    fun decideEc_today100IsPending_past100IsZero() {
        val today = decideEc(100, null, null, null, isToday = true)
        assertTrue(today.pending)
        assertNull(today.driver)
        val past = decideEc(100, null, null, null, isToday = false)
        assertFalse(past.pending)
        assertEquals(0.0, past.driver!!, 0.001)
        val ok = decideEc(0, 1.5, 0.2, 0.1, false)
        assertEquals(1.5, ok.driver!!, 0.001)
    }
}
