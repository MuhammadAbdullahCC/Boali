package com.lynboa.walk.maps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.data.RoutePoint

/** Location preview is stopped when Lyn's tab closes. Only TrackingService records a route. */
@Composable
fun LynLocationPreview(repo: FitnessRepository, permitted: Boolean, recording: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(context, lifecycle, permitted, recording) {
        if (!permitted) {
            repo.locationState.value = "Grant precise location to show your position"
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val client = LocationServices.getFusedLocationProviderClient(context)
            var started = false
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { fix ->
                        if (fix.hasAccuracy() && fix.accuracy <= 100f &&
                            !fix.isFromMockProvider && fix.time <= System.currentTimeMillis() + 10000L) {
                            repo.showLiveLocation(RoutePoint(fix.latitude, fix.longitude, fix.time, fix.accuracy))
                        }
                    }
                }
            }
            fun stop() {
                if (started) {
                    client.removeLocationUpdates(callback)
                    started = false
                }
            }
            fun start() {
                if (started) return
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {
                    repo.locationState.value = "Precise location permission missing"
                    return
                }
                if (!manager.isLocationEnabled) {
                    repo.locationState.value = "Device location is OFF in Settings"
                    return
                }
                repo.locationState.value = "Finding your current position…"
                started = true
                client.lastLocation.addOnSuccessListener { fix ->
                    if (started && fix != null && fix.hasAccuracy() && !fix.isFromMockProvider &&
                        System.currentTimeMillis() - fix.time in 0..30000L && fix.accuracy <= 100f) {
                        repo.showLiveLocation(RoutePoint(fix.latitude, fix.longitude, fix.time, fix.accuracy))
                    }
                }
                try {
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
                        .setMinUpdateIntervalMillis(2000L)
                        .setMinUpdateDistanceMeters(2f).build()
                    client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
                        .addOnFailureListener { e ->
                            repo.locationState.value = "Location failed: " + (e.localizedMessage ?: "unavailable")
                            stop()
                        }
                } catch (e: Exception) {
                    repo.locationState.value = "Location unavailable: " + e.message
                    stop()
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> start()
                    Lifecycle.Event.ON_STOP -> stop()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
            onDispose {
                lifecycle.removeObserver(observer)
                stop()
            }
        }
    }
}
