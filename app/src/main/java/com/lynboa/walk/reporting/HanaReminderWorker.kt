package com.lynboa.walk.reporting

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lynboa.walk.MainActivity
import com.lynboa.walk.R
import com.lynboa.walk.WalkApp
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Background preparation only. WhatsApp has no official silent-send Android intent.
 * True automated delivery needs a separate, approved server-side Business Platform sender.
 */
class HanaReminderWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!HanaReport.enabled(context)) return Result.success()
        val today = HanaReport.today()
        if (HanaReport.recordedDate(context) == today.toString()) return Result.success()

        val repo = (context as WalkApp).repository
        repo.refresh()
        val report = HanaReport.compose(context, repo, today)
        HanaReport.saveSnapshot(context, today, report)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            "Hana's daily reports", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return Result.success()

        val intent = Intent(context, MainActivity::class.java)
            .putExtra("open_hana", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 820,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        NotificationManagerCompat.from(context).notify(820,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Hana has prepared your 8 PM report")
                .setContentText(if (HanaReport.hasConsent(context))
                    "Ali's report is ready. Tap to review and send via WhatsApp."
                    else "Report ready. Confirm Ali's permission before sharing.")
                .setContentIntent(pending)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build())
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "hana_reports"
        private const val WORK_NAME = "hana_eight_pm"

        /** WorkManager is battery-friendly but can be delayed by Android/HyperOS. */
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!HanaReport.enabled(context)) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val now = ZonedDateTime.now(ZoneId.of("Asia/Karachi"))
            var next = now.withHour(20).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMillis().coerceAtLeast(0L)
            val work = PeriodicWorkRequestBuilder<HanaReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
