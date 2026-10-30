package com.lynboa.walk.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lynboa.walk.domain.FitnessMath
import java.time.LocalDate

data class SensorUpdate(val total: Int, val previous: Int)
data class DailyProgress(val date: String, val steps: Int = 0, val waterMl: Int = 0,
                         val milestones: Int = 0)
data class Walk(val id: Long, val startedAt: Long, val endedAt: Long?,
                val distanceMeters: Double)
data class RoutePoint(val lat: Double, val lon: Double, val at: Long, val accuracy: Float)

/** SQLite with explicit schema versions and transactional sensor checkpoints. */
class FitnessDatabase(context: Context) : SQLiteOpenHelper(context, "lynboa.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE daily (day TEXT PRIMARY KEY NOT NULL,
            steps INTEGER NOT NULL DEFAULT 0, water_ml INTEGER NOT NULL DEFAULT 0,
            milestones INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE checkpoint (id INTEGER PRIMARY KEY CHECK(id = 1),
            raw INTEGER NOT NULL, elapsed_nanos INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE walks (id INTEGER PRIMARY KEY AUTOINCREMENT,
            started_at INTEGER NOT NULL, ended_at INTEGER, distance_m REAL NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT,
            walk_id INTEGER NOT NULL REFERENCES walks(id), lat REAL NOT NULL, lon REAL NOT NULL,
            at INTEGER NOT NULL, accuracy REAL NOT NULL)""")
        db.execSQL("CREATE INDEX ix_points_walk ON points(walk_id, at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema versions get incremental ALTER TABLE migrations here.
        error("Missing migration from $oldVersion to $newVersion; refusing to delete user history")
    }

    @Synchronized fun day(date: String): DailyProgress {
        readableDatabase.rawQuery("SELECT steps,water_ml,milestones FROM daily WHERE day=?", arrayOf(date)).use {
            if (it.moveToFirst()) return DailyProgress(date, it.getInt(0), it.getInt(1), it.getInt(2))
        }
        return DailyProgress(date)
    }

    /** Checkpoint and daily delta commit together. App/service death cannot double count. */
    @Synchronized fun acceptSensor(raw: Long, elapsedNanos: Long, date: String,
                                   dailyGoal: Int): SensorUpdate {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var lastRaw: Long? = null
            var lastNanos: Long? = null
            db.rawQuery("SELECT raw,elapsed_nanos FROM checkpoint WHERE id=1", null).use {
                if (it.moveToFirst()) { lastRaw = it.getLong(0); lastNanos = it.getLong(1) }
            }
            val delta = FitnessMath.stepDelta(lastRaw, lastNanos, raw, elapsedNanos)
            var oldSteps = 0
            var oldMask = 0
            db.rawQuery("SELECT steps,milestones FROM daily WHERE day=?", arrayOf(date)).use {
                if (it.moveToFirst()) { oldSteps = it.getInt(0); oldMask = it.getInt(1) }
            }
            val newSteps = (oldSteps.toLong() + delta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            // Mask bits: 3k, 5k, 7k, daily goal.
            val targets = intArrayOf(3000, 5000, 7000, dailyGoal)
            var mask = oldMask
            targets.forEachIndexed { i, target -> if (newSteps >= target) mask = mask or (1 shl i) }
            val values = ContentValues().apply {
                put("day", date); put("steps", newSteps); put("milestones", mask)
            }
            db.insertWithOnConflict("daily", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE daily SET steps=?, milestones=? WHERE day=?",
                arrayOf<Any>(newSteps, mask, date))
            val checkpoint = ContentValues().apply {
                put("id", 1); put("raw", raw); put("elapsed_nanos", elapsedNanos)
            }
            db.insertWithOnConflict("checkpoint", null, checkpoint, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return SensorUpdate(newSteps, if (delta > 0) oldSteps else newSteps)
        } finally { db.endTransaction() }
    }

    @Synchronized fun addWater(date: String, ml: Int): DailyProgress {
        val values = ContentValues().apply { put("day", date) }
        writableDatabase.insertWithOnConflict("daily", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.execSQL("UPDATE daily SET water_ml = MAX(0, water_ml + ?) WHERE day=?",
            arrayOf<Any>(ml, date))
        return day(date)
    }

    @Synchronized fun newWalk(now: Long): Walk {
        val active = activeWalk()
        if (active != null) return active
        val values = ContentValues().apply { put("started_at", now); put("distance_m", 0.0) }
        val id = writableDatabase.insertOrThrow("walks", null, values)
        return Walk(id, now, null, 0.0)
    }

    @Synchronized fun finishWalk(now: Long) {
        writableDatabase.execSQL("UPDATE walks SET ended_at=? WHERE ended_at IS NULL", arrayOf(now))
    }

    @Synchronized fun activeWalk(): Walk? {
        readableDatabase.rawQuery("SELECT id,started_at,ended_at,distance_m FROM walks " +
            "WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1", null).use {
            return if (it.moveToFirst()) parseWalk(it) else null
        }
    }

    @Synchronized fun newestWalk(): Walk? {
        readableDatabase.rawQuery("SELECT id,started_at,ended_at,distance_m FROM walks " +
            "ORDER BY id DESC LIMIT 1", null).use {
            return if (it.moveToFirst()) parseWalk(it) else null
        }
    }

    /** All sessions are retained by date, including sessions with no accepted GPS fixes. */
    @Synchronized fun walkHistory(limit: Int = 365): List<Walk> {
        val walks = mutableListOf<Walk>()
        readableDatabase.rawQuery(
            "SELECT id,started_at,ended_at,distance_m FROM walks ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) walks.add(parseWalk(cursor))
        }
        return walks
    }

    private fun parseWalk(c: android.database.Cursor) = Walk(
        c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2), c.getDouble(3))

    @Synchronized fun points(walkId: Long): List<RoutePoint> {
        val result = mutableListOf<RoutePoint>()
        readableDatabase.rawQuery("SELECT lat,lon,at,accuracy FROM points WHERE walk_id=? ORDER BY at",
            arrayOf(walkId.toString())).use {
            while (it.moveToNext()) result.add(RoutePoint(it.getDouble(0), it.getDouble(1),
                it.getLong(2), it.getFloat(3)))
        }
        return result
    }

    /** Returns accepted route distance, or null when fix was rejected. */
    @Synchronized fun addLocation(walkId: Long, point: RoutePoint): Double? {
        if (point.accuracy <= 0f || point.accuracy > 35f ||
            !point.lat.isFinite() || !point.lon.isFinite()) return null
        val db = writableDatabase
        db.beginTransaction()
        try {
            var last: RoutePoint? = null
            db.rawQuery("SELECT lat,lon,at,accuracy FROM points WHERE walk_id=? ORDER BY at DESC LIMIT 1",
                arrayOf(walkId.toString())).use {
                if (it.moveToFirst()) last = RoutePoint(it.getDouble(0), it.getDouble(1),
                    it.getLong(2), it.getFloat(3))
            }
            val meters = last?.let {
                FitnessMath.acceptedSegmentMeters(it.lat, it.lon, it.at, point.lat, point.lon,
                    point.at, point.accuracy)
            } ?: if (last == null) 0.0 else return null
            val values = ContentValues().apply {
                put("walk_id", walkId); put("lat", point.lat); put("lon", point.lon)
                put("at", point.at); put("accuracy", point.accuracy)
            }
            db.insertOrThrow("points", null, values)
            db.execSQL("UPDATE walks SET distance_m = distance_m + ? WHERE id=?",
                arrayOf<Any>(meters, walkId))
            db.setTransactionSuccessful()
            return meters
        } finally { db.endTransaction() }
    }
}
