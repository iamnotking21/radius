package com.radius.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.radius.android.R
import com.radius.android.ui.discover.DiscoverScreen
import com.radius.android.ui.radar.RadarScreen
import com.radius.android.ui.theme.RadiusTheme
import com.radius.android.ui.threads.ThreadsScreen

/**
 * Three tabs. Discover, Radar, Threads. That is the whole app's top level and it does not grow.
 *
 * THERE IS NO SWIPE DECK ANYWHERE IN THIS TREE AND THERE NEVER WILL BE. It is on the banned list
 * in root CLAUDE.md. Discover is a finite daily set rendered as a list, not a stack of cards to be
 * flung. If a card-swipe gesture shows up in a diff here, that is a product-integrity failure, not
 * a UI preference.
 *
 * !! UNVERIFIED !! Never compiled, never rendered, never run on a device (blocker B5).
 */
@Composable
fun RadiusApp() {
    // rememberSaveable: the selected tab survives rotation and process death. A user who rotates
    // the phone while Radar is running must not be silently dropped back to Discover.
    var selected by rememberSaveable { mutableStateOf(RadiusDestination.DISCOVER) }

    Scaffold(
        containerColor = RadiusTheme.colors.background,
        bottomBar = {
            NavigationBar(
                containerColor = RadiusTheme.colors.surface,
            ) {
                RadiusDestination.entries.forEach { destination ->
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination },
                        icon = {
                            // PLACEHOLDER GLYPH. design-system owns the real icon set and it has
                            // not shipped. A coloured dot is honest; a borrowed Material icon
                            // would look finished and quietly become permanent.
                            Box(
                                Modifier
                                    .size(RadiusTheme.spacing.sm)
                                    .background(destination.accent(), CircleShape),
                            )
                        },
                        label = { Text(label) },
                        // Labels always visible. With icon-only tabs, a 200% font scale user and a
                        // TalkBack user both lose. The label is not decoration.
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = RadiusTheme.colors.ink,
                            unselectedTextColor = RadiusTheme.colors.inkMuted,
                            indicatorColor = RadiusTheme.colors.surfaceRaised,
                        ),
                        // No custom semantics here on purpose: NavigationBarItem already announces
                        // selected state to TalkBack correctly. Overriding it usually makes it worse.
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selected) {
                RadiusDestination.DISCOVER -> DiscoverScreen()
                RadiusDestination.RADAR -> RadarScreen()
                RadiusDestination.THREADS -> ThreadsScreen()
            }
        }
    }
}

/**
 * Mirrors [com.radius.shared.domain.AppMode]. Kept as a separate UI-layer enum on purpose: the
 * shared enum is a CONTRACT with two consumers, and navigation state is not its business.
 */
enum class RadiusDestination(val labelRes: Int) {
    DISCOVER(R.string.tab_discover),
    RADAR(R.string.tab_radar),
    THREADS(R.string.tab_threads),
}

@Composable
private fun RadiusDestination.accent(): Color = when (this) {
    RadiusDestination.DISCOVER -> RadiusTheme.colors.accentDiscover
    RadiusDestination.RADAR -> RadiusTheme.colors.accentRadar
    RadiusDestination.THREADS -> RadiusTheme.colors.accentThreads
}
