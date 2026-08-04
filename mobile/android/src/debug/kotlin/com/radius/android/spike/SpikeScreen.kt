package com.radius.android.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.radius.android.ui.theme.RadiusTheme
import com.radius.shared.ble.DutyProfile

/**
 * DELIBERATELY PLAIN. This is an instrument, not a product surface.
 *
 * Two rules were followed anyway, because breaking them costs more than the styling saves:
 *
 *  - **Design tokens only** ([RadiusTheme.colors], [RadiusTheme.spacing]). No hardcoded colours or
 *    spacing, even here. The moment one hex literal is acceptable "because it's debug", the token
 *    discipline is a suggestion.
 *  - **Everything is text.** Every counter has a label, nothing is conveyed by colour alone, and
 *    the layout is a single scrolling column, so it survives 200 % font scale by construction
 *    rather than by testing. It also means the whole state can be read aloud, which matters when
 *    the person running the capture is holding two phones in a car park.
 *
 * What is NOT here, on purpose: charts, smoothing, rolling averages, "signal quality" gauges. Every
 * one of those is an interpretation, and the interpretation belongs off-device where it can be
 * checked against the raw file.
 */
@Composable
internal fun SpikeScreen(
    stats: SpikeStats,
    config: SpikeConfig,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onConfigChange: (SpikeConfig) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFlush: () -> Unit,
) {
    val spacing = RadiusTheme.spacing
    val colors = RadiusTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "RADIUS SPIKE — Phase 0 instrument",
            color = colors.ink,
        )
        Text(
            text = "Debug build. Records raw RSSI and peer addresses to local storage. " +
                "Nothing leaves this device: the app declares no INTERNET permission.",
            color = colors.inkMuted,
        )

        HorizontalDivider(color = colors.outline)

        if (!permissionsGranted) {
            Text("Bluetooth permissions not granted. A scan without them returns NOTHING, silently.")
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Bluetooth permissions")
            }
            HorizontalDivider(color = colors.outline)
        }

        // ------------------------------------------------------------------ configuration
        Text("CONFIG (changing any of these restarts into a new file)", color = colors.ink)

        StepperRow(
            label = "This device's slot",
            value = config.deviceSlot.toString(),
            enabled = !stats.running,
            onDown = {
                onConfigChange(config.copy(deviceSlot = (config.deviceSlot - 1).coerceAtLeast(1)))
            },
            onUp = {
                onConfigChange(config.copy(deviceSlot = (config.deviceSlot + 1).coerceAtMost(8)))
            },
        )
        Text(
            "Every handset in a session needs a DIFFERENT slot. Two on one slot is the " +
                "decision-35 twin case and fills the log with E_SELF_EID.",
            color = colors.inkMuted,
        )

        ToggleRow(
            label = "Advertise (transmit)",
            checked = config.advertise,
            enabled = !stats.running,
            description = if (config.advertise) {
                "Requesting the ADVERTISE role. Source is DEBUG_SPIKE_HARNESS."
            } else {
                "SCAN_ONLY. Default, and the radio defaults to it independently."
            },
            onChange = { onConfigChange(config.copy(advertise = it)) },
        )

        ToggleRow(
            label = "CONNECTABLE flag (bit0)",
            checked = config.connectable,
            enabled = !stats.running,
            description = "Also sets the link-layer PDU type. Controllers may rotate the address " +
                "differently for ADV_IND vs ADV_NONCONN_IND — capture both.",
            onChange = { onConfigChange(config.copy(connectable = it)) },
        )

        ToggleRow(
            label = "Max capture (SCAN_MODE_LOW_LATENCY)",
            checked = config.maxCapture,
            enabled = !stats.running,
            description = if (config.maxCapture) {
                "ON — 100% duty. Needed for §5.3 C5 capture yield. " +
                    "ANY BATTERY NUMBER FROM THIS RUN IS INVALID."
            } else {
                "OFF — duty profile governs. Use this for battery runs."
            },
            onChange = { onConfigChange(config.copy(maxCapture = it)) },
        )

        StepperRow(
            label = "Duty profile",
            value = config.duty.name,
            enabled = !stats.running,
            onDown = { onConfigChange(config.copy(duty = config.duty.previous())) },
            onUp = { onConfigChange(config.copy(duty = config.duty.next())) },
        )

        StepperRow(
            label = "tx_power_cal claimed (dBm)",
            value = config.txPowerCalDbm.toString(),
            enabled = !stats.running,
            onDown = { onConfigChange(config.copy(txPowerCalDbm = config.txPowerCalDbm - 5)) },
            onUp = { onConfigChange(config.copy(txPowerCalDbm = config.txPowerCalDbm + 5)) },
        )
        Text(
            "Clamped to the seven legal values (SPEC §3.2). UNMEASURED for every real handset — " +
                "calibration/ is a placeholder and filling it is a spike deliverable.",
            color = colors.inkMuted,
        )

        HorizontalDivider(color = colors.outline)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Button(
                onClick = if (stats.running) onStop else onStart,
                enabled = permissionsGranted,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (stats.running) "Stop run" else "Start run")
            }
            OutlinedButton(onClick = onFlush, modifier = Modifier.weight(1f)) {
                Text("Flush to disk")
            }
        }

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ integrity first
        Text("CAPTURE INTEGRITY", color = colors.ink)
        Text(
            text = stats.integrityNote,
            color = if (stats.bridgedAddresses > 0 || stats.bridgedEids > 0 ||
                stats.diagnosticsDropped > 0 || stats.writeFailures > 0
            ) {
                colors.danger
            } else {
                colors.inkMuted
            },
            modifier = Modifier.semantics { contentDescription = stats.integrityNote },
        )

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ B8 counters
        Text("B8 — RPA CO-ROTATION SCREEN", color = colors.ink)
        Stat("Unique advertiser addresses", stats.uniqueAdvertiserAddresses.toString())
        Stat("Unique ephemeral ids", stats.uniqueEphemeralIds.toString())
        Stat("Addresses seen with >1 eid", stats.bridgedAddresses.toString())
        Stat("Eids seen with >1 address", stats.bridgedEids.toString())
        Text(
            "Both bridge counters MUST be 0. A non-zero value is real evidence of failure. " +
                "A zero value is NOT evidence of success — a phone's scanner hops channels and " +
                "misses packets, so this is a screen, not the §5.3 measurement. That needs three " +
                "sniffer dongles and it cannot see the address type reliably either.",
            color = colors.inkMuted,
        )

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ run counters
        Text("RUN", color = colors.ink)
        Stat("Running", stats.running.toString())
        Stat("Run id", stats.runId)
        Stat("Elapsed", "${stats.elapsedMillis / 1000}s")
        Stat("Sightings recorded", stats.sightings.toString())
        Stat("Product stream (cross-check)", stats.productStreamSightings.toString())
        Stat("Carrier B candidates (UUID, no service data)", stats.carrierBObservations.toString())
        Stat("Self-eid frames (E_SELF_EID)", stats.selfEidDrops.toString())
        Stat("Diagnostic records DROPPED by us", stats.diagnosticsDropped.toString())
        Stat("File write failures", stats.writeFailures.toString())

        Text("Resolved peers by slot", color = colors.ink)
        if (stats.resolvedBySlot.isEmpty()) {
            Text("none yet", color = colors.inkMuted)
        } else {
            stats.resolvedBySlot.toSortedMap().forEach { (slot, n) -> Stat("slot $slot", n.toString()) }
        }

        Text("Bands", color = colors.ink)
        if (stats.bandCounts.isEmpty()) {
            Text("none yet", color = colors.inkMuted)
        } else {
            stats.bandCounts.forEach { (band, n) -> Stat(band, n.toString()) }
        }

        Text("Decode errors", color = colors.ink)
        if (stats.decodeErrors.isEmpty()) {
            Text("none", color = colors.inkMuted)
        } else {
            stats.decodeErrors.forEach { (code, n) -> Stat(code, n.toString()) }
        }

        Text("Address type bits (HINT ONLY — TxAdd is not visible to Android)", color = colors.ink)
        if (stats.addressTypeCounts.isEmpty()) {
            Text("none yet", color = colors.inkMuted)
        } else {
            stats.addressTypeCounts.forEach { (t, n) -> Stat(t, n.toString()) }
        }

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ radio
        Text("RADIO", color = colors.ink)
        Stat("Availability", stats.radioAvailability)
        Stat("Advertise role", stats.advertiseRole)
        Stat("Advertise status", "${stats.advertiseStatus} ${stats.advertiseDetail}")
        Stat("Peripheral role supported", stats.peripheralRoleSupported.toString())
        Stat("Scan starts", stats.scanStarts.toString())
        Stat("Scan failures", stats.scanFailures.toString())
        Stat("Scan throttle deferrals", stats.scanThrottles.toString())
        Stat("Epoch rotations", stats.epochRotations.toString())
        Stat("Adapter state changes", stats.adapterEvents.toString())
        Stat("Last radio event", stats.lastEventLine)

        if (!stats.peripheralRoleSupported) {
            Text(
                "THIS HANDSET REPORTS NO PERIPHERAL ROLE. It can see others and cannot be seen. " +
                    "That is a product finding (KEY_SCHEDULE §4.3.6): a user is entitled to be " +
                    "told, in plain language, rather than left wondering why nobody waves.",
                color = colors.danger,
            )
        }

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ export
        Text("EXPORT — adb only, no other egress exists", color = colors.ink)
        Text(stats.directory, color = colors.inkMuted)
        Text(stats.adbCommand, color = colors.accentRadar)
        Text(
            "events.jsonl is the record (every sighting AND every radio lifecycle event, in " +
                "arrival order, contiguous sequence numbers). sightings.csv is the same rows " +
                "flattened. meta.json is device, OS build fingerprint, config and the honesty " +
                "flags. Nothing is deduplicated, smoothed or sampled.",
            color = colors.inkMuted,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One semantics node per row so TalkBack reads "label, value" rather than two
            // disconnected fragments.
            .semantics { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = RadiusTheme.colors.inkMuted, modifier = Modifier.weight(1f))
        Text(value, color = RadiusTheme.colors.ink)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    description: String,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RadiusTheme.colors.ink, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    Text(description, color = RadiusTheme.colors.inkMuted)
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RadiusTheme.colors.ink, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDown, enabled = enabled) { Text("-") }
        Text(
            text = value,
            color = RadiusTheme.colors.ink,
            modifier = Modifier
                .padding(horizontal = RadiusTheme.spacing.sm)
                .semantics { contentDescription = "$label: $value" },
        )
        OutlinedButton(onClick = onUp, enabled = enabled) { Text("+") }
    }
}

private fun DutyProfile.next(): DutyProfile =
    DutyProfile.entries[(ordinal + 1) % DutyProfile.entries.size]

private fun DutyProfile.previous(): DutyProfile =
    DutyProfile.entries[(ordinal - 1 + DutyProfile.entries.size) % DutyProfile.entries.size]
