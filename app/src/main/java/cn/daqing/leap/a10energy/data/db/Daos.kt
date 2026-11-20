package cn.daqing.leap.a10energy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startAt DESC")
    fun observeAll(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips ORDER BY startAt DESC")
    suspend fun getAll(): List<TripEntity>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: String): TripEntity?

    @Query("SELECT * FROM trips WHERE status = 'OPEN' ORDER BY startAt DESC LIMIT 1")
    suspend fun getOpen(): TripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TripEntity)

    @Query("UPDATE trips SET note = :note, tags = :tags, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateNote(id: String, note: String, tags: String, updatedAt: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncStateEntity)
}

@Dao
interface OfficialDayDao {
    @Query("SELECT * FROM official_days ORDER BY date DESC")
    fun observeAll(): Flow<List<OfficialDayEntity>>

    @Query("SELECT * FROM official_days WHERE date = :date")
    suspend fun get(date: String): OfficialDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfficialDayEntity)
}
