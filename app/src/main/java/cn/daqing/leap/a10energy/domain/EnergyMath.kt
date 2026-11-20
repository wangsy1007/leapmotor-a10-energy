package cn.daqing.leap.a10energy.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun flagsToSource(precise: Boolean, integer: Boolean): SocSource = when {
    precise && integer -> SocSource.MIXED
    precise -> SocSource.PRECISE
    integer -> SocSource.INTEGER
    else -> SocSource.UNKNOWN
}

fun TripRecord.distanceKm(): Double? {
    distanceOverrideKm?.let { return if (it >= 0.0 && it < 1500.0) it else null }
    val start = startOdo ?: return null
    val end = endOdo ?: return null
    val delta = end - start
    if (delta < 0.0 || delta >= 1500.0) return null
    return delta
}

fun TripRecord.deltaSoc(): Double? {
    deltaSocOverride?.let { return it }
    val start = startSoc ?: return null
    val end = endSoc ?: return null
    return start - end
}

fun TripRecord.energyKwh(capacityKwh: Double): Double? {
    energyOverrideKwh?.let { return it }
    val delta = deltaSoc() ?: return null
    if (capacityKwh <= 0.0) return null
    return delta / 100.0 * capacityKwh
}

/** 里程不足 0.5 km 或电量为负时不计算百公里电耗。 */
fun TripRecord.kwhPer100km(capacityKwh: Double): Double? {
    val km = distanceKm() ?: return null
    val energy = energyKwh(capacityKwh) ?: return null
    if (km < 0.5 || energy < 0.0) return null
    return energy / km * 100.0
}

fun TripRecord.coldestBattery(): Double? =
    listOfNotNull(startBatteryTemp, endBatteryTemp).minOrNull()

fun TripRecord.bandTemp(): Double? {
    val start = startBatteryTemp
    val end = endBatteryTemp
    return when {
        start != null && end != null -> (start + end) / 2.0
        else -> start ?: end
    }
}

/** 实充 kWh / 充入 SOC 比例。例如 10 kWh、+20% → 50 kWh。 */
fun inferUsableCapacity(chargedKwh: Double, deltaSocPercent: Double): Double? {
    if (chargedKwh <= 0.0 || deltaSocPercent <= 0.0) return null
    if (deltaSocPercent > 100.0) return null
    return chargedKwh / (deltaSocPercent / 100.0)
}

data class TempBand(val label: String, val minInclusive: Double?, val maxExclusive: Double?)

val TEMP_BANDS = listOf(
    TempBand("低于 -20℃", null, -20.0),
    TempBand("-20 ~ -10℃", -20.0, -10.0),
    TempBand("-10 ~ 0℃", -10.0, 0.0),
    TempBand("0 ~ 10℃", 0.0, 10.0),
    TempBand("10℃ 及以上", 10.0, null),
)

fun bandOf(temp: Double): TempBand = TEMP_BANDS.first { band ->
    val lowerOk = band.minInclusive == null || temp >= band.minInclusive
    val upperOk = band.maxExclusive == null || temp < band.maxExclusive
    lowerOk && upperOk
}

data class BandStat(
    val label: String,
    val trips: Int,
    val km: Double,
    val kwh: Double,
    val kwhPer100km: Double?,
)

data class AggregateStat(
    val trips: Int,
    val totalKm: Double,
    val totalKwh: Double,
    val weightedKwhPer100km: Double?,
    val bands: List<BandStat>,
)

fun aggregate(trips: List<TripRecord>, capacityKwh: Double): AggregateStat {
    val closed = trips.filter { it.status == TripStatus.CLOSED }
    val totalKm = closed.sumOf { it.distanceKm() ?: 0.0 }
    val totalKwh = closed.sumOf { trip ->
        val energy = trip.energyKwh(capacityKwh)
        if (energy != null && energy > 0.0) energy else 0.0
    }
    val qualified = closed.filter { trip ->
        val km = trip.distanceKm() ?: return@filter false
        val energy = trip.energyKwh(capacityKwh) ?: return@filter false
        km >= 0.5 && energy >= 0.0
    }
    val qKm = qualified.sumOf { it.distanceKm() ?: 0.0 }
    val qKwh = qualified.sumOf { it.energyKwh(capacityKwh) ?: 0.0 }
    val weighted = if (qKm > 0.0) qKwh / qKm * 100.0 else null
    val grouped = (TEMP_BANDS.map { it.label } + "温度未知").associateWith { mutableListOf<TripRecord>() }
    closed.forEach { trip ->
        val temp = trip.bandTemp()
        val key = if (temp == null) "温度未知" else bandOf(temp).label
        grouped.getValue(key).add(trip)
    }
    val bands = grouped.map { (label, rows) ->
        val km = rows.sumOf { it.distanceKm() ?: 0.0 }
        val kwh = rows.sumOf { trip ->
            val energy = trip.energyKwh(capacityKwh)
            if (energy != null && energy > 0.0) energy else 0.0
        }
        val rate = if (km >= 0.5 && kwh >= 0.0 && rows.any { (it.distanceKm() ?: 0.0) >= 0.5 }) {
            val q = rows.filter { (it.distanceKm() ?: 0.0) >= 0.5 && (it.energyKwh(capacityKwh) ?: -1.0) >= 0.0 }
            val dk = q.sumOf { it.distanceKm() ?: 0.0 }
            val de = q.sumOf { it.energyKwh(capacityKwh) ?: 0.0 }
            if (dk > 0.0) de / dk * 100.0 else null
        } else {
            null
        }
        BandStat(label, rows.size, km, kwh, rate)
    }
    return AggregateStat(closed.size, totalKm, totalKwh, weighted, bands)
}

