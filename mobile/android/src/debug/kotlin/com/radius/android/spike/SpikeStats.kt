package com.radius.android.spike

/**
 * The live readout. What a person standing in a car park needs to see to know whether the run is
 * working, without waiting to pull the file.
 *
 * SNAPSHOT VALUE TYPE. Recomputed and republished by [SpikeController]; nothing here is mutated in
 * place, so the UI cannot read a half-updated state.
 *
 * @property uniqueAdvertiserAddresses THE HEADLINE COUNTER, and the raw material for B8. Every
 *   distinct advertising address this device has heard carrying our service UUID. Against a known
 *   number of physical handsets, its growth rate IS the observed RPA rotation period.
 * @property bridgedAddresses addresses observed with MORE THAN ONE ephemeral id.
 * @property bridgedEids ephemeral ids observed from MORE THAN ONE address.
 *
 * The two `bridged*` counters together are the `KEY_SCHEDULE.md` §4.3.1 bijection test, computed
 * live from what this phone's own receiver saw. **BOTH MUST BE ZERO.** A single non-zero value is
 * the §4.1 bridging attack and there is no "mostly passes".
 *
 * READ THE LIMIT BEFORE READING THE NUMBER. This is a SCREEN, not the measurement:
 *
 *  - A phone's scanner hops channels and misses packets, so zero here is "no overlap observed",
 *    not "no overlap". §5.1 is explicit that a missed packet and an absent packet are
 *    indistinguishable, which is precisely why three sniffer dongles exist.
 *  - It cannot see the address TYPE reliably (`SPEC.md`/`KEY_SCHEDULE.md` §4.3.2 needs the TxAdd
 *    bit, which Android never exposes), so the catastrophic public-address case can pass here.
 *  - It only sees devices running our build, so it says nothing about the general population.
 *
 * A NON-ZERO value is real evidence of failure. A ZERO value is not evidence of success. Those two
 * statements are not symmetric and the asymmetry is the whole reason §5.3 exists.
 */
