package com.radius.android.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
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
 *
 * ## Layout rules, learned on hardware
 *
 * Three things were wrong the first time this ran on a real handset (Redmi 15 5G, API 36) and all
 * three are structural, so they are written down rather than left to be re-derived:
 *
 *  - **The window is edge-to-edge and we must say what to do about it.** API 35+ ignores the
 *    opt-out. Insets are consumed ONCE, here, with `safeDrawing` on the root — status bar, nav bar
 *    and display cutout together. Content is clipped to the safe area rather than scrolling under a
 *    transparent bar: this is an instrument, and a number half-hidden behind a clock is a number
 *    somebody misreads.
 *  - **Start/Stop is NEVER inside the scroll.** The procedure text alone is two and a half screens,
 *    and the operator is holding two phones in a car park. A control that requires finding the
 *    right scroll offset first is a control that does not exist under field conditions. It is
 *    pinned to the bottom, with the one-line run status next to it.
 *  - **A label in a Row never competes with its own value for width.** See [StepperRow].
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadiusTheme.colors.surface.canvas)
            // ONE inset consumption for the whole screen. safeDrawing = system bars + display
            // cutout + IME, so this survives a gesture-nav phone, a three-button phone and a
            // punch-hole cutout without three separate fixes.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        SpikeBody(
            stats = stats,
            config = config,
            permissionsGranted = permissionsGranted,
            onRequestPermissions = onRequestPermissions,
            onConfigChange = onConfigChange,
            // weight(1f), NOT fillMaxSize: the scrolling body takes whatever is left AFTER the
            // pinned bar has been measured. Reverse those and the bar is pushed off the bottom of
            // the screen by the very content it exists to stay in front of.
            modifier = Modifier.weight(1f),
        )
        RunControlBar(
            stats = stats,
            config = config,
            permissionsGranted = permissionsGranted,
            onStart = onStart,
            onStop = onStop,
            onFlush = onFlush,
        )
    }
}

