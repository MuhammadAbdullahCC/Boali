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
import android.location.LocationListener
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.withContext

/** Always-on low-power step counter, plus GPS ONLY during an explicitly started walk. */
private sealed interface GpsWork {
    data class Fix(val location: RoutePoint) : GpsWork
    data object Finish : GpsWork
}
class TrackingService : Service(), SensorEventListener {
    private val app get() = application as WalkApp
    private val repo get() = app.repository
    private val manager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val location by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val readings = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
    private val gpsReadings = Channel<GpsWork>(Channel.UNLIMITED)
    private var listening = false
    private var gpsCallback: LocationCallback? = null
    private var platformGpsListener: LocationListener? = null
    private val platformLocationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastReceivedElapsed = 0L
    private var lastNotificationAt = 0L
    private val locationWatchdog = object : Runnable {
        override fun run() {
            if (repo.activeWalk() == null) return
            val silence = SystemClock.elapsedRealtime() - lastReceivedElapsed
            if (silence > 18_000L && platformGpsListener == null) {
                startPlatformGpsFallback("Fused GPS silent for 18 seconds")
            }
            mainHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (work in gpsReadings) {
                when (work) {
                    is GpsWork.Fix -> repo.gps(work.location)
                    GpsWork.Finish -> {
                        repo.endWalk()
                        repo.locationState.value = "Walk route saved · recording stopped"
                        if (!listening) withContext(Dispatchers.Main) { stopSelf() }
                    }
                }
            }
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
        val stepsGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        when (intent?.action) {
            ACTION_START_WALK -> {
                startWalk()
                if (stepsGranted) startSteps()
            }
            ACTION_STOP_WALK -> stopWalk()
            else -> {
                if (stepsGranted) startSteps()
                else repo.sensorState.value = "Activity Recognition needed for steps"
                if (repo.activeWalk() != null && hasLocationPermission()) startGps()
                else if (!stepsGranted) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startSteps() {
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            repo.sensorState.value = "No hardware step counter on this device"
            return
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
            return
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
        repo.recorderState(true, "Subscribing to high accuracy GPS…")
        try {
            // Do not request the HEALTH service type unless its permission is granted.
            // This is important on devices where steps permission was denied.
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
            ServiceCompat.startForeground(this, CoachNotifications.TRACK_ID,
                CoachNotifications.persistent(this, repo.progress.value.steps, true),
                types)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateDistanceMeters(2f).setMinUpdateIntervalMillis(1500L).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach(::captureLocation)
                }
            }
            gpsCallback = callback
            lastReceivedElapsed = SystemClock.elapsedRealtime()
            mainHandler.removeCallbacks(locationWatchdog)
            mainHandler.postDelayed(locationWatchdog, 18_000L)
            location.requestLocationUpdates(request, callback, mainLooper)
                .addOnSuccessListener {
                    repo.recorderState(true, "GPS updates subscribed · take a walk")
                }
                .addOnFailureListener { e ->
                    gpsCallback = null
                    startPlatformGpsFallback("Fused GPS failed: " +
                        (e.localizedMessage ?: "unknown"))
                }
            // A recent fix can immediately anchor the track, before periodic updates.
            location.lastLocation.addOnSuccessListener { previous ->
                if (gpsCallback != null && previous != null &&
                    System.currentTimeMillis() - previous.time in 0..15_000L) {
                    captureLocation(previous)
                }
            }
            notifyProgress()
        } catch (e: SecurityException) {
            repo.recorderState(false, "GPS permission rejected")
            repo.locationState.value = "GPS permission rejected: " + e.message
            gpsCallback = null
        } catch (e: Exception) {
            gpsCallback = null
            startPlatformGpsFallback("Fused location unavailable: " +
                (e.localizedMessage ?: e.javaClass.simpleName))
        }
    }

    /**
     * Redmi/HyperOS fallback: Android's own GPS provider, independent of
     * Google Play services. Enable it if fused updates stop or fail.
     */
    private fun startPlatformGpsFallback(reason: String) {
        if (platformGpsListener != null || !hasLocationPermission()) return
        try {
            val provider = when {
                platformLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                platformLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                else -> {
                    repo.recorderState(false, "No Android location provider enabled")
                    return
                }
            }
            val fallback = LocationListener { fix -> captureLocation(fix) }
            platformLocationManager.requestLocationUpdates(
                provider, 2_000L, 2f, fallback, Looper.getMainLooper())
            platformGpsListener = fallback
            repo.recorderState(true, reason + " · Android " + provider + " active")
        } catch (error: Exception) {
            repo.recorderState(false, "Both GPS providers failed: " +
                (error.localizedMessage ?: error.javaClass.simpleName))
        }
    }

    private fun stopPlatformGps() {
        platformGpsListener?.let {
            platformLocationManager.removeUpdates(it)
        }
        platformGpsListener = null
        mainHandler.removeCallbacks(locationWatchdog)
    }

    private fun captureLocation(fix: Location) {
        // Watchdog cares about incoming fixes, even if accuracy filtering rejects one.
        lastReceivedElapsed = SystemClock.elapsedRealtime()
        repo.incomingGps()
        when {
            fix.isFromMockProvider -> repo.rejectedGps("Mock location ignored")
            !fix.hasAccuracy() -> repo.rejectedGps("GPS accuracy unavailable")
            fix.time > System.currentTimeMillis() + 10000L ->
                repo.rejectedGps("Future GPS timestamp ignored")
            System.currentTimeMillis() - fix.time > 30000L ->
                repo.rejectedGps("Stale GPS fix ignored")
            fix.accuracy > 45f -> repo.rejectedGps(
                "GPS ±" + fix.accuracy.toInt() + "m · need ≤45m")
            else -> gpsReadings.trySend(GpsWork.Fix(RoutePoint(
                fix.latitude, fix.longitude, fix.time, fix.accuracy)))
        }
    }

    private fun stopWalk() {
        gpsCallback?.let { location.removeLocationUpdates(it) }
        gpsCallback = null
        stopPlatformGps()
        repo.recorderState(false, "Saving final GPS points…")
        // Finish is serialized after queued position writes so route points aren't lost.
        gpsReadings.trySend(GpsWork.Finish)
        if (listening) ServiceCompat.startForeground(this, CoachNotifications.TRACK_ID,
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
        stopPlatformGps()
        readings.close(); gpsReadings.close(); scope.cancel()
        repo.sensorState.value = "Tracker stopped. Open app to restart."
        if (repo.activeWalk() != null) repo.recorderState(
            false, "GPS recorder stopped by Android · reopen Boali to resume")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.lynboa.walk.START"
        const val ACTION_START_WALK = "com.lynboa.walk.START_WALK"
        const val ACTION_STOP_WALK = "com.lynboa.walk.STOP_WALK"
    }
}
