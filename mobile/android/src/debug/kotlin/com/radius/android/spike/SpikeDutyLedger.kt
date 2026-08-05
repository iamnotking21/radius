package com.radius.android.spike

import android.os.SystemClock

/**
 * RADIO-ON-TIME LEDGER. The thing that makes a battery number attributable.
 *
 * ## Why this exists
 *
 * `PHASE0_GO_NO_GO.md` P1 is `<4 %/hr scanning`. "Scanning" is not a state of the world; it is a
 * state of our software, and a run in which the OEM killed our scan after twelve minutes will report
 * an excellent %/hr for a phone that was doing nothing. Without this ledger the two are the same
 * number.
 *
 * So every battery sample carries the cumulative milliseconds during which a scan was actually open
 * and the transmitter was actually live. `drain / hour` becomes `drain / hour-of-real-scanning`,
 * which is the figure the budget is about, and the ratio `scan_on_ms / elapsed_ms` is a direct
 * measurement of whether the platform honoured what we asked for.
 *
 * ## THE LIMIT, WHICH IS LARGE AND MUST BE STATED WITH EVERY NUMBER THIS PRODUCES
 *
 * **This measures HOST-REQUESTED radio time, not receiver-on time.** When we call `startScan` with
 * `SCAN_MODE_BALANCED`, AOSP's `ScanManager` duty-cycles the receiver inside the controller at a
 * nominal 1024 ms on / 4096 ms off. From the app there is no callback, no counter and no API for
 * that: the ledger sees one continuous "scan open" interval for the whole period. Actual receiver
 * time is therefore roughly `scan_on_ms × nominal_duty_pct`, and **`nominal_duty_pct` is a
 * documented AOSP value that several vendors override with their own.**
 *
 * That product is written into every battery row as `nominal_scan_duty_pct`, kept as a separate
 * column rather than multiplied in, because multiplying an OEM-modified constant into a measured
 * quantity produces a number that looks measured and is not. Recovering the real duty needs a
 * sniffer or a Battery Historian trace — see the report section on which figures stand alone.
 *
 * ## Monotonic clock, on purpose
 *
 * Durations use `SystemClock.elapsedRealtime()`. Wall clock jumps when the platform re-syncs NTP,
 * and a 900 ms jump in the middle of a 90-minute capture silently rewrites the denominator of every
 * %/hr figure in the run. `elapsedRealtime` also keeps counting through deep sleep, which is exactly
 * what an idle-drain measurement needs and what `uptimeMillis` would not give.
 */
internal class SpikeDutyLedger {

    private var scanOpenSinceMs: Long = NOT_OPEN
    private var advertiseLiveSinceMs: Long = NOT_OPEN

    private var scanAccumulatedMs: Long = 0L
    private var advertiseAccumulatedMs: Long = 0L

    private var runStartElapsedMs: Long = 0L

    /** Transitions counted, so a flapping scan is visible as flapping and not as uptime. */
    var scanOpenTransitions: Long = 0L
        private set
    var advertiseLiveTransitions: Long = 0L
        private set

    fun start(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        runStartElapsedMs = nowElapsedMs
        scanOpenSinceMs = NOT_OPEN
        advertiseLiveSinceMs = NOT_OPEN
        scanAccumulatedMs = 0L
        advertiseAccumulatedMs = 0L
        scanOpenTransitions = 0L
        advertiseLiveTransitions = 0L
    }

    fun onScanOpened(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        // Idempotent. A SCAN_STARTED while already open means the radio restarted a scan without a
        // stop in between (the `applyScanLocked` "restart" path); it did not gain a second receiver.
        if (scanOpenSinceMs != NOT_OPEN) return
        scanOpenSinceMs = nowElapsedMs
        scanOpenTransitions++
    }

    fun onScanClosed(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val since = scanOpenSinceMs
        if (since == NOT_OPEN) return
        scanAccumulatedMs += (nowElapsedMs - since).coerceAtLeast(0L)
        scanOpenSinceMs = NOT_OPEN
    }

    fun onAdvertiseLive(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        if (advertiseLiveSinceMs != NOT_OPEN) return
        advertiseLiveSinceMs = nowElapsedMs
        advertiseLiveTransitions++
    }

    fun onAdvertiseStopped(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val since = advertiseLiveSinceMs
        if (since == NOT_OPEN) return
        advertiseAccumulatedMs += (nowElapsedMs - since).coerceAtLeast(0L)
        advertiseLiveSinceMs = NOT_OPEN
    }

    val scanning: Boolean get() = scanOpenSinceMs != NOT_OPEN
    val advertising: Boolean get() = advertiseLiveSinceMs != NOT_OPEN

    fun scanOnMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val open = scanOpenSinceMs
        return scanAccumulatedMs + if (open == NOT_OPEN) 0L else (nowElapsedMs - open).coerceAtLeast(0L)
    }

    fun advertiseOnMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val open = advertiseLiveSinceMs
        return advertiseAccumulatedMs +
            if (open == NOT_OPEN) 0L else (nowElapsedMs - open).coerceAtLeast(0L)
    }

    fun elapsedMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long =
        (nowElapsedMs - runStartElapsedMs).coerceAtLeast(0L)

    /** Fraction of the run during which a scan was open, percent. Host-level. See the class note. */
    fun scanOnPct(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Int {
        val elapsed = elapsedMs(nowElapsedMs)
        if (elapsed <= 0L) return 0
        return (scanOnMs(nowElapsedMs) * 100L / elapsed).toInt()
    }

    private companion object {
        const val NOT_OPEN = -1L
    }
}
