package cn.daqing.leap.a10energy.domain

import java.util.Locale
import java.util.UUID

/**
 * 分段行程状态机。重复的车端 sts 不累计里程和电量；
 * 停车超时用手机时间判断，因为休眠时 sts 会停住。
 * 充电或插枪立即收尾，并且不把充电过程记成行程。
 * 手动行程只由「结束行程」收尾。
 */
class TripEngine(
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    fun reduce(
        state: TrackerState,
        snap: VehicleSnapshot?,
        now: Long,
        manual: ManualCommand = ManualCommand.NONE,
        config: EngineConfig = EngineConfig(),
    ): ReduceResult {
        val effects = mutableListOf<TripEffect>()
        val messages = mutableListOf<String>()
        var open = state.open
        var lastFrameSts = state.lastFrameSts
        var anchor = state.anchor

        fun finish(reason: String, keepShort: Boolean, endAt: Long) {
            val current = open ?: return
            val closed = current.copy(
                status = TripStatus.CLOSED,
                endAt = endAt,
                closeReason = reason,
                parkedSince = null,
            )
            val km = closed.distanceKm()
            val drop = !keepShort && closed.source != TripSource.MANUAL && (km == null || km < config.minKeepKm)
            if (drop) {
                effects += TripEffect.Delete(closed.id)
                messages += "本次不足 ${config.minKeepKm} km，未保存"
            } else {
                effects += TripEffect.Save(closed)
                messages += "行程已结束（${reasonLabel(reason)}）"
            }
            anchor = anchorFrom(closed, charging = false)
            open = null
        }

        if (manual == ManualCommand.START) {
            if (open != null) return result(open, lastFrameSts, anchor, effects, listOf("已有进行中的行程"))
            if (snap?.soc == null || snap.odoKm == null) {
                return result(open, lastFrameSts, anchor, effects, listOf("没有可用车况，无法手动开始"))
            }
            val started = snap.startRecord(ids(), TripSource.MANUAL, now)
            effects += TripEffect.Save(started)
            if (snap.sts > 0) lastFrameSts = maxOf(lastFrameSts, snap.sts)
            return result(
                started,
                lastFrameSts,
                anchor.carry(snap, now, charging = snap.charging || snap.plugged),
                effects,
                listOf("已按当前车况开始行程"),
            )
        }

        if (manual == ManualCommand.END) {
            if (open == null) return result(null, lastFrameSts, anchor, effects, listOf("当前没有进行中的行程"))
            if (snap != null && snap.sts > lastFrameSts && snap.sts > 0) {
                open = open!!.applySample(snap, now, config.maxJumpKm)
                lastFrameSts = snap.sts
            }
            finish("MANUAL", keepShort = true, endAt = now)
            return result(open, lastFrameSts, anchor, effects, messages)
        }

        if (open != null && open!!.source == TripSource.MANUAL) {
            if (snap != null && snap.sts > lastFrameSts && snap.sts > 0) {
                val updated = open!!.applySample(snap, now, config.maxJumpKm)
                effects += TripEffect.Save(updated)
                return result(updated, snap.sts, anchor.carry(snap, now, charging = false), effects, messages)
            }
            return result(open, lastFrameSts, anchor, effects, messages)
        }

        val newFrame = snap != null && snap.sts > lastFrameSts && snap.sts > 0
        if (!newFrame) {
            if (open != null) {
                val wall = open!!.lastNewFrameWall ?: open!!.startAt
                if (now - wall >= config.lostContactMs) {
                    finish("LOST", keepShort = false, endAt = wall)
                    return result(open, lastFrameSts, anchor, effects, messages)
                }
                val parked = open!!.parkedSince
                if (parked != null && now - parked >= config.parkTimeoutMs) {
                    finish("PARK", keepShort = false, endAt = parked)
                }
            }
            return result(open, lastFrameSts, anchor, effects, messages)
        }

        val frame = snap!!
        if (frame.charging || frame.plugged) {
            val wasCharging = anchor.charging
            if (open != null) {
                val endAt = open!!.lastNewFrameWall ?: now
                finish(if (frame.charging) "CHARGING" else "PLUGGED", keepShort = false, endAt = endAt)
                messages += if (frame.charging) "充电中，已切段并暂停记行程" else "已插枪，已结束行程并暂停记行程"
            } else if (!wasCharging) {
                messages += if (frame.charging) "充电中，暂停记行程" else "已插枪，暂停记行程"
            }
            anchor = anchor.carry(frame, now, charging = true)
            return result(open, frame.sts, anchor, effects, messages)
        }

        if (anchor.charging) {
            anchor = anchor.carry(frame, now, charging = false)
            if (frame.moving && frame.soc != null && frame.odoKm != null) {
                val started = frame.startRecord(ids(), TripSource.AUTO, now)
                effects += TripEffect.Save(started)
                messages += "充电结束，已开始新行程"
                return result(started, frame.sts, anchor, effects, messages)
            }
            messages += "充电结束，已重新对齐里程，不把充电过程记成行程"
            return result(null, frame.sts, anchor, effects, messages)
        }

        if (open != null && open!!.lastSts > 0 && frame.sts - open!!.lastSts >= config.lostContactMs) {
            open = open!!.applySample(frame, now, config.maxJumpKm)
            val stillMoving = frame.moving
            finish("LOST", keepShort = false, endAt = now)
            if (stillMoving && frame.soc != null && frame.odoKm != null) {
                val started = frame.startRecord(ids(), TripSource.AUTO, now)
                effects += TripEffect.Save(started)
                messages += "失联较久，上一段已收尾并开始新行程"
                return result(started, frame.sts, anchor.carry(frame, now, charging = false), effects, messages)
            }
            return result(open, frame.sts, anchor, effects, messages)
        }

        if (open == null) {
            if (!frame.moving) {
                val previous = anchor.odo
                val current = frame.odoKm
                if (previous != null && current != null && anchor.at != null && anchor.soc != null && frame.soc != null) {
                    val delta = current - previous
                    if (delta >= config.gapFillKm && delta < config.maxJumpKm) {
                        val gap = gapRecord(anchor, frame, now, ids())
                        if ((gap.distanceKm() ?: 0.0) >= config.minKeepKm) {
                            effects += TripEffect.Save(gap)
                            messages += "停车期间里程增加 ${String.format(Locale.CHINA, "%.1f", delta)} km，已补记一段"
                        }
                    }
                }
                return result(null, frame.sts, anchor.carry(frame, now, charging = false), effects, messages)
            }
            if (frame.soc == null || frame.odoKm == null) {
                messages += "正在行驶，但这一帧缺少 SOC 或总里程"
                return result(null, frame.sts, anchor, effects, messages)
            }
            val started = frame.startRecord(ids(), TripSource.AUTO, now)
            effects += TripEffect.Save(started)
            messages += "检测到行驶，已开始行程"
            return result(started, frame.sts, anchor.carry(frame, now, charging = false), effects, messages)
        }

        var updated = open!!.applySample(frame, now, config.maxJumpKm)
        updated = if (frame.moving) {
            updated.copy(parkedSince = null)
        } else if (updated.parkedSince == null) {
            updated.copy(parkedSince = now)
        } else {
            updated
        }
        if (updated.parkedSince != null && now - updated.parkedSince!! >= config.parkTimeoutMs) {
            open = updated
            finish("PARK", keepShort = false, endAt = updated.parkedSince ?: now)
            return result(open, frame.sts, anchor.carry(frame, now, charging = false), effects, messages)
        }
        effects += TripEffect.Save(updated)
        return result(updated, frame.sts, anchor.carry(frame, now, charging = false), effects, messages)
    }

    private fun result(
        open: TripRecord?,
        lastFrameSts: Long,
        anchor: Anchor,
        effects: List<TripEffect>,
        messages: List<String>,
    ) = ReduceResult(TrackerState(open, lastFrameSts, anchor), effects, messages)
}