internal data class SpikeStats(
    val running: Boolean = false,
    /**
     * Why the last start attempt refused, or empty. Non-empty means NO RUN IS IN PROGRESS and no
     * file exists for the attempt — the harness could not open its capture files and said so
     * instead of crashing through the foreground service. Rendered in the danger colour.
     */
    val startFailure: String = "",
    val runId: String = "-",
    val elapsedMillis: Long = 0L,
    val directory: String = "-",
    val adbCommand: String = "-",

    val sightings: Long = 0L,
    val productStreamSightings: Long = 0L,
    val carrierBObservations: Long = 0L,

    val uniqueAdvertiserAddresses: Int = 0,
    val uniqueEphemeralIds: Int = 0,
    val bridgedAddresses: Int = 0,
    val bridgedEids: Int = 0,

    val resolvedBySlot: Map<Int, Long> = emptyMap(),
    val selfEidDrops: Long = 0L,
    val decodeErrors: Map<String, Long> = emptyMap(),
    val bandCounts: Map<String, Long> = emptyMap(),
    val addressTypeCounts: Map<String, Long> = emptyMap(),

    val scanFailures: Long = 0L,
    val scanThrottles: Long = 0L,
    val scanStarts: Long = 0L,
    val epochRotations: Long = 0L,
    val adapterEvents: Long = 0L,

    val advertiseStatus: String = "STOPPED",
    val advertiseDetail: String = "",
    val advertiseRole: String = "SCAN_ONLY",
    val radioAvailability: String = "UNKNOWN",
    val peripheralRoleSupported: Boolean = false,

    val diagnosticsDropped: Long = 0L,
    /**
     * Radio LIFECYCLE events the radio produced and could not hand over. A different hole from
     * [diagnosticsDropped] and a worse-behaved one: a lost `SCAN_STOPPED` leaves `SpikeDutyLedger`
     * with an interval that never closes, which inflates `scan_on_ms` — the denominator that makes
     * the whole battery figure attributable. A dropped sighting costs one row; a dropped event
     * corrupts a total.
     */
    val radioEventsDropped: Long = 0L,
    /**
     * Scan results whose `timestampNanos` was unusable, so the observation time was forced to
     * CALLBACK time. See `BleRadio.timestampsClamped`.
     *
     * The third loss counter, and the least like the other two. Nothing is missing from the file
     * when this is non-zero — every row is present, every row looks ordinary, and every timing
     * figure derived from those rows is quantised to when the app was scheduled rather than when
     * the packet arrived. It has to reach [integrityNote] for exactly that reason: a run on such a
     * handset is one where P2 latency is VOID, and until this was surfaced the verdict on that run
     * still read "no bridging observed so far", which is a clean bill of health for a file whose
     * timing conclusions cannot be drawn.
     */
    val timestampsClamped: Long = 0L,
    val writeFailures: Long = 0L,
    val lastEventLine: String = "",

    // ---------------------------------------------------------------------------------------------
    // WHICH QUESTION THIS RUN IS ANSWERING. See SpikeMode — the three measurements are mutually
    // hostile and each produces a plausible-looking file under the wrong mode.
    // ---------------------------------------------------------------------------------------------
    val mode: SpikeMode = SpikeMode.CAPTURE,
    val modeHeadline: String = "",

    // ---------------------------------------------------------------------------------------------
    // go/no-go P1 — BATTERY. Every field here is WHOLE-DEVICE, not app. See SpikeBattery.kt.
    // ---------------------------------------------------------------------------------------------
    val batterySamples: Long = 0L,
    val batteryLevelPct: Int = -1,
    val batteryLevelDeltaPct: Int = 0,
    val batteryChargeDeltaUah: Long = 0L,
    val batteryTemperatureDeciC: Int = -1,
    val batteryPlugged: Boolean = false,
    val batteryEverPlugged: Boolean = false,
    val batteryScreenInteractive: Boolean = false,
    val batteryDeviceIdle: Boolean = false,
    val batteryPowerSave: Boolean = false,
    /** Null until enough of the run has elapsed to fit a slope through. Deliberately not zero. */
    val percentPerHourFromLevel: Double? = null,
    val percentPerHourFromCharge: Double? = null,
    val batteryInvalidReason: String = "",

    /**
     * HOST-REQUESTED radio time, not receiver-on time. The value that makes a %/hr figure
     * attributable rather than a story. See SpikeDutyLedger.kt for the size of the caveat.
     */
    val scanOnMs: Long = 0L,
    val advertiseOnMs: Long = 0L,
    val scanOnPct: Int = 0,
    val scanOpenTransitions: Long = 0L,
    val nominalScanDutyPct: Int = 0,
    val scanModeName: String = "-",

    // ---------------------------------------------------------------------------------------------
    // go/no-go P2 — DISCOVERY LATENCY. Every value UNCORRECTED for inter-device clock offset.
    // ---------------------------------------------------------------------------------------------
    val latencySamples: Long = 0L,
    val latencyP50Ms: Long? = null,
    val latencyP95Ms: Long? = null,
    /** A NEGATIVE value is direct, model-free proof of clock skew of at least that size. */
    val latencyMinMs: Long? = null,
    val latencyMaxMs: Long? = null,
    val latencyAfterOnWindow: Long = 0L,
    /** Peer-cycles that produced nothing. TIMER-driven, so a total blackout still counts. */
    val latencyMissedPeerCycles: Long = 0L,
    /** Peers that missed enough consecutive cycles to be recorded as gone. See LatencyTracker. */
    val latencyPeersDeparted: Long = 0L,
    /** Peers currently expected to be heard every cycle. The denominator of a miss rate. */
    val latencyPeersExpected: Int = 0,
    val latencyCyclesClosed: Long = 0L,
    /** This device's own transmit-side lag from cycle start, on ONE clock. Subtractable honestly. */
    val emitStartLagMs: Long = -1L,
    val networkTimeAvailable: Boolean = false,
    /** Which of the three network-time states this handset is in. See SpikeClockReference. */
    val networkTimeSource: String = "",

    // ---------------------------------------------------------------------------------------------
    // SPEC §5.0 — ACQUISITION RATE and PEER DENSITY.
    // ---------------------------------------------------------------------------------------------
    val densityBuckets: Long = 0L,
    val distinctPeersTotal: Int = 0,
    /** "Heard from in the last PEER_LIVENESS_MS", not an observation of concurrency. */
    val concurrentPeers: Int = 0,
    val peakConcurrentPeers: Int = 0,
) {
    /**
     * The one-line verdict on whether the capture is usable at all. Deliberately worded so that a
     * degraded run is not silently treated as a clean one.
     *
     * ORDER MATTERS AND IS NOT ARBITRARY. Our own losses come first, because a run with holes we
     * made cannot support ANY conclusion of the form "X never happened" — which is the shape of
     * both B8 and `SPEC.md` §5.0. Mode-voiding comes next, because it invalidates a specific
     * counter rather than the whole file. Only then does the file get to report a finding.
     */
    val integrityNote: String
        get() = when {
            startFailure.isNotEmpty() -> "NOT RUNNING — $startFailure"
            diagnosticsDropped > 0L || writeFailures > 0L || radioEventsDropped > 0L ||
                timestampsClamped > 0L ->
                "DEGRADED — $diagnosticsDropped dropped, $radioEventsDropped radio events lost, " +
                    "$writeFailures write failures, $timestampsClamped clamped scan timestamps. " +
                    "The defects in this file are OURS or this handset's. Treat absence claims " +
                    "as void." +
                    if (radioEventsDropped > 0L) {
                        " A LOST RADIO EVENT ALSO CORRUPTS scan_on_ms: an unclosed scan interval " +
                            "inflates it, so the battery attribution is wrong too, not just gappy."
                    } else {
                        ""
                    } +
                    if (timestampsClamped > 0L) {
                        // Deliberately the loudest of the three. A dropped row is visibly absent;
                        // a clamped timestamp leaves a row that looks perfectly normal and is
                        // wrong, which is the failure mode this whole file is written against.
                        " THIS HANDSET FABRICATES SCAN TIMESTAMPS: $timestampsClamped " +
                            "observations had observedAt forced to CALLBACK time, so they are " +
                            "quantised to when we were scheduled, not when the packet arrived. " +
                            "Nothing is missing — the rows look normal and are wrong. P2 " +
                            "latency and every inter-arrival gap in this run are VOID, not " +
                            "noisy. Grep events.jsonl for TIMESTAMP_CLAMPED, and re-run P2 on " +
                            "a different handset."
                    } else {
                        ""
                    }
            mode == SpikeMode.LATENCY_PROBE && (bridgedAddresses > 0 || bridgedEids > 0) ->
                "BRIDGING COUNTERS VOID IN THIS MODE — the latency probe stops and restarts our " +
                    "own transmitter once per cycle, which rotates our address INSIDE a protocol " +
                    "epoch. $bridgedEids bridged eids here is self-inflicted, not a B8 finding. " +
                    "Re-run in CAPTURE mode to test §4.3.1."
            bridgedAddresses > 0 || bridgedEids > 0 ->
                "BRIDGING OBSERVED — $bridgedAddresses addr, $bridgedEids eid. " +
                    "Invariant 5 fails on this hardware. Confirm on a sniffer, then §4.3.4."
            mode == SpikeMode.BATTERY_BASELINE ->
                "BASELINE — radio deliberately off. Zero sightings is correct. This run is the " +
                    "SUBTRAHEND for a scanning run, not an answer on its own."
            sightings == 0L -> "NO SIGHTINGS YET"
            else -> "no bridging observed so far — NOT a pass, see §5.1"
        }

    /**
     * The battery verdict, in the same never-flatter-ourselves style.
     *
     * Returns a reason to distrust the number when there is one, and when there is not, still says
     * what the number is missing: whole-device drain is not app drain and there is no way to make it
     * one from a single run.
     */
    val batteryNote: String
        get() = batteryInvalidReason.ifEmpty {
            buildString {
                append("Whole-device drain, unplugged, ")
                append(if (batteryScreenInteractive) "SCREEN ON" else "screen off")
                append(". Subtract a paired BATTERY BASELINE run on this handset to get Radar's ")
                append("own cost. scan_on=")
                append(scanOnMs / 1000)
                append("s (")
                append(scanOnPct)
                append("% of run, HOST-REQUESTED — controller duty nominally ")
                append(nominalScanDutyPct)
                append("%).")
            }
        }

    /**
     * The latency verdict. Never prints a percentile without the correction warning attached, and
     * escalates when the device has itself observed proof of skew.
     */
    val latencyNote: String
        get() = when {
            mode != SpikeMode.LATENCY_PROBE ->
                "Not measured in this mode. Latency needs the peer's transmitter on a known " +
                    "schedule — switch to LATENCY PROBE on BOTH handsets."
            latencySamples == 0L ->
                "No samples yet. Each cycle yields at most one per peer, so expect roughly one " +
                    "per minute per peer once both phones are running."
            // Ahead of the skew branches on purpose: skew is CORRECTABLE off-device, a fabricated
            // reception time is not. There is nothing to subtract, because the quantity that would
            // have been measured was never recorded.
            timestampsClamped > 0L ->
                "VOID ON THIS HANDSET — $timestampsClamped scan results had no usable " +
                    "timestampNanos and were dated at callback time. Every percentile below is a " +
                    "measurement of our own scheduling latency. No off-device correction can " +
                    "recover it. Re-run P2 on a different handset and record this one in " +
                    "docs/oem.md."
            (latencyMinMs ?: 0L) < 0L ->
                "CLOCK SKEW PROVEN: a first sighting arrived ${-(latencyMinMs ?: 0L)}ms BEFORE " +
                    "the cycle it belongs to. A packet cannot arrive before it was sent, so these " +
                    "clocks differ by at least that much and every p50 below is wrong by at least " +
                    "that much. Correct with the paired-sum estimator over both devices' files."
            !networkTimeAvailable ->
                "UNCORRECTED, AND NO INDEPENDENT ERROR BAR ON THIS HANDSET. $networkTimeSource " +
                    "The paired-sum estimator over BOTH devices' latency.csv is the only skew " +
                    "control left, and it needs both phones advertising."
            else ->
                "UNCORRECTED for the clock offset between handsets. Subtract it off-device — " +
                    "paired-sum over both latency.csv files, or the two network offsets in " +
                    "meta.json. See SpikeLatency.kt."
        }
}
