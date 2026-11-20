package cn.daqing.leap.a10energy.data

import cn.daqing.leap.a10energy.domain.SocSource
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.TripSource
import cn.daqing.leap.a10energy.domain.TripStatus
import cn.daqing.leap.a10energy.domain.VehicleSnapshot
import cn.daqing.leap.a10energy.protocol.AppJson
import cn.daqing.leap.a10energy.protocol.LeapSession
import cn.daqing.leap.a10energy.protocol.doubleFlex
import cn.daqing.leap.a10energy.protocol.intFlex
import cn.daqing.leap.a10energy.protocol.longFlex
import cn.daqing.leap.a10energy.protocol.obj
import cn.daqing.leap.a10energy.protocol.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ServerHttpResult(
    val ok: Boolean,
    val httpCode: Int,
    val message: String,
    val raw: String,
    val json: JsonObject? = null,
)

data class ServerStatusPayload(
    val version: String = "",
    val collectorEnabled: Boolean = false,
    val collectorRunning: Boolean = false,
    val rateLimited: Boolean = false,
    val rateLimitMessage: String? = null,
    val statusMessage: String = "",
    val lastError: String = "",
    val lastPollAt: Long = 0,
    val lastFrameSts: Long = 0,
    val snapshot: VehicleSnapshot? = null,
    val openTrip: TripRecord? = null,
    val capacityKwh: Double = 50.0,
    val driveSec: Int = 6,
    val parkSec: Int = 60,
    val parkTimeoutSec: Int = 60,
    val syncDays: Int = 7,
    val sessionSummary: String = "",
)

data class ServerStatsPayload(
    val raw: JsonObject,
)

data class OfficialDayDto(
    val date: String,
    val driverKwh: Double?,
    val acKwh: Double?,
    val otherKwh: Double?,
    val mileageKm: Double?,
    val pending: Boolean,
    val note: String,
    val syncedAt: Long,
)

/**
 * 自建采集服务 REST 客户端。Token 与地址由设置页输入，不硬编码。
 */