enum class DatePreset { TODAY, WEEK, MONTH, ALL, D7, D30 }

fun rangeStart(preset: DatePreset, nowMs: Long, zone: ZoneId = Shanghai.zone): Long? {
    val zdt = Instant.ofEpochMilli(nowMs).atZone(zone)
    return when (preset) {
        DatePreset.ALL -> null
        DatePreset.TODAY -> zdt.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        DatePreset.WEEK -> {
            val monday = zdt.toLocalDate().minusDays((zdt.dayOfWeek.value - 1).toLong())
            monday.atStartOfDay(zone).toInstant().toEpochMilli()
        }
        DatePreset.MONTH -> zdt.toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        DatePreset.D7 -> nowMs - 7L * 24 * 3600 * 1000
        DatePreset.D30 -> nowMs - 30L * 24 * 3600 * 1000
    }
}

fun filterByPreset(trips: List<TripRecord>, preset: DatePreset, nowMs: Long): List<TripRecord> {
    val start = rangeStart(preset, nowMs) ?: return trips
    return trips.filter { it.startAt >= start && it.startAt <= nowMs }
}

data class TripFilter(
    val preset: DatePreset = DatePreset.ALL,
    val tempMax: Double? = null,
    val tag: String = "",
)

fun applyTripFilter(trips: List<TripRecord>, filter: TripFilter, nowMs: Long): List<TripRecord> {
    val tag = filter.tag.trim()
    return filterByPreset(trips, filter.preset, nowMs).filter { trip ->
        val tempOk = filter.tempMax == null || (trip.coldestBattery()?.let { it <= filter.tempMax } == true)
        val tagOk = tag.isBlank() || trip.tags.split(',', '，').any { it.trim() == tag } || trip.tags.contains(tag) || trip.note.contains(tag)
        tempOk && tagOk
    }
}

fun buildManualTrip(input: ManualInput, newId: String): ManualBuild {
    if (input.endAt <= input.startAt) return ManualBuild(error = "结束时间要晚于开始时间")
    val hasOdo = input.startOdo != null || input.endOdo != null
    if (hasOdo && (input.startOdo == null || input.endOdo == null)) {
        return ManualBuild(error = "起始里程和结束里程需要一起填")
    }
    if (input.startOdo != null && input.endOdo != null) {
        if (input.startOdo < 0 || input.endOdo < input.startOdo) return ManualBuild(error = "结束里程不能小于起始里程")
        if (input.endOdo - input.startOdo >= 1500) return ManualBuild(error = "里程差过大，请核对总里程")
    }
    val distanceOverride = if (input.startOdo != null) null else input.distanceKm
    if (distanceOverride == null && input.startOdo == null) return ManualBuild(error = "请填写行驶里程，或填写起止总里程")
    if (distanceOverride != null && (distanceOverride < 0 || distanceOverride >= 1500)) {
        return ManualBuild(error = "行驶里程需要在 0 到 1500 km 之间")
    }
    val socPair = input.startSoc != null || input.endSoc != null
    if (socPair && (input.startSoc == null || input.endSoc == null)) return ManualBuild(error = "起始 SOC 和结束 SOC 需要一起填")
    if (input.startSoc != null && (input.startSoc !in 0.0..100.0 || input.endSoc!! !in 0.0..100.0)) {
        return ManualBuild(error = "SOC 需要在 0 到 100 之间")
    }
    val deltaOverride = if (input.startSoc != null) null else input.deltaSoc
    if (deltaOverride == null && input.startSoc == null) return ManualBuild(error = "请填写起止 SOC，或填写掉电百分比")
    if (deltaOverride != null && deltaOverride !in -20.0..100.0) return ManualBuild(error = "掉电百分比不合理")
    val precise = input.socSource == SocSource.PRECISE || input.socSource == SocSource.MIXED
    val integer = input.socSource == SocSource.INTEGER || input.socSource == SocSource.MIXED
    val trip = TripRecord(
        id = input.id ?: newId,
        source = TripSource.MANUAL,
        status = TripStatus.CLOSED,
        startAt = input.startAt,
        endAt = input.endAt,
        startOdo = input.startOdo,
        endOdo = input.endOdo,
        startSoc = input.startSoc,
        endSoc = input.endSoc,
        startBatteryTemp = input.startBattery,
        endBatteryTemp = input.endBattery,
        startInteriorTemp = input.startInterior,
        endInteriorTemp = input.endInterior,
        socSource = input.socSource,
        note = input.note.trim(),
        tags = input.tags.trim(),
        closeReason = "MANUAL",
        sampleCount = 0,
        distanceOverrideKm = distanceOverride,
        deltaSocOverride = deltaOverride,
        parkedSince = null,
        lastNewFrameWall = input.endAt,
        lastSts = 0,
        sawPrecise = precise,
        sawInteger = integer,
    )
    return ManualBuild(trip = trip)
}

