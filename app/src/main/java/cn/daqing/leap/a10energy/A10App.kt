package cn.daqing.leap.a10energy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import cn.daqing.leap.a10energy.data.DiagnosticStore
import cn.daqing.leap.a10energy.data.EncryptedSessionRepository
import cn.daqing.leap.a10energy.data.LiveCache
import cn.daqing.leap.a10energy.data.SettingsStore
import cn.daqing.leap.a10energy.data.TripRepository
import cn.daqing.leap.a10energy.data.VehicleSync
import cn.daqing.leap.a10energy.data.db.AppDatabase
import cn.daqing.leap.a10energy.protocol.LeapClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class A10App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val db = AppDatabase.create(this)
        val sessions = EncryptedSessionRepository(this)
        val settings = SettingsStore(this)
        val diagnostics = DiagnosticStore(this)
        val cache = LiveCache()
        val trips = TripRepository(db)
        val client = LeapClient(sessions)
        val sync = VehicleSync(client, trips, settings, diagnostics, cache)
        graph = AppGraph(db, sessions, settings, diagnostics, cache, trips, client, sync)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            client.ensureDeviceId()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "行程自动记录", NotificationManager.IMPORTANCE_LOW)
        channel.description = "后台轮询车况并记录分段行程"
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "a10_poll"
    }
}

class AppGraph(
    val database: AppDatabase,
    val sessions: EncryptedSessionRepository,
    val settings: SettingsStore,
    val diagnostics: DiagnosticStore,
    val cache: LiveCache,
    val trips: TripRepository,
    val client: LeapClient,
    val sync: VehicleSync,
)
