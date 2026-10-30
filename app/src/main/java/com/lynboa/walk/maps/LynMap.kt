package com.lynboa.walk.maps

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lynboa.walk.data.RoutePoint
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

object MapConfig {
    const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
    const val CITY_LAT = 32.06344
    const val CITY_LON = 73.37776
    // Raster street tiles avoid the blank vector basemap encountered on some Redmi devices.
    // Interactive use only, NEVER prefetch OSM Standard tiles for offline storage.
    const val STREET_STYLE_JSON = """{"version":8,"name":"Boali streets","sources":{"streets":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"street-tiles","type":"raster","source":"streets"}]}"""
}

private fun routeGeoJson(points: List<RoutePoint>): String {
    val coordinates = JSONArray()
    points.forEach { coordinates.put(JSONArray().put(it.lon).put(it.lat)) }
    val features = JSONArray()
    if (points.size >= 2) features.put(JSONObject().put("type", "Feature")
        .put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", "LineString")
            .put("coordinates", coordinates)))
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun pointGeoJson(point: RoutePoint?): String {
    val features = JSONArray()
    if (point != null) features.put(JSONObject().put("type", "Feature")
        .put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", "Point")
            .put("coordinates", JSONArray().put(point.lon).put(point.lat))))
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

private fun fitRoute(map: MapLibreMap, points: List<RoutePoint>) {
    when {
        points.isEmpty() -> Unit
        points.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(
            LatLng(points[0].lat, points[0].lon), 16.0))
        else -> {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(LatLng(it.lat, it.lon)) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 58))
        }
    }
}

/** Map failure must never stop GPS tracking. Its sources come only from persisted routes. */
@Composable
fun LynMap(points: List<RoutePoint>, live: RoutePoint?, following: Boolean,
           recenterToken: Int, modifier: Modifier = Modifier, vectorStyle: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPoints by rememberUpdatedState(points)
    val latestLive by rememberUpdatedState(live)
    val latestFollowing by rememberUpdatedState(following)
    var mapError by remember(vectorStyle) { mutableStateOf<String?>(null) }
    var lastRecenterToken by remember { mutableIntStateOf(-1) }

    val mapResult = remember(context, vectorStyle) {
        runCatching {
            MapRuntime.ensure(context)
            MapView(context).also { view ->
                view.onCreate(null)
                view.addOnDidFailLoadingMapListener { message ->
                    mapError = message.take(140)
                }
                view.getMapAsync { map ->
                    val loaded = Style.OnStyleLoaded { style ->
                        mapError = null
                        style.addSource(GeoJsonSource("walk-route", routeGeoJson(latestPoints)))
                        style.addSource(GeoJsonSource("current-position", pointGeoJson(latestLive)))
                        style.addLayer(LineLayer("route-glow", "walk-route").withProperties(
                            PropertyFactory.lineColor(Color.rgb(123, 57, 186)),
                            PropertyFactory.lineWidth(14f),
                            PropertyFactory.lineOpacity(.50f)))
                        style.addLayer(LineLayer("route-line", "walk-route").withProperties(
                            PropertyFactory.lineColor(Color.rgb(246, 191, 255)),
                            PropertyFactory.lineWidth(5.5f)))
                        style.addLayer(CircleLayer("position-halo", "current-position").withProperties(
                            PropertyFactory.circleColor(Color.rgb(177, 112, 246)),
                            PropertyFactory.circleRadius(20f),
                            PropertyFactory.circleOpacity(.32f)))
                        style.addLayer(CircleLayer("position-core", "current-position").withProperties(
                            PropertyFactory.circleColor(Color.WHITE),
                            PropertyFactory.circleStrokeColor(Color.rgb(104, 43, 167)),
                            PropertyFactory.circleStrokeWidth(3f),
                            PropertyFactory.circleRadius(8f)))
                        val initial = latestLive ?: latestPoints.lastOrNull()
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(initial?.lat ?: MapConfig.CITY_LAT,
                                           initial?.lon ?: MapConfig.CITY_LON))
                            .zoom(if (initial != null) 16.0 else 13.4).build()
                        if (!latestFollowing && latestPoints.size > 1) {
                            view.post { fitRoute(map, latestPoints) }
                        }
                    }
                    if (vectorStyle) map.setStyle(MapConfig.STYLE, loaded)
                    else map.setStyle(Style.Builder().fromJson(MapConfig.STREET_STYLE_JSON), loaded)
                }
            }
        }
    }
    if (mapResult.isFailure) {
        Text("Map renderer unavailable. GPS walks continue recording: " +
            (mapResult.exceptionOrNull()?.localizedMessage ?: "Unknown error"),
            color = ComposeColor.White, modifier = modifier.fillMaxSize())
        return
    }
    val view = mapResult.getOrThrow()

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

    LaunchedEffect(view, points, live, following, recenterToken) {
        view.getMapAsync { map ->
            map.style?.getSourceAs<GeoJsonSource>("walk-route")?.setGeoJson(routeGeoJson(points))
            map.style?.getSourceAs<GeoJsonSource>("current-position")?.setGeoJson(pointGeoJson(live))
            if (!following && points.size >= 2) {
                view.post { if (view.width > 0 && view.height > 0) fitRoute(map, points) }
            } else if (live != null &&
                (following || recenterToken != lastRecenterToken)) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(live.lat, live.lon), 16.5))
                lastRecenterToken = recenterToken
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (mapError != null) {
            Text("Street tiles failed: " + mapError + ". Switch map style or check data connection.",
                color = ComposeColor.White,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(10.dp).background(ComposeColor(0xDD3D1F41)).padding(8.dp))
        }
    }
}
