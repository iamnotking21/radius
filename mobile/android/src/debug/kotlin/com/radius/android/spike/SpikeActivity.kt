package com.radius.android.spike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.radius.android.ui.theme.RadiusTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The spike harness screen. DEBUG SOURCE SET ONLY — this file is not compiled into a release APK.
 *
 * It is a separate launcher entry ("Radius Spike"), not a hidden gesture inside the app, because a
 * person doing a 90-minute capture in a car park should not have to remember a secret tap sequence,
 * and because a lab instrument that looks like the product is a lab instrument someone will
 * screenshot into a design review.
 *
 * Also startable from a laptop, which is how the long runs will actually be driven:
 * ```
 *   adb shell am start -n com.radius.android.debug/com.radius.android.spike.SpikeActivity
 * ```
 */
@AndroidEntryPoint
internal class SpikeActivity : ComponentActivity() {

    @Inject
    lateinit var controller: SpikeController

    private var permissionsGranted by mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionsGranted = requiredPermissions().all { granted(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted = requiredPermissions().all { granted(it) }

        setContent {
            RadiusTheme {
                val stats by controller.stats.collectAsState()
                val config by controller.config.collectAsState()

                SpikeScreen(
                    stats = stats,
                    config = config,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { requestPermissions.launch(requiredPermissions()) },
                    onConfigChange = controller::updateConfig,
                    onStart = { SpikeForegroundService.start(this) },
                    onStop = { SpikeForegroundService.stop(this) },
                    onFlush = controller::flush,
                )
            }
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Exactly the permissions the capture needs, per API level, and no more.
     *
     * `ACCESS_FINE_LOCATION` appears ONLY on API 29-30, where there is no `BLUETOOTH_SCAN` and a
     * scan without it returns zero results SILENTLY — no exception, no callback, nothing. On 31+
     * `BLUETOOTH_SCAN` with `neverForLocation` replaces it entirely and the user never sees a
     * location prompt. That asymmetry is in the main manifest with its justification; it is
     * repeated here because a spike run that quietly finds nothing on an Android 10 handset would
     * be recorded as a hardware finding when it is a permission bug.
     */
    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Not for the capture — for the foreground-service notification to be visible. If it is
            // refused the service still runs, and the user has a radio on that they cannot see.
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}
