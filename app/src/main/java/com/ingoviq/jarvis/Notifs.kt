package com.ingoviq.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifs {
    const val CH_SERVICE = "jarvis_service"
    const val CH_ALERT = "jarvis_alert"
    const val ID_SERVICE = 7
    const val ID_TAP = 8

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Jarvis running", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Speaker connected", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Android ne background madhun mic suru karu dila nahi tar: "tap karun Jarvis suru kara". */
    fun showTapToStart(ctx: Context) {
        createChannels(ctx)
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle("🔊 Speaker connect zala")
            .setContentText("Jarvis suru karayla ithe tap kara")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_TAP, n)
        } catch (ignored: SecurityException) {
        }
    }
}
