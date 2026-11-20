package cn.daqing.leap.a10energy.data

import cn.daqing.leap.a10energy.protocol.AppJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerModeTest {
    @Test
    fun normalizeServerBaseUrl_addsHttpAndTrimsSlash() {
        assertEquals("http://192.0.2.10:18080", normalizeServerBaseUrl("192.0.2.10:18080/"))
        assertEquals("https://example.com", normalizeServerBaseUrl("https://example.com/"))
        assertEquals("", normalizeServerBaseUrl("  "))
    }

    @Test
    fun shouldRunLocalPolling_mutexWithServerMode() {
        val local = ServerConfig(mode = CollectMode.LOCAL)
        val server = ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://192.0.2.10:18080", apiToken = "x")
        assertTrue(shouldRunLocalPolling(local, autoRecord = true))
        assertFalse(shouldRunLocalPolling(local, autoRecord = false))
        assertFalse(shouldRunLocalPolling(server, autoRecord = true))
        assertFalse(shouldRunLocalPolling(server, autoRecord = false))
    }

    @Test
    fun serverReady_requiresUrlAndToken() {
        assertFalse(ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://x", apiToken = "").serverReady)
        assertFalse(ServerConfig(mode = CollectMode.LOCAL, baseUrl = "http://x", apiToken = "t").serverReady)
        assertTrue(ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://x", apiToken = "t").serverReady)
    }

    @Test
    fun parseStatus_readsVehicleAndCollector() {
        val raw = """
            {
              "version": "1.1.0",
              "collector": {
                "enabled": true,
                "running": true,
                "rateLimited": false,
                "statusMessage": "ok",
                "lastError": "",
                "lastPollAt": 100,
                "lastFrameSts": 200
              },
              "session": {"nickname": "LP", "phoneMasked": "138****0000", "carType": "A10"},
              "vehicle": {
                "sts": 1700000000000,
                "soc": 55.5,
                "socPrecise": true,
                "odoKm": 12.3,
                "batteryMinTemp": -18.0,
                "moving": false,
                "charging": false,
                "plugged": false
              },
              "settings": {
                "batteryUsableKwh": 50.0,
                "pollDrivingSeconds": 6,
                "pollParkedSeconds": 60,
                "parkTimeoutSeconds": 60,
                "syncDays": 7
              }
            }
        """.trimIndent()
        val json = AppJson.parseToJsonElement(raw) as JsonObject
        val status = parseStatus(json)
        assertEquals("1.1.0", status.version)
        assertTrue(status.collectorRunning)
        assertEquals(55.5, status.snapshot!!.soc!!, 0.001)
        assertEquals(-18.0, status.snapshot!!.batteryMinTemp!!, 0.001)
        assertTrue(status.sessionSummary.contains("LP"))
    }

    @Test
    fun parseTrip_fromServerPayload() {
        val raw = """
            {
              "id": "t1",
              "source": "AUTO",
              "status": "CLOSED",
              "startAt": 1,
              "endAt": 2,
              "startOdo": 10.0,
              "endOdo": 20.0,
              "startSoc": 80.0,
              "endSoc": 76.0,
              "socSource": "PRECISE",
              "note": "",
              "tags": "",
              "sampleCount": 3,
              "lastSts": 9,
              "sawPrecise": true,
              "sawInteger": false
            }
        """.trimIndent()
        val trip = parseTrip(AppJson.parseToJsonElement(raw) as JsonObject)
        assertEquals("t1", trip.id)
        assertEquals(10.0, trip.startOdo!!, 0.001)
        assertEquals(20.0, trip.endOdo!!, 0.001)
    }
}
