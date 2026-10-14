package com.guardianmesh.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Foreground-service notifications (framework API only; minSdk 26 so channels always exist). */
object Notifications {
    const val CH_SOS = "guardian_sos"
    const val CH_RELAY = "guardian_relay"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SOS, "Active SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while an SOS is active and your location is being shared"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RELAY, "Guardian relay", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone listens for nearby SOS signals"
            }
        )
    }

    fun build(ctx: Context, channel: String, title: String, text: String): Notification {
        ensureChannels(ctx)
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = launch?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (channel == CH_SOS) Notification.CATEGORY_ALARM else Notification.CATEGORY_SERVICE)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
    }

    fun update(ctx: Context, id: Int, n: Notification) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(id, n) }
    }
}
