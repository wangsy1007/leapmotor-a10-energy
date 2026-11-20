package cn.daqing.leap.a10energy.data

import cn.daqing.leap.a10energy.data.db.SyncStateEntity
import cn.daqing.leap.a10energy.data.db.TripEntity
import cn.daqing.leap.a10energy.domain.Anchor
import cn.daqing.leap.a10energy.domain.SocSource
import cn.daqing.leap.a10energy.domain.TrackerState
import cn.daqing.leap.a10energy.domain.TripRecord
import cn.daqing.leap.a10energy.domain.TripSource
import cn.daqing.leap.a10energy.domain.TripStatus

fun TripEntity.toRecord(): TripRecord = TripRecord(
    id = id,
    source = runCatching { TripSource.valueOf(source) }.getOrDefault(TripSource.AUTO),
    status = runCatching { TripStatus.valueOf(status) }.getOrDefault(TripStatus.CLOSED),
    startAt = startAt,
    endAt = endAt,
    startOdo = startOdo,
    endOdo = endOdo,
    startSoc = startSoc,
    endSoc = endSoc,
    startBatteryTemp = startBatteryTemp,
    endBatteryTemp = endBatteryTemp,
    startInteriorTemp = startInteriorTemp,
    endInteriorTemp = endInteriorTemp,
    socSource = runCatching { SocSource.valueOf(socSource) }.getOrDefault(SocSource.UNKNOWN),
    note = note,
    tags = tags,
    closeReason = closeReason,
    sampleCount = sampleCount,
    distanceOverrideKm = distanceOverrideKm,
    deltaSocOverride = deltaSocOverride,
    energyOverrideKwh = energyOverrideKwh,
    parkedSince = parkedSince,
    lastNewFrameWall = lastNewFrameWall,
    lastSts = lastSts,
    sawPrecise = sawPrecise,
    sawInteger = sawInteger,
)

fun TripRecord.toEntity(updatedAt: Long): TripEntity = TripEntity(
    id = id,
    source = source.name,
    status = status.name,
    startAt = startAt,
    endAt = endAt,
    startOdo = startOdo,
    endOdo = endOdo,
    startSoc = startSoc,
    endSoc = endSoc,
    startBatteryTemp = startBatteryTemp,
    endBatteryTemp = endBatteryTemp,
    startInteriorTemp = startInteriorTemp,
    endInteriorTemp = endInteriorTemp,
    socSource = socSource.name,
    note = note,
    tags = tags,
    closeReason = closeReason,
    sampleCount = sampleCount,
    distanceOverrideKm = distanceOverrideKm,
    deltaSocOverride = deltaSocOverride,
    energyOverrideKwh = energyOverrideKwh,
    parkedSince = parkedSince,
    lastNewFrameWall = lastNewFrameWall,
    lastSts = lastSts,
    sawPrecise = sawPrecise,
    sawInteger = sawInteger,
    updatedAt = updatedAt,
)

fun SyncStateEntity.toAnchor(): Anchor = Anchor(
    at = anchorAt,
    sts = anchorSts,
    odo = anchorOdo,
    soc = anchorSoc,
    batteryTemp = anchorBattery,
    interiorTemp = anchorInterior,
    socPrecise = anchorSocPrecise,
    charging = anchorCharging,
)

fun SyncStateEntity.withTracker(state: TrackerState): SyncStateEntity = copy(
    lastFrameSts = state.lastFrameSts,
    anchorAt = state.anchor.at,
    anchorSts = state.anchor.sts,
    anchorOdo = state.anchor.odo,
    anchorSoc = state.anchor.soc,
    anchorBattery = state.anchor.batteryTemp,
    anchorInterior = state.anchor.interiorTemp,
    anchorSocPrecise = state.anchor.socPrecise,
    anchorCharging = state.anchor.charging,
)
