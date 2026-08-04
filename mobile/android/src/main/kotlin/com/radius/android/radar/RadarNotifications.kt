package com.radius.android.radar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.radius.android.MainActivity
import com.radius.android.R

/**
 * THE RADAR FOREGROUND-SERVICE NOTIFICATION.
 *
 * This is a UI SURFACE, not plumbing. For an active user it is the single most-seen piece of the
 * product — it sits in the shade for the whole session — and it is the user's only continuous
 * proof that we are honest about when the radio is on. It gets designed, not defaulted.
 *
 * Decisions here, each with a reason:
 *
 *  - The text names what we are NOT doing: "Your location is never used or stored." A persistent
 *    Bluetooth notification on a dating app invites exactly one suspicion, and answering it in the
 *    place where the suspicion occurs is worth more than a paragraph in a privacy policy.
 *  - Lock-screen visibility is SECRET. Someone glancing at a locked phone on a table must not
 *    learn that the owner is using a dating app. It stays fully visible in the unlocked shade,
 *    which is what the foreground-service rules actually require.
 *  - Silent and LOW importance. It must never buzz, ping, or heads-up. It is a status, not an event.
 *  - A "Turn off" action, one tap, straight to the service. Killing the radio must never require
 *    opening the app, and it must never ask "are you sure".
 *  - Ghost mode gets its own text. A user in ghost mode seeing "Discovering people nearby" would
 *    reasonably think ghost mode had failed.
 *
 * !! UNVERIFIED !! Never compiled, never seen on a device (blocker B5).
 */
object RadarNotifications {

    const val CHANNEL_ID: String = "radius.radar"
    const val NOTIFICATION_ID: Int = 1001

    private const val REQUEST_CONTENT = 0
    private const val REQUEST_STOP = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.radar_notification_channel_name),
            // LOW: visible, never audible, never heads-up.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.radar_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, isGhost: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, RadarForegroundService::class.java)
                .setAction(RadarForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val bodyRes = if (isGhost) {
            R.string.radar_notification_text_ghost
        } else {
            R.string.radar_notification_text
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle(context.getString(R.string.radar_notification_title))
            .setContentText(context.getString(bodyRes))
            // Full text visible when expanded — the location sentence is the important part and it
            // is long enough to be truncated on a narrow phone.
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(bodyRes)))
            .setContentIntent(contentIntent)
            .addAction(
                0,
                context.getString(R.string.radar_notification_stop),
                stopIntent,
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
