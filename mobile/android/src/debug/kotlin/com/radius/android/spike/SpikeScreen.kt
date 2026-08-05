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

        // ------------------------------------------------------------------ mode + procedure
        // FIRST, ABOVE EVERYTHING ELSE. The three Phase 0 measurements are mutually hostile and each
        // produces a plausible-looking file under the wrong mode, so choosing the mode is the first
        // decision of a run and therefore the first control on the screen. The instrument then
        // states, in words, what the current combination can and cannot claim.
        Text("MODE — what this run is FOR", color = colors.ink)
        StepperRow(
            label = "Mode",
            value = config.mode.label,
            enabled = !stats.running,
            onDown = { onConfigChange(config.copy(mode = config.mode.previous())) },
            onUp = { onConfigChange(config.copy(mode = config.mode.next())) },
        )
        Text(SpikeProcedure.headline(config.mode, config.maxCapture), color = colors.accentRadar)

        // The procedure lives on the phone because the person running this is in a car park with no
        // laptop. A runbook in a repository is a runbook they do not have.
        Text("WHAT TO DO", color = colors.ink)
        SpikeProcedure.steps(config.mode).forEach { step -> Text(step, color = colors.inkMuted) }

        HorizontalDivider(color = colors.outline)

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

        // ------------------------------------------------------------------ P2 discovery latency
        Text("P2 — DISCOVERY LATENCY (target p50 <= 5s)", color = colors.ink)
        Stat("Samples", stats.latencySamples.toString())
        Stat("p50", stats.latencyP50Ms.msOrDash())
        Stat("p95", stats.latencyP95Ms.msOrDash())
        Stat("Min (negative = proven skew)", stats.latencyMinMs.msOrDash())
        Stat("Max", stats.latencyMaxMs.msOrDash())
        Stat("First seen AFTER the ON window", stats.latencyAfterOnWindow.toString())
        Stat("Peer-cycles with no sighting at all", stats.latencyMissedPeerCycles.toString())
        Stat("Our own transmit lag from cycle start", stats.emitStartLagMs.msOrDash())
        Stat(
            "Network time (for the error bar)",
            if (stats.networkTimeAvailable) "AVAILABLE" else "NONE",
        )
        if (stats.networkTimeSource.isNotEmpty()) {
            Text(stats.networkTimeSource, color = colors.inkMuted)
        }
        Text(
            text = stats.latencyNote,
            // Danger, not muted, when the device has itself observed proof that the clocks disagree
            // or has no way to bound the disagreement. A caveat rendered in the same grey as
            // everything else is a caveat nobody reads.
            color = if ((stats.latencyMinMs ?: 0L) < 0L ||
                (stats.mode == SpikeMode.LATENCY_PROBE && !stats.networkTimeAvailable)
            ) {
                colors.danger
            } else {
                colors.inkMuted
            },
            modifier = Modifier.semantics { contentDescription = stats.latencyNote },
        )

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ P1 battery
        Text("P1 — BATTERY (target <4%/hr scanning, <1%/day idle)", color = colors.ink)
        Stat("Samples", stats.batterySamples.toString())
        Stat("Level now", if (stats.batteryLevelPct < 0) "-" else "${stats.batteryLevelPct}%")
        Stat("Level change this run", "${stats.batteryLevelDeltaPct}%")
        Stat("Charge counter change", "${stats.batteryChargeDeltaUah} uAh")
        Stat("%/hr from level (coarse)", stats.percentPerHourFromLevel.rateOrDash())
        Stat("%/hr from charge counter", stats.percentPerHourFromCharge.rateOrDash())
        Stat("Temperature", if (stats.batteryTemperatureDeciC < 0) "-" else
            "${stats.batteryTemperatureDeciC / 10.0} C")
        Stat("Plugged now / ever this run", "${stats.batteryPlugged} / ${stats.batteryEverPlugged}")
        Stat("Screen interactive", stats.batteryScreenInteractive.toString())
        Stat("Doze / power save", "${stats.batteryDeviceIdle} / ${stats.batteryPowerSave}")
        Text(
            text = stats.batteryNote,
            color = if (stats.batteryInvalidReason.isNotEmpty()) colors.danger else colors.inkMuted,
            modifier = Modifier.semantics { contentDescription = stats.batteryNote },
        )

        Text("RADIO TIME — what makes the number above attributable", color = colors.ink)
        Stat("Scan open", "${stats.scanOnMs / 1000}s (${stats.scanOnPct}% of run)")
        Stat("Advertising live", "${stats.advertiseOnMs / 1000}s")
        Stat("Scan open transitions", stats.scanOpenTransitions.toString())
        Stat("Scan mode in effect", stats.scanModeName)
        Stat("Nominal controller duty", "${stats.nominalScanDutyPct}%")
        Text(
            "Scan open is HOST-REQUESTED radio time. The controller duty-cycles the receiver " +
                "inside itself and gives an app no way to see it, so real receiver time is roughly " +
                "this x the nominal duty — and several OEMs override that nominal. A battery " +
                "figure with a low scan-open percentage is a figure for a phone that was not " +
                "scanning.",
            color = colors.inkMuted,
        )

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ SPEC 5.0 density
        Text("SPEC 5.0 — ACQUISITION RATE AND PEER DENSITY", color = colors.ink)
        Stat("Buckets written", stats.densityBuckets.toString())
        Stat("Distinct peers this run", stats.distinctPeersTotal.toString())
        Stat("Concurrent peers now", stats.concurrentPeers.toString())
        Stat("Peak concurrent", stats.peakConcurrentPeers.toString())
        Text(
            "\"Concurrent\" means heard from in the last " +
                "${SpikeTiming.PEER_LIVENESS_MS / 1000}s — concurrency is not observable, only " +
                "recency is. density_peers.csv carries per-peer first/last timestamps so any other " +
                "window can be re-derived. Buckets with zero packets ARE written; a success rate " +
                "computed only over buckets where something succeeded is 100% by construction.",
            color = colors.inkMuted,
        )

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ B8 counters
        Text("B8 — RPA CO-ROTATION SCREEN", color = colors.ink)
        Stat("Unique advertiser addresses", stats.uniqueAdvertiserAddresses.toString())
        Stat("Unique ephemeral ids", stats.uniqueEphemeralIds.toString())
        Stat("Addresses seen with >1 eid", stats.bridgedAddresses.toString())
        Stat("Eids seen with >1 address", stats.bridgedEids.toString())
        if (!config.mode.bijectionValid) {
            Text(
                "VOID IN THIS MODE. The latency probe stops and restarts our own transmitter once " +
                    "per cycle, which rotates this device's advertising address INSIDE a protocol " +
                    "epoch. Any bridging seen here is self-inflicted by the instrument. Test §4.3.1 " +
                    "in CAPTURE mode.",
                color = colors.danger,
            )
        }
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
                "flattened. battery.csv is P1 with the radio state on every row. latency.csv is " +
                "P2, uncorrected. density.csv and density_peers.csv are SPEC 5.0. meta.json is " +
                "device, OS build fingerprint, config, both clock references and the honesty " +
                "flags. Nothing is deduplicated, smoothed or sampled.",
            color = colors.inkMuted,
            textAlign = TextAlign.Start,
        )

        HorizontalDivider(color = colors.outline)

        // ------------------------------------------------------------------ the trust table
        // LAST, so it is what a person sees after scrolling past a screenful of numbers, which is
        // the moment they are most likely to quote one. Every measurement, and whether it can be
        // read on its own.
        Text("BEFORE YOU QUOTE ANY NUMBER ABOVE", color = colors.ink)
        SpikeProcedure.MEASUREMENTS.forEach { m ->
            val line = "${m.name} — ${m.trust.label}. Answers: ${m.answers}. ${m.caveat}"
            Text(
                text = line,
                color = when (m.trust) {
                    SpikeProcedure.Trust.STANDS_ALONE -> colors.inkMuted
                    else -> colors.danger
                },
                modifier = Modifier.semantics { contentDescription = line },
            )
        }
    }
}

/** Milliseconds, or a dash. Never a zero standing in for "not measured". */
private fun Long?.msOrDash(): String = if (this == null || this == -1L) "-" else "${this}ms"

/** A rate to one decimal, or a dash. A suppressed projection must not render as 0.0. */
private fun Double?.rateOrDash(): String =
    if (this == null) "-- (too early / unavailable)" else String.format("%.2f %%/hr", this)

private fun SpikeMode.next(): SpikeMode =
    SpikeMode.entries[(ordinal + 1) % SpikeMode.entries.size]

private fun SpikeMode.previous(): SpikeMode =
    SpikeMode.entries[(ordinal - 1 + SpikeMode.entries.size) % SpikeMode.entries.size]

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
