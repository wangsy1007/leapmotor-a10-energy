package cn.daqing.leap.a10energy.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.daqing.leap.a10energy.A10App
import cn.daqing.leap.a10energy.MainActivity
import cn.daqing.leap.a10energy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val started = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            started.set(false)
            loop?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("正在记录行程"))
        if (started.compareAndSet(false, true)) {
            loop = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private suspend fun runLoop() {
        val app = application as A10App
        while (scope.isActive && started.get()) {
            val prefs = app.graph.settings.current()
            if (!prefs.autoRecord) break
            val outcome = app.graph.sync.pollOnce()
            val base = (if (outcome.moving) prefs.driveIntervalSec else prefs.parkIntervalSec) * 1000L
            val wait = (outcome.waitMs ?: base).coerceIn(3_000L, 6 * 3_600_000L)
            val title = when {
                outcome.rateLimited -> "风控冷却中"
                outcome.moving -> "行驶中记录"
                else -> "停车监测"
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification("$title · ${outcome.message}"))
            delay(wait)
        }
        started.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): android.app.Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, A10App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("零跑 A10 电耗记录")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        started.set(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "cn.daqing.leap.a10energy.STOP"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PollService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PollService::class.java).setAction(ACTION_STOP))
        }
    }
}
