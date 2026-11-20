package cn.daqing.leap.a10energy.domain

import kotlinx.serialization.Serializable

@Serializable
data class VehicleSnapshot(
    val sts: Long = 0,
    val collectTime: Long = 0,
    val soc: Double? = null,
    val socPrecise: Boolean = false,
    val odoKm: Double? = null,
    val speedKmh: Double? = null,
    val gear: Int? = null,
    val batteryMinTemp: Double? = null,
    val interiorTemp: Double? = null,
    val chargeCode: Int? = null,
    val vehicleStateCode: Int? = null,
    val rangeKm: Double? = null,
    val moving: Boolean = false,
    val charging: Boolean = false,
    val plugged: Boolean = false,
) {
    fun statusLabel(): String = when {
        charging -> "充电中"
        plugged -> "已插枪"
        moving -> "行驶中"
        gear == 0 || gear == 2 -> "驻车"
        else -> "静止或未知"
    }
}

enum class TripSource { AUTO, MANUAL, GAP }

enum class TripStatus { OPEN, CLOSED }

enum class SocSource { PRECISE, INTEGER, MIXED, UNKNOWN }

enum class ManualCommand { NONE, START, END }

data class TripRecord(
    val id: String,
    val source: TripSource,
    val status: TripStatus,
    val startAt: Long,
    val endAt: Long? = null,
    val startOdo: Double? = null,
    val endOdo: Double? = null,
    val startSoc: Double? = null,
    val endSoc: Double? = null,
    val startBatteryTemp: Double? = null,
    val endBatteryTemp: Double? = null,
    val startInteriorTemp: Double? = null,
    val endInteriorTemp: Double? = null,
    val socSource: SocSource = SocSource.UNKNOWN,
    val note: String = "",
    val tags: String = "",
    val closeReason: String? = null,
    val sampleCount: Int = 0,
    val distanceOverrideKm: Double? = null,
    val deltaSocOverride: Double? = null,
    val energyOverrideKwh: Double? = null,
    val parkedSince: Long? = null,
    val lastNewFrameWall: Long? = null,
    val lastSts: Long = 0,
    val sawPrecise: Boolean = false,
    val sawInteger: Boolean = false,
)

data class Anchor(
    val at: Long? = null,
    val sts: Long = 0,
    val odo: Double? = null,
    val soc: Double? = null,
    val batteryTemp: Double? = null,
    val interiorTemp: Double? = null,
    val socPrecise: Boolean = false,
    val charging: Boolean = false,
)

data class TrackerState(
    val open: TripRecord? = null,
    val lastFrameSts: Long = 0,
    val anchor: Anchor = Anchor(),
)

sealed class TripEffect {
    data class Save(val trip: TripRecord) : TripEffect()
    data class Delete(val id: String) : TripEffect()
}

data class ReduceResult(
    val state: TrackerState,
    val effects: List<TripEffect>,
    val messages: List<String>,
)

data class EngineConfig(
    val parkTimeoutMs: Long = 60_000,
    val lostContactMs: Long = 30 * 60 * 1000,
    val minKeepKm: Double = 0.2,
    val gapFillKm: Double = 1.0,
    val maxJumpKm: Double = 1500.0,
)

data class VehicleSummary(
    val vin: String,
    val carId: String = "",
    val carType: String = "",
    val nickName: String = "",
    val plateNumber: String = "",
    val seriesName: String = "",
    val source: String = "",
    val rightList: String = "",
) {
    fun displayName(): String = nickName.ifBlank { seriesName.ifBlank { carType.ifBlank { vin } } }
}

data class RouteInfo(
    val code: Int?,
    val message: String,
    val appRegion: String,
    val appCenter: String,
    val regionOk: Boolean,
    val regionNote: String,
)

data class SignalParse(
    val code: Int?,
    val message: String,
    val snapshot: VehicleSnapshot?,
    val keys: Set<String>,
)

data class EcParse(
    val code: Int?,
    val message: String,
    val driver: Double?,
    val ac: Double?,
    val other: Double?,
)

data class DailyEcDecision(
    val driver: Double?,
    val ac: Double?,
    val other: Double?,
    val pending: Boolean,
    val note: String,
)

data class MileageDay(val day: String, val km: Double?)

data class MileageParse(
    val code: Int?,
    val message: String,
    val totalMileage: Double?,
    val days: List<MileageDay>,
)

data class CheckItem(val id: String, val label: String, val present: Boolean, val detail: String)

data class ManualInput(
    val id: String? = null,
    val startAt: Long,
    val endAt: Long,
    val distanceKm: Double? = null,
    val startOdo: Double? = null,
    val endOdo: Double? = null,
    val startSoc: Double? = null,
    val endSoc: Double? = null,
    val deltaSoc: Double? = null,
    val startBattery: Double? = null,
    val endBattery: Double? = null,
    val startInterior: Double? = null,
    val endInterior: Double? = null,
    val socSource: SocSource = SocSource.UNKNOWN,
    val note: String = "",
    val tags: String = "",
)

data class ManualBuild(val error: String? = null, val trip: TripRecord? = null)