/** Everything that is allowed to scroll. Reference material, live counters, the trust table. */
@Composable
private fun SpikeBody(
    stats: SpikeStats,
    config: SpikeConfig,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onConfigChange: (SpikeConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = RadiusTheme.spacing
    val colors = RadiusTheme.colors

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space8),
    ) {
        Text(
            text = "RADIUS SPIKE — Phase 0 instrument",
            color = colors.content.primary,
        )
        Text(
            text = "Debug build. Records raw RSSI and peer addresses to local storage. " +
                "Nothing leaves this device: the app declares no INTERNET permission.",
            color = colors.content.secondary,
        )

        HorizontalDivider(color = colors.border.hairline)

        if (!permissionsGranted) {
            Text(
                "Bluetooth permissions not granted. A scan without them returns NOTHING, silently.",
                color = colors.status.danger,
            )
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Bluetooth permissions")
            }
            // CONFIRMED ON A REDMI 15 5G, and it costs a field session if it is a surprise.
            Text(SpikeProcedure.GRANT_FROM_LAPTOP_NOTE, color = colors.content.secondary)
            HorizontalDivider(color = colors.border.hairline)
        }

        // ------------------------------------------------------------------ mode + procedure
        // FIRST, ABOVE EVERYTHING ELSE. The three Phase 0 measurements are mutually hostile and each
        // produces a plausible-looking file under the wrong mode, so choosing the mode is the first
        // decision of a run and therefore the first control on the screen. The instrument then
        // states, in words, what the current combination can and cannot claim.
        Text("MODE — what this run is FOR", color = colors.content.primary)
        StepperRow(
            label = "Mode",
            value = config.mode.label,
            enabled = !stats.running,
            onDown = { onConfigChange(config.copy(mode = config.mode.previous())) },
            onUp = { onConfigChange(config.copy(mode = config.mode.next())) },
        )
        Text(SpikeProcedure.headline(config.mode, config.maxCapture), color = colors.accent.radar.default)

        // The procedure lives on the phone because the person running this is in a car park with no
        // laptop. A runbook in a repository is a runbook they do not have.
        Text("WHAT TO DO", color = colors.content.primary)
        SpikeProcedure.steps(config.mode).forEach { step -> Text(step, color = colors.content.secondary) }

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ configuration
        Text("CONFIG (changing any of these restarts into a new file)", color = colors.content.primary)

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
            color = colors.content.secondary,
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
            color = colors.content.secondary,
        )

        HorizontalDivider(color = colors.border.hairline)

        // Start / Stop / Flush ARE NOT HERE ANY MORE. They live in RunControlBar, pinned below
        // this scroll region, because on a 1080x2340 handset at default font scale they sat two
        // and a half screens down behind the procedure text. Do not "tidy" them back into the
        // flow: the reason is field ergonomics, not aesthetics.

        // THE RUN REFUSED TO START. Above everything else, in danger, with the reason spelled out:
        // the previous behaviour was an exception escaping through the foreground service, which
        // killed the instrument while its "Radius spike running" notification stayed on screen.
        // A person in a car park needs to be told the file could not be opened, not left watching
        // a counter that will never move.
        if (stats.startFailure.isNotEmpty()) {
            Text("RUN REFUSED — NOTHING IS BEING RECORDED", color = colors.status.danger)
            Text(
                text = stats.startFailure,
                color = colors.status.danger,
                modifier = Modifier.semantics { contentDescription = stats.startFailure },
            )
            HorizontalDivider(color = colors.border.hairline)
        }

        // ------------------------------------------------------------------ integrity first
        Text("CAPTURE INTEGRITY", color = colors.content.primary)
        Text(
            text = stats.integrityNote,
            color = if (stats.bridgedAddresses > 0 || stats.bridgedEids > 0 ||
                stats.diagnosticsDropped > 0 || stats.writeFailures > 0 ||
                stats.radioEventsDropped > 0 || stats.timestampsClamped > 0
            ) {
                colors.status.danger
            } else {
                colors.content.secondary
            },
            modifier = Modifier.semantics { contentDescription = stats.integrityNote },
        )

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ P2 discovery latency
        Text("P2 — DISCOVERY LATENCY (target p50 <= 5s)", color = colors.content.primary)
        Stat("Samples", stats.latencySamples.toString())
        Stat("p50", stats.latencyP50Ms.msOrDash())
        Stat("p95", stats.latencyP95Ms.msOrDash())
        Stat("Min (negative = proven skew)", stats.latencyMinMs.msOrDash())
        Stat("Max", stats.latencyMaxMs.msOrDash())
        Stat("First seen AFTER the ON window", stats.latencyAfterOnWindow.toString())
        Stat("Peer-cycles with no sighting at all", stats.latencyMissedPeerCycles.toString())
        // The three numbers that make the miss count readable. A high miss count against one
        // departed peer is a handset that died; the same count spread over several expected peers
        // with none departed is an acquisition-rate problem. Different findings, different fixes.
        Stat("Peers expected each cycle", stats.latencyPeersExpected.toString())
        Stat("Peers that went away and stayed away", stats.latencyPeersDeparted.toString())
        Stat("Cycles closed (miss-rate denominator)", stats.latencyCyclesClosed.toString())
        Stat("Our own transmit lag from cycle start", stats.emitStartLagMs.msOrDash())
        Stat(
            "Network time (for the error bar)",
            if (stats.networkTimeAvailable) "AVAILABLE" else "NONE",
        )
        if (stats.networkTimeSource.isNotEmpty()) {
            Text(stats.networkTimeSource, color = colors.content.secondary)
        }
        Text(
            text = stats.latencyNote,
            // Danger, not muted, when the device has itself observed proof that the clocks disagree
            // or has no way to bound the disagreement. A caveat rendered in the same grey as
            // everything else is a caveat nobody reads.
            color = if ((stats.latencyMinMs ?: 0L) < 0L ||
                (stats.mode == SpikeMode.LATENCY_PROBE && !stats.networkTimeAvailable)
            ) {
                colors.status.danger
            } else {
                colors.content.secondary
            },
            modifier = Modifier.semantics { contentDescription = stats.latencyNote },
        )

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ P1 battery
        Text("P1 — BATTERY (target <4%/hr scanning, <1%/day idle)", color = colors.content.primary)
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
            color = if (stats.batteryInvalidReason.isNotEmpty()) colors.status.danger else colors.content.secondary,
            modifier = Modifier.semantics { contentDescription = stats.batteryNote },
        )

        Text("RADIO TIME — what makes the number above attributable", color = colors.content.primary)
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
            color = colors.content.secondary,
        )

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ SPEC 5.0 density
        Text("SPEC 5.0 — ACQUISITION RATE AND PEER DENSITY", color = colors.content.primary)
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
            color = colors.content.secondary,
        )

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ B8 counters
        Text("B8 — RPA CO-ROTATION SCREEN", color = colors.content.primary)
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
                color = colors.status.danger,
            )
        }
        Text(
            "Both bridge counters MUST be 0. A non-zero value is real evidence of failure. " +
                "A zero value is NOT evidence of success — a phone's scanner hops channels and " +
                "misses packets, so this is a screen, not the §5.3 measurement. That needs three " +
                "sniffer dongles and it cannot see the address type reliably either.",
            color = colors.content.secondary,
        )

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ run counters
        Text("RUN", color = colors.content.primary)
        Stat("Running", stats.running.toString())
        Stat("Run id", stats.runId)
        Stat("Elapsed", "${stats.elapsedMillis / 1000}s")
        Stat("Sightings recorded", stats.sightings.toString())
        Stat("Product stream (cross-check)", stats.productStreamSightings.toString())
        Stat("Carrier B candidates (UUID, no service data)", stats.carrierBObservations.toString())
        Stat("Self-eid frames (E_SELF_EID)", stats.selfEidDrops.toString())
        Stat("Diagnostic records DROPPED by us", stats.diagnosticsDropped.toString())
        // A different loss from the one above, and worse: a lost SCAN_STOPPED leaves the duty
        // ledger's interval open and inflates scan_on_ms, which is what the battery figure is
        // divided by. Shown separately so the two are never read as one number.
        Stat("Radio LIFECYCLE events lost (corrupts scan_on_ms)", stats.radioEventsDropped.toString())
        // The third loss counter, and the only one that costs no rows. Non-zero means this handset
        // fabricates ScanResult.timestampNanos, so P2 is measuring our own scheduling. Visible here
        // because the person in the car park should be able to abandon the run in minute two rather
        // than discover it in the file six weeks later.
        Stat("Scan timestamps CLAMPED (voids P2 timing)", stats.timestampsClamped.toString())
        Stat("File write failures", stats.writeFailures.toString())

        Text("Resolved peers by slot", color = colors.content.primary)
        if (stats.resolvedBySlot.isEmpty()) {
            Text("none yet", color = colors.content.secondary)
        } else {
            stats.resolvedBySlot.toSortedMap().forEach { (slot, n) -> Stat("slot $slot", n.toString()) }
        }

        Text("Bands", color = colors.content.primary)
        if (stats.bandCounts.isEmpty()) {
            Text("none yet", color = colors.content.secondary)
        } else {
            stats.bandCounts.forEach { (band, n) -> Stat(band, n.toString()) }
        }

        Text("Decode errors", color = colors.content.primary)
        if (stats.decodeErrors.isEmpty()) {
            Text("none", color = colors.content.secondary)
        } else {
            stats.decodeErrors.forEach { (code, n) -> Stat(code, n.toString()) }
        }

        Text("Address type bits (HINT ONLY — TxAdd is not visible to Android)", color = colors.content.primary)
        if (stats.addressTypeCounts.isEmpty()) {
            Text("none yet", color = colors.content.secondary)
        } else {
            stats.addressTypeCounts.forEach { (t, n) -> Stat(t, n.toString()) }
        }

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ radio
        Text("RADIO", color = colors.content.primary)
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
                color = colors.status.danger,
            )
        }

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ export
        Text("EXPORT — adb only, no other egress exists", color = colors.content.primary)
        Text(stats.directory, color = colors.content.secondary)
        Text(stats.adbCommand, color = colors.accent.radar.default)
        Text(
            "events.jsonl is the record (every sighting AND every radio lifecycle event, in " +
                "arrival order, contiguous sequence numbers). sightings.csv is the same rows " +
                "flattened. battery.csv is P1 with the radio state on every row. latency.csv is " +
                "P2, uncorrected. density.csv and density_peers.csv are SPEC 5.0. meta.json is " +
                "device, OS build fingerprint, config, both clock references and the honesty " +
                "flags. Nothing is deduplicated, smoothed or sampled.",
            color = colors.content.secondary,
            textAlign = TextAlign.Start,
        )

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ driving from a laptop
        // On the screen, not only in docs/oem.md, for the same reason the procedure is: the person
        // who needs this is the person who does not have the repository open.
        Text("DRIVING THIS FROM A LAPTOP — what works, what is refused", color = colors.content.primary)
        SpikeProcedure.HOST_NOTES.forEach { note ->
            Text(note, color = colors.content.secondary)
        }

        HorizontalDivider(color = colors.border.hairline)

        // ------------------------------------------------------------------ the trust table
        // LAST, so it is what a person sees after scrolling past a screenful of numbers, which is
        // the moment they are most likely to quote one. Every measurement, and whether it can be
        // read on its own.
        Text("BEFORE YOU QUOTE ANY NUMBER ABOVE", color = colors.content.primary)
        SpikeProcedure.MEASUREMENTS.forEach { m ->
            val line = "${m.name} — ${m.trust.label}. Answers: ${m.answers}. ${m.caveat}"
            Text(
                text = line,
                color = when (m.trust) {
                    SpikeProcedure.Trust.STANDS_ALONE -> colors.content.secondary
                    else -> colors.status.danger
                },
                modifier = Modifier.semantics { contentDescription = line },
            )
        }
    }
}

