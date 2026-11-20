package cn.daqing.leap.a10energy.data

import cn.daqing.leap.a10energy.data.db.AppDatabase
import cn.daqing.leap.a10energy.data.db.OfficialDayEntity
import cn.daqing.leap.a10energy.data.db.SyncStateEntity
import cn.daqing.leap.a10energy.domain.ManualBuild
import cn.daqing.leap.a10energy.domain.ReduceResult
import cn.daqing.leap.a10energy.domain.TrackerState
import cn.daqing.leap.a10energy.domain.TripEffect
import cn.daqing.leap.a10energy.domain.TripRecord
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class TripRepository(private val db: AppDatabase) {
    fun observeTrips(): Flow<List<TripRecord>> = db.trips().observeAll().map { rows -> rows.map { it.toRecord() } }

    fun observeSync(): Flow<SyncStateEntity?> = db.syncState().observe()

    fun observeOfficial(): Flow<List<OfficialDayEntity>> = db.officialDays().observeAll()

    suspend fun loadTracker(): Pair<SyncStateEntity, TrackerState> {
        val sync = db.syncState().get() ?: SyncStateEntity()
        val open = db.trips().getOpen()?.toRecord()
        return sync to TrackerState(open = open, lastFrameSts = sync.lastFrameSts, anchor = sync.toAnchor())
    }

    suspend fun apply(base: SyncStateEntity, result: ReduceResult, now: Long) {
        db.withTransaction {
            result.effects.forEach { effect ->
                when (effect) {
                    is TripEffect.Save -> db.trips().upsert(effect.trip.toEntity(now))
                    is TripEffect.Delete -> db.trips().delete(effect.id)
                }
            }
            db.syncState().upsert(base.withTracker(result.state))
        }
    }

    suspend fun saveSync(entity: SyncStateEntity) {
        db.syncState().upsert(entity)
    }

    suspend fun getTrip(id: String): TripRecord? = db.trips().get(id)?.toRecord()

    suspend fun getAllTrips(): List<TripRecord> = db.trips().getAll().map { it.toRecord() }

    suspend fun updateNote(id: String, note: String, tags: String) {
        db.trips().updateNote(id, note.trim(), tags.trim(), System.currentTimeMillis())
    }

    suspend fun saveManual(built: ManualBuild) {
        val trip = built.trip ?: return
        db.trips().upsert(trip.toEntity(System.currentTimeMillis()))
    }

    suspend fun deleteTrip(id: String) {
        val (sync, tracker) = loadTracker()
        val existing = db.trips().get(id)?.toRecord()
        db.withTransaction {
            db.trips().delete(id)
            if (tracker.open?.id == id) {
                val anchorOdo = existing?.endOdo ?: existing?.startOdo ?: sync.anchorOdo
                db.syncState().upsert(
                    sync.copy(
                        anchorOdo = anchorOdo,
                        anchorSoc = existing?.endSoc ?: sync.anchorSoc,
                        anchorAt = existing?.endAt ?: existing?.startAt ?: sync.anchorAt,
                        anchorCharging = false,
                    ),
                )
            }
        }
    }

    suspend fun saveOfficial(entity: OfficialDayEntity) {
        val previous = db.officialDays().get(entity.date)
        val merged = if (entity.pending && previous?.driverKwh != null && entity.driverKwh == null) {
            previous.copy(pending = true, note = entity.note, syncedAt = entity.syncedAt, rawEc = entity.rawEc)
        } else {
            entity
        }
        db.officialDays().upsert(merged)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
