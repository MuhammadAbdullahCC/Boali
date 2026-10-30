package com.lynboa.walk.maps

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
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
import com.lynboa.walk.data.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.modules.OfflineTileProvider
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Native Canvas map: entirely separate from MapLibre / OpenGL.
 * GPS route storage is in SQLite and never depends on whether map tiles load.
 */
@Composable
fun LynMap(points: List<RoutePoint>, live: RoutePoint?, following: Boolean,
           recenterToken: Int, modifier: Modifier = Modifier, vectorStyle: Boolean = false,
           offlineArchive: String? = null) {
    if (vectorStyle) {
        LegacyLynMap(points, live, following, recenterToken, modifier, true)
        return
    }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var diagnostics by remember { mutableStateOf("Checking map connection…") }
    var centerAt by remember { mutableLongStateOf(-1L) }
    var centerToken by remember { mutableIntStateOf(-1) }
    var displayedRoute by remember { mutableStateOf("") }
    var drawnRoute by remember { mutableStateOf("") }

    val mapResult = remember(context, offlineArchive) {
        runCatching {
            val config = Configuration.getInstance()
            config.userAgentValue = "Boali/0.7 (+https://github.com/MuhammadAbdullahCC/Boali)"
            config.osmdroidBasePath = File(context.filesDir, "boali-map").apply { mkdirs() }
            config.osmdroidTileCache = File(context.cacheDir, "boali-tiles").apply { mkdirs() }
            config.tileDownloadThreads = 2.toShort()
            MapView(context).apply {
                val offlineFile = offlineArchive?.let { File(it) }?.takeIf { it.isFile }
                if (offlineFile != null) {
                    setTileProvider(OfflineTileProvider(
                        SimpleRegisterReceiver(context), arrayOf(offlineFile)))
                    setTileSource(TileSourceFactory.MAPNIK)
                    setUseDataConnection(false)
                } else {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setUseDataConnection(true)
                }
                setMultiTouchControls(true)
                minZoomLevel = 3.0
                maxZoomLevel = 19.0
                controller.setZoom(16.0)
                val initial = live ?: points.lastOrNull()
                controller.setCenter(GeoPoint(initial?.lat ?: MapConfig.CITY_LAT,
                    initial?.lon ?: MapConfig.CITY_LON))
                setBackgroundColor(Color.rgb(231, 219, 238))
            }
        }
    }
    if (mapResult.isFailure) {
        Text("Native map unavailable: " + (mapResult.exceptionOrNull()?.message ?: "error"),
            color = UiColor.White, modifier = modifier.fillMaxSize())
        return
    }
    val map = mapResult.getOrThrow()
    // Rebuild only the route overlays when the saved GPS points change.
    // Breaks do NOT connect across long GPS outages.
    val routeOverlays = remember(map) { mutableListOf<Polyline>() }
    val position = remember(map) {
        Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "Lyn · current GPS position"
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(6, Color.rgb(116, 42, 173))
                setSize(42, 42)
            }
            map.overlays.add(this)
        }
    }

    LaunchedEffect(map, offlineArchive) {
        if (offlineArchive != null) {
            diagnostics = "OFFLINE · local MBTiles street map"
            return@LaunchedEffect
        }
        diagnostics = withContext(Dispatchers.IO) {
            try {
                val c = live ?: points.lastOrNull()
                val lat = (c?.lat ?: MapConfig.CITY_LAT).coerceIn(-85.0, 85.0)
                val lon = c?.lon ?: MapConfig.CITY_LON
                val zoom = 15
                val n = 1 shl zoom
                val x = floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
                val y = floor((1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * n)
                    .toInt().coerceIn(0, n - 1)
                val request = URL("https://tile.openstreetmap.org/" +
                    zoom + "/" + x + "/" + y + ".png").openConnection() as HttpURLConnection
                try {
                    request.setRequestProperty("User-Agent",
                        "Boali/0.7 (+https://github.com/MuhammadAbdullahCC/Boali)")
                    request.connectTimeout = 12000
                    request.readTimeout = 12000
                    request.useCaches = true
                    val status = request.responseCode
                    if (status == 200) {
                        val magic = ByteArray(4)
                        val count = request.inputStream.use { it.read(magic) }
                        if (count == 4 && magic[0] == 0x89.toByte() &&
                            magic[1] == 0x50.toByte()) "Tile server OK (PNG)"
                        else "Server returned an unexpected tile image"
                    } else "Tile server HTTP " + status
                } finally { request.disconnect() }
            } catch (e: Exception) {
                "Tile error: " + e.javaClass.simpleName + " · " +
                    (e.localizedMessage ?: "network problem").take(100)
            }
        }
        Log.i("BoaliMap", diagnostics)
    }

    DisposableEffect(map, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            map.onPause()
            map.onDetach()
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { view ->
            val path = points.map { GeoPoint(it.lat, it.lon) }
            val routeId = (points.firstOrNull()?.at ?: 0L).toString() + ":" +
                (points.lastOrNull()?.at ?: 0L) + ":" + points.size
            if (routeId != drawnRoute) {
                drawnRoute = routeId
                routeOverlays.forEach { view.overlays.remove(it) }
                routeOverlays.clear()
                val segments = mutableListOf<MutableList<GeoPoint>>()
                points.forEach { point ->
                    if (segments.isEmpty() || point.breakBefore) {
                        segments.add(mutableListOf())
                    }
                    segments.last().add(GeoPoint(point.lat, point.lon))
                }
                segments.filter { it.size >= 2 }.forEach { segment ->
                    val halo = Polyline(view).apply {
                        outlinePaint.color = Color.rgb(67, 20, 107)
                        outlinePaint.strokeWidth = 17f
                        setPoints(segment)
                    }
                    val trail = Polyline(view).apply {
                        outlinePaint.color = Color.rgb(233, 133, 255)
                        outlinePaint.strokeWidth = 8f
                        setPoints(segment)
                    }
                    // Insert behind the current-position dot.
                    view.overlays.add(0, halo)
                    view.overlays.add(1, trail)
                    routeOverlays.add(halo)
                    routeOverlays.add(trail)
                }
            }
            position.isEnabled = live != null
            if (live != null) position.position = GeoPoint(live.lat, live.lon)

            if (!following && path.isNotEmpty() && displayedRoute != routeId) {
                displayedRoute = routeId
                view.post {
                    if (view.width > 0 && view.height > 0) {
                        val north = path.maxOf { it.latitude }
                        val south = path.minOf { it.latitude }
                        val east = path.maxOf { it.longitude }
                        val west = path.minOf { it.longitude }
                        if (north - south < .00002 && east - west < .00002) {
                            view.controller.setCenter(path.last())
                            view.controller.setZoom(17.0)
                        } else {
                            view.zoomToBoundingBox(
                                BoundingBox(north, east, south, west), true, 65)
                        }
                    }
                }
            } else if (live != null &&
                (recenterToken != centerToken || following && live.at != centerAt)) {
                view.controller.animateTo(GeoPoint(live.lat, live.lon))
                centerAt = live.at
                centerToken = recenterToken
            }
            view.invalidate()
        })
        Text(diagnostics, color = UiColor.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(UiColor(0xDD39264B)).padding(6.dp))
    }
}
