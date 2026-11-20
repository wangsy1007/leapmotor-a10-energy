package cn.daqing.leap.a10energy.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, SyncStateEntity::class, OfficialDayEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun syncState(): SyncStateDao
    abstract fun officialDays(): OfficialDayDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "a10-energy.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
