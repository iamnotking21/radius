package com.radius.android.spike

import android.bluetooth.le.ScanSettings
import com.radius.shared.ble.DutyProfile

/**
 * ==================================================================================================
 * UNMEASURED GUESSES — HARNESS HALF. EVERY TIMING CONSTANT BELOW IS A NUMBER SOMEBODY MADE UP AT A
 * DESK.
 *
 * This is the SECOND HOME of the block that starts in
 * `mobile/shared/src/androidMain/.../BleRadio.android.kt` (`BOUNDARY_SETTLE_MS`,
 * `ADVERTISE_RESTART_GAP_MS`, `ADAPTER_SETTLE_MS`, `REGISTRATION_BACKOFF_MS`, `CONSERVE_ON_MS`,
 * `CONSERVE_OFF_MS`). It is separate ONLY because those constants ship and these do not — the
 * harness is `src/debug` and is deleted when Phase 0 closes. The rule is identical and there are two
 * places to look, not one, so the pointer is written into both files.
 *
 * None of these has been observed on a radio. They are not tuned, not derived from a datasheet, and
 * not recommended by anyone. They are starting points chosen to be plausible and to be VARIED BY THE
 * RIG — which is why they are named constants in one block rather than literals scattered through
 * five files.
 *
 * DO NOT read a value here as evidence that it is correct, and do not cite one in a report. When a
 * real run produces a real number, replace the value AND delete it from this block, so the set of
 * things still being guessed is always visible at a glance. A constant that has moved out of this
 * block has been measured; a constant still in it has not.
 *
 * EVERY ONE OF THESE IS COPIED INTO `meta.json` AT RUN START. A capture whose parameters are only
 * knowable by reading the source at the commit it was built from is a capture nobody can re-derive
 * in six weeks.
 * ==================================================================================================
 */
internal object SpikeTiming {

    // ---------------------------------------------------------------------------------------------
    // BATTERY (go/no-go P1)
    // ---------------------------------------------------------------------------------------------

    /**
     * UNMEASURED GUESS. Battery sampling period.
     *
     * 60 s is a compromise nobody has validated. Too fast and the sampler itself becomes a load
     * (each sample is a `BatteryManager` binder round trip plus a file write) and starts measuring
     * itself; too slow and a 30-minute run has too few points to fit a slope through. The
     * `BATTERY_PROPERTY_CHARGE_COUNTER` reading is what makes 60 s plausibly enough — µAh moves
     * continuously where the 1 % level reading can sit still for twenty minutes and then step.
     */
    const val BATTERY_SAMPLE_MS = 60_000L

    /**
     * UNMEASURED GUESS. Minimum run length before the on-screen %/hr projection is shown at all.
     *
     * A drain rate extrapolated from four minutes of a 4-hour discharge curve is not an estimate, it
     * is a rounding error multiplied by fifteen. The screen refuses to display a number before this,
     * because a number on a screen gets quoted and a "--" does not.
     */
    const val BATTERY_MIN_PROJECTION_MS = 10 * 60_000L

    // ---------------------------------------------------------------------------------------------
    // DISCOVERY LATENCY (go/no-go P2)
    // ---------------------------------------------------------------------------------------------

    /**
     * UNMEASURED GUESS. Period of the latency probe's advertise ON/OFF cycle, aligned to the Unix
     * epoch in UTC milliseconds so that two handsets agree on the schedule with no pairing step.
     *
     * 60 000 ms divides 900 000 ms (the protocol epoch) exactly 15 times, so a cycle boundary never
     * straddles a key-rotation boundary and cycle 0 of every protocol epoch coincides with one. That
     * coincidence is deliberate AND is a confound — see [LatencyCycle.epochCycleIndex].
     */
    const val LATENCY_CYCLE_MS = 60_000L

    /**
     * UNMEASURED GUESS. How long the emitter advertises within each cycle.
     *
     * Must be comfortably longer than any plausible discovery latency or the measurement truncates
     * itself: a latency longer than the ON window is recorded as "never seen this cycle", which
     * reads as a total acquisition failure rather than a slow one. 20 s against a 5 s target is 4x
     * headroom. If real p95s land near 20 s, this constant is wrong and the runs before that
     * discovery are censored data, not clean data.
     */
    const val LATENCY_ON_MS = 20_000L

