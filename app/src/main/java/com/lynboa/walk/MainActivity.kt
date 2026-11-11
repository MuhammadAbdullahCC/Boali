package com.lynboa.walk

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.tracking.TrackingService
import com.lynboa.walk.ui.LynBoaScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as WalkApp).repository
        setContent {
            val ctx = LocalContext.current
            val diagnostics = remember { ctx.getSharedPreferences("lynboa_diagnostics", Context.MODE_PRIVATE) }
            var previousCrash by remember { mutableStateOf(diagnostics.getString("last_crash", null)) }
            if (previousCrash != null) {
                AlertDialog(
                    onDismissRequest = { previousCrash = null },
                    title = { Text("Boali recovered from a crash") },
                    text = { Text("Copy this error and send it for a fix:\n\n" + previousCrash!!.take(1800)) },
                    confirmButton = {
                        TextButton(onClick = {
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Boali crash report", previousCrash!!))
                            diagnostics.edit().remove("last_crash").apply()
                            previousCrash = null
                        }) { Text("Copy crash report") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            diagnostics.edit().remove("last_crash").apply()
                            previousCrash = null
                        }) { Text("Dismiss") }
                    }
                )
            }
            PermissionGate(repo,
                openHana = intent?.getBooleanExtra("open_hana", false) == true,
                startSteps = { startTracker(TrackingService.ACTION_START) },
                startWalk = { startTracker(TrackingService.ACTION_START_WALK) },
                stopWalk = { startTracker(TrackingService.ACTION_STOP_WALK) },
                openBatterySettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
        }
    }

    private fun startTracker(action: String) {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (error: RuntimeException) {
            (application as WalkApp).repository.sensorState.value =
                "Tracking blocked: ${error.javaClass.simpleName}: ${error.message}"
        }
    }
}

@Composable
private fun PermissionGate(repo: FitnessRepository, openHana: Boolean, startSteps: () -> Unit,
                           startWalk: () -> Unit, stopWalk: () -> Unit,
                           openBatterySettings: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sensorAvailable = remember {
        (context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
    val stepPermission = Manifest.permission.ACTIVITY_RECOGNITION
    var hasSteps by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, stepPermission) ==
        PackageManager.PERMISSION_GRANTED) }
    var hasLocation by remember { mutableStateOf(ContextCompat.checkSelfPermission(context,
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var pendingStartWalk by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocation) {
            repo.locationState.value = "Location allowed · finding you…"
            if (pendingStartWalk) startWalk()
        } else repo.locationState.value = "Allow precise location to show your position"
        pendingStartWalk = false
    }
    val stepLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasSteps = result[stepPermission] == true
        if (hasSteps) startSteps()
        else repo.sensorState.value = "Allow Activity Recognition to count real steps"
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                repo.refresh()
                hasSteps = ContextCompat.checkSelfPermission(context, stepPermission) ==
                    PackageManager.PERMISSION_GRANTED
                hasLocation = ContextCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (hasSteps && sensorAvailable) startSteps()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LynBoaScreen(repo, sensorAvailable, hasSteps, hasLocation,
        onRequestLocation = {
            pendingStartWalk = false
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onEnable = {
            val permissions = mutableListOf(stepPermission)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            stepLauncher.launch(permissions.toTypedArray())
        },
        onStartWalk = {
            if (hasLocation) startWalk()
            else {
                pendingStartWalk = true
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        onStopWalk = stopWalk,
        onOpenBatterySettings = openBatterySettings,
        openHana = openHana)
}
