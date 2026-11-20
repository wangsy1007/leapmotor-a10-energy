package cn.daqing.leap.a10energy.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    indices = [Index("startAt"), Index("status")],
)
data class TripEntity(
    @PrimaryKey val id: String,
    val source: String,
    val status: String,
    val startAt: Long,
    val endAt: Long?,
    val startOdo: Double?,
    val endOdo: Double?,
    val startSoc: Double?,
    val endSoc: Double?,
    val startBatteryTemp: Double?,
    val endBatteryTemp: Double?,
    val startInteriorTemp: Double?,
    val endInteriorTemp: Double?,
    val socSource: String,
    val note: String,
    val tags: String,
    val closeReason: String?,
    val sampleCount: Int,
    val distanceOverrideKm: Double?,
    val deltaSocOverride: Double?,
    val energyOverrideKwh: Double?,
    val parkedSince: Long?,
    val lastNewFrameWall: Long?,
    val lastSts: Long,
    val sawPrecise: Boolean,
    val sawInteger: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastFrameSts: Long = 0,
    val anchorAt: Long? = null,
    val anchorSts: Long = 0,
    val anchorOdo: Double? = null,
    val anchorSoc: Double? = null,
    val anchorBattery: Double? = null,
    val anchorInterior: Double? = null,
    val anchorSocPrecise: Boolean = false,
    val anchorCharging: Boolean = false,
    val backoffLevel: Int = 0,
    val cooldownUntilMs: Long = 0,
    val selectedVin: String = "",
    val carType: String = "",
    val nickName: String = "",
    val plate: String = "",
    val appRegion: String = "",
    val appCenter: String = "",
    val signalStrategy: String = "",
    val lastError: String = "",
    val lastSuccessAt: Long = 0,
    val lastCarJson: String = "",
    val statusMessage: String = "",
)

@Entity(tableName = "official_days")
data class OfficialDayEntity(
    @PrimaryKey val date: String,
    val driverKwh: Double?,
    val acKwh: Double?,
    val otherKwh: Double?,
    val mileageKm: Double?,
    val pending: Boolean,
    val note: String,
    val rawEc: String,
    val syncedAt: Long,
)
