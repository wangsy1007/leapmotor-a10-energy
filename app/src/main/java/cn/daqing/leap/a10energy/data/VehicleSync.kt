package cn.daqing.leap.a10energy.data

import cn.daqing.leap.a10energy.data.db.OfficialDayEntity
import cn.daqing.leap.a10energy.data.db.SyncStateEntity
import cn.daqing.leap.a10energy.domain.EngineConfig
import cn.daqing.leap.a10energy.domain.ManualCommand
import cn.daqing.leap.a10energy.domain.Shanghai
import cn.daqing.leap.a10energy.domain.TripEngine
import cn.daqing.leap.a10energy.domain.VehicleSnapshot
import cn.daqing.leap.a10energy.domain.backoffMessage
import cn.daqing.leap.a10energy.domain.dayWindowSeconds
import cn.daqing.leap.a10energy.domain.decideEc
import cn.daqing.leap.a10energy.domain.parseCarRoute
import cn.daqing.leap.a10energy.domain.parseLastWeekEc
import cn.daqing.leap.a10energy.domain.parseMileage
import cn.daqing.leap.a10energy.domain.parseSignalResponse
import cn.daqing.leap.a10energy.domain.parseVehicles
import cn.daqing.leap.a10energy.domain.registerRateLimit
import cn.daqing.leap.a10energy.domain.validateAppRegion
import cn.daqing.leap.a10energy.protocol.AppJson
import cn.daqing.leap.a10energy.protocol.Endpoints
import cn.daqing.leap.a10energy.protocol.LeapClient
import cn.daqing.leap.a10energy.protocol.LeapHttpResult
import cn.daqing.leap.a10energy.protocol.friendlyLeapMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate

class LiveCache {
    val snapshot = MutableStateFlow<VehicleSnapshot?>(null)
    val message = MutableStateFlow("还没有车况")
    val vehicles = MutableStateFlow<List<cn.daqing.leap.a10energy.domain.VehicleSummary>>(emptyList())
    val busy = MutableStateFlow(false)
}

data class PollOutcome(
    val message: String,
    val moving: Boolean = false,
    val waitMs: Long? = null,
    val rateLimited: Boolean = false,
)

