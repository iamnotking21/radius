package com.radius.android.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.radius.android.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps a spike run alive with the screen off and the app backgrounded. DEBUG SOURCE SET ONLY.
 *
 * ## Why the harness needs one at all
 *
 * `KEY_SCHEDULE.md` §5.2 lists the capture conditions, and three of the five cannot be produced
 * from a foreground Activity: **fg screen-off**, **background under a foreground service**, and
 * **after Doze entry**. Those are also the conditions where OEM power management is most likely to
 * change controller behaviour, which is the whole reason they are on the list. A harness that can
 * only capture with the screen on would measure the easy half of the problem and report it as the
 * problem.
 *
 * ## Deliberately NOT the product's Radar service
 *
 * `RadarForegroundService` is the product surface: its notification is designed, its copy is
 * user-facing, its lifecycle is tied to a user's ghost-mode expectations. This one is a lab
 * instrument that says so in as many words. Sharing them would mean every change to the spike
 * touched a product file, and — worse — that a screenshot from a lab run could be mistaken for the
 * shipping experience.
 *
 * FGS type is `connectedDevice`, NOT `location`. Same reason as the product service: the manifest
 * asserts `neverForLocation` on `BLUETOOTH_SCAN`, and declaring a location FGS type would
 * contradict it and be untrue.
 */
@AndroidEntryPoint
internal class SpikeForegroundService : LifecycleService() {

    @Inject
    lateinit var controller: SpikeController

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (error: Exception) {
            // API 31+ throws ForegroundServiceStartNotAllowedException from the background; 34+
            // throws SecurityException when the FGS-type permissions are missing. Crashing the
            // instrument because it could not start is not a useful failure mode — and the failure
            // itself is a finding, so it is logged loudly rather than swallowed.
            Log.e(TAG, "SPIKE FGS REFUSED — this is a Phase 0 finding, record it", error)
            stopSelf()
            return START_NOT_STICKY
        }

        controller.start()

        // NOT START_STICKY. If an OEM battery manager kills this service, THAT IS THE MEASUREMENT.
        // Silently resurrecting would hide the exact behaviour mobile/android/docs/oem.md exists to
        // document, and would turn "Xiaomi killed us after 12 minutes" into "the log has a gap".
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Radius spike (debug)",
                // LOW: no sound, no vibration. A 90-minute capture must not buzz every time
                // anything changes, and it must not alter the device's power behaviour, which is
                // one of the things being measured.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Phase 0 BLE measurement run. Debug builds only." },
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SpikeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle("Radius spike running")
            .setContentText("BLE measurement capture. Debug build. Not a product feature.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, SpikeForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    companion object {
        private const val TAG = "RadiusSpikeFgs"
        private const val CHANNEL_ID = "radius_spike_debug"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_STOP: String = "com.radius.android.spike.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SpikeForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SpikeForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
