package com.radius.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.radius.android.ui.RadiusApp
import com.radius.android.ui.theme.RadiusTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity.
 *
 * `singleTask` in the manifest, so a tap on the Radar foreground-service notification returns to
 * the running instance rather than stacking a second copy of the app on top of a live radio.
 *
 * !! UNVERIFIED !! Never compiled or run (blocker B5).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Required for targetSdk 35 — Android 15 enforces edge-to-edge and ignores the opt-out.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            RadiusTheme {
                RadiusApp()
            }
        }
    }
}
