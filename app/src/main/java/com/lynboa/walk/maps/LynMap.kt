package com.lynboa.walk.maps

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
        .put("features", JSONArray().put(JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", JSONObject().put("type", "LineString")
                .put("coordinates", coords)))).toString()
}

@Composable
fun LynMap(points: List<RoutePoint>, following: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) {
        MapView(context).also { view ->
            view.onCreate(null)
            view.getMapAsync { map ->
                map.setStyle(MapConfig.STYLE) { style ->
                    style.addSource(GeoJsonSource("walk-route", geoJson(points)))
                    style.addLayer(LineLayer("route-glow", "walk-route").withProperties(
                        PropertyFactory.lineColor(Color.rgb(183, 101, 250)),
                        PropertyFactory.lineWidth(13f), PropertyFactory.lineOpacity(0.35f)))
                    style.addLayer(LineLayer("route-line", "walk-route").withProperties(
                        PropertyFactory.lineColor(Color.rgb(246, 190, 255)),
                        PropertyFactory.lineWidth(5f)))
                }
                val last = points.lastOrNull()
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(last?.lat ?: MapConfig.CITY_LAT,
                                   last?.lon ?: MapConfig.CITY_LON))
                    .zoom(if (last != null) 16.0 else 13.4).build()
            }
        }
    }

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
    LaunchedEffect(mapView, points, following) {
        mapView.getMapAsync { map ->
            (map.style?.getSourceAs<GeoJsonSource>("walk-route"))?.setGeoJson(geoJson(points))
            if (following && points.isNotEmpty()) {
                val p = points.last()
                map.animateCamera(CameraUpdateFactory.newLatLng(LatLng(p.lat, p.lon)))
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}