/**
 * PINNED. Never scrolls. The three things a person in a car park must be able to do without first
 * finding the right scroll offset: start the run, stop the run, and see at a glance whether
 * anything is being recorded.
 *
 * The status line duplicates information that also appears in the scrolling body, and that
 * duplication is deliberate — the two states that must never be scrolled past are "the run was
 * REFUSED and nothing is being written" and "the permissions are missing so the scan will return
 * nothing, silently". Both render here in danger colour, with the full explanation still in place
 * further up.
 */
@Composable
private fun RunControlBar(
    stats: SpikeStats,
    config: SpikeConfig,
    permissionsGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFlush: () -> Unit,
) {
    val spacing = RadiusTheme.spacing
    val colors = RadiusTheme.colors

    // Nothing is conveyed by colour alone: every state below says what it is in words first.
    val status: String
    val statusColor: Color
    when {
        stats.startFailure.isNotEmpty() -> {
            status = "RUN REFUSED — NOTHING IS BEING RECORDED. Reason is above, under RUN REFUSED."
            statusColor = colors.status.danger
        }

        !permissionsGranted -> {
            status = "PERMISSIONS NOT GRANTED — scroll up and tap Grant. A scan without them " +
                "returns nothing, silently."
            statusColor = colors.status.danger
        }

        stats.running -> {
            status = "RECORDING — ${config.mode.label} · ${stats.elapsedMillis / 1000}s · " +
                "${stats.sightings} sightings"
            statusColor = colors.accent.radar.default
        }

        else -> {
            status = "IDLE — ${config.mode.label}. Nothing is being written."
            statusColor = colors.content.secondary
        }
    }

    HorizontalDivider(color = colors.border.hairline)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Elevation as surface LIGHTENING plus a hairline, per the token notes — not a shadow.
            .background(colors.surface.raised)
            .padding(spacing.space16),
        verticalArrangement = Arrangement.spacedBy(spacing.space8),
    ) {
        Text(
            text = status,
            color = statusColor,
            modifier = Modifier.semantics { contentDescription = status },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.space8),
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

/**
 * `label ............ value`, the row this screen is mostly made of.
 *
 * BOTH SIDES CARRY A WEIGHT, and that is the whole point. This had the same defect as
 * [StepperRow] — the label weighted, the value not — and it was live: on hardware,
 * `Stat("Last radio event", "DUTY role=SCAN_ONLY source=DEBUG_SPIKE_HARNESS")` measured
 * **1099px tall for one row**, because the value claimed all 990px of content width as its
 * intrinsic width and the label was left with nothing to render "Last radio event" into but a
 * single-glyph column.
 *
 * It survived the first hardware run only because it sits far enough down the screen that nobody
 * had scrolled to it yet. With a weight on each side neither can be squeezed below half, a long
 * value wraps inside its own half instead of eating the label's, and short pairs still read as
 * left-and-right because the value is end-aligned.
 */
@Composable
private fun Stat(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One semantics node per row so TalkBack reads "label, value" rather than two
            // disconnected fragments.
            .semantics { contentDescription = "$label: $value" },
        horizontalArrangement = Arrangement.spacedBy(RadiusTheme.spacing.space8),
    ) {
        Text(label, color = RadiusTheme.colors.content.secondary, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = RadiusTheme.colors.content.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
        Text(label, color = RadiusTheme.colors.content.primary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    Text(description, color = RadiusTheme.colors.content.secondary)
}

/**
 * Label on its own line, then `[-] value [+]`.
 *
 * ## Why it is not one Row
 *
 * It was, and on hardware the "Mode" label rendered as `M` / `o` / `d` / `e`, one letter per line,
 * down the left edge — at DEFAULT font scale, not an accessibility setting.
 *
 * The mechanism, because it will recur anywhere a Row mixes text with fixed-size controls: a Row
 * measures its UNWEIGHTED children first, at whatever width they ask for, and only then divides
 * what is left among the weighted ones. The label had the weight; the value did not. So
 * `"CAPTURE (B8 + sightings)"` — 567px of the 990px content width — plus two 163px buttons took
 * their intrinsic widths first, and the label was handed the 51px remainder, which is one glyph.
 * Giving the label a bigger weight would only move the failure to a longer mode name.
 *
 * So the label stops competing for width at all. Inside the control row it is the VALUE that
 * carries the weight, because the value is the thing that is allowed to wrap: the buttons are
 * fixed, the value has slack, and no arrangement of label length, value length or font scale can
 * squeeze any of the three below its minimum. The extra line costs about 74px and buys an
 * instrument that cannot be misread.
 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RadiusTheme.spacing.space4),
    ) {
        Text(label, color = RadiusTheme.colors.content.primary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RadiusTheme.spacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TalkBack would otherwise announce a bare "minus button" with no idea what it steps.
            OutlinedButton(
                onClick = onDown,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "$label: previous value" },
            ) { Text("-") }
            Text(
                text = value,
                color = RadiusTheme.colors.content.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "$label: $value" },
            )
            OutlinedButton(
                onClick = onUp,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "$label: next value" },
            ) { Text("+") }
        }
    }
}

private fun DutyProfile.next(): DutyProfile =
    DutyProfile.entries[(ordinal + 1) % DutyProfile.entries.size]

private fun DutyProfile.previous(): DutyProfile =
    DutyProfile.entries[(ordinal - 1 + DutyProfile.entries.size) % DutyProfile.entries.size]