private fun VehicleSnapshot.startRecord(id: String, source: TripSource, now: Long): TripRecord {
    val precise = soc != null && socPrecise
    val integer = soc != null && !socPrecise
    return TripRecord(
        id = id,
        source = source,
        status = TripStatus.OPEN,
        startAt = now,
        startOdo = odoKm,
        endOdo = odoKm,
        startSoc = soc,
        endSoc = soc,
        startBatteryTemp = batteryMinTemp,
        endBatteryTemp = batteryMinTemp,
        startInteriorTemp = interiorTemp,
        endInteriorTemp = interiorTemp,
        socSource = flagsToSource(precise, integer),
        sampleCount = 1,
        parkedSince = if (moving) null else now,
        lastNewFrameWall = now,
        lastSts = sts,
        sawPrecise = precise,
        sawInteger = integer,
    )
}

private fun TripRecord.applySample(snap: VehicleSnapshot, now: Long, maxJumpKm: Double): TripRecord {
    var endOdo = this.endOdo
    if (snap.odoKm != null && endOdo != null) {
        val delta = snap.odoKm - endOdo
        if (delta >= 0.0 && delta <= maxJumpKm) endOdo = snap.odoKm
    } else if (snap.odoKm != null && endOdo == null) {
        endOdo = snap.odoKm
    }
    val endSoc = if (snap.soc != null && snap.soc in 0.0..100.0) snap.soc else this.endSoc
    val precise = sawPrecise || (snap.soc != null && snap.socPrecise)
    val integer = sawInteger || (snap.soc != null && !snap.socPrecise)
    return copy(
        endOdo = endOdo,
        endSoc = endSoc,
        endBatteryTemp = snap.batteryMinTemp ?: endBatteryTemp,
        endInteriorTemp = snap.interiorTemp ?: endInteriorTemp,
        socSource = flagsToSource(precise, integer),
        sampleCount = sampleCount + 1,
        lastSts = snap.sts,
        lastNewFrameWall = now,
        sawPrecise = precise,
        sawInteger = integer,
    )
}