class ServerApiClient(
    private val configStore: ServerConfigStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun testConnection(): ServerHttpResult {
        val config = configStore.current()
        val health = get("/healthz", config, auth = false)
        if (!health.ok) return health
        val status = get("/api/v1/status", config, auth = true)
        val version = status.json?.str("version").orEmpty()
        val collector = status.json?.obj("collector")
        val running = collector?.let { it["running"].toString().contains("true") } == true
        val message = if (status.ok) {
            "连通正常 · 服务端 $version · 采集${if (running) "中" else "未运行"}"
        } else {
            status.message
        }
        configStore.update {
            it.copy(lastTestOk = status.ok, lastTestMessage = message)
        }
        return status.copy(message = message, ok = status.ok)
    }

    suspend fun fetchStatus(): Pair<ServerHttpResult, ServerStatusPayload?> {
        val result = get("/api/v1/status")
        if (!result.ok || result.json == null) return result to null
        return result to parseStatus(result.json)
    }

    suspend fun pollNow(): Pair<ServerHttpResult, ServerStatusPayload?> {
        val result = post("/api/v1/poll/now")
        val status = result.json?.obj("status")?.let { parseStatus(it) }
            ?: fetchStatus().second
        return result to status
    }

    suspend fun pushSession(
        session: LeapSession,
        selectedVin: String,
        carType: String,
        appRegion: String,
        appCenter: String,
    ): ServerHttpResult {
        val body = buildJsonObject {
            put("phone", session.phone)
            put("deviceId", session.deviceId)
            put("accountId", session.accountId)
            put("nickname", session.nickname)
            put("token", session.accountToken)
            put("refreshToken", session.refreshToken)
            put("tokenExpiresAtMs", session.accountExpiresAtSec * 1000)
            put("selectedVin", selectedVin)
            put("carType", carType)
            put("appRegion", appRegion)
            put("appCenter", appCenter)
            put("carToken", session.carToken)
            put("carRefreshToken", session.carRefreshToken)
            put("carTokenExpiresAtMs", session.carExpiresAtMs)
            put("signR2", session.signR2)
            put("signR3", session.signR3)
            put("hkdfKeyHex", session.hkdfKeyHex)
            put("userId", session.userId)
        }
        val result = post("/api/v1/session/sync", body.toString())
        if (result.ok) {
            configStore.update { it.copy(sessionPushed = true) }
        }
        return result
    }

    suspend fun clearServerSession(): ServerHttpResult = delete("/api/v1/session")

    suspend fun setCollectorEnabled(enabled: Boolean): ServerHttpResult =
        put("/api/v1/settings", buildJsonObject { put("collectorEnabled", enabled) }.toString())

    suspend fun updateSettings(
        capacityKwh: Double? = null,
        driveSec: Int? = null,
        parkSec: Int? = null,
        parkTimeoutSec: Int? = null,
        syncDays: Int? = null,
        collectorEnabled: Boolean? = null,
    ): ServerHttpResult {
        val body = buildJsonObject {
            capacityKwh?.let { put("batteryUsableKwh", it) }
            driveSec?.let { put("pollDrivingSeconds", it) }
            parkSec?.let { put("pollParkedSeconds", it) }
            parkTimeoutSec?.let { put("parkTimeoutSeconds", it) }
            syncDays?.let { put("syncDays", it) }
            collectorEnabled?.let { put("collectorEnabled", it) }
        }
        return put("/api/v1/settings", body.toString())
    }

    suspend fun manualStart(): ServerHttpResult = post("/api/v1/trips/manual-start")
    suspend fun manualStop(): ServerHttpResult = post("/api/v1/trips/manual-stop")

    suspend fun listTrips(): Pair<ServerHttpResult, List<TripRecord>> {
        val result = get("/api/v1/trips")
        val trips = (result.json?.get("trips") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let(::parseTrip) }
            .orEmpty()
        return result to trips
    }

    suspend fun createTrip(trip: TripRecord): ServerHttpResult {
        val body = buildJsonObject {
            put("id", trip.id)
            put("startAt", trip.startAt)
            put("endAt", trip.endAt ?: trip.startAt)
            trip.distanceOverrideKm?.let { put("distanceKm", it) }
            trip.startOdo?.let { put("startOdo", it) }
            trip.endOdo?.let { put("endOdo", it) }
            trip.startSoc?.let { put("startSoc", it) }
            trip.endSoc?.let { put("endSoc", it) }
            trip.deltaSocOverride?.let { put("deltaSoc", it) }
            trip.startBatteryTemp?.let { put("startBatteryTemp", it) }
            trip.endBatteryTemp?.let { put("endBatteryTemp", it) }
            trip.startInteriorTemp?.let { put("startInteriorTemp", it) }
            trip.endInteriorTemp?.let { put("endInteriorTemp", it) }
            put("socSource", trip.socSource.name)
            put("note", trip.note)
            put("tags", trip.tags)
        }
        return post("/api/v1/trips", body.toString())
    }

    suspend fun patchTripNote(id: String, note: String, tags: String): ServerHttpResult {
        val body = buildJsonObject {
            put("note", note)
            put("tags", tags)
        }
        return patch("/api/v1/trips/$id", body.toString())
    }

    suspend fun deleteTrip(id: String): ServerHttpResult = delete("/api/v1/trips/$id")

    suspend fun exportCsv(): ServerHttpResult = getRaw("/api/v1/trips/export.csv")

    suspend fun fetchStats(): Pair<ServerHttpResult, ServerStatsPayload?> {
        val result = get("/api/v1/stats")
        return result to result.json?.let { ServerStatsPayload(it) }
    }

    suspend fun fetchDailyEnergy(): Pair<ServerHttpResult, List<OfficialDayDto>> {
        val result = get("/api/v1/daily-energy")
        val days = (result.json?.get("days") as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            OfficialDayDto(
                date = obj.str("date").orEmpty(),
                driverKwh = obj.doubleFlex("driverKwh"),
                acKwh = obj.doubleFlex("acKwh"),
                otherKwh = obj.doubleFlex("otherKwh"),
                mileageKm = obj.doubleFlex("mileageKm"),
                pending = obj["pending"].toString().contains("true"),
                note = obj.str("note").orEmpty(),
                syncedAt = obj.longFlex("syncedAt") ?: 0L,
            )
        }.orEmpty()
        return result to days
    }

    suspend fun runDiagnostics(): ServerHttpResult = post("/api/v1/diagnostics/run")
    suspend fun getDiagnostics(): ServerHttpResult = get("/api/v1/diagnostics")

    private suspend fun get(path: String, config: ServerConfig? = null, auth: Boolean = true) =
        execute("GET", path, null, config, auth)

    private suspend fun getRaw(path: String) = execute("GET", path, null, null, true, parseJson = false)

    private suspend fun post(path: String, body: String = "{}") =
        execute("POST", path, body, null, true)

    private suspend fun put(path: String, body: String) =
        execute("PUT", path, body, null, true)

    private suspend fun patch(path: String, body: String) =
        execute("PATCH", path, body, null, true)

    private suspend fun delete(path: String) =
        execute("DELETE", path, null, null, true)

    private suspend fun execute(
        method: String,
        path: String,
        body: String?,
        configOverride: ServerConfig?,
        auth: Boolean,
        parseJson: Boolean = true,
    ): ServerHttpResult = withContext(Dispatchers.IO) {
        val config = configOverride ?: configStore.current()
        val base = config.normalizedBaseUrl()
        if (base.isBlank()) {
            return@withContext ServerHttpResult(false, -1, "请先填写服务器地址", "")
        }
        if (auth && config.apiToken.isBlank()) {
            return@withContext ServerHttpResult(false, -1, "请先填写 Bearer Token", "")
        }
        val url = base + if (path.startsWith("/")) path else "/$path"
        val builder = Request.Builder().url(url)
        if (auth) builder.header("Authorization", "Bearer ${config.apiToken}")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                val media = "application/json; charset=utf-8".toMediaType()
                builder.method(method, (body ?: "{}").toRequestBody(media))
            }
        }
        try {
            http.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = if (parseJson) {
                    runCatching { AppJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                } else {
                    null
                }
                val message = json?.str("message")
                    ?: json?.str("detail")
                    ?: if (response.isSuccessful) "OK" else "HTTP ${response.code}"
                ServerHttpResult(response.isSuccessful, response.code, message, raw, json)
            }
        } catch (e: Exception) {
            ServerHttpResult(false, -1, "网络错误：${e.message ?: e.javaClass.simpleName}", "")
        }
    }
}

