package cn.daqing.leap.a10energy.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.daqing.leap.a10energy.data.db.AppDatabase
import cn.daqing.leap.a10energy.data.db.OfficialDayEntity
import cn.daqing.leap.a10energy.data.db.TripEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TripDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun tripCrud_andOfficialDayReplace() = runBlocking {
        val entity = sample("a")
        db.trips().upsert(entity)
        assertEquals(1, db.trips().observeAll().first().size)
        db.trips().updateNote("a", "大庆通勤", "极寒", 9L)
        val updated = db.trips().get("a")!!
        assertEquals("大庆通勤", updated.note)
        assertEquals("极寒", updated.tags)
        assertEquals(9L, updated.updatedAt)
        db.trips().upsert(sample("b").copy(startAt = entity.startAt - 1_000, status = "OPEN"))
        assertEquals("b", db.trips().getOpen()!!.id)
        db.trips().delete("a")
        assertNull(db.trips().get("a"))

        db.officialDays().upsert(OfficialDayEntity("2026-11-20", 7.0, 0.5, 0.9, 22.0, false, "", "{}", 1L))
        db.officialDays().upsert(OfficialDayEntity("2026-11-20", 8.0, 0.5, 0.9, 22.0, false, "更新", "{}", 2L))
        assertEquals(8.0, db.officialDays().get("2026-11-20")!!.driverKwh!!, 0.001)
        assertEquals(1, db.officialDays().observeAll().first().size)
    }

    private fun sample(id: String) = TripEntity(
        id = id,
        source = "AUTO",
        status = "CLOSED",
        startAt = 1_700_000_000_000,
        endAt = 1_700_000_100_000,
        startOdo = 10.0,
        endOdo = 12.0,
        startSoc = 80.0,
        endSoc = 78.0,
        startBatteryTemp = -18.0,
        endBatteryTemp = -17.0,
        startInteriorTemp = 12.0,
        endInteriorTemp = 18.0,
        socSource = "PRECISE",
        note = "",
        tags = "",
        closeReason = "PARK",
        sampleCount = 4,
        distanceOverrideKm = null,
        deltaSocOverride = null,
        energyOverrideKwh = null,
        parkedSince = null,
        lastNewFrameWall = 1_700_000_100_000,
        lastSts = 1_700_000_100_000,
        sawPrecise = true,
        sawInteger = false,
        updatedAt = 1L,
    )
}
