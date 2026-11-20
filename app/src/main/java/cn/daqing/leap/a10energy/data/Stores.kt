package cn.daqing.leap.a10energy.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import cn.daqing.leap.a10energy.domain.redactSecrets
import cn.daqing.leap.a10energy.protocol.LeapSession
import cn.daqing.leap.a10energy.protocol.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val Context.settingsDataStore by preferencesDataStore(name = "a10_settings")

data class AppSettings(
    val autoRecord: Boolean = false,
    val driveIntervalSec: Int = 6,
    val parkIntervalSec: Int = 60,
    val parkTimeoutSec: Int = 60,
    val capacityKwh: Double = 50.0,
    val syncDays: Int = 7,
)

class SettingsStore(private val context: Context) {
    val flow: Flow<AppSettings> = context.settingsDataStore.data
        .map { it.toSettings() }
        .catch { emit(AppSettings()) }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        val next = block(current()).sanitized()
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.auto] = next.autoRecord
            prefs[Keys.drive] = next.driveIntervalSec
            prefs[Keys.park] = next.parkIntervalSec
            prefs[Keys.parkTimeout] = next.parkTimeoutSec
            prefs[Keys.capacity] = next.capacityKwh.toString()
            prefs[Keys.syncDays] = next.syncDays
        }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        autoRecord = this[Keys.auto] ?: false,
        driveIntervalSec = this[Keys.drive] ?: 6,
        parkIntervalSec = this[Keys.park] ?: 60,
        parkTimeoutSec = this[Keys.parkTimeout] ?: 60,
        capacityKwh = this[Keys.capacity]?.toDoubleOrNull() ?: 50.0,
        syncDays = this[Keys.syncDays] ?: 7,
    ).sanitized()

    private object Keys {
        val auto = booleanPreferencesKey("auto_record")
        val drive = intPreferencesKey("drive_sec")
        val park = intPreferencesKey("park_sec")
        val parkTimeout = intPreferencesKey("park_timeout_sec")
        val capacity = stringPreferencesKey("capacity_kwh")
        val syncDays = intPreferencesKey("sync_days")
    }
}

fun AppSettings.sanitized(): AppSettings = copy(
    driveIntervalSec = driveIntervalSec.coerceIn(3, 120),
    parkIntervalSec = parkIntervalSec.coerceIn(15, 600),
    parkTimeoutSec = parkTimeoutSec.coerceIn(30, 600),
    capacityKwh = capacityKwh.coerceIn(5.0, 200.0),
    syncDays = syncDays.coerceIn(1, 14),
)

class EncryptedSessionRepository(context: Context) : SessionRepository {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "leap_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun current(): LeapSession = withContext(Dispatchers.IO) {
        mutex.withLock { read() }
    }

    override suspend fun save(session: LeapSession) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit()
                .putString(DEVICE, session.deviceId)
                .putString(PHONE, session.phone)
                .putString(NICK, session.nickname)
                .putString(ACCOUNT, session.accountToken)
                .putString(REFRESH, session.refreshToken)
                .putString(ACCOUNT_ID, session.accountId)
                .putLong(ACCOUNT_EXP, session.accountExpiresAtSec)
                .putString(CAR, session.carToken)
                .putString(CAR_REFRESH, session.carRefreshToken)
                .putLong(CAR_EXP, session.carExpiresAtMs)
                .putString(HKDF, session.hkdfKeyHex)
                .putString(USER, session.userId)
                .putString(R2, session.signR2)
                .putString(R3, session.signR3)
                .putBoolean(CAR_REFRESH_BROKEN, session.carRefreshBroken)
                .apply()
        }
    }

    private fun read(): LeapSession = LeapSession(
        deviceId = prefs.getString(DEVICE, "").orEmpty(),
        phone = prefs.getString(PHONE, "").orEmpty(),
        nickname = prefs.getString(NICK, "").orEmpty(),
        accountToken = prefs.getString(ACCOUNT, "").orEmpty(),
        refreshToken = prefs.getString(REFRESH, "").orEmpty(),
        accountId = prefs.getString(ACCOUNT_ID, "").orEmpty(),
        accountExpiresAtSec = prefs.getLong(ACCOUNT_EXP, 0L),
        carToken = prefs.getString(CAR, "").orEmpty(),
        carRefreshToken = prefs.getString(CAR_REFRESH, "").orEmpty(),
        carExpiresAtMs = prefs.getLong(CAR_EXP, 0L),
        hkdfKeyHex = prefs.getString(HKDF, "").orEmpty(),
        userId = prefs.getString(USER, "").orEmpty(),
        signR2 = prefs.getString(R2, "").orEmpty(),
        signR3 = prefs.getString(R3, "").orEmpty(),
        carRefreshBroken = prefs.getBoolean(CAR_REFRESH_BROKEN, false),
    )

    private companion object {
        const val DEVICE = "device_id"
        const val PHONE = "phone"
        const val NICK = "nickname"
        const val ACCOUNT = "account_token"
        const val REFRESH = "refresh_token"
        const val ACCOUNT_ID = "account_id"
        const val ACCOUNT_EXP = "account_exp"
        const val CAR = "car_token"
        const val CAR_REFRESH = "car_refresh"
        const val CAR_EXP = "car_exp"
        const val HKDF = "hkdf"
        const val USER = "user_id"
        const val R2 = "sign_r2"
        const val R3 = "sign_r3"
        const val CAR_REFRESH_BROKEN = "car_refresh_broken"
    }
}

class DiagnosticStore(context: Context) {
    private val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }

    fun write(name: String, content: String) {
        File(dir, "$name.json").writeText(redactSecrets(content))
    }

    fun read(name: String): String = File(dir, "$name.json").takeIf { it.exists() }?.readText().orEmpty()

    fun readAll(): String = listOf("vehicles", "route", "signal", "energy", "mileage")
        .joinToString("\n\n") { name ->
            val body = read(name).ifBlank { "（还没有）" }
            "===== $name =====\n$body"
        }
}