internal fun parseStatus(root: JsonObject): ServerStatusPayload {
    val collector = root.obj("collector")
    val settings = root.obj("settings")
    val session = root.obj("session")
    val vehicle = root.obj("vehicle")
    val open = root.obj("openTrip")
    val summary = listOfNotNull(
        session?.str("nickname")?.takeIf { it.isNotBlank() },
        session?.str("phoneMasked"),
        session?.str("carType"),
    ).joinToString(" · ")
    return ServerStatusPayload(
        version = root.str("version").orEmpty(),
        collectorEnabled = collector?.get("enabled").toString().contains("true"),
        collectorRunning = collector?.get("running").toString().contains("true"),
        rateLimited = collector?.get("rateLimited").toString().contains("true"),
        rateLimitMessage = collector?.str("rateLimitMessage"),
        statusMessage = collector?.str("statusMessage").orEmpty(),
        lastError = collector?.str("lastError").orEmpty(),
        lastPollAt = collector?.longFlex("lastPollAt") ?: 0L,
        lastFrameSts = collector?.longFlex("lastFrameSts") ?: 0L,
        snapshot = vehicle?.let(::parseSnapshot),
        openTrip = open?.let(::parseTrip),
        capacityKwh = settings?.doubleFlex("batteryUsableKwh") ?: 50.0,
        driveSec = settings?.intFlex("pollDrivingSeconds") ?: 6,
        parkSec = settings?.intFlex("pollParkedSeconds") ?: 60,
        parkTimeoutSec = settings?.intFlex("parkTimeoutSeconds") ?: 60,
        syncDays = settings?.intFlex("syncDays") ?: 7,
        sessionSummary = summary,
    )
}

