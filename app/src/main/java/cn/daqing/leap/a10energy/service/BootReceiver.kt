package cn.daqing.leap.a10energy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.daqing.leap.a10energy.data.EncryptedSessionRepository
import cn.daqing.leap.a10energy.data.ServerConfigStore
import cn.daqing.leap.a10energy.data.SettingsStore
import cn.daqing.leap.a10energy.data.shouldRunLocalPolling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(context)
                val session = EncryptedSessionRepository(context)
                val server = ServerConfigStore(context)
                if (
                    shouldRunLocalPolling(server.current(), settings.current().autoRecord) &&
                    session.current().refreshToken.isNotBlank()
                ) {
                    PollService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
