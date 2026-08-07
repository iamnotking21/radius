package com.radius.android.spike

/**
 * What a run is FOR. Chosen before the run, recorded in the run header, and not changeable while a
 * run is live.
 *
 * ## Why one harness has modes instead of one mode having options
 *
 * The Phase 0 measurements are mutually hostile. The co-rotation capture needs a 100 %-duty receiver
 * to hit `KEY_SCHEDULE.md` §5.3 C5's ≥60 % packet yield; a battery figure taken with that receiver
 * is fiction. The latency probe needs the transmitter stopped and restarted once a minute; that
 * makes the same handset's own advertising address rotate inside a protocol epoch, which is
 * indistinguishable from the §4.3.1 bijection failure B8 exists to detect. The idle-drain figure
 * needs the radio OFF entirely, which produces no sightings at all.
 *
 * Every one of those combinations produces a plausible-looking file. A mode is a declaration of
 * which question the file answers, written into `meta.json` at the top, so that six weeks later
 * nobody computes a battery number from a max-capture run or a bijection verdict from a latency run.
 * The modes are hostile to each other; the honesty flag is not optional.
 */
internal enum class SpikeMode {

    /**
     * **B8 co-rotation screen, band distribution, raw sightings.** The original harness behaviour.
     *
     * Advertising, when enabled, runs continuously and rotates only at the protocol's own 15-minute
     * boundaries. This is the ONLY mode in which the bijection counters mean anything.
     *
     * Pair with `maxCapture = true` for a §5.3-compliant capture. Any battery figure from such a run
     * is VOID by construction and the header says so.
     */
    CAPTURE,

    /**
     * **Discovery latency, go/no-go P2.** Advertising is duty-cycled on a UTC-aligned schedule
     * (`SpikeTiming.LATENCY_CYCLE_MS` / `LATENCY_ON_MS`) so that two handsets with no shared clock
     * and no pairing step agree on when a peer started transmitting. See `SpikeLatency.kt` for the
     * full argument, the three independent skew controls, and the error bars.
     *
     * **VOID FOR B8.** The per-cycle advertising restarts change our own advertising address inside
     * a protocol epoch. That is the exact shape of a bijection failure and it would be self-inflicted.
     *
     * Run it on BOTH handsets with `advertise = true` — the paired-sum estimator is the only method
     * here that removes clock skew exactly, and it needs both devices' files.
     */
    LATENCY_PROBE,

    /**
     * **The subtrahend.** Battery sampling with the radio never started: no scan, no advertisement,
     * no diagnostics stream. The app is alive, the foreground service is up, the notification is
     * showing, and nothing is on the air.
     *
     * This is what `<1 %/day idle` is measured against, and it is also the control that makes the
     * scanning number mean something. Without it, a `4.2 %/hr` scanning figure cannot be separated
     * from the handset's own baseline — and the handset's own baseline on a cheap MediaTek device
     * with three vendor services running is not small.
     *
     * Run it FIRST, on the same handset, at the same screen state, in the same session, back to back
     * with the scanning run. A baseline taken last week on a different battery state is not a
     * control.
     */
    BATTERY_BASELINE,
    ;

    val label: String
        get() = when (this) {
            CAPTURE -> "CAPTURE (B8 + sightings)"
            LATENCY_PROBE -> "LATENCY PROBE (P2)"
            BATTERY_BASELINE -> "BATTERY BASELINE (radio off)"
        }

    /** True when the radio is started at all. */
    val usesRadio: Boolean get() = this != BATTERY_BASELINE

    /**
     * True when THIS MODE permits the bijection counters to be reported at all.
     *
     * A CAPABILITY OF THE MODE, NOT A VERDICT ABOUT A RUN'S DATA. `true` here means only that our
     * own advertising did not rotate the address inside an epoch and so cannot have manufactured the
     * failure B8 looks for. It says nothing about whether anything was heard: a CAPTURE run that saw
     * zero advertisers reports zero bridged addresses, and decision 69 is explicit that a zero is
     * not a pass. `meta.json` therefore writes this as `bijection_permitted_by_mode` and computes a
     * separate `bijection_screen_evidence` from the rows at the end of the run.
     */
    val bijectionValid: Boolean get() = this == CAPTURE
}
