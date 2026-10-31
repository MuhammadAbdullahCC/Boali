package com.lynboa.walk.maps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.lynboa.walk.data.RoutePoint

/** This is a licensed Google Maps SDK basemap, not a Google Maps webpage or screenshot. */
object GoogleMapsSetup {
    fun isConfigured(context: Context): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA)
        val key = info.metaData?.getString("com.google.android.geo.API_KEY").orEmpty()
        key.startsWith("AIza") && key.length >= 35
    }.getOrDefault(false)
}

/**
 * Google Maps shows the data collected independently by TrackingService.
 * Never uses Directions API to invent paths or overwrite actual GPS points.
 */
@Composable
fun GoogleLynMap(
    points: List<RoutePoint>,
    live: RoutePoint?,
    following: Boolean,
    recenterToken: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var mapStatus by remember { mutableStateOf("Loading Google Maps…") }
    var lastRouteKey by remember { mutableStateOf("") }
    var lastCenteredAt by remember { mutableLongStateOf(0L) }
    var lastRecenterToken by remember { mutableIntStateOf(-1) }
    var lastReviewKey by remember { mutableStateOf("") }
    val renderedLines = remember { mutableListOf<Polyline>() }
    var positionMarker by remember { mutableStateOf<Marker?>(null) }
    var originMarker by remember { mutableStateOf<Marker?>(null) }
    var finishMarker by remember { mutableStateOf<Marker?>(null) }
    val latestLive by rememberUpdatedState(live)
    val latestPoints by rememberUpdatedState(points)

    val viewResult = remember(context) {
        runCatching {
            MapView(context).also { view ->
                view.onCreate(null)
                view.getMapAsync { map ->
                    googleMap = map
                    map.mapType = GoogleMap.MAP_TYPE_NORMAL
                    map.uiSettings.isMapToolbarEnabled = false
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isCompassEnabled = true
                    map.setOnMapLoadedCallback { mapStatus = "Google Maps loaded" }
                    val initial = latestLive ?: latestPoints.lastOrNull()
                    if (initial != null) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(initial.lat, initial.lon), 16.5f))
                    }
                }
            }
        }
    }

    if (viewResult.isFailure) {
        Text("Google Maps could not initialize: " +
            (viewResult.exceptionOrNull()?.localizedMessage ?: "device error"),
            color = UiColor.White, modifier = modifier.fillMaxSize())
        return
    }
    val view = viewResult.getOrThrow()
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(googleMap, points, live, following, recenterToken) {
        val map = googleMap ?: return@LaunchedEffect
        val routeKey = (points.firstOrNull()?.at ?: 0L).toString() + ":" +
            (points.lastOrNull()?.at ?: 0L) + ":" + points.size
        if (lastRouteKey != routeKey) {
            lastRouteKey = routeKey
            renderedLines.forEach { it.remove() }
            renderedLines.clear()
            val segments = mutableListOf<MutableList<LatLng>>()
            points.forEach { p ->
                if (segments.isEmpty() || p.breakBefore) segments.add(mutableListOf())
                segments.last().add(LatLng(p.lat, p.lon))
            }
            segments.filter { it.size > 1 }.forEach { segment ->
                renderedLines.add(map.addPolyline(PolylineOptions()
                    .addAll(segment).color(Color.rgb(100, 39, 151))
                    .width(17f).zIndex(2f)))
                renderedLines.add(map.addPolyline(PolylineOptions()
                    .addAll(segment).color(Color.rgb(235, 145, 255))
                    .width(8f).zIndex(3f)))
            }
            originMarker?.remove()
            finishMarker?.remove()
            originMarker = points.firstOrNull()?.let {
                map.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon))
                    .title("Walk started")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
            }
            finishMarker = points.lastOrNull()?.let {
                map.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon))
                    .title("Last saved GPS point")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET)))
            }
        }
        if (live != null) {
            val here = LatLng(live.lat, live.lon)
            if (positionMarker == null) {
                positionMarker = map.addMarker(MarkerOptions().position(here)
                    .title("Your live GPS location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                    .zIndex(10f))
            } else positionMarker?.position = here

            if (recenterToken != lastRecenterToken ||
                (following && live.at - lastCenteredAt >= 6500L)) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(here, 16.5f))
                lastCenteredAt = live.at
                lastRecenterToken = recenterToken
            }
        } else {
            positionMarker?.remove()
            positionMarker = null
        }
        if (!following && points.isNotEmpty() && lastReviewKey != routeKey) {
            lastReviewKey = routeKey
            if (points.size >= 2) {
                val bounds = LatLngBounds.builder()
                points.forEach { bounds.include(LatLng(it.lat, it.lon)) }
                view.post {
                    if (view.width > 0 && view.height > 0) {
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 65))
                    }
                }
            } else {
                val p = points[0]
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 17f))
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Text(mapStatus, color = UiColor.White,
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(7.dp).background(UiColor(0xDD352144)).padding(6.dp))
    }
}
