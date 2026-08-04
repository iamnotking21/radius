package com.radius.android

import android.app.Application
import com.radius.android.radar.RadarNotifications
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and the root of the Hilt graph.
 *
 * Hilt stops at this module. It is the ANDROID UI GRAPH and nothing more — ADR-007 forbids any DI
 * framework inside `:shared`, which uses constructor injection and a plain factory. The one seam
 * is [com.radius.android.di.SharedModule].
 *
 * !! UNVERIFIED !! Never compiled or run (blocker B5).
 */
@HiltAndroidApp
class RadiusApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Created up-front rather than lazily at service start: on some OEMs a channel created in
        // the same tick as startForeground() produces a notification with no channel metadata,
        // which then shows with the wrong importance or not at all.
        RadarNotifications.ensureChannel(this)
    }
}
