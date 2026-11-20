package cn.daqing.leap.a10energy.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.serverDataStore by preferencesDataStore(name = "a10_server_mode")

enum class CollectMode {
    LOCAL,
    SERVER,
}

private val USER_ID_REGEX = Regex("^[A-Za-z0-9._-]{2,64}$")

fun isValidCloudUserId(raw: String): Boolean = USER_ID_REGEX.matches(raw.trim())

data class ServerConfig(
    val mode: CollectMode = CollectMode.LOCAL,
    val baseUrl: String = "",
    /** 云端数据绑定 ID（必填，服务器模式）。 */
    val userId: String = "",
    /** 可选；仅当自建服务显式配置了 API_TOKEN 时才需要。默认不发送。 */
    val apiToken: String = "",
    val lastTestOk: Boolean = false,
    val lastTestMessage: String = "",
    val sessionPushed: Boolean = false,
) {
    val serverReady: Boolean
        get() = mode == CollectMode.SERVER &&
            normalizedBaseUrl().isNotBlank() &&
            isValidCloudUserId(userId)

    fun normalizedBaseUrl(): String = normalizeServerBaseUrl(baseUrl)
}

fun normalizeServerBaseUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isBlank()) return ""
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
        url = "http://$url"
    }
    return url.trimEnd('/')
}

class ServerConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "leap_server_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val flow: Flow<ServerConfig> = appContext.serverDataStore.data
        .map { prefsRow ->
            val modeName = prefsRow[Keys.mode] ?: CollectMode.LOCAL.name
            ServerConfig(
                mode = runCatching { CollectMode.valueOf(modeName) }.getOrDefault(CollectMode.LOCAL),
                baseUrl = prefsRow[Keys.baseUrl].orEmpty(),
                userId = prefsRow[Keys.userId].orEmpty().ifBlank { readSecret(USER_ID) },
                apiToken = readSecret(TOKEN),
                lastTestOk = prefsRow[Keys.lastTestOk] ?: false,
                lastTestMessage = prefsRow[Keys.lastTestMessage].orEmpty(),
                sessionPushed = prefsRow[Keys.sessionPushed] ?: false,
            )
        }
        .catch { emit(ServerConfig()) }

    suspend fun current(): ServerConfig = flow.first()

    suspend fun update(block: (ServerConfig) -> ServerConfig) {
        val next = block(current())
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prefs.edit()
                    .putString(TOKEN, next.apiToken)
                    .putString(USER_ID, next.userId.trim())
                    .apply()
            }
        }
        appContext.serverDataStore.edit { row ->
            row[Keys.mode] = next.mode.name
            row[Keys.baseUrl] = next.baseUrl.trim()
            row[Keys.userId] = next.userId.trim()
            row[Keys.lastTestOk] = next.lastTestOk
            row[Keys.lastTestMessage] = next.lastTestMessage
            row[Keys.sessionPushed] = next.sessionPushed
        }
    }

    private fun readSecret(key: String): String = prefs.getString(key, "").orEmpty()

    private object Keys {
        val mode = stringPreferencesKey("collect_mode")
        val baseUrl = stringPreferencesKey("server_base_url")
        val userId = stringPreferencesKey("cloud_user_id")
        val lastTestOk = booleanPreferencesKey("last_test_ok")
        val lastTestMessage = stringPreferencesKey("last_test_message")
        val sessionPushed = booleanPreferencesKey("session_pushed")
    }

    private companion object {
        const val TOKEN = "api_token"
        const val USER_ID = "cloud_user_id"
    }
}
