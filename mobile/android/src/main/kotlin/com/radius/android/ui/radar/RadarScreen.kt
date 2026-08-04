package com.radius.android.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.radius.android.R
import com.radius.android.ui.theme.RadiusTheme

/**
 * RADAR — the moat. BLE proximity, works with no internet.
 *
 * !! PLACEHOLDER !! The radio is not wired to this screen. Nothing here talks to
 * `RadiusCore` yet, because `RadiusCore.create()` is deliberately a TODO until
 * `mobile/shared/protocol/` exists. Buttons below are inert and say so.
 *
 * INVARIANTS THIS SCREEN OWNS. None of them are negotiable and none are "polish":
 *
 *  1 · NO MAP. There is no map view here, there will never be a map view here, and there is no
 *      library in this module capable of drawing one.
 *  3 · The radar dial (not yet built) places each node at a RANDOM ANGLE, seeded once per session.
 *      The angle encodes nothing. It must not be derived from signal strength, arrival order, or
 *      anything else correlated with the real world, because a user who could read direction off
 *      the dial could walk a stranger down.
 *  2 · Distance is four coarse bands, displayed hedged. The number shown is a band midpoint plus
 *      jitter — an illustration, not a measurement — and the copy says "about".
 * 10 · GHOST MODE IS ONE TAP FROM THIS SCREEN. It is the switch below, at the top, not buried in
 *      Settings and not behind a confirmation dialog. If a redesign moves it, that is a safety
 *      regression and the PR is blocked.
 *
 *  A11Y · The animated dial is decorative. Everyone gets the same information as a plain labelled
 *      LIST, always present, never a fallback. A TalkBack user is not offered a lesser product.
 */
@Composable
fun RadarScreen() {
    // TODO(radar): hoist to a ViewModel backed by RadiusCore.radar once create() is implemented.
    //  Local state here is scaffolding, not the design.
    var isGhost by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Scrollable even though it looks short: at 200% font scale this content is taller
            // than a phone screen, and clipped safety controls are not acceptable.
            .verticalScroll(rememberScrollState())
            .padding(RadiusTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(RadiusTheme.spacing.md),
    ) {
        Text(
            text = stringResource(R.string.radar_title),
            style = MaterialTheme.typography.headlineMedium,
            color = RadiusTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )

        GhostModeControl(
            isGhost = isGhost,
            onChange = { isGhost = it },
        )

        HorizontalDivider(color = RadiusTheme.colors.outline)

        OutlinedButton(
            onClick = {
                // INTENTIONALLY INERT. Starting RadarForegroundService here would crash on API 31+
                // without the runtime BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE grants, and the
                // permission-request flow is not built yet. Wiring a button that throws is worse
                // than a button that admits it does nothing.
                // TODO(radar): permission rationale flow -> RadarForegroundService.start(context).
            },
            enabled = false,
            modifier = Modifier.heightIn(min = RadiusTheme.spacing.touchTarget),
        ) {
            Text(stringResource(R.string.radar_start))
        }

        Text(
            text = stringResource(R.string.radar_placeholder_note),
            style = MaterialTheme.typography.bodySmall,
            color = RadiusTheme.colors.inkMuted,
        )

        // ---- the list equivalent. Not a fallback: the primary accessible representation. ----
        Text(
            text = stringResource(R.string.radar_list_heading),
            style = MaterialTheme.typography.titleMedium,
            color = RadiusTheme.colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.radar_list_a11y_note),
            style = MaterialTheme.typography.bodySmall,
            color = RadiusTheme.colors.inkMuted,
        )
        Text(
            text = stringResource(R.string.radar_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = RadiusTheme.colors.inkMuted,
        )
    }
}

/**
 * SAFETY INVARIANT 10 — ghost mode, one tap, on the Radar screen.
 *
 * The whole row is the touch target, not just the switch: a one-tap control that demands precision
 * on a 32dp thumb is not really a one-tap control, and this is the button people reach for when
 * they suddenly feel unsafe.
 *
 * No confirmation dialog. Ever. Turning yourself invisible is not a destructive action that needs
 * protecting against; hesitating over a modal is.
 */
@Composable
private fun GhostModeControl(
    isGhost: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RadiusTheme.spacing.touchTarget)
            .toggleable(
                value = isGhost,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.radar_ghost_label),
                style = MaterialTheme.typography.titleMedium,
                color = RadiusTheme.colors.ink,
            )
            Text(
                text = stringResource(R.string.radar_ghost_description),
                style = MaterialTheme.typography.bodySmall,
                color = RadiusTheme.colors.inkMuted,
            )
        }
        Switch(
            checked = isGhost,
            // null: the Row owns the click. Two independent targets would announce twice to
            // TalkBack and give the switch a second, smaller hit area.
            onCheckedChange = null,
        )
    }

    // Live region: a screen-reader user must hear that they became invisible without having to go
    // looking for the change. This is a safety state, not a preference toggle.
    Text(
        text = stringResource(
            if (isGhost) R.string.radar_ghost_on_announcement else R.string.radar_ghost_off_announcement,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (isGhost) RadiusTheme.colors.accentRadar else RadiusTheme.colors.inkMuted,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