fun exportCsv(trips: List<TripRecord>, capacityKwh: Double): String {
    val header = listOf(
        "开始时间", "结束时间", "来源", "状态", "里程km", "百公里电耗", "耗电kWh", "掉电%",
        "起始SOC", "结束SOC", "电池最低温起", "电池最低温止", "车内温起", "车内温止",
        "SOC来源", "标签", "备注", "结束原因",
    )
    val lines = trips.sortedBy { it.startAt }.map { trip ->
        listOf(
            Shanghai.format(trip.startAt),
            Shanghai.format(trip.endAt),
            sourceLabel(trip.source),
            if (trip.status == TripStatus.OPEN) "进行中" else "已结束",
            num(trip.distanceKm(), 3),
            num(trip.kwhPer100km(capacityKwh), 2),
            num(trip.energyKwh(capacityKwh), 3),
            num(trip.deltaSoc(), 2),
            num(trip.startSoc, 1),
            num(trip.endSoc, 1),
            num(trip.startBatteryTemp, 1),
            num(trip.endBatteryTemp, 1),
            num(trip.startInteriorTemp, 1),
            num(trip.endInteriorTemp, 1),
            socLabel(trip.socSource),
            trip.tags,
            trip.note,
            trip.closeReason?.let { reasonLabel(it) }.orEmpty(),
        ).joinToString(",") { csvCell(it) }
    }
    return "\uFEFF# usableCapacityKwh=$capacityKwh\n" + (listOf(header.joinToString(",")) + lines).joinToString("\n")
}

private fun csvCell(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.contains(',') || escaped.contains('\n') || escaped.contains('"')) "\"$escaped\"" else escaped
}

private fun num(value: Double?, digits: Int): String =
    if (value == null || value.isNaN()) "" else String.format(Locale.CHINA, "%.${digits}f", value)

fun sourceLabel(source: TripSource): String = when (source) {
    TripSource.AUTO -> "自动"
    TripSource.MANUAL -> "手动"
    TripSource.GAP -> "补记"
}

fun socLabel(source: SocSource): String = when (source) {
    SocSource.PRECISE -> "精确"
    SocSource.INTEGER -> "整数"
    SocSource.MIXED -> "混合"
    SocSource.UNKNOWN -> "未知"
}

fun reasonLabel(reason: String): String = when (reason) {
    "PARK" -> "停车"
    "CHARGING" -> "充电"
    "PLUGGED" -> "插枪"
    "LOST" -> "失联"
    "MANUAL" -> "手动"
    "GAP" -> "里程补记"
    else -> reason
}

fun dayWindowSeconds(date: LocalDate, nowEpochSec: Long, zone: ZoneId = Shanghai.zone): Pair<Long, Long> {
    val start = date.atStartOfDay(zone).toEpochSecond()
    val endOfDay = date.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
    val end = minOf(endOfDay, nowEpochSec)
    return start to end
}

fun decideEc(code: Int?, driver: Double?, ac: Double?, other: Double?, isToday: Boolean): DailyEcDecision {
    if (code == 0 || code == 200 || (code == null && driver != null)) {
        return DailyEcDecision(driver ?: 0.0, ac ?: 0.0, other ?: 0.0, pending = false, note = "")
    }
    if (code == 100) {
        return if (isToday) {
            DailyEcDecision(null, null, null, pending = true, note = "今天云端可能还没记账，先看本地分段")
        } else {
            DailyEcDecision(0.0, 0.0, 0.0, pending = false, note = "当日无行驶能耗")
        }
    }
    if (code == 2) return DailyEcDecision(null, null, null, pending = true, note = "时间窗无效，已尽量把终点收敛到现在")
    return DailyEcDecision(null, null, null, pending = true, note = "官方能耗暂不可用（code=$code）")
}

object Shanghai {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val date: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val parsers = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    )

    fun format(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        return Instant.ofEpochMilli(ms).atZone(zone).format(dateTime)
    }

    fun clock(ms: Long?): String {
        if (ms == null || ms <= 0) return "--"
        return Instant.ofEpochMilli(ms).atZone(zone).format(clock)
    }

    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(date)

    fun parse(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        for (pattern in parsers) {
            try {
                val local = java.time.LocalDateTime.parse(value, pattern)
                return local.atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
