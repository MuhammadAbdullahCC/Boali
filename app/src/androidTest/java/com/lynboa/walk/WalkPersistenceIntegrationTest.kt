package com.lynboa.walk

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lynboa.walk.data.FitnessDatabase
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.data.RoutePoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Real Android SQLite integration test. Simulates a continuous recorded walk,
 * a long GPS outage, recovery, GPX export, and an upgrade from the installed v1 DB.
 */
@RunWith(AndroidJUnit4::class)
class WalkPersistenceIntegrationTest {
    @Test fun previousVersionUpgradesAndGpsRouteSurvivesRestart() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.deleteDatabase("lynboa.db")
        val old = ctx.openOrCreateDatabase("lynboa.db", Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE daily (day TEXT PRIMARY KEY NOT NULL, " +
            "steps INTEGER NOT NULL DEFAULT 0, water_ml INTEGER NOT NULL DEFAULT 0, " +
            "milestones INTEGER NOT NULL DEFAULT 0)")
        old.execSQL("CREATE TABLE checkpoint (id INTEGER PRIMARY KEY CHECK(id=1), " +
            "raw INTEGER NOT NULL, elapsed_nanos INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE walks (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "started_at INTEGER NOT NULL, ended_at INTEGER, distance_m REAL NOT NULL DEFAULT 0)")
        old.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "walk_id INTEGER NOT NULL REFERENCES walks(id), lat REAL NOT NULL, " +
            "lon REAL NOT NULL, at INTEGER NOT NULL, accuracy REAL NOT NULL)")
        old.execSQL("CREATE INDEX ix_points_walk ON points(walk_id, at)")
        val day = LocalDate.now().toString()
        old.execSQL("INSERT INTO daily(day,steps,water_ml,milestones) VALUES(?,123,500,0)",
            arrayOf(day))
        old.version = 1
        old.close()

        val repository = FitnessRepository(ctx)
        assertEquals("Upgrade must preserve step history", 123, repository.progress.value.steps)
        assertEquals(500, repository.progress.value.waterMl)
        repository.beginWalk()
        val base = System.currentTimeMillis() - 180_000L
        repository.gps(RoutePoint(32.06344, 73.37776, base, 8f))
        repository.gps(RoutePoint(32.06356, 73.37778, base + 10_000, 8f))
        repository.gps(RoutePoint(32.06368, 73.37780, base + 20_000, 8f))
        // Large gap: preserve next position without inventing a 200m straight line.
        repository.gps(RoutePoint(32.06545, 73.37800, base + 95_000, 7f))
        repository.gps(RoutePoint(32.06558, 73.37800, base + 107_000, 7f))
        repository.endWalk()

        val database = FitnessDatabase(ctx)
        val walk = database.newestWalk() ?: error("No completed session was persisted")
        val points = database.points(walk.id)
        assertEquals("Five valid GPS fixes must persist", 5, points.size)
        assertTrue("First fix after GPS loss begins a new segment", points[3].breakBefore)
        assertFalse("Normal walking resumes on continuous segment", points[4].breakBefore)
        assertTrue("Walking meters should be accumulated", walk.distanceMeters > 30)
        assertTrue("Gap must not inflate walking distance", walk.distanceMeters < 70)
        assertNotNull("Recorded walk must be marked completed", walk.endedAt)

        val restored = FitnessRepository(ctx)
        assertEquals(123, restored.progress.value.steps)
        assertEquals(walk.id, restored.walk.value?.id)
        assertEquals(5, restored.route.value.size)
        val gpx = restored.exportGpx(walk.id)
        assertTrue(gpx.startsWith("<?xml version="))
        assertEquals("One GPX segment per continuous GPS span", 2,
            gpx.split("<trkseg>").size - 1)
        assertEquals(5, gpx.split("<trkpt ").size - 1)
        database.close()
    }
}
