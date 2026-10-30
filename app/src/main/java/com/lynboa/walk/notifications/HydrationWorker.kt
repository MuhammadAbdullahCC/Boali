package com.lynboa.walk.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.lynboa.walk.data.FitnessDatabase
import com.lynboa.walk.data.FitnessRepository
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class HydrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("lynboa_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("water_reminders", true)) return Result.success()
        val hour = LocalTime.now().hour
        if (hour in 9..22 && FitnessDatabase(applicationContext)
                .day(FitnessRepository.todayKey()).waterMl < 2000) {
            CoachNotifications.water(applicationContext)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HydrationWorker>(2, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "hydrate", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
