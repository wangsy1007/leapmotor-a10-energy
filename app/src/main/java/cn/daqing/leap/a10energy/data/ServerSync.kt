package cn.daqing.leap.a10energy.data

import cn.daqing.leap.a10energy.data.db.OfficialDayEntity
import cn.daqing.leap.a10energy.domain.ManualBuild
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.TripStatus
import cn.daqing.leap.a10energy.domain.exportCsv
import cn.daqing.leap.a10energy.protocol.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * 服务器同步模式：App 只读自建服务，并把行程缓存进 Room 以便断网查看。
 * 与本地直连互斥——进入本模式后应停止 PollService。
 */
class ServerSync(
    private val api: ServerApiClient,
    private val trips: TripRepository,
    private val settings: SettingsStore,
    private val serverConfig: ServerConfigStore,
    private val cache: LiveCache,
    private val diagnostics: DiagnosticStore,
) {
    private val mutex = Mutex()
    val serverStatus = MutableStateFlow<ServerStatusPayload?>(null)
    val modeHint = MutableStateFlow("")

    suspend fun refresh(): PollOutcome = mutex.withLock {
        cache.busy.value = true
        try {
            val (result, status) = api.fetchStatus()
            if (!result.ok || status == null) {
                val message = result.message.ifBlank { "无法连接服务器" }
                cache.message.value = message
                modeHint.value = message
                return@withLock PollOutcome(message)
            }
            applyStatus(status)
            pullTripsLocked()
            pullOfficialLocked()
            val message = status.rateLimitMessage
                ?: status.statusMessage.ifBlank { "已从服务器同步" }
            cache.message.value = message
            modeHint.value = if (serverConfig.current().sessionPushed) {
                "登录信息已同步至服务器，现由服务器 24 小时采集"
            } else {
                "服务器采集模式"
            }
            PollOutcome(message, moving = status.snapshot?.moving == true)
        } finally {
            cache.busy.value = false
        }
    }

    suspend fun pollNow(): PollOutcome = mutex.withLock {
        cache.busy.value = true
        try {
            val (result, status) = api.pollNow()
            if (status != null) applyStatus(status)
            pullTripsLocked()
            val message = if (result.ok) {
                result.message.takeIf { it.isNotBlank() && it != "OK" }
                    ?: status?.statusMessage
                    ?: "已请求服务器立即采集"
            } else {
                result.message
            }
            cache.message.value = message
            PollOutcome(message, moving = status?.snapshot?.moving == true)
        } finally {
            cache.busy.value = false
        }
    }

    suspend fun pushSession(
        sessions: EncryptedSessionRepository,
    ): String = mutex.withLock {
        val session = sessions.current()
        if (!session.loggedIn) return@withLock "请先在本机完成子账号短信登录"
        val sync = trips.loadTracker().first
        val result = api.pushSession(
            session = session,
            selectedVin = sync.selectedVin,
            carType = sync.carType,
            appRegion = sync.appRegion,
            appCenter = sync.appCenter,
        )
        if (!result.ok) return@withLock result.message
        settings.update { it.copy(autoRecord = false) }
        modeHint.value = "登录信息已同步至服务器，现由服务器 24 小时采集"
        cache.message.value = modeHint.value
        refreshAfterPush()
        modeHint.value
    }

    private suspend fun refreshAfterPush() {
        val (_, status) = api.fetchStatus()
        if (status != null) applyStatus(status)
        pullTripsLocked()
    }

    suspend fun setCollector(enabled: Boolean): String {
        val result = api.setCollectorEnabled(enabled)
        if (result.ok) refresh()
        return if (result.ok) {
            if (enabled) "已打开服务器采集" else "已关闭服务器采集"
        } else {
            result.message
        }
    }

    suspend fun manualStart(): PollOutcome {
        val result = api.manualStart()
        refresh()
        return PollOutcome(result.message)
    }

    suspend fun manualEnd(): PollOutcome {
        val result = api.manualStop()
        refresh()
        return PollOutcome(result.message)
    }

    suspend fun syncOfficialDays(): String {
        // 服务器模式下「同步官方能耗」= 触发 poll/now（服务端会顺带拉每日能耗）再拉取 daily-energy
        val (result, _) = api.pollNow()
        if (!result.ok) return result.message
        pullOfficialLocked()
        return "已请求服务器同步官方每日能耗"
    }

    suspend fun saveManual(built: ManualBuild): String {
        val trip = built.trip ?: return built.error ?: "无法保存"
        val result = api.createTrip(trip)
        if (!result.ok) return result.message
        trips.saveManual(built)
        pullTripsLocked()
        return "已同步到服务器"
    }

    suspend fun updateNote(id: String, note: String, tags: String): String {
        val result = api.patchTripNote(id, note, tags)
        if (!result.ok) return result.message
        trips.updateNote(id, note, tags)
        return "备注已同步到服务器"
    }

    suspend fun deleteTrip(id: String): String {
        val result = api.deleteTrip(id)
        if (!result.ok) return result.message
        trips.deleteTrip(id)
        return "已从服务器删除"
    }

    suspend fun exportCsvText(capacity: Double): String {
        val result = api.exportCsv()
        if (result.ok && result.raw.isNotBlank()) return result.raw
        return exportCsv(trips.getAllTrips(), capacity)
    }

    suspend fun runDiagnostics(): String {
        val result = api.runDiagnostics()
        val text = result.raw.ifBlank { result.message }
        diagnostics.write("server", text)
        return text
    }

    suspend fun pushLocalSettings(prefs: AppSettings, collectorEnabled: Boolean? = null): String {
        val result = api.updateSettings(
            capacityKwh = prefs.capacityKwh,
            driveSec = prefs.driveIntervalSec,
            parkSec = prefs.parkIntervalSec,
            parkTimeoutSec = prefs.parkTimeoutSec,
            syncDays = prefs.syncDays,
            collectorEnabled = collectorEnabled,
        )
        return if (result.ok) "已同步设置到服务器" else result.message
    }

    private suspend fun applyStatus(status: ServerStatusPayload) {
        serverStatus.value = status
        status.snapshot?.let {
            cache.snapshot.value = it
            val (sync, _) = trips.loadTracker()
            trips.saveSync(
                sync.copy(
                    lastCarJson = AppJson.encodeToString(it),
                    lastSuccessAt = System.currentTimeMillis(),
                    lastFrameSts = maxOf(sync.lastFrameSts, it.sts),
                    statusMessage = status.statusMessage,
                    lastError = status.lastError,
                    cooldownUntilMs = if (status.rateLimited) {
                        System.currentTimeMillis() + 60_000
                    } else {
                        sync.cooldownUntilMs
                    },
                ),
            )
        }
        settings.update {
            it.copy(
                capacityKwh = status.capacityKwh,
                driveIntervalSec = status.driveSec,
                parkIntervalSec = status.parkSec,
                parkTimeoutSec = status.parkTimeoutSec,
                syncDays = status.syncDays,
                autoRecord = false,
            )
        }
    }

    private suspend fun pullTripsLocked() {
        val (result, remote) = api.listTrips()
        if (result.ok) cacheTrips(remote)
    }

    private suspend fun cacheTrips(remote: List<TripRecord>) {
        remote.forEach { trip ->
            trips.saveManual(ManualBuild(trip = trip))
        }
        val openIds = remote.filter { it.status == TripStatus.OPEN }.map { it.id }.toSet()
        trips.getAllTrips()
            .filter { it.status == TripStatus.OPEN && it.id !in openIds }
            .forEach { stale -> trips.deleteTrip(stale.id) }
    }

    private suspend fun pullOfficialLocked() {
        val (result, days) = api.fetchDailyEnergy()
        if (!result.ok) return
        days.forEach { day ->
            trips.saveOfficial(
                OfficialDayEntity(
                    date = day.date,
                    driverKwh = day.driverKwh,
                    acKwh = day.acKwh,
                    otherKwh = day.otherKwh,
                    mileageKm = day.mileageKm,
                    pending = day.pending,
                    note = day.note,
                    rawEc = "",
                    syncedAt = day.syncedAt,
                ),
            )
        }
    }
}

/** 本地轮询与服务器模式互斥：服务器模式下禁止启动本地 PollService。 */
fun shouldRunLocalPolling(config: ServerConfig, autoRecord: Boolean): Boolean {
    if (config.mode == CollectMode.SERVER) return false
    return autoRecord
}
