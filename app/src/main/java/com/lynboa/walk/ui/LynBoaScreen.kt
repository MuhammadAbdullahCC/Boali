package com.lynboa.walk.ui

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
import com.lynboa.walk.maps.LynLocationPreview
import com.lynboa.walk.maps.OfflineCityMap
import java.text.NumberFormat
import java.time.LocalDate

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
    val sensorStatus by repo.sensorState.collectAsState()
    val livePosition by repo.liveLocation.collectAsState()
    val locationStatus by repo.locationState.collectAsState()
    var recenterToken by remember { mutableIntStateOf(0) }
    val offlineStatus by repo.offlineStatus.collectAsState()
    val weight by repo.weightKg.collectAsState()
    val stride by repo.strideCm.collectAsState()
    val reminders by repo.waterReminders.collectAsState()
    var lyn by remember { mutableStateOf(false) }
    val accent = if (lyn) Lilac else Pink
    val context = LocalContext.current
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
                            recording && hasFix -> "● RECORDING · ${route.size} accepted GPS points"
                            recording -> "● RECORDING · waiting for accurate GPS"
                            hasFix -> "● LIVE POSITION · route not recording"
                            else -> "○ Waiting for live position · route not recording"
                        }, color = Lilac, fontSize = 11.sp)
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
                                Text(if (recording) "WALK IN PROGRESS"
                                    else "YOUR LAST WALK", color = accent, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Text(String.format("%.2f km", (walk?.distanceMeters ?: 0.0) / 1000.0),
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
                        LynMap(route, livePosition, following = recording, recenterToken = recenterToken)
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
                            Text("© OpenStreetMap contributors · OpenFreeMap", color = Chalk,
                                fontSize = 9.sp)
                        }
                    }
                    GlassCard(accent) {
                        Text("ROUTE GUIDE", color = Lilac, fontWeight = FontWeight.Bold)
                        Text("The glowing dot shows your current position. Tap Record walk to save accepted GPS points and draw your purple route. Preview alone never records a walk.",
                            color = Chalk, fontSize = 12.sp)
                    }
                    GlassCard(accent) {
                        Text("CITY MAP · OFFLINE", color = Lilac, fontWeight = FontWeight.Bold,
                            fontSize = 13.sp)
                        Text(offlineStatus, color = Chalk, fontSize = 12.sp)
                        Text("Save city streets for use without mobile data. Download once over Wi-Fi.",
                            fontSize = 12.sp, color = Color.LightGray)
                        OutlinedButton(onClick = { OfflineCityMap.download(context, repo) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Lilac)) {
                            Text("↓ Download Jalalpur map")
                        }
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
