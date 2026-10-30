package com.lynboa.walk

import android.app.Application
import android.util.Log
import com.lynboa.walk.data.FitnessRepository
import com.lynboa.walk.notifications.CoachNotifications
import com.lynboa.walk.notifications.HydrationWorker

class WalkApp : Application() {
    val repository by lazy { FitnessRepository(this) }
    override fun onCreate() {
        super.onCreate()
        // Record fatal exceptions locally so the next launch can show diagnostics.
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                getSharedPreferences("lynboa_diagnostics", MODE_PRIVATE).edit()
                    .putString("last_crash", "${java.time.Instant.now()}\n${error.stackTraceToString()}")
                    .commit()
            } catch (_: Throwable) { }
            if (prior != null) prior.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
        // Never load the map's native renderer as a prerequisite for step tracking.
        runCatching { CoachNotifications.createChannels(this) }
            .onFailure { Log.e("Boali", "Notification setup failed", it) }
        runCatching { HydrationWorker.schedule(this) }
            .onFailure { Log.e("Boali", "Water reminder scheduling failed", it) }
    }
}
