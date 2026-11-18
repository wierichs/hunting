package org.wierichs.dogtracker.communication

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.wierichs.dogtracker.MainActivity
import org.wierichs.dogtracker.R

internal object NearbyBroadcastNotifications {
    const val CHANNEL_ID = "nearby_broadcasts"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Nearby-Rundnachrichten", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Empfangene Rundnachrichten aus der Jagdkarte"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    fun show(context: Context, deviceId: String, message: NearbyBroadcast) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 23,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nearby_broadcast)
            .setContentTitle("Nearby-Rundnachricht")
            .setContentText(message.template.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.template.text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(message.sentAt)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify("$deviceId:${message.id}", 2102, notification)
        } catch (failure: SecurityException) {
            Log.w("NearbyBroadcasts", "Rundnachricht konnte nicht als Benachrichtigung angezeigt werden.", failure)
        }
    }
}
