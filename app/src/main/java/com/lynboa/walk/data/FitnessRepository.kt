package com.lynboa.walk.data

import android.content.Context
import com.lynboa.walk.domain.FitnessMath
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class FitnessRepository(context: Context) {
    private val db = FitnessDatabase(context.applicationContext)
    private val prefs = context.getSharedPreferences("lynboa_settings", Context.MODE_PRIVATE)
    private val launchDay: LocalDate = LocalDate.parse(
        prefs.getString("launch_day", null) ?: LocalDate.now().toString().also {
            prefs.edit().putString("launch_day", it).apply()
        })

    val progress = MutableStateFlow(db.day(todayKey()))
    val walk = MutableStateFlow(db.newestWalk())
    val route = MutableStateFlow(walk.value?.let { db.points(it.id) } ?: emptyList())
    val history = MutableStateFlow(db.walkHistory())
    val reviewWalk = MutableStateFlow<Walk?>(null)
    val reviewRoute = MutableStateFlow<List<RoutePoint>>(emptyList())
    val sensorState = MutableStateFlow("Not started")
    val liveLocation = MutableStateFlow<RoutePoint?>(null)
    val locationState = MutableStateFlow("Location permission not requested")
    fun showLiveLocation(p: RoutePoint) {
        if (!p.lat.isFinite() || !p.lon.isFinite() || p.lat !in -90.0..90.0 ||
            p.lon !in -180.0..180.0 || p.accuracy <= 0f) return
        liveLocation.value = p
        locationState.value = "Live position · ±" + p.accuracy.toInt() + " m"
    }
    val offlineStatus = MutableStateFlow("Jalalpur Bhattian map: online")
    val weightKg = MutableStateFlow(prefs.getInt("weight_kg", 75))
    val strideCm = MutableStateFlow(prefs.getInt("stride_cm", 70))
    val waterReminders = MutableStateFlow(prefs.getBoolean("water_reminders", true))

    fun goal(): Int = FitnessMath.dailyGoal(launchDay, LocalDate.now())
    fun goalLabel(): String = "Starts at 7,000, +100/day, max 12,000"

    @Synchronized fun refresh() {
        progress.value = db.day(todayKey())
        walk.value = db.newestWalk()
        route.value = walk.value?.let { db.points(it.id) } ?: emptyList()
        history.value = db.walkHistory()
        reviewWalk.value?.let { reviewed ->
            reviewWalk.value = history.value.firstOrNull { it.id == reviewed.id }
            reviewRoute.value = reviewWalk.value?.let { db.points(it.id) } ?: emptyList()
        }
    }

    /** Preview any saved session without modifying its route or ending the live walk. */
    @Synchronized fun selectWalk(id: Long?) {
        reviewWalk.value = if (id == null) null else history.value.firstOrNull { it.id == id }
        reviewRoute.value = reviewWalk.value?.let { db.points(it.id) } ?: emptyList()
    }

    @Synchronized fun sensorReading(raw: Long, elapsedNanos: Long): SensorUpdate {
        val result = db.acceptSensor(raw, elapsedNanos, todayKey(), goal())
        progress.value = db.day(todayKey())
        return result
    }

    fun water(ml: Int) { progress.value = db.addWater(todayKey(), ml) }
    fun beginWalk() { db.newWalk(System.currentTimeMillis()); refresh() }
    fun endWalk() { db.finishWalk(System.currentTimeMillis()); refresh() }
    fun activeWalk(): Walk? = db.activeWalk()

    fun gps(point: RoutePoint) {
        showLiveLocation(point)
        val active = db.activeWalk() ?: return
        if (db.addLocation(active.id, point) != null) refresh()
    }

    fun setWeight(kg: Int) {
        weightKg.value = kg.coerceIn(30, 250)
        prefs.edit().putInt("weight_kg", weightKg.value).apply()
    }
    fun setStride(cm: Int) {
        strideCm.value = cm.coerceIn(35, 120)
        prefs.edit().putInt("stride_cm", strideCm.value).apply()
    }
    fun setWaterReminders(enabled: Boolean) {
        waterReminders.value = enabled
        prefs.edit().putBoolean("water_reminders", enabled).apply()
    }
    companion object { fun todayKey(): String = LocalDate.now().toString() }
}