    /**
     * UNMEASURED GUESS, AND IT IS THE CONSTANT THAT DECIDES WHETHER THE SKEW DETECTOR WORKS.
     *
     * A sighting timestamped within this much of the NEXT cycle boundary is attributed to that next
     * cycle, producing a NEGATIVE offset. That negative is the whole of `SpikeLatency.kt` METHOD 4:
     * a packet cannot arrive before it was sent, so a negative offset is model-free proof that this
     * device's clock is behind the emitter's by at least that much.
     *
     * Why a bounded window rather than "nearest boundary": with a plain nearest-boundary rule, any
     * arrival in the emitter's OFF period (20 s–60 s into the cycle) more than half-way round would
     * be reported as tens of seconds of negative skew, which is nonsense — those are far more likely
     * to be a batched scan result, or a peer that is not running the probe at all. 10 s says "we
     * will believe a skew of up to ten seconds; beyond that the method has broken down". When it
     * does break down the symptom is visible rather than silent: `latency_after_on_window` climbs.
     *
     * If real captures show skews near this bound, this constant is wrong AND so is the method.
     */
    const val LATENCY_SKEW_ATTRIBUTION_MS = 10_000L

    /**
     * UNMEASURED GUESS. Consecutive missed cycles after which a peer is recorded as DEPARTED and
     * stops accruing misses.
     *
     * It exists because the alternative is worse in a specific direction. Without it, "expected"
     * only ever grows: a peer that walks out of range at minute 20 of a 90-minute run keeps being
     * counted missing for the remaining 70 cycles, and one departure manufactures seventy failures.
     * With it, a genuine dead peer costs 3 misses and one DEPARTED row, and a peer that is merely
     * hard to hear re-joins the expected set the moment it is heard again.
     *
     * 3 is a guess and it is the wrong kind of number to guess: too low and an intermittent peer is
     * repeatedly declared dead and resurrected, inflating `peers_departed`; too high and a real
     * departure keeps inventing misses for longer. Both symptoms are visible in the file
     * (`PEER_DEPARTED` row count against `PEER_MISSED` run lengths), which is how the right value
     * gets found. Three cycles is three minutes at [LATENCY_CYCLE_MS] — long enough that a peer
     * surviving one bad scan window is not written off.
     */
    const val LATENCY_PEER_DEPARTURE_MISSES = 3

    /**
     * UNMEASURED GUESS. Cap on latency samples held in memory for the on-screen percentile.
     *
     * The FILE is never sampled or capped. This bounds only the live readout, because an unbounded
     * list in a 12-hour run is a memory leak in an instrument, which is the least acceptable place
     * to have one.
     */
    const val LATENCY_SCREEN_SAMPLE_CAP = 4_096

    // ---------------------------------------------------------------------------------------------
    // ACQUISITION RATE / PEER DENSITY (SPEC §5.0)
    // ---------------------------------------------------------------------------------------------

    /**
     * UNMEASURED GUESS. Width of one acquisition-rate bucket.
     *
     * `SPEC.md` §5.0 asks for "acquisition success rate over a 10-minute window". 60 s buckets are
     * recorded rather than one 10-minute number so the analysis can build any window it wants, and
     * so a run that was fine for eight minutes and dead for two is visibly that rather than
     * 80 %.
     */
    const val DENSITY_BUCKET_MS = 60_000L

    /**
     * UNMEASURED GUESS, AND THE MOST ARGUABLE NUMBER IN THIS FILE. How long after its last packet a
     * peer still counts as "concurrent".
     *
     * There is no such thing as an observed concurrent peer count — only "seen within the last N
     * seconds", and N is a choice that moves the answer. At 30 s a peer advertising every 250 ms
     * that we miss for 29 s is still counted; at 5 s it would not be. The raw per-bucket first/last
     * timestamps are in the file so any N can be re-derived off-device; this value governs the live
     * readout and the `concurrent_peers` column only.
     */
    const val PEER_LIVENESS_MS = 30_000L

    // ---------------------------------------------------------------------------------------------
    // NOMINAL RADIO PARAMETERS — NOT GUESSES, BUT NOT MEASUREMENTS EITHER.
    //
    // These are the values Android's `ScanManager`/`AdvertiseManager` document. Several OEMs ship
    // their own. They are recorded in every capture so an off-device analysis can compute an
    // EXPECTED packet count, and every such computation is nominal until a sniffer says otherwise.
    // ---------------------------------------------------------------------------------------------