internal fun parseSnapshot(obj: JsonObject): VehicleSnapshot = VehicleSnapshot(
    sts = obj.longFlex("sts") ?: 0L,
    collectTime = obj.longFlex("collectTime") ?: 0L,
    soc = obj.doubleFlex("soc"),
    socPrecise = obj["socPrecise"].toString().contains("true"),
    odoKm = obj.doubleFlex("odoKm"),
    speedKmh = obj.doubleFlex("speedKmh"),
    gear = obj.intFlex("gear"),
    batteryMinTemp = obj.doubleFlex("batteryMinTemp"),
    interiorTemp = obj.doubleFlex("interiorTemp"),
    chargeCode = obj.intFlex("chargeCode"),
    vehicleStateCode = obj.intFlex("vehicleStateCode"),
    rangeKm = obj.doubleFlex("rangeKm"),
    moving = obj["moving"].toString().contains("true"),
    charging = obj["charging"].toString().contains("true"),
    plugged = obj["plugged"].toString().contains("true"),
)

internal fun parseTrip(obj: JsonObject): TripRecord = TripRecord(
    id = obj.str("id").orEmpty(),
    source = runCatching { TripSource.valueOf(obj.str("source") ?: "AUTO") }.getOrDefault(TripSource.AUTO),
    status = runCatching { TripStatus.valueOf(obj.str("status") ?: "CLOSED") }.getOrDefault(TripStatus.CLOSED),
    startAt = obj.longFlex("startAt") ?: 0L,
    endAt = obj.longFlex("endAt"),
    startOdo = obj.doubleFlex("startOdo"),
    endOdo = obj.doubleFlex("endOdo"),
    startSoc = obj.doubleFlex("startSoc"),
    endSoc = obj.doubleFlex("endSoc"),
    startBatteryTemp = obj.doubleFlex("startBatteryTemp"),
    endBatteryTemp = obj.doubleFlex("endBatteryTemp"),
    startInteriorTemp = obj.doubleFlex("startInteriorTemp"),
    endInteriorTemp = obj.doubleFlex("endInteriorTemp"),
    socSource = runCatching { SocSource.valueOf(obj.str("socSource") ?: "UNKNOWN") }.getOrDefault(SocSource.UNKNOWN),
    note = obj.str("note").orEmpty(),
    tags = obj.str("tags").orEmpty(),
    closeReason = obj.str("closeReason"),
    sampleCount = obj.intFlex("sampleCount") ?: 0,
    distanceOverrideKm = obj.doubleFlex("distanceOverrideKm") ?: obj.doubleFlex("distanceKm"),
    deltaSocOverride = obj.doubleFlex("deltaSocOverride") ?: obj.doubleFlex("deltaSoc"),
    energyOverrideKwh = obj.doubleFlex("energyOverrideKwh"),
    parkedSince = obj.longFlex("parkedSince"),
    lastNewFrameWall = obj.longFlex("lastNewFrameWall"),
    lastSts = obj.longFlex("lastSts") ?: 0L,
    sawPrecise = obj["sawPrecise"].toString().contains("true"),
    sawInteger = obj["sawInteger"].toString().contains("true"),
)
