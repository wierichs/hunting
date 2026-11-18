package org.wierichs.dogtracker.communication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import org.json.JSONObject
import org.wierichs.dogtracker.R

internal enum class DogSignal(val code: String, val label: String) {
    RING("ring", "Klingeln"), VIBRATE("vibrate", "Vibrieren")
}

internal enum class DogSignalResult { CONFIRMED, UNAVAILABLE, UNCONFIRMED, SUPPRESSED }

internal const val DOG_SIGNAL_ACTION = "dog_signal_v1"
internal const val DOG_SIGNAL_ACK = "dog_signal_ack_v1"

internal data class DogSignalPacket(val id: String, val dogId: String, val sentAt: Long, val signal: DogSignal) {
    fun encode(profileId: String): ByteArray = JSONObject().put("action", DOG_SIGNAL_ACTION)
        .put("profile_id", profileId).put("id", id).put("dog_id", dogId)
        .put("sent_at_ms", sentAt).put("signal", signal.code).toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun create(dogId: String, signal: DogSignal, now: Long) =
            DogSignalPacket(UUID.randomUUID().toString(), dogId, now, signal)

        fun parse(json: JSONObject, now: Long): DogSignalPacket? {
            val id = broadcastId(json) ?: return null
            val dog = json.optString("dog_id").takeIf { it.isNotBlank() } ?: return null
            val sentAt = json.optLong("sent_at_ms", -1)
            if (sentAt !in (now - 10_000L)..(now + 5_000L)) return null
            val signal = DogSignal.entries.firstOrNull { it.code == json.optString("signal") } ?: return null
            return DogSignalPacket(id, dog, sentAt, signal)
        }
    }
}

internal object DogSignalNotifications {
    private fun channel(signal: DogSignal) = "dog_signal_${signal.code}"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(DogSignal.entries.map { signal ->
            NotificationChannel(channel(signal), "Hundegerät: ${signal.label}", NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    enableVibration(signal == DogSignal.VIBRATE)
                    if (signal == DogSignal.VIBRATE) setSound(null, null)
                }
        })
    }

    fun show(context: Context, signal: DogSignal): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(channel(signal)) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_nearby_broadcast)
            .setContentTitle("Hundegerät angestupst")
            .setContentText(signal.label)
            .setTimeoutAfter(5_000)
            .build()
        return try {
            manager.notify(2103, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
