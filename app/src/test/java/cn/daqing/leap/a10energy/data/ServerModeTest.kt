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
        val server = ServerConfig(
            mode = CollectMode.SERVER,
            baseUrl = "http://192.0.2.10:18080",
            userId = "phone-a",
        )
        assertTrue(shouldRunLocalPolling(local, autoRecord = true))
        assertFalse(shouldRunLocalPolling(local, autoRecord = false))
        assertFalse(shouldRunLocalPolling(server, autoRecord = true))
        assertFalse(shouldRunLocalPolling(server, autoRecord = false))
    }

    @Test
    fun serverReady_requiresUrlAndUserId_notToken() {
        assertFalse(ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://x", userId = "").serverReady)
        assertFalse(ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://x", userId = "a").serverReady)
        assertFalse(ServerConfig(mode = CollectMode.LOCAL, baseUrl = "http://x", userId = "phone-a").serverReady)
        assertTrue(ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://x", userId = "phone-a").serverReady)
        assertTrue(ServerConfig(mode = CollectMode.SERVER, baseUrl = "http://x", userId = "phone-a", apiToken = "").serverReady)
    }

    @Test
    fun cloudUserId_validation() {
        assertTrue(isValidCloudUserId("ab"))
        assertTrue(isValidCloudUserId("my.user_id-1"))
        assertFalse(isValidCloudUserId(""))
        assertFalse(isValidCloudUserId("a"))
        assertFalse(isValidCloudUserId("bad id"))
        assertFalse(isValidCloudUserId("中文"))
    }

    @Test
    fun parseStatus_readsBindingAndVehicle() {
        val raw = """
            {
              "version": "1.2.0",
              "userId": "phone-a",
              "binding": {
                "userId": "phone-a",
                "hasLeapSession": true,
                "tripCount": 3
              },
              "collector": {
                "enabled": true,
                "running": true,
                "hasSession": true,
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
        assertEquals("1.2.0", status.version)
        assertEquals("phone-a", status.userId)
        assertTrue(status.hasLeapSession)
        assertEquals(3, status.tripCount)
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
