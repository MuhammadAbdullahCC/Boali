package com.lynboa.walk.notifications

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
import com.lynboa.walk.MainActivity
import com.lynboa.walk.R

object CoachNotifications {
    const val TRACK_ID = 3201
    const val TRACK_CHANNEL = "tracking"
    private const val REWARD_CHANNEL = "rewards"
    private const val WATER_CHANNEL = "hydration"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(TRACK_CHANNEL,
            "Step and route tracking", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(REWARD_CHANNEL,
            "Lyn and Boa messages", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(WATER_CHANNEL,
            "Water reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun persistent(context: Context, steps: Int, walk: Boolean): android.app.Notification =
        NotificationCompat.Builder(context, TRACK_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (walk) "Lyn is recording your route" else "Boa is counting your steps")
            .setContentText("${steps.toString()} steps today · ${if (walk) "route recording" else "walk not recording"}")
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).build()

    private fun send(context: Context, channel: String, id: Int, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(id,
            NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true).setContentIntent(openApp(context)).build())
    }

    fun reward(context: Context, stepTarget: Int) {
        val message = when (stepTarget) {
            3000 -> "Boa: Three thousand? Acceptable. Don't mistake that for praise. ♡"
            5000 -> "Boa: Keep going. Perhaps I'll reward that discipline."
            7000 -> "Boa: Good boy. Don't look so pleased. You still have work to do. ♡"
            else -> "Lyn: You met your goal. I suppose you've earned one smile."
        }
        send(context, REWARD_CHANNEL, 5000 + stepTarget, "A message from the girls", message)
    }
    fun water(context: Context) {
        send(context, WATER_CHANNEL, 4001, "Boa requests your attention",
            "Drink a glass of water. You wouldn't keep me waiting, would you? 💗")
    }
}
