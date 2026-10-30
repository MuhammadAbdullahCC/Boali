package com.lynboa.walk.tracking

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lynboa.walk.WalkApp
import com.lynboa.walk.data.RoutePoint
import com.lynboa.walk.notifications.CoachNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Always-on low-power step counter, plus GPS ONLY during an explicitly started walk. */
class TrackingService : Service(), SensorEventListener {
    private val app get() = application as WalkApp
    private val repo get() = app.repository
    private val manager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val location by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readings = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
    private val gpsReadings = Channel<RoutePoint>(Channel.UNLIMITED)
    private var listening = false
    private var gpsCallback: LocationCallback? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (fix in gpsReadings) repo.gps(fix)
        }
        scope.launch {
            for ((raw, nanos) in readings) {
                val before = repo.progress.value.steps
                val result = repo.sensorReading(raw, nanos)
                val today = repo.progress.value.steps
                val thresholds = intArrayOf(3000, 5000, 7000, repo.goal())
                thresholds.forEach { threshold ->
                    if (before < threshold && today >= threshold) {
                        CoachNotifications.reward(this@TrackingService, threshold)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotificationAt > 60000L) {
                    lastNotificationAt = now
                    notifyProgress()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) {
            repo.sensorState.value = "Activity permission required"
            stopSelf(); return START_NOT_STICKY
        }
        startSteps()
        when (intent?.action) {
            ACTION_START_WALK -> startWalk()
            ACTION_STOP_WALK -> stopWalk()
            else -> if (repo.activeWalk() != null && hasLocationPermission()) startGps()
        }
        return START_STICKY
    }

    private fun startSteps() {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            repo.sensorState.value = "No hardware step counter on this device"
            stopSelf(); return
        }
        val notification = CoachNotifications.persistent(this, repo.progress.value.steps,
            repo.activeWalk() != null)
        try {
            ServiceCompat.startForeground(this, CoachNotifications.TRACK_ID, notification,
                if (repo.activeWalk() != null && hasLocationPermission())
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (error: Exception) {
            repo.sensorState.value = "Tracking could not start: ${error.message}"
            stopSelf(); return
        }
        if (!listening) {
            listening = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        repo.sensorState.value = if (listening) "Hardware step counter active" else "Sensor registration failed"
    }

    private fun startWalk() {
        if (!hasLocationPermission()) {
            repo.sensorState.value = "Precise location permission required for Lyn"
            return
        }
        if (repo.activeWalk() == null) repo.beginWalk()
        repo.locationState.value = "Searching for GPS…"
        startGps()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startGps() {
        if (gpsCallback != null || !hasLocationPermission()) return
        if (!(getSystemService(LOCATION_SERVICE) as LocationManager).isLocationEnabled) {
            repo.locationState.value = "Location is OFF in Android settings. Enable it to record Lyn\u0027s route."
            return
        }
        repo.locationState.value = "Location ON · waiting for accurate GPS fix…"
        try {
            ServiceCompat.startForeground(this, CoachNotifications.TRACK_ID,
                CoachNotifications.persistent(this, repo.progress.value.steps, true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateDistanceMeters(5f).setMinUpdateIntervalMillis(2500L).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach(::captureLocation)
                }
            }
            gpsCallback = callback
            location.requestLocationUpdates(request, callback, mainLooper)
                .addOnFailureListener { e ->
                    repo.locationState.value = "GPS request failed: " + (e.localizedMessage ?: "unavailable")
                    gpsCallback = null
                }
            notifyProgress()
        } catch (e: SecurityException) {
            repo.locationState.value = "GPS permission rejected: " + e.message
            gpsCallback = null
        } catch (e: Exception) {
            repo.locationState.value = "Could not start GPS: " + e.message
            gpsCallback = null
        }
    }

    private fun captureLocation(fix: Location) {
        if (fix.isFromMockProvider || !fix.hasAccuracy()) return
        if (fix.time > System.currentTimeMillis() + 10000L) return
        gpsReadings.trySend(RoutePoint(fix.latitude, fix.longitude, fix.time, fix.accuracy))
    }

    private fun stopWalk() {
        gpsCallback?.let { location.removeLocationUpdates(it) }
        gpsCallback = null
        repo.endWalk()
        repo.locationState.value = "Route saved · recording stopped"
        ServiceCompat.startForeground(this, CoachNotifications.TRACK_ID,
            CoachNotifications.persistent(this, repo.progress.value.steps, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    }

    private fun notifyProgress() {
        val note = CoachNotifications.persistent(this, repo.progress.value.steps, gpsCallback != null)
        getSystemService(android.app.NotificationManager::class.java)
            .notify(CoachNotifications.TRACK_ID, note)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        readings.trySend(event.values[0].toLong() to event.timestamp)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        if (listening) manager.unregisterListener(this)
        gpsCallback?.let { location.removeLocationUpdates(it) }
        readings.close(); gpsReadings.close(); scope.cancel()
        repo.sensorState.value = "Tracker stopped. Open app to restart."
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.lynboa.walk.START"
        const val ACTION_START_WALK = "com.lynboa.walk.START_WALK"
        const val ACTION_STOP_WALK = "com.lynboa.walk.STOP_WALK"
    }
}
