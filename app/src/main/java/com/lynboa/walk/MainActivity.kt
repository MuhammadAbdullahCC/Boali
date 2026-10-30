package com.lynboa.walk

import android.Manifest
import android.content.Intent
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
                startSteps = { startTracker(TrackingService.ACTION_START) },
                startWalk = { startTracker(TrackingService.ACTION_START_WALK) },
                stopWalk = { startTracker(TrackingService.ACTION_STOP_WALK) })
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
private fun PermissionGate(repo: FitnessRepository, startSteps: () -> Unit,
                           startWalk: () -> Unit, stopWalk: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sensorAvailable = remember {
        (context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
    val stepPermission = Manifest.permission.ACTIVITY_RECOGNITION
    val hasSteps = ContextCompat.checkSelfPermission(context, stepPermission) ==
        PackageManager.PERMISSION_GRANTED
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startWalk()
        else repo.sensorState.value = "Precise GPS permission needed for Lyn's route"
    }
    val stepLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[stepPermission] == true) startSteps()
        else repo.sensorState.value = "Allow Activity Recognition to count real steps"
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                repo.refresh()
                if (ContextCompat.checkSelfPermission(context, stepPermission) ==
                    PackageManager.PERMISSION_GRANTED && sensorAvailable) startSteps()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LynBoaScreen(repo, sensorAvailable, hasSteps,
        onEnable = {
            val permissions = mutableListOf(stepPermission)
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            stepLauncher.launch(permissions.toTypedArray())
        },
        onStartWalk = {
            if (!hasSteps) repo.sensorState.value = "Enable the step counter first"
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) startWalk()
            else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onStopWalk = stopWalk)
}