    /** `SPEC.md` §6: 250 ms foreground / 1000 ms background. Android exposes coarse modes, not ms. */
    const val NOMINAL_ADV_INTERVAL_FOREGROUND_MS = 250L
    const val NOMINAL_ADV_INTERVAL_BACKGROUND_MS = 1_000L

    /** AOSP `ScanManager` window/interval pairs, as percentages. OEM-modified in practice. */
    const val NOMINAL_DUTY_LOW_POWER_PCT = 10
    const val NOMINAL_DUTY_BALANCED_PCT = 25
    const val NOMINAL_DUTY_LOW_LATENCY_PCT = 100

    /** Nominal advertising interval for a duty profile, milliseconds. */
    fun nominalAdvertiseIntervalMs(duty: DutyProfile): Long = when (duty) {
        DutyProfile.FOREGROUND -> NOMINAL_ADV_INTERVAL_FOREGROUND_MS
        DutyProfile.BACKGROUND, DutyProfile.CONSERVE -> NOMINAL_ADV_INTERVAL_BACKGROUND_MS
    }

    /** Nominal controller-level scan duty for the mode actually in effect, percent. */
    fun nominalScanDutyPct(scanMode: Int): Int = when (scanMode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> NOMINAL_DUTY_LOW_LATENCY_PCT
        ScanSettings.SCAN_MODE_BALANCED -> NOMINAL_DUTY_BALANCED_PCT
        ScanSettings.SCAN_MODE_LOW_POWER -> NOMINAL_DUTY_LOW_POWER_PCT
        else -> 0
    }

    /** The `ScanSettings` mode a config will actually produce. Mirrors `DutyProfile.toScanMode`. */
    fun effectiveScanMode(config: SpikeConfig): Int = config.scanModeOverride ?: when (config.duty) {
        DutyProfile.FOREGROUND -> ScanSettings.SCAN_MODE_BALANCED
        DutyProfile.BACKGROUND, DutyProfile.CONSERVE -> ScanSettings.SCAN_MODE_LOW_POWER
    }

    fun scanModeName(mode: Int): String = when (mode) {
        ScanSettings.SCAN_MODE_OPPORTUNISTIC -> "OPPORTUNISTIC"
        ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
        ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
        else -> "UNKNOWN($mode)"
    }

    /**
     * Every constant above, as it will be written into `meta.json`. One function so that adding a
     * constant without recording it requires deliberately not editing the map two lines below the
     * constant.
     */
    fun describeForMeta(): Map<String, String> = linkedMapOf(
        "timing_battery_sample_ms" to BATTERY_SAMPLE_MS.toString(),
        "timing_battery_min_projection_ms" to BATTERY_MIN_PROJECTION_MS.toString(),
        "timing_latency_cycle_ms" to LATENCY_CYCLE_MS.toString(),
        "timing_latency_on_ms" to LATENCY_ON_MS.toString(),
        "timing_latency_skew_attribution_ms" to LATENCY_SKEW_ATTRIBUTION_MS.toString(),
        "timing_latency_peer_departure_misses" to LATENCY_PEER_DEPARTURE_MISSES.toString(),
        "timing_density_bucket_ms" to DENSITY_BUCKET_MS.toString(),
        "timing_peer_liveness_ms" to PEER_LIVENESS_MS.toString(),
        "nominal_adv_interval_fg_ms" to NOMINAL_ADV_INTERVAL_FOREGROUND_MS.toString(),
        "nominal_adv_interval_bg_ms" to NOMINAL_ADV_INTERVAL_BACKGROUND_MS.toString(),
        "nominal_duty_low_power_pct" to NOMINAL_DUTY_LOW_POWER_PCT.toString(),
        "nominal_duty_balanced_pct" to NOMINAL_DUTY_BALANCED_PCT.toString(),
        "nominal_duty_low_latency_pct" to NOMINAL_DUTY_LOW_LATENCY_PCT.toString(),
        "timing_constants_status" to
            "ALL UNMEASURED GUESSES — see SpikeTiming.kt and BleRadio.android.kt",
    )
}
