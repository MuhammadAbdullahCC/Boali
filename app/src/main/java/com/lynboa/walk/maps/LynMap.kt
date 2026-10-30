package com.lynboa.walk.maps

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
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
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

object MapConfig {
    const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
    const val CITY_LAT = 32.06344
    const val CITY_LON = 73.37776
}

private fun geoJson(points: List<RoutePoint>): String {
    val coords = JSONArray()
    points.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
    return JSONObject().put("type", "FeatureCollection")
        .put("features", if (points.size < 2) JSONArray() else JSONArray().put(JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "LineString")
                .put("coordinates", coords)))).toString()
}

private fun pointGeoJson(point: RoutePoint?): String {
    val features = JSONArray()
    if (point != null) features.put(JSONObject().put("type", "Feature")
        .put("properties", JSONObject())
        .put("geometry", JSONObject().put("type", "Point")
            .put("coordinates", JSONArray().put(point.lon).put(point.lat))))
    return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
}

@Composable
fun LynMap(points: List<RoutePoint>, live: RoutePoint?, following: Boolean,
           recenterToken: Int, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestLive by rememberUpdatedState(live)
    val latestPoints by rememberUpdatedState(points)
    var centeredOnce by remember { mutableStateOf(false) }
    var lastRecenterToken by remember { mutableIntStateOf(0) }
    val mapViewResult = remember(context) {
        runCatching {
            MapRuntime.ensure(context)
            MapView(context).also { view ->
            view.onCreate(null)
            view.getMapAsync { map ->
                map.setStyle(MapConfig.STYLE) { style ->
                    style.addSource(GeoJsonSource("walk-route", geoJson(latestPoints)))
                    style.addSource(GeoJsonSource("current-position", pointGeoJson(latestLive)))
                    style.addLayer(LineLayer("route-glow", "walk-route").withProperties(
                        PropertyFactory.lineColor(Color.rgb(183, 101, 250)),
                        PropertyFactory.lineWidth(13f), PropertyFactory.lineOpacity(0.35f)))
                    style.addLayer(LineLayer("route-line", "walk-route").withProperties(
                        PropertyFactory.lineColor(Color.rgb(246, 190, 255)),
                        PropertyFactory.lineWidth(5f)))
                    style.addLayer(CircleLayer("position-halo", "current-position").withProperties(
                        PropertyFactory.circleColor(Color.rgb(188, 121, 251)),
                        PropertyFactory.circleRadius(23f),
                        PropertyFactory.circleOpacity(.26f)))
                    style.addLayer(CircleLayer("position-core", "current-position").withProperties(
                        PropertyFactory.circleColor(Color.rgb(255, 249, 255)),
                        PropertyFactory.circleStrokeColor(Color.rgb(106, 45, 187)),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleRadius(8f)))
                }
                val last = latestPoints.lastOrNull()
                val current = latestLive
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(current?.lat ?: last?.lat ?: MapConfig.CITY_LAT,
                                   current?.lon ?: last?.lon ?: MapConfig.CITY_LON))
                    .zoom(if (current != null || last != null) 16.0 else 13.4).build()
            }
            }
        }
    }
    if (mapViewResult.isFailure) {
        Text("Lyn's map cannot start on this device, but step tracking still works. " +
            (mapViewResult.exceptionOrNull()?.message ?: "Map error"),
            modifier = modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White)
        return
    }
    val mapView = mapViewResult.getOrThrow()

    DisposableEffect(mapView, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    LaunchedEffect(mapView, points, live, following, recenterToken) {
        mapView.getMapAsync { map ->
            map.style?.getSourceAs<GeoJsonSource>("walk-route")?.setGeoJson(geoJson(points))
            map.style?.getSourceAs<GeoJsonSource>("current-position")?.setGeoJson(pointGeoJson(live))
            if (live != null && (!centeredOnce || following || recenterToken != lastRecenterToken)) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(live.lat, live.lon), 16.5))
                centeredOnce = true
                lastRecenterToken = recenterToken
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}
