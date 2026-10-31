package com.lynboa.walk.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.lynboa.walk.R
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.data.RoutePoint
import com.lynboa.walk.domain.FitnessMath
import com.lynboa.walk.maps.LynMap
import com.lynboa.walk.maps.GoogleLynMap
import com.lynboa.walk.maps.GoogleMapsSetup
import com.lynboa.walk.maps.LynLocationPreview
import com.lynboa.walk.maps.OfflineRasterMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Pink = Color(0xFFF0A4CF)
private val Lilac = Color(0xFFC9A1FF)
private val Ink = Color(0xFF160E1A)
private val Chalk = Color(0xFFFFF1F7)
private val Glass = Color(0x663D293E)

@Composable
fun LynBoaScreen(repo: FitnessRepository, sensorAvailable: Boolean, hasPermission: Boolean,
                 hasLocation: Boolean, onRequestLocation: () -> Unit,
                 onEnable: () -> Unit, onStartWalk: () -> Unit, onStopWalk: () -> Unit,
                 onOpenBatterySettings: () -> Unit) {
    val progress by repo.progress.collectAsState()
    val walk by repo.walk.collectAsState()
    val route by repo.route.collectAsState()
    val history by repo.history.collectAsState()
    val reviewedWalk by repo.reviewWalk.collectAsState()
    val reviewedRoute by repo.reviewRoute.collectAsState()
    val displayWalk = reviewedWalk ?: walk
    val displayRoute = if (reviewedWalk != null) reviewedRoute else route
    var allHistory by remember { mutableStateOf(false) }
    var vectorMap by remember { mutableStateOf(false) }
    var useOfflineMap by remember { mutableStateOf(false) }
    val sensorStatus by repo.sensorState.collectAsState()
    val livePosition by repo.liveLocation.collectAsState()
    val locationStatus by repo.locationState.collectAsState()
    val gpsRecorder by repo.gpsRecorder.collectAsState()
    var recenterToken by remember { mutableIntStateOf(0) }
    val offlineStatus by repo.offlineStatus.collectAsState()
    val offlineArchive by repo.offlineArchive.collectAsState()
    val weight by repo.weightKg.collectAsState()
    val stride by repo.strideCm.collectAsState()
    val reminders by repo.waterReminders.collectAsState()
    var lyn by remember { mutableStateOf(false) }
    val accent = if (lyn) Lilac else Pink
    val context = LocalContext.current
    val googleMapsReady = remember(context) { GoogleMapsSetup.isConfigured(context) }
    var showGoogleMap by remember { mutableStateOf(googleMapsReady) }
    val scope = rememberCoroutineScope()
    val importOfflineMap = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { chosen ->
        if (chosen != null) {
            scope.launch {
                repo.offlineStatus.value = "Importing offline map…"
                val result = withContext(Dispatchers.IO) {
                    runCatching { OfflineRasterMap.import(context, chosen) }
                }
                result.onSuccess { message ->
                    repo.setImportedMap(OfflineRasterMap.archive(context).absolutePath)
                    repo.offlineStatus.value = message
                    useOfflineMap = true
                    vectorMap = false
                }.onFailure { e ->
                    repo.offlineStatus.value = "Map import failed: " +
                        (e.localizedMessage ?: "unknown error")
                }
            }
        }
    }
    var exportWalkId by remember { mutableStateOf<Long?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        val walkId = exportWalkId
        if (uri != null && walkId != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(repo.exportGpx(walkId))
                } ?: error("Could not write the selected file")
            }.onSuccess { exportMessage = "GPX route exported successfully." }
                .onFailure { exportMessage = "GPX export failed: " + it.localizedMessage }
        }
        exportWalkId = null
    }
    val locationManager = remember(context) { context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager }
    val gpsEnabled = locationManager.isLocationEnabled
    val recording = walk != null && walk!!.endedAt == null
    val hasFix = livePosition != null && System.currentTimeMillis() - livePosition!!.at < 45_000L
    val goal = repo.goal()
    val percent = (progress.steps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)

    MaterialTheme(colorScheme = darkColorScheme(primary = accent, surface = Ink)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            if (lyn) listOf(Color(0xFF190D2F), Ink, Color(0xFF0B0712))
            else listOf(Color(0xFF391B32), Ink, Color(0xFF150A15))))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("LYN  ×  BOA", color = Chalk, fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp)
                        Text("Your footsteps. Their judgment.", color = Color.LightGray, fontSize = 11.sp)
                    }
                    Text(LocalDate.now().toString(), color = accent, fontSize = 12.sp)
                }

                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .background(Color.White.copy(alpha = .07f), RoundedCornerShape(24.dp))
                    .padding(5.dp)) {
                    TabPill("BOA · STEPS", !lyn, Pink, Modifier.weight(1f)) { lyn = false }
                    Spacer(Modifier.width(6.dp))
                    TabPill("LYN · ROUTE", lyn, Lilac, Modifier.weight(1f)) { lyn = true }
                }

                CharacterHero(lyn, accent)
                if (lyn) {
                    LynLocationPreview(repo, hasLocation, recording)
                    GlassCard(accent) {
                        Text("LIVE POSITION", color = Lilac, fontWeight = FontWeight.Bold)
                        Text(when {
                            !hasLocation -> "Location permission needed. Tap Allow precise location."
                            !gpsEnabled -> "Location is OFF in Android settings."
                            hasFix -> "GPS FIX · " + locationStatus
                            else -> locationStatus
                        }, color = Chalk, fontSize = 13.sp)
                        Text(when {
                            recording && gpsRecorder.serviceActive ->
                                "● RECORDER ACTIVE · " + route.size + " GPS points saved"
                            recording -> "● WALK STARTED · GPS recorder inactive"
                            hasFix -> "● LIVE PREVIEW · tap Record walk to save movement"
                            else -> "○ Waiting for GPS · route not recording"
                        }, color = Lilac, fontSize = 11.sp)
                        if (recording) {
                            Text("GPS: " + gpsRecorder.received + " received · " +
                                gpsRecorder.saved + " stored · " + gpsRecorder.skipped + " skipped",
                                color = Chalk, fontSize = 12.sp)
                            Text(gpsRecorder.reason, color = Color.LightGray, fontSize = 11.sp)
                            if (route.size == 1) Text(
                                "First GPS point saved. Walk at least 20–30 m for a visible trail.",
                                color = Lilac, fontSize = 11.sp)
                        }
                        if (hasFix) Text(
                            "Position: ${String.format("%.5f", livePosition!!.lat)}, ${String.format("%.5f", livePosition!!.lon)} · ±${livePosition!!.accuracy.toInt()} m",
                            color = Color.LightGray, fontSize = 11.sp)
                        if (!hasLocation) Button(onClick = onRequestLocation,
                            colors = ButtonDefaults.buttonColors(containerColor = Lilac,
                                contentColor = Ink)) { Text("Allow precise location") }
                    }
                    GlassCard(accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (reviewedWalk != null) "SAVED WALK · REVIEW"
                                    else if (recording) "WALK IN PROGRESS"
                                    else "YOUR LAST WALK", color = accent, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text(String.format("%.2f km", (displayWalk?.distanceMeters ?: 0.0) / 1000.0),
                                    color = Chalk, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                                Text("GPS route distance · accepted fixes only", color = Color.LightGray,
                                    fontSize = 11.sp)
                            }
                            Button(onClick = {
                                if (walk != null && walk!!.endedAt == null) onStopWalk()
                                else onStartWalk()
                            }, colors = ButtonDefaults.buttonColors(containerColor = Lilac,
                                contentColor = Color(0xFF23102C))) {
                                Text(if (recording) "End walk" else "Record walk")
                            }
                        }
                    }
                    Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().height(350.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Lilac.copy(alpha = .6f), RoundedCornerShape(24.dp))) {
                        if (showGoogleMap && googleMapsReady) {
                            GoogleLynMap(displayRoute,
                                if (reviewedWalk == null) livePosition else null,
                                following = recording && reviewedWalk == null,
                                recenterToken = recenterToken)
                        } else {
                            LynMap(displayRoute,
                                if (reviewedWalk == null) livePosition else null,
                                following = recording && reviewedWalk == null,
                                recenterToken = recenterToken, vectorStyle = vectorMap,
                                offlineArchive = if (useOfflineMap && !vectorMap)
                                    offlineArchive else null)
                        }
                        Box(Modifier.align(Alignment.TopStart).padding(12.dp)
                            .background(Color(0xDD27172F), RoundedCornerShape(20.dp))
                            .padding(horizontal = 13.dp, vertical = 8.dp)) {
                            Text(if (hasFix) "● LIVE · ±${livePosition!!.accuracy.toInt()} m"
                                else "Jalalpur · waiting for GPS", color = Chalk, fontSize = 12.sp)
                        }
                        TextButton(onClick = { recenterToken++ }, enabled = livePosition != null,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                                .background(Color(0xDD432855), RoundedCornerShape(14.dp))) {
                            Text("⌖ Center on me", color = Chalk)
                        }
                        Box(Modifier.align(Alignment.BottomStart).padding(10.dp)
                            .background(Color(0xDD1D1425), RoundedCornerShape(12.dp))
                            .padding(6.dp)) {
                            Text(if (vectorMap) "© OpenStreetMap contributors · OpenFreeMap"
                                else "© OpenStreetMap contributors · Standard street tiles", color = Chalk,
                                fontSize = 9.sp)
                        }
                    }
                    GlassCard(accent) {
                        Text("GOOGLE MAPS", color = Lilac, fontWeight = FontWeight.Bold)
                        if (googleMapsReady) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Display Google Maps with Boali's recorded GPS trail",
                                    modifier = Modifier.weight(1f), color = Chalk,
                                    fontSize = 12.sp)
                                Switch(checked = showGoogleMap, onCheckedChange = {
                                    showGoogleMap = it
                                    if (it) {
                                        useOfflineMap = false
                                        vectorMap = false
                                    }
                                })
                            }
                        } else {
                            Text("Google Maps is built into Boali, but it needs a Google Cloud Maps SDK for Android API key and billing enabled. Add the key as the GitHub Actions secret GOOGLE_MAPS_ANDROID_API_KEY and rebuild.",
                                color = Chalk, fontSize = 12.sp)
                            Text("Native street map remains active until Google Maps is configured.",
                                color = Color.LightGray, fontSize = 11.sp)
                        }
                    }
                    GlassCard(accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MAP LAYER", color = Lilac, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                showGoogleMap = false
                                vectorMap = !vectorMap
                                if (vectorMap) useOfflineMap = false
                            },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Lilac)) {
                                Text(if (vectorMap) "Use street map" else "Try vector map")
                            }
                        }
                        Text("Native streets show your actual GPS route. The legacy vector map is only an alternate renderer. Offline imported MBTiles work in native mode.",
                            color = Color.LightGray, fontSize = 11.sp)
                    }
                    GlassCard(accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("WALK HISTORY", color = Lilac, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            if (reviewedWalk != null) TextButton(onClick = {
                                repo.selectWalk(null); recenterToken++
                            }) { Text("Back to live", color = Lilac) }
                        }
                        Text("Each session is saved on your phone. Tap a date to see its purple route.",
                            color = Color.LightGray, fontSize = 12.sp)
                        if (history.isEmpty()) {
                            Text("No walks saved yet. Record a walk, move outdoors, then End walk.",
                                color = Chalk, fontSize = 12.sp)
                        } else {
                            val format = remember { SimpleDateFormat("EEE, dd MMM yyyy · h:mm a", Locale.getDefault()) }
                            history.take(if (allHistory) history.size else 7).forEach { saved ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (reviewedWalk?.id == saved.id)
                                        Lilac.copy(alpha = .19f) else Color.White.copy(alpha = .06f))
                                    .clickable {
                                        repo.selectWalk(saved.id)
                                        recenterToken++
                                    }.padding(11.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(format.format(Date(saved.startedAt)),
                                            color = Chalk, fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold)
                                        Text(if (saved.endedAt == null) "Currently recording"
                                            else "Completed session", color = Color.LightGray,
                                            fontSize = 10.sp)
                                    }
                                    Text(String.format("%.2f km", saved.distanceMeters / 1000.0),
                                        color = Lilac, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (displayWalk != null && displayRoute.isNotEmpty()) {
                                OutlinedButton(onClick = {
                                    exportWalkId = displayWalk.id
                                    val filename = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
                                        .format(Date(displayWalk.startedAt))
                                    exportLauncher.launch("Boali-" + filename + ".gpx")
                                }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Lilac)) {
                                    Text("Export selected route as GPX")
                                }
                            }
                            exportMessage?.let {
                                Text(it, color = Lilac, fontSize = 11.sp)
                            }
                            if (history.size > 7) TextButton(onClick = { allHistory = !allHistory }) {
                                Text(if (allHistory) "Show recent" else "Show all saved walks",
                                    color = Lilac)
                            }
                        }
                    }
                    GlassCard(accent) {
                        Text("ROUTE GUIDE", color = Lilac, fontWeight = FontWeight.Bold)
                        Text("The dot displays GPS location. It cannot create a walking trail on its own. Tap Record walk, check the GPS received/stored counters, and walk outdoors. Your saved route can be reopened by date without internet.",
                            color = Chalk, fontSize = 12.sp)
                    }
                    GlassCard(accent) {
                        Text("OFFLINE MAP · LYN", color = Lilac,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Your GPS records routes without internet. For street imagery offline, " +
                            "import a raster MBTiles file from an offline-compatible map provider.",
                            color = Chalk, fontSize = 12.sp)
                        Text(offlineStatus, color = Color.LightGray, fontSize = 11.sp)
                        OutlinedButton(onClick = {
                            importOfflineMap.launch(arrayOf("*/*"))
                        }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Lilac)) {
                            Text("Import offline streets (.mbtiles)")
                        }
                        if (offlineArchive != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Use imported map without mobile data",
                                    color = Chalk, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                Switch(checked = useOfflineMap,
                                    onCheckedChange = { enabled ->
                                        useOfflineMap = enabled
                                        if (enabled) {
                                            showGoogleMap = false
                                            vectorMap = false
                                        }
                                    })
                            }
                        }
                        Text("Offline tiles must be raster PNG/JPEG MBTiles. The old " +
                            "OpenFreeMap vector download cannot be loaded by this native renderer.",
                            color = Color.LightGray, fontSize = 10.sp)
                    }
                    GlassCard(accent) {
                        Text("LYN'S VERDICT", color = Lilac, fontWeight = FontWeight.Bold)
                        Text(if (route.size > 12) "Your route was almost respectable. Do it again tomorrow."
                            else "Show me a real route. I don't reward promises.", color = Chalk)
                    }
                } else {
                    GlassCard(accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("TODAY'S STEPS", color = Pink, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                Text(NumberFormat.getIntegerInstance().format(progress.steps),
                                    color = Chalk, fontSize = 39.sp, fontWeight = FontWeight.ExtraBold)
                                Text("/ ${NumberFormat.getIntegerInstance().format(goal)} goal",
                                    color = Color.LightGray, fontSize = 13.sp)
                            }
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                                CircularProgressIndicator(progress = { percent }, modifier = Modifier.size(82.dp),
                                    color = Pink, trackColor = Color.White.copy(alpha = .12f),
                                    strokeWidth = 9.dp)
                                Text("${(percent * 100).toInt()}%", color = Chalk,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(repo.goalLabel(), color = Color.LightGray, fontSize = 11.sp)
                        if (!sensorAvailable) Text("This phone has no hardware step-counter sensor.",
                            color = Color(0xFFFFB0A1))
                        else if (!hasPermission) Button(onClick = onEnable,
                            colors = ButtonDefaults.buttonColors(containerColor = Pink,
                                contentColor = Ink)) { Text("Enable real step tracking") }
                        Text("● ${if (hasPermission) sensorStatus else "Activity Recognition permission needed"}",
                            color = if (hasPermission) Pink else Color.LightGray, fontSize = 11.sp)
                        Text("Your steps come from the motion sensor, not GPS. Zero steps before walking is normal.",
                            color = Color.LightGray, fontSize = 11.sp)
                    }
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MetricCard("🔥", "Estimated active kcal",
                            FitnessMath.activeKcal(progress.steps, stride, weight).toString(),
                            Pink, Modifier.weight(1f))
                        MetricCard("↗", "Step-based distance",
                            String.format("%.2f km", FitnessMath.walkedKm(progress.steps, stride)),
                            Pink, Modifier.weight(1f))
                    }
                    GlassCard(accent) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("WATER LOG", color = Pink, fontWeight = FontWeight.Bold)
                                Text("${progress.waterMl} / 2000 ml", color = Chalk,
                                    fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(onClick = { repo.water(250) },
                                colors = ButtonDefaults.buttonColors(containerColor = Pink,
                                    contentColor = Ink)) { Text("+ 250 ml") }
                        }
                        LinearProgressIndicator(progress = { (progress.waterMl / 2000f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(9.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = Pink, trackColor = Color.White.copy(alpha = .1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Water reminders (about every 2 hours)",
                                modifier = Modifier.weight(1f), color = Chalk, fontSize = 12.sp)
                            Switch(checked = reminders, onCheckedChange = repo::setWaterReminders,
                                colors = SwitchDefaults.colors(checkedThumbColor = Pink))
                        }
                    }
                    GlassCard(accent) {
                        Text("BOA'S VERDICT", color = Pink, fontWeight = FontWeight.Bold)
                        Text(when {
                            progress.steps >= goal -> "Good boy. You've earned my attention. Just this once. ♡"
                            progress.steps >= 7000 -> "Seven thousand already? Don't get smug. ♡"
                            progress.steps >= 3000 -> "A little progress. I suppose that's something."
                            else -> "You want praise? Earn it. I'll be watching."
                        }, color = Chalk)
                    }
                }
                GlassCard(accent) {
                    Text("CALIBRATION", color = accent, fontWeight = FontWeight.Bold)
                    Text("Steps come from the phone sensor. Calories are estimates based on your settings.",
                        color = Color.LightGray, fontSize = 12.sp)
                    SettingRow("Weight", "$weight kg", accent,
                        onMinus = { repo.setWeight(weight - 1) }, onPlus = { repo.setWeight(weight + 1) })
                    SettingRow("Stride", "$stride cm", accent,
                        onMinus = { repo.setStride(stride - 1) }, onPlus = { repo.setStride(stride + 1) })
                }
                GlassCard(accent) {
                    Text("REDMI · HYPEROS SETUP", color = accent, fontWeight = FontWeight.Bold)
                    Text("Allow Activity Recognition and precise location. In HyperOS, give Boali battery mode 'No restrictions', enable Background autostart if offered, and keep notifications enabled during walks.",
                        color = Chalk, fontSize = 12.sp)
                    OutlinedButton(onClick = onOpenBatterySettings,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)) {
                        Text("Open Boali system settings")
                    }
                }
                Text("Local GPS history · No account · Map tiles use network",
                    color = Color.White.copy(alpha = .5f), fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, accent: Color,
                    modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(20.dp))
        .background(if (selected) accent.copy(alpha = .27f) else Color.Transparent)
        .border(1.dp, if (selected) accent.copy(alpha = .75f) else Color.Transparent,
            RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Chalk else Color.LightGray,
            fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CharacterHero(lyn: Boolean, accent: Color) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(282.dp)
        .clip(RoundedCornerShape(26.dp))
        .border(1.dp, accent.copy(alpha = .65f), RoundedCornerShape(26.dp))) {
        Image(painterResource(if (lyn) R.drawable.lyn else R.drawable.boa), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .10f), Color.Transparent, Color(0x991C0D20), Ink))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 23.dp, vertical = 14.dp)) {
            Text(if (lyn) "LYN" else "BOA", color = Chalk, fontSize = 37.sp,
                letterSpacing = 5.sp, fontWeight = FontWeight.Black)
            Text(if (lyn) "THE COLD OBSERVER  /  ROUTE TRACKING"
                else "THE DIFFICULT REWARD  /  STEPS & WATER", color = accent,
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun GlassCard(accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)
        .clip(RoundedCornerShape(23.dp))
        .background(Brush.linearGradient(listOf(Color(0xAA765878), Glass, Color(0xBB2D1B30))))
        .border(1.dp, Brush.linearGradient(listOf(accent.copy(alpha = .72f),
            Color.White.copy(alpha = .11f), accent.copy(alpha = .19f))),
            RoundedCornerShape(23.dp))
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
}

@Composable
private fun MetricCard(symbol: String, label: String, number: String, accent: Color,
                       modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .08f))
        .border(1.dp, accent.copy(alpha = .25f), RoundedCornerShape(20.dp))
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(symbol, fontSize = 20.sp)
        Text(number, color = Chalk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.LightGray, fontSize = 11.sp)
    }
}

@Composable
private fun SettingRow(title: String, value: String, accent: Color,
                       onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Chalk, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, color = accent, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        Text(" − ", color = Chalk, modifier = Modifier
            .clip(CircleShape).background(Color.White.copy(alpha = .1f))
            .clickable(onClick = onMinus).padding(7.dp), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(7.dp))
        Text(" + ", color = Chalk, modifier = Modifier
            .clip(CircleShape).background(Color.White.copy(alpha = .1f))
            .clickable(onClick = onPlus).padding(7.dp), fontWeight = FontWeight.Bold)
    }
}
