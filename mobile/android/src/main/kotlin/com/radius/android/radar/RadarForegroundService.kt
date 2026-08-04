package com.radius.android.radar

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.radius.shared.ble.BleRadio
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps the BLE radio alive while Radar is on, and — just as importantly — makes that visible.
 *
 * !! STILL A STUB. NOT FUNCTIONAL. NEVER RUN ON HARDWARE. !!
 * The service starts, shows its notification and holds the injected [BleRadio]. It does NOT scan
 * or advertise yet.
 *
 * The original blocker is gone — `mobile/shared/protocol/` has landed and the radio can now build
 * and transmit a real frame — but the remaining gap is not a technical one. This is the PRODUCT
 * path, and the product path needs a server-issued `ADVERTISE` role (`KEY_SCHEDULE.md` §2.2, §9.3)
 * before it may transmit anything. [BleRadio] fails closed on exactly that, so wiring a
 * `startAdvertising` call in here today would produce a service that scans, is refused permission
 * to advertise, and finds only the debug harness. The honest thing is to leave it inert until
 * there is an issuance path.
 *
 * The PHASE 0 measurement path does exist and is deliberately separate: see
 * `mobile/android/src/debug/kotlin/com/radius/android/spike/`. It has its own foreground service
 * so that a lab instrument and a product surface never share a notification, a lifecycle or a
 * screenshot.
 *
 * WHY A FOREGROUND SERVICE AND NOT WORKMANAGER: WorkManager cannot hold a continuous BLE scan;
 * its minimum periodic interval is 15 minutes and Doze will defer even that. Radar needs a live
 * radio while the user expects to be discoverable. WorkManager's role in this product is the
 * retention/purge job, not the radio.
 *
 * FOREGROUND SERVICE TYPE IS `connectedDevice`, NOT `location`. That is not a technicality: the
 * manifest asserts `neverForLocation` on BLUETOOTH_SCAN, and declaring a location FGS type would
 * contradict it and be untrue.
 */
@AndroidEntryPoint
class RadarForegroundService : LifecycleService() {

    /**
     * Injected from [com.radius.android.di.SharedModule]. This is the ONLY direction the
     * dependency flows: Hilt (Android UI graph) hands platform objects to the shared core.
     * Nothing in `:shared` knows Hilt exists, and it must stay that way (ADR-007).
     */
    @Inject
    lateinit var bleRadio: BleRadio

    // TODO(radar): hoist to shared core state once RadiusCore.create() is implemented.
    private var isGhost: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopRadio()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startInForeground()
        }

        // START_STICKY: if the system kills us under memory pressure the user's expectation is
        // that Radar is still on. NOTE FOR REAL HARDWARE: several OEMs (Samsung, Xiaomi, Huawei)
        // will not honour this, or will restart the service and immediately doze it. That is an
        // OEM workaround problem, documented in mobile/android/docs/oem.md, not something
        // START_STICKY solves on its own.
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = RadarNotifications.build(this, isGhost)

        try {
            ServiceCompat.startForeground(
                this,
                RadarNotifications.NOTIFICATION_ID,
                notification,
                // minSdk is 29, so the connectedDevice type is always available. No version branch.
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (error: Exception) {
            // API 31+ throws ForegroundServiceStartNotAllowedException when started from the
            // background, and API 34+ throws SecurityException if the permissions backing the
            // declared FGS type are not held. Both are caught deliberately: crashing the app
            // because Radar could not start is not an acceptable failure mode.
            // TODO(radar): surface this to the UI as "Radar could not start" with a real reason.
            Log.e(TAG, "startForeground refused", error)
            stopSelf()
            return
        }

        // TODO(radar): everything that actually matters. Updated 2026-08-04 — items 2 and 4 are
        //  now DONE INSIDE THE RADIO rather than owed here, which changes what this file becomes.
        //   1. runtime permission preconditions (BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE)
        //   2. DONE in BleRadio: startScan/startAdvertising against the real service UUID and the
        //      real 19-byte frame. Blocked here only on a server-issued ADVERTISE role.
        //   3. adaptive duty controller: stationary detection, <20% battery, no peer for 10min.
        //      The radio OBEYS a DutyProfile; nothing yet DECIDES one. That decision belongs here.
        //   4. DONE in BleRadio: the 15-minute UTC epoch loop, stop -> re-derive -> rebuild ->
        //      start (KEY_SCHEDULE.md §4.2). Whether the controller actually rotates the RPA in
        //      response is B8 and is UNMEASURED on every handset.
        //   5. wake-lock policy — and measuring, not guessing, whether one is needed at all
        //   6. battery instrumentation for the <4%/hr CI gate (Battery Historian trace on the PR)
        //   7. detecting that an OEM killed us and saying so. The dishonest failure mode is the
        //      dangerous one: the user believes they are discoverable and is not.
    }

    private fun stopRadio() {
        bleRadio.stopScan()
        bleRadio.stopAdvertising()
    }

    override fun onDestroy() {
        stopRadio()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RadarFgService"

        const val ACTION_START: String = "com.radius.android.radar.START"
        const val ACTION_STOP: String = "com.radius.android.radar.STOP"

        /**
         * Callers must already hold the BLE runtime permissions. This does not request them, and
         * on API 31+ starting without them throws inside the service.
         */
        fun start(context: Context) {
            val intent = Intent(context, RadarForegroundService::class.java)
                .setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RadarForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