class VehicleSync(
    private val client: LeapClient,
    private val trips: TripRepository,
    private val settings: SettingsStore,
    private val diagnostics: DiagnosticStore,
    private val cache: LiveCache,
    private val engine: TripEngine = TripEngine(),
    private val gatewayBase: String = Endpoints().gatewayBase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    suspend fun refresh(): PollOutcome = pollOnce()

    suspend fun pollOnce(): PollOutcome = mutex.withLock {
        cache.busy.value = true
        try {
            pollLocked()
        } finally {
            cache.busy.value = false
        }
    }

    suspend fun manualStart(): PollOutcome = mutex.withLock { manual(ManualCommand.START) }

    suspend fun manualEnd(): PollOutcome = mutex.withLock { manual(ManualCommand.END) }

    suspend fun syncOfficialDays(): String = mutex.withLock {
        val now = clock()
        val (sync, _) = trips.loadTracker()
        val blocked = backoffMessage(
            cn.daqing.leap.a10energy.domain.BackoffState(sync.backoffLevel, sync.cooldownUntilMs),
            now,
        )
        if (blocked != null) return@withLock blocked
        if (sync.selectedVin.isBlank()) return@withLock "还没有车辆，请先在车况页刷新"
        val prefs = settings.current()
        client.hintVin = sync.selectedVin
        client.hintCarType = sync.carType
        val zoneNow = Instant.ofEpochMilli(now).atZone(Shanghai.zone).toLocalDate()
        val beginDay = zoneNow.minusDays((prefs.syncDays - 1).toLong())
        val beginMs = beginDay.atStartOfDay(Shanghai.zone).toInstant().toEpochMilli()
        val mileage = client.mileageDetail(sync.selectedVin, beginMs, now)
        diagnostics.write("mileage", mileage.raw.ifBlank { mileage.message })
        if (mileage.rateLimited) {
            noteRateLimit(sync, now, 15 * 60 * 1000)
            return@withLock friendlyLeapMessage(mileage)
        }
        val mileageDays = if (mileage.ok) parseMileage(mileage.raw).days.associate { it.day to it.km } else emptyMap()
        var done = 0
        var stopped = ""
        for (offset in 0 until prefs.syncDays) {
            val date = beginDay.plusDays(offset.toLong())
            val (beginSec, endSec) = dayWindowSeconds(date, now / 1000)
            if (endSec < beginSec) continue
            val ec = client.getLastWeekEc(sync.selectedVin, beginSec, endSec)
            diagnostics.write("energy", ec.raw.ifBlank { ec.message })
            if (ec.rateLimited) {
                noteRateLimit(sync, clock(), 15 * 60 * 1000)
                stopped = friendlyLeapMessage(ec)
                break
            }
            val parsed = if (ec.raw.isNotBlank()) parseLastWeekEc(ec.raw) else parseLastWeekEc("""{"code":${ec.code ?: -1},"message":"${ec.message}"}""")
            val decision = decideEc(parsed.code ?: ec.code, parsed.driver, parsed.ac, parsed.other, date == zoneNow)
            val dayKey = date.toString()
            trips.saveOfficial(
                OfficialDayEntity(
                    date = dayKey,
                    driverKwh = decision.driver,
                    acKwh = decision.ac,
                    otherKwh = decision.other,
                    mileageKm = mileageDays[dayKey],
                    pending = decision.pending,
                    note = decision.note,
                    rawEc = ec.raw.take(4000),
                    syncedAt = clock(),
                ),
            )
            done += 1
        }
        if (stopped.isNotBlank()) "已同步 $done 天，随后触发风控。$stopped" else "已同步近 ${prefs.syncDays} 天官方能耗"
    }

    private suspend fun manual(command: ManualCommand): PollOutcome {
        val now = clock()
        val (sync, tracker) = trips.loadTracker()
        val snap = cache.snapshot.value ?: decodeSnapshot(sync.lastCarJson)
        if (snap == null) return PollOutcome("没有车况，请先刷新")
        val result = engine.reduce(
            state = tracker,
            snap = snap,
            now = now,
            manual = command,
            config = engineConfig(),
        )
        trips.apply(sync.copy(statusMessage = result.messages.joinToString(" ")), result, now)
        val message = result.messages.joinToString(" ").ifBlank { "已处理" }
        cache.message.value = message
        return PollOutcome(message, moving = snap.moving)
    }

    private suspend fun pollLocked(): PollOutcome {
        val now = clock()
        val (sync0, tracker) = trips.loadTracker()
        var sync = sync0
        val blocked = backoffMessage(
            cn.daqing.leap.a10energy.domain.BackoffState(sync.backoffLevel, sync.cooldownUntilMs),
            now,
        )
        if (blocked != null) {
            cache.message.value = blocked
            trips.saveSync(sync.copy(statusMessage = blocked))
            return PollOutcome(blocked, waitMs = (sync.cooldownUntilMs - now).coerceAtLeast(5_000), rateLimited = true)
        }
        client.hintVin = sync.selectedVin
        client.hintCarType = sync.carType
        if (sync.selectedVin.isBlank()) {
            val listed = loadVehicles(sync)
            sync = listed.first
            val blockedOutcome = listed.second
            if (blockedOutcome != null) return blockedOutcome
        }
        if (sync.selectedVin.isBlank()) {
            val message = "账号下没有车辆。请确认子账号已经接受车辆共享。"
            cache.message.value = message
            trips.saveSync(sync.copy(lastError = message, statusMessage = message))
            return PollOutcome(message)
        }
        client.hintVin = sync.selectedVin
        client.hintCarType = sync.carType
        if (sync.appRegion.isBlank() || validateAppRegion(sync.appRegion) != null) {
            val routed = refreshRoute(sync)
            sync = routed
        }
        val fetched = fetchSignal(sync)
        sync = fetched.sync
        val http = fetched.http
        if (http == null) return PollOutcome(sync.statusMessage.ifBlank { "没有拿到车况" })
        if (http.rateLimited) {
            noteRateLimit(sync, now, 15 * 60 * 1000)
            val latest = trips.loadTracker().first
            val message = backoffMessage(
                cn.daqing.leap.a10energy.domain.BackoffState(latest.backoffLevel, latest.cooldownUntilMs),
                clock(),
            ) ?: friendlyLeapMessage(http)
            cache.message.value = message
            return PollOutcome(message, waitMs = (latest.cooldownUntilMs - clock()).coerceAtLeast(5_000), rateLimited = true)
        }
        diagnostics.write("signal", http.raw.ifBlank { http.message })
        if (!http.ok && http.code != null && http.code != 0) {
            val message = friendlyLeapMessage(http)
            cache.message.value = message
            trips.saveSync(sync.copy(lastError = message, statusMessage = message))
            return PollOutcome(message)
        }
        val parsed = parseSignalResponse(http.raw.ifBlank { "{}" })
        val snap = parsed.snapshot
        if (snap == null) {
            val message = parsed.message.ifBlank { "车况里没有 signalMap。可到诊断页查看原始 JSON，并确认 appRegion。" }
            cache.message.value = message
            trips.saveSync(sync.copy(lastError = message, statusMessage = message, signalStrategy = ""))
            return PollOutcome(message)
        }
        cache.snapshot.value = snap
        val config = engineConfig()
        val reduced = engine.reduce(tracker, snap, now, ManualCommand.NONE, config)
        val stale = if (snap.sts > 0 && now - snap.sts > 10 * 60 * 1000) {
            "车端 sts 已超过 10 分钟，车辆可能在休眠，这不是新的行驶帧。"
        } else {
            ""
        }
        val message = (reduced.messages + stale).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "车况已更新 ${Shanghai.clock(now)}" }
        val json = AppJson.encodeToString(snap)
        trips.apply(
            sync.copy(
                lastError = "",
                lastSuccessAt = now,
                lastCarJson = json,
                statusMessage = message,
                signalStrategy = fetched.strategy,
            ),
            reduced,
            now,
        )
        cache.message.value = message
        return PollOutcome(message, moving = snap.moving)
    }

    private suspend fun loadVehicles(sync: SyncStateEntity): Pair<SyncStateEntity, PollOutcome?> {
        val result = client.vehicleList()
        diagnostics.write("vehicles", result.raw.ifBlank { result.message })
        if (result.rateLimited) {
            noteRateLimit(sync, clock(), 15 * 60 * 1000)
            val message = friendlyLeapMessage(result)
            cache.message.value = message
            return sync to PollOutcome(message, rateLimited = true, waitMs = 15 * 60 * 1000)
        }
        if (!result.ok) {
            val message = friendlyLeapMessage(result)
            cache.message.value = message
            trips.saveSync(sync.copy(lastError = message, statusMessage = message))
            return sync to PollOutcome(message)
        }
        val vehicles = parseVehicles(result.raw)
        cache.vehicles.value = vehicles
        val chosen = vehicles.firstOrNull { it.source == "shared" } ?: vehicles.firstOrNull()
        if (chosen == null) return sync to null
        val next = sync.copy(
            selectedVin = chosen.vin,
            carType = chosen.carType,
            nickName = chosen.nickName,
            plate = chosen.plateNumber,
        )
        trips.saveSync(next)
        return next to null
    }

    private suspend fun refreshRoute(sync: SyncStateEntity): SyncStateEntity {
        val result = client.carRoute(sync.selectedVin)
        diagnostics.write("route", result.raw.ifBlank { result.message })
        if (result.rateLimited) return sync
        val route = if (result.raw.isNotBlank()) parseCarRoute(result.raw) else return sync
        val region = if (route.regionOk) route.appRegion else ""
        val next = sync.copy(appRegion = region, appCenter = route.appCenter)
        trips.saveSync(next)
        return next
    }

    private suspend fun fetchSignal(sync: SyncStateEntity): SignalFetch {
        val region = sync.appRegion.takeIf { validateAppRegion(it) == null }.orEmpty()
        val attempts = signalAttempts(region, sync.signalStrategy, gatewayBase)
        var last: LeapHttpResult? = null
        var lastStrategy = sync.signalStrategy
        for (attempt in attempts) {
            val result = client.querySignal(sync.selectedVin, attempt.base, attempt.json)
            last = result
            lastStrategy = attempt.name
            if (result.rateLimited) return SignalFetch(sync, result, attempt.name)
            val parsed = if (result.ok && result.raw.isNotBlank()) parseSignalResponse(result.raw) else null
            if (parsed?.snapshot != null && (parsed.snapshot.soc != null || parsed.snapshot.odoKm != null || parsed.keys.isNotEmpty())) {
                return SignalFetch(sync, result, attempt.name)
            }
            if (result.signatureError || result.authError) {
                // client 已经重交换并重试过一次，换下一种读法
                continue
            }
        }
        return SignalFetch(sync, last, lastStrategy)
    }

    private suspend fun noteRateLimit(sync: SyncStateEntity, now: Long, minimumMs: Long) {
        val next = registerRateLimit(
            cn.daqing.leap.a10energy.domain.BackoffState(sync.backoffLevel, sync.cooldownUntilMs),
            now,
            minimumMs,
        )
        val message = backoffMessage(next, now).orEmpty()
        trips.saveSync(
            sync.copy(
                backoffLevel = next.level,
                cooldownUntilMs = next.cooldownUntilMs,
                lastError = message,
                statusMessage = message,
            ),
        )
        cache.message.value = message
    }

    private suspend fun engineConfig(): EngineConfig {
        val prefs = settings.current()
        return EngineConfig(parkTimeoutMs = prefs.parkTimeoutSec * 1000L)
    }

    suspend fun captureVehicles(): String = mutex.withLock {
        val result = client.vehicleList()
        val text = result.raw.ifBlank { result.message }
        diagnostics.write("vehicles", text)
        if (result.ok) cache.vehicles.value = parseVehicles(result.raw)
        diagnostics.read("vehicles").ifBlank { friendlyLeapMessage(result) }
    }

    suspend fun captureRoute(): String = mutex.withLock {
        val (sync, _) = trips.loadTracker()
        if (sync.selectedVin.isBlank()) return@withLock "还没有 VIN。请先拉取车辆列表。"
        client.hintVin = sync.selectedVin
        client.hintCarType = sync.carType
        val result = client.carRoute(sync.selectedVin)
        diagnostics.write("route", result.raw.ifBlank { result.message })
        if (result.raw.isNotBlank()) {
            val route = parseCarRoute(result.raw)
            if (route.regionOk) trips.saveSync(sync.copy(appRegion = route.appRegion, appCenter = route.appCenter))
        }
        diagnostics.read("route").ifBlank { friendlyLeapMessage(result) }
    }

    suspend fun captureSignalOnly(): String = mutex.withLock {
        val (sync, _) = trips.loadTracker()
        if (sync.selectedVin.isBlank()) return@withLock "还没有 VIN。请先拉取车辆列表。"
        client.hintVin = sync.selectedVin
        client.hintCarType = sync.carType
        val fetched = fetchSignal(sync)
        val http = fetched.http
        val text = http?.raw?.ifBlank { http.message } ?: "没有响应"
        diagnostics.write("signal", text)
        if (http != null && http.ok) {
            val snap = parseSignalResponse(http.raw).snapshot
            if (snap != null) {
                cache.snapshot.value = snap
                trips.saveSync(sync.copy(lastCarJson = AppJson.encodeToString(snap), signalStrategy = fetched.strategy, lastSuccessAt = clock()))
            }
        }
        diagnostics.read("signal").ifBlank { text }
    }

    suspend fun captureTodayEnergy(): String = mutex.withLock {
        val (sync, _) = trips.loadTracker()
        if (sync.selectedVin.isBlank()) return@withLock "还没有 VIN。请先拉取车辆列表。"
        client.hintVin = sync.selectedVin
        client.hintCarType = sync.carType
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(Shanghai.zone).toLocalDate()
        val (begin, end) = dayWindowSeconds(today, now / 1000)
        val result = client.getLastWeekEc(sync.selectedVin, begin, end)
        diagnostics.write("energy", result.raw.ifBlank { result.message })
        diagnostics.read("energy").ifBlank { friendlyLeapMessage(result) }
    }

    suspend fun selectVehicle(vin: String) {
        val vehicle = cache.vehicles.value.firstOrNull { it.vin == vin } ?: return
        val (sync, _) = trips.loadTracker()
        trips.saveSync(
            sync.copy(
                selectedVin = vehicle.vin,
                carType = vehicle.carType,
                nickName = vehicle.nickName,
                plate = vehicle.plateNumber,
                appRegion = "",
                signalStrategy = "",
            ),
        )
    }
}

data class SignalAttempt(val name: String, val base: String, val json: Boolean)

fun signalAttempts(region: String, preferred: String, gateway: String): List<SignalAttempt> {
    val all = mutableListOf<SignalAttempt>()
    if (region.isNotBlank()) {
        all += SignalAttempt("REGION_FORM", region, false)
        all += SignalAttempt("REGION_JSON", region, true)
    }
    all += SignalAttempt("GATEWAY_FORM", gateway, false)
    val preferredItem = all.firstOrNull { it.name == preferred }
    return if (preferredItem == null) all else listOf(preferredItem) + all.filter { it.name != preferred }
}

private data class SignalFetch(val sync: SyncStateEntity, val http: LeapHttpResult?, val strategy: String)

private fun decodeSnapshot(json: String): VehicleSnapshot? {
    if (json.isBlank()) return null
    return try {
        AppJson.decodeFromString(VehicleSnapshot.serializer(), json)
    } catch (_: Exception) {
        null
    }
}