private fun gapRecord(anchor: Anchor, snap: VehicleSnapshot, now: Long, id: String): TripRecord {
    val sawPrecise = (anchor.soc != null && anchor.socPrecise) || (snap.soc != null && snap.socPrecise)
    val sawInteger = (anchor.soc != null && !anchor.socPrecise) || (snap.soc != null && !snap.socPrecise)
    return TripRecord(
        id = id,
        source = TripSource.GAP,
        status = TripStatus.CLOSED,
        startAt = anchor.at ?: now,
        endAt = now,
        startOdo = anchor.odo,
        endOdo = snap.odoKm,
        startSoc = anchor.soc,
        endSoc = snap.soc,
        startBatteryTemp = anchor.batteryTemp,
        endBatteryTemp = snap.batteryMinTemp,
        startInteriorTemp = anchor.interiorTemp,
        endInteriorTemp = snap.interiorTemp,
        socSource = flagsToSource(sawPrecise, sawInteger),
        closeReason = "GAP",
        sampleCount = 2,
        lastSts = snap.sts,
        lastNewFrameWall = now,
        sawPrecise = sawPrecise,
        sawInteger = sawInteger,
    )
}

private fun anchorFrom(trip: TripRecord, charging: Boolean) = Anchor(
    at = trip.endAt ?: trip.startAt,
    sts = trip.lastSts,
    odo = trip.endOdo,
    soc = trip.endSoc,
    batteryTemp = trip.endBatteryTemp,
    interiorTemp = trip.endInteriorTemp,
    socPrecise = trip.sawPrecise && !trip.sawInteger,
    charging = charging,
)

private fun Anchor.carry(snap: VehicleSnapshot, now: Long, charging: Boolean) = Anchor(
    at = now,
    sts = snap.sts,
    odo = snap.odoKm ?: odo,
    soc = snap.soc ?: soc,
    batteryTemp = snap.batteryMinTemp ?: batteryTemp,
    interiorTemp = snap.interiorTemp ?: interiorTemp,
    socPrecise = if (snap.soc != null) snap.socPrecise else socPrecise,
    charging = charging,
)
