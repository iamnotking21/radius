package com.radius.android.spike

import android.os.Build
import android.os.SystemClock
import java.time.DateTimeException

/**
 * DISCOVERY LATENCY — go/no-go **P2** (`p50 ≤ 5 s` foreground, `p95 ≤ 15 s`).
 *
 * ================================================================================================
 * THE PROBLEM, STATED HONESTLY BEFORE ANY CODE
 * ================================================================================================
 *
 * Discovery latency is *"time between a peer starting to advertise and this device first seeing
 * it"*. That interval has its two ends on **two different phones with two different clocks**. A
 * naive `t_seen(scanner) − t_started(emitter)` measures `true_latency + (offset_scanner −
 * offset_emitter)`, and the offset term is unbounded: two Android handsets can disagree by tens of
 * milliseconds if both have fresh NTP, or by minutes if one has network time disabled and the user
 * set the clock by hand. A 5 s threshold judged with an unbounded error term is not a measurement.
 *
 * Three things are done about it, and all three are recorded so the analysis can cross-check them
 * against each other. **If they disagree, that disagreement is the finding.**
 *
 * ------------------------------------------------------------------------------------------------
 * METHOD 1 — UTC-ALIGNED CYCLE. No operator action, no countdown, no tapping.
 * ------------------------------------------------------------------------------------------------
 *
 * The emitter advertises in a repeating ON/OFF cycle whose boundaries are computed from
 * `System.currentTimeMillis() mod` [SpikeTiming.LATENCY_CYCLE_MS] — an absolute schedule anchored to
 * the Unix epoch. The scanner computes the SAME boundaries from its OWN clock. For each cycle the
 * scanner records `t_first_seen − cycle_start`.
 *
 * No human is in the loop, so there is no reaction-time term and no "did we both tap at three?".
 * The residual error is exactly the clock offset between the two handsets, which is what methods 2
 * and 3 bound.
 *
 * This is deliberately preferred over a coordinated tap at a countdown. A tap adds two human
 * reaction times (~200–400 ms each, and correlated with how tired the operator is at 9pm in a car
 * park) on top of the clock offset it was supposed to remove, and it makes every sample a manual
 * event — which caps a session at a few dozen samples when p95 wants hundreds.
 *
 * ------------------------------------------------------------------------------------------------
 * METHOD 2 — THE PAIRED-SUM ESTIMATOR. The one that actually removes the skew.
 * ------------------------------------------------------------------------------------------------
 *
 * Run BOTH handsets as emitter AND scanner simultaneously (`advertise = true` on both, same
 * `LATENCY_PROBE` mode, different slots). Write `o_A`, `o_B` for the two clock offsets from true
 * time, `L_AB` for the true latency of B's frames reaching A. Then:
 *
 * ```
 *   measured_AB = L_AB + (o_A − o_B)
 *   measured_BA = L_BA + (o_B − o_A)
 *   ---------------------------------------------------
 *   measured_AB + measured_BA = L_AB + L_BA        ← the offset term cancels EXACTLY
 * ```
 *
 * So `(measured_AB + measured_BA) / 2` is an unbiased estimate of the mean true one-way latency,
 * **with no assumption about either clock at all**. This is the same trick NTP uses on a two-way
 * timestamp exchange, and it is the reason the harness cycles symmetrically instead of designating
 * one phone the emitter.
 *
 * The complement is just as useful: `(measured_AB − measured_BA) / 2 = (o_A − o_B) + (L_AB −
 * L_BA)/2`, which estimates the clock offset itself — under the weaker, STATED assumption that the
 * two directions have similar latency distributions. Compare it against method 3. Two independent
 * estimates of the same offset that agree is evidence; one estimate is an assertion.
 *
 * **This estimator needs BOTH devices' files.** A single phone's `latency.csv` cannot compute it,
 * and the on-screen p50 is therefore method 1 only — uncorrected, biased by the offset, and labelled
 * as such on the screen.
 *
 * ------------------------------------------------------------------------------------------------
 * METHOD 3 — NETWORK-TIME OFFSET, RECORDED PER DEVICE. The error bar.
 * ------------------------------------------------------------------------------------------------
 *
 * `SystemClock.currentNetworkTimeClock()` returns time derived from the NTP sample the platform
 * holds, independent of whatever the user set the clock to. Recording
 * `currentTimeMillis() − currentNetworkTime()` at run START and run END on both handsets gives:
 *
 *  - the offset of each handset's wall clock from a COMMON reference, so the difference of the two
 *    offsets is the skew — subtractable off-device, from two `meta.json` files;
 *  - the DRIFT of each handset over the run (start vs end), which is the honest error bar on that
 *    subtraction. A phone oscillator is good to roughly 10–20 ppm, i.e. tens of milliseconds per
 *    hour; if the recorded drift is much larger than that, the platform re-synced mid-run and the
 *    correction is not a constant.
 *
 * It can also be UNAVAILABLE, in two different ways, and the two are recorded separately because
 * only one of them is fixable in the car park:
 *
 *  - **`UNAVAILABLE_NO_NTP_SAMPLE`** — the call throws `DateTimeException` because the platform
 *    holds no network time, which is precisely the case on a handset that has been in aeroplane
 *    mode all evening. Fixable: let it see a network, turn automatic date & time on, re-run.
 *  - **`UNAVAILABLE_API_LT_33`** — and THIS IS THE ONE THAT MATTERS FOR THE DEVICE MATRIX. The
 *    public `SystemClock.currentNetworkTimeClock()` API landed in **API 33 (Android 13)**, not at
 *    our API 29 floor. There is no public equivalent below it. So **on every handset running
 *    Android 10, 11 or 12 this method does not exist at all** — which includes a large share of
 *    exactly the budget MediaTek tier that `PHASE0_SPIKE_MATRIX.md` §2 puts in the FIRST batch. On
 *    those devices method 2 (the paired-sum estimator) is not one of two skew controls, it is the
 *    ONLY one, and running the latency probe on a single advertising handset there produces a
 *    number with no bound on its error whatsoever.
 *
 * Neither case is silently defaulted to zero. **A run where either device reports
 * `network_time_available=false` has no method-3 error bar and must fall back to method 2 or be
 * treated as uncalibrated.**
 *
 * (This was found by `lintDebug`, not by inspection: the first draft of this file asserted API 29
 * in a comment and called a 33+ API. The comment would have been wrong in the repository and the
 * harness would have crashed on the first budget handset it ran on.)
 *
 * ------------------------------------------------------------------------------------------------
 * METHOD 4 — THE SIGN CHECK THAT COSTS NOTHING. A negative latency IS skew, directly observed.
 * ------------------------------------------------------------------------------------------------
 *
 * A packet cannot arrive before it was sent. So any `t_first_seen < cycle_start` is proof that the
 * scanner's clock is behind the emitter's by at least `|latency|`, with no modelling whatsoever.
 * [LatencyTracker.minLatencyMs] is therefore a free lower bound on the absolute skew, computed
 * on-device and shown on the screen. If it reads −1400 ms, every method-1 number in that run is
 * overstated... or understated, depending on direction — but it is certainly wrong by at least that
 * much, and nobody should be quoting a 5 s threshold against it without correcting first.
 *
 * **GETTING THIS RIGHT NEEDED A SIGNED OFFSET, AND THE OBVIOUS IMPLEMENTATION HAS NONE.** The
 * natural expression — `observedAt mod CYCLE_MS` — is `floorMod`, whose range is `[0, CYCLE_MS)`. It
 * *cannot* return a negative number. Under it, a packet arriving 300 ms before our notion of the
 * boundary does not read as `−300 ms`; it reads as `59 700 ms`, a plausible-looking enormous latency
 * in the wrong cycle. The detector would have been dead on arrival and the run would have looked
 * merely disappointing rather than mis-clocked. [LatencyCycle.attributedCycleIndex] fixes it by
 * attributing an arrival within [SpikeTiming.LATENCY_SKEW_ATTRIBUTION_MS] of the next boundary to
 * that next cycle, which makes the offset genuinely signed.
 *
 * ================================================================================================
 * WHAT THIS STILL DOES NOT MEASURE, AND MUST NOT BE READ AS MEASURING
 * ================================================================================================
 *
 *  - **The scan duty cycle dominates the answer at the target thresholds.** `SCAN_MODE_BALANCED` is
 *    1024 ms on / 4096 ms off in AOSP: the receiver is off three quarters of the time, so a
 *    uniformly-arriving first packet waits ~1.5 s on average and up to ~3 s purely for a scan window
 *    to open. Against a 5 s P2 threshold that is most of the budget, and it is a DUTY choice, not a
 *    radio capability. The scan mode in effect is on every row for this reason.
 *  - **The emitter cannot start instantly either.** `startAdvertising` is asynchronous and
 *    `onStartSuccess` arrives later; that lag is real latency the user experiences and it is
 *    measurable on ONE clock. It is recorded as an `EMIT_STARTED` row (`lag_ms` from cycle start),
 *    so the analysis can subtract it and separate "our software was slow to transmit" from "the air
 *    was slow".
 *  - **Advertising interval quantises the floor.** At the nominal 250 ms foreground interval no
 *    scanner can resolve better than one interval, whatever the clocks say.
 *
 * ================================================================================================
 * AND THE INTERACTION THAT WOULD OTHERWISE POISON THE B8 SCREEN — READ THIS
 * ================================================================================================
 *
 * The probe stops and restarts advertising once per cycle. On any controller that honours a
 * stop→start as an RPA rotation trigger (which is the entire hypothesis behind
 * `ADVERTISE_RESTART_GAP_MS`), the emitter's advertising address changes **within** a 15-minute
 * protocol epoch while its `ephemeral_id` stays the same. A scanner then legitimately observes one
 * eid against several addresses — which is exactly the shape of a `KEY_SCHEDULE.md` §4.3.1 bijection
 * failure, and it would be **self-inflicted by the instrument**.
 *
 * So: **a `LATENCY_PROBE` run is VOID for B8.** It is marked void in `meta.json`, in the on-screen
 * integrity note, and in this comment. The bijection counters keep running (they cost nothing and
 * the raw rows are in the file either way) but they must not be reported. Run B8 in
 * [SpikeMode.CAPTURE], where the only advertising restarts are the protocol's own epoch boundaries.
 */
internal object LatencyCycle {

    /**
     * Absolute cycle index since the Unix epoch. Identical on both handsets up to clock offset.
     *
     * `floorDiv`, not `/`. Kotlin's `/` truncates toward zero, which puts every pre-1970 or
     * skewed-backwards timestamp in the wrong cycle — a one-character bug that corrupts exactly the
     * samples the skew detector exists to catch, and is invisible in the output.
     */
    fun cycleIndex(nowMs: Long): Long = Math.floorDiv(nowMs, SpikeTiming.LATENCY_CYCLE_MS)

    /** Wall-clock millisecond at which the cycle containing [nowMs] began. Emitter side. */
    fun cycleStartMs(nowMs: Long): Long = cycleIndex(nowMs) * SpikeTiming.LATENCY_CYCLE_MS

    /**
     * SCANNER SIDE. The cycle a sighting is attributed to, which is NOT always the cycle it fell in.
     *
     * An arrival within [SpikeTiming.LATENCY_SKEW_ATTRIBUTION_MS] of the next boundary is attributed
     * to that NEXT cycle, so that [signedOffsetMs] can be negative. See METHOD 4 in the file header:
     * without this the offset is a `floorMod` and can never be negative, and the only free error bar
     * in the whole measurement silently does not exist.
     */
    fun attributedCycleIndex(nowMs: Long): Long {
        val idx = cycleIndex(nowMs)
        val into = Math.floorMod(nowMs, SpikeTiming.LATENCY_CYCLE_MS)
        return if (into >= SpikeTiming.LATENCY_CYCLE_MS - SpikeTiming.LATENCY_SKEW_ATTRIBUTION_MS) {
            idx + 1
        } else {
            idx
        }
    }

    fun attributedCycleStartMs(nowMs: Long): Long =
        attributedCycleIndex(nowMs) * SpikeTiming.LATENCY_CYCLE_MS

    /**
     * Offset from the attributed cycle boundary, in
     * `[−LATENCY_SKEW_ATTRIBUTION_MS, CYCLE_MS − LATENCY_SKEW_ATTRIBUTION_MS)`.
     *
     * NEGATIVE = proof of clock skew. Positive and larger than [SpikeTiming.LATENCY_ON_MS] = arrived
     * after the emitter should have stopped, which is either a very slow discovery, a batched scan
     * result, or a peer that is not running the probe. All three are recorded, none is discarded.
     */
    fun signedOffsetMs(nowMs: Long): Long = nowMs - attributedCycleStartMs(nowMs)

    /** True while the emitter should be transmitting. */
    fun inOnWindow(nowMs: Long): Boolean =
        Math.floorMod(nowMs, SpikeTiming.LATENCY_CYCLE_MS) < SpikeTiming.LATENCY_ON_MS

    /** Milliseconds until the next ON→OFF or OFF→ON transition. Always > 0. */
    fun millisUntilNextTransition(nowMs: Long): Long {
        val into = Math.floorMod(nowMs, SpikeTiming.LATENCY_CYCLE_MS)
        return if (into < SpikeTiming.LATENCY_ON_MS) {
            SpikeTiming.LATENCY_ON_MS - into
        } else {
            SpikeTiming.LATENCY_CYCLE_MS - into
        }
    }

    /**
     * Which cycle within the current 15-minute protocol epoch this is, 0-based.
     *
     * **Cycle 0 of every protocol epoch coincides with a key-rotation boundary**, where the radio
     * performs its own stop → `ADVERTISE_RESTART_GAP_MS` → start on top of ours. That cycle
     * therefore measures a doubled restart and is a different experiment from cycles 1-14. It is
     * recorded rather than skipped, so the analysis can drop it deliberately — and so that the
     * difference between cycle 0 and the rest is itself visible, because "how long does a peer take
     * to reappear after an epoch rotation" is a real product question the radar UI depends on.
     */
    fun epochCycleIndex(nowMs: Long): Long {
        val cyclesPerEpoch = EPOCH_MS / SpikeTiming.LATENCY_CYCLE_MS
        return Math.floorMod(cycleIndex(nowMs), cyclesPerEpoch)
    }

    /** 15 minutes, `KEY_SCHEDULE.md` §3. Local copy: this file must not depend on the key schedule. */
    private const val EPOCH_MS = 900_000L
}

/**
 * The two clock readings that make a cross-device timestamp comparison auditable.
 *
 * Taken at run start and again at run end. See METHOD 3 above.
 */
internal data class SpikeClockReference(
    val wallUtcMs: Long,
    val elapsedRealtimeMs: Long,
    val networkTimeAvailable: Boolean,
    /** `currentTimeMillis() − networkTime()`. Meaningless when [networkTimeAvailable] is false. */
    val networkOffsetMs: Long,
    /** Which of the three states this is. Written verbatim into `meta.json`. */
    val source: String,
) {
    companion object {

        const val SOURCE_API_TOO_OLD =
            "UNAVAILABLE_API_LT_33 — SystemClock.currentNetworkTimeClock() landed in API 33 and " +
                "there is no public equivalent below it. This handset cannot provide a method-3 " +
                "error bar AT ALL. Use the paired-sum estimator over both devices' latency.csv."

        const val SOURCE_NO_SAMPLE =
            "UNAVAILABLE_NO_NTP_SAMPLE — the platform holds no network time (aeroplane mode, or " +
                "no data connection since boot). Turn automatic date & time on, let the phone see " +
                "a network, and re-run."

        const val SOURCE_OK = "SystemClock.currentNetworkTimeClock (API 33+)"

        fun capture(): SpikeClockReference {
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()

            // API 33, NOT 29. Caught by lintDebug against an earlier draft that claimed our API
            // floor and called a Tiramisu API — which would have thrown NoSuchMethodError on the
            // first Android 10/11/12 handset, i.e. on most of the budget tier the device matrix
            // deliberately tests FIRST.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return SpikeClockReference(wall, elapsed, false, 0L, SOURCE_API_TOO_OLD)
            }
            return try {
                val network = SystemClock.currentNetworkTimeClock().millis()
                SpikeClockReference(wall, elapsed, true, wall - network, SOURCE_OK)
            } catch (unavailable: DateTimeException) {
                // The throw is INFORMATION and is recorded as a flag, never swallowed into a zero:
                // a zero offset and an unknown offset are not the same thing, and treating them the
                // same is how an uncalibrated run gets reported as a calibrated one.
                SpikeClockReference(wall, elapsed, false, 0L, SOURCE_NO_SAMPLE)
            } catch (unsupported: Exception) {
                // Some OEM builds throw something else entirely rather than DateTimeException.
                SpikeClockReference(
                    wall,
                    elapsed,
                    false,
                    0L,
                    "${SOURCE_NO_SAMPLE} (threw ${unsupported.javaClass.simpleName})",
                )
            }
        }
    }

    fun describe(prefix: String): Map<String, String> = linkedMapOf(
        "${prefix}_wall_utc_ms" to wallUtcMs.toString(),
        "${prefix}_elapsed_realtime_ms" to elapsedRealtimeMs.toString(),
        "${prefix}_network_time_available" to networkTimeAvailable.toString(),
        "${prefix}_network_time_source" to source,
        "${prefix}_network_offset_ms" to if (networkTimeAvailable) {
            networkOffsetMs.toString()
        } else {
            "UNAVAILABLE — no method-3 error bar for this device, see SpikeLatency.kt"
        },
    )
}

/**
 * Per-cycle first-sighting accumulator. One instance per run.
 *
 * Holds NO signal-strength value and no band. Latency is a time question; mixing a distance proxy
 * into it would put an `rssi`-shaped field in a third file for no analytical gain.
 *
 * NOT THREAD-SAFE. `SpikeController` calls every method under its own lock.
 *
 * ================================================================================================
 * THE MISS COUNTER, AND WHY IT IS DRIVEN BY A CLOCK RATHER THAN BY PACKETS
 * ================================================================================================
 *
 * [missedCycles] is the counter that lets the file header say a p50 is not hiding a 40 % failure
 * rate. For it to mean that, it has to be able to count the case where NOTHING ARRIVED — and the
 * first implementation could not, for two independent reasons, both of which failed in the
 * flattering direction:
 *
 *  1. **It advanced the cycle on a sighting.** So a cycle in which no peer said anything never
 *     ended, and no miss was ever attributed to it. If the peer handset ran out of battery at
 *     minute 10 of 90, the counter froze at minute 10 and the p50 over those ten good minutes read
 *     clean for the whole run. The one thing the counter existed to catch was the one thing it
 *     structurally could not see. [advanceTo] is now called from a timer in `SpikeController`, so
 *     the cycle ends because time passed, which is the only thing that actually ends a cycle.
 *
 *  2. **"Expected" was a monotone high-water mark.** `lastCycleExpectedPeers = maxOf(previous,
 *     seenThisCycle.size)` never came down, so a peer that legitimately walked away was counted
 *     missing in every remaining cycle of the run, forever. Ten minutes of a real peer leaving
 *     would manufacture eighty misses out of one departure. The expected set is now EXPLICIT — a
 *     map of peer key to consecutive misses — and a peer that misses
 *     [SpikeTiming.LATENCY_PEER_DEPARTURE_MISSES] cycles in a row is recorded as DEPARTED once and
 *     removed. Its misses up to that point are real misses and stay counted; what stops is the
 *     invention of new ones.
 *
 * A departure and a run of misses are different findings and both are in the file. Neither is
 * inferred from the other, and neither is inferred from a percentile.
 *
 * **Cycles during which the process was not running at all are NOT misses.** If Doze or an OEM
 * killer suspends us for twenty minutes, we did not fail to hear the peer — we were not listening.
 * Those cycles land in [unaccountedCycles], which is a Phase 0 finding of its own and is a
 * different one from "the radio missed a peer that was transmitting".
 */
internal class LatencyTracker(
    /**
     * How many cycles' seen-sets stay open. Two, so that a sighting delivered slightly out of order
     * — a batched scan result, or the [LatencyCycle.attributedCycleIndex] skew window pulling an
     * arrival back into the previous cycle — still dedups against the right cycle rather than
     * polluting the current one.
     *
     * **CONSEQUENCE: MISS ACCOUNTING LAGS BY ONE CYCLE.** A cycle is not accounted when it ends; it
     * is accounted when it falls out of this window, one cycle later. That is the price of not
     * counting a miss against a peer whose packet was merely late — and the trade is deliberate,
     * because a wrongly-counted miss is a false failure report and a late-counted miss is only
     * late. [closeElapsedCycles] settles the outstanding ones at the end of a run so the lag never
     * costs the last cycles of a capture.
     */
    private val retainedCycles: Int = 2,
    private val departureMisses: Int = SpikeTiming.LATENCY_PEER_DEPARTURE_MISSES,
) {

    /** cycle index -> peers seen in it. Only the [retainedCycles] most recent are open. */
    private val seenByCycle = LinkedHashMap<Long, LinkedHashSet<String>>()
    private var highestCycle: Long = UNSET

    /** peer key -> consecutive cycles missed. Presence means "this peer is expected to be heard". */
    private val expected = LinkedHashMap<String, Int>()

    private val pendingMisses = ArrayDeque<LatencyMiss>()

    /** Bounded, screen only. The file holds every sample. See [SpikeTiming.LATENCY_SCREEN_SAMPLE_CAP]. */
    private val samplesMs = ArrayDeque<Long>()

    var totalSamples: Long = 0L
        private set

    /**
     * The smallest latency ever observed, including negatives. A NEGATIVE value is direct,
     * model-free proof of clock skew of at least that magnitude (METHOD 4).
     */
    var minLatencyMs: Long = Long.MAX_VALUE
        private set

    var maxLatencyMs: Long = Long.MIN_VALUE
        private set

    /** Samples whose first sighting landed after the emitter's ON window should have closed. */
    var afterOnWindow: Long = 0L
        private set

    /**
     * Peer-cycles in which a peer that was expected produced nothing at all. Counted, never
     * inferred — see the class KDoc for what it took to make that sentence true.
     */
    var missedCycles: Long = 0L
        private set

    /** Peers that missed [departureMisses] cycles in a row and stopped being expected. */
    var peersDeparted: Long = 0L
        private set

    /** Cycles that have fully elapsed and been accounted for. The denominator of a miss RATE. */
    var cyclesClosed: Long = 0L
        private set

    /** Sightings attributed to a cycle that had already closed. Recorded, not silently dropped. */
    var lateArrivals: Long = 0L
        private set

    /**
     * Cycles skipped because the harness was not running (Doze, OEM kill, process suspension).
     *
     * NOT counted as misses. We were not listening, so "the peer was not heard" says nothing about
     * the peer. It is its own finding — and on the OEM-kill runs it is THE finding.
     */
    var unaccountedCycles: Long = 0L
        private set

    /** Miss rows the queue could not hold because nobody drained it. A loss, so it is counted. */
    var missRowsDropped: Long = 0L
        private set

    /** Peers currently expected to be heard each cycle. */
    val expectedPeers: Int get() = expected.size

    /**
     * @return the latency in milliseconds if this is the FIRST sighting of [peerKey] in the cycle
     *   the sighting is attributed to, or `null` if the peer has already been counted in that cycle
     *   (every subsequent packet in a cycle is density data, not latency data) or if that cycle has
     *   already been closed and accounted for.
     */
    fun onSighting(peerKey: String, observedAtEpochMs: Long): LatencySample? {
        val cycle = LatencyCycle.attributedCycleIndex(observedAtEpochMs)
        advanceToCycle(cycle)

        val seen = seenByCycle[cycle]
        if (seen == null) {
            // The cycle this belongs to has already been closed and its misses counted. Re-opening
            // it would let a late packet retroactively un-miss a peer, which is the one direction
            // the counter must not be able to move.
            lateArrivals++
            return null
        }
        if (!seen.add(peerKey)) return null

        val cycleStart = LatencyCycle.attributedCycleStartMs(observedAtEpochMs)
        val latency = observedAtEpochMs - cycleStart
        totalSamples++
        if (latency < minLatencyMs) minLatencyMs = latency
        if (latency > maxLatencyMs) maxLatencyMs = latency
        if (latency > SpikeTiming.LATENCY_ON_MS) afterOnWindow++

        samplesMs.addLast(latency)
        while (samplesMs.size > SpikeTiming.LATENCY_SCREEN_SAMPLE_CAP) samplesMs.removeFirst()

        return LatencySample(
            cycleIndex = cycle,
            epochCycleIndex = LatencyCycle.epochCycleIndex(observedAtEpochMs),
            cycleStartUtcMs = cycleStart,
            peerKey = peerKey,
            firstSeenUtcMs = observedAtEpochMs,
            latencyMs = latency,
        )
    }

    /**
     * TIMER ENTRY POINT. Advance the cycle to whichever one contains [nowMs], closing and
     * accounting for every cycle that has fully elapsed.
     *
     * This is what makes a total blackout countable. Call it whether or not anything was heard —
     * ESPECIALLY when nothing was heard.
     */
    fun advanceTo(nowMs: Long) {
        advanceToCycle(LatencyCycle.attributedCycleIndex(nowMs))
    }

    /**
     * END OF RUN. Account for every cycle that has FULLY ELAPSED by [nowMs] and is still open.
     *
     * Called from `SpikeController.stop`, and it is the counterpart of the trailing density-bucket
     * flush: without it the [retainedCycles] accounting lag would silently discard the misses in
     * the last minute or two of every run — and on an OEM-kill capture the last two minutes are the
     * entire finding.
     *
     * THE IN-PROGRESS CYCLE IS LEFT ALONE. A cycle that has not finished cannot have been missed;
     * counting it would manufacture one miss per expected peer at every single stop, which is the
     * instrument reporting a failure it caused by being switched off.
     */
    fun closeElapsedCycles(nowMs: Long) {
        val currentCycle = LatencyCycle.attributedCycleIndex(nowMs)
        seenByCycle.keys.toList()
            .filter { it < currentCycle }
            .forEach(::closeCycle)
    }

    /** Take the miss rows accumulated since the last call. The caller writes them to the file. */
    fun drainMisses(): List<LatencyMiss> {
        if (pendingMisses.isEmpty()) return emptyList()
        val out = pendingMisses.toList()
        pendingMisses.clear()
        return out
    }

    private fun advanceToCycle(cycle: Long) {
        if (highestCycle == UNSET) {
            highestCycle = cycle
            seenByCycle[cycle] = LinkedHashSet()
            return
        }
        if (cycle <= highestCycle) return

        val gap = cycle - highestCycle
        if (gap > MAX_CATCHUP_CYCLES) {
            // We were not running. Close what is open — those cycles we did observe — and record
            // the rest as unaccounted rather than manufacturing a miss per peer per cycle for a
            // period during which our receiver was off. See the class KDoc.
            seenByCycle.keys.toList().forEach(::closeCycle)
            unaccountedCycles += gap - 1
            expected.keys.toList().forEach { expected[it] = 0 }
            highestCycle = cycle
            seenByCycle[cycle] = LinkedHashSet()
            return
        }

        var c = highestCycle + 1
        while (c <= cycle) {
            seenByCycle[c] = LinkedHashSet()
            highestCycle = c
            while (seenByCycle.size > retainedCycles) {
                closeCycle(seenByCycle.keys.first())
            }
            c++
        }
    }

    /**
     * A cycle has fully elapsed. Every expected peer that was not heard in it is a miss.
     *
     * A peer heard for the first time in this cycle JOINS the expected set as this cycle closes,
     * not before: it cannot have been missed in the cycles before we knew it existed.
     */
    private fun closeCycle(cycle: Long) {
        val seen = seenByCycle.remove(cycle) ?: return
        cyclesClosed++
        val cycleStart = cycle * SpikeTiming.LATENCY_CYCLE_MS

        for (peer in expected.keys.toList()) {
            if (peer in seen) {
                expected[peer] = 0
                continue
            }
            missedCycles++
            val consecutive = (expected[peer] ?: 0) + 1
            val departed = consecutive >= departureMisses
            if (departed) {
                expected.remove(peer)
                peersDeparted++
            } else {
                expected[peer] = consecutive
            }
            enqueueMiss(
                LatencyMiss(
                    cycleIndex = cycle,
                    cycleStartUtcMs = cycleStart,
                    peerKey = peer,
                    consecutiveMisses = consecutive,
                    departed = departed,
                ),
            )
        }

        for (peer in seen) expected.putIfAbsent(peer, 0)
    }

    private fun enqueueMiss(miss: LatencyMiss) {
        if (pendingMisses.size >= MAX_PENDING_MISSES) {
            // Counted, never silent. The queue only grows if the drain stopped, which is itself
            // worth knowing; dropping without counting would under-report misses, and this whole
            // class exists because under-reporting misses flatters the result.
            missRowsDropped++
            return
        }
        pendingMisses.addLast(miss)
    }

    fun reset() {
        seenByCycle.clear()
        expected.clear()
        pendingMisses.clear()
        highestCycle = UNSET
        samplesMs.clear()
        totalSamples = 0L
        minLatencyMs = Long.MAX_VALUE
        maxLatencyMs = Long.MIN_VALUE
        afterOnWindow = 0L
        missedCycles = 0L
        peersDeparted = 0L
        cyclesClosed = 0L
        lateArrivals = 0L
        unaccountedCycles = 0L
        missRowsDropped = 0L
    }

    /**
     * Nearest-rank percentile over the retained window. SCREEN ONLY.
     *
     * Nearest-rank rather than interpolated on purpose: with a few dozen samples an interpolated
     * percentile invents a value between two real observations and reads as more precise than the
     * data is. The file has every sample; compute whatever estimator the analysis prefers there.
     */
    fun percentileMs(p: Int): Long? {
        if (samplesMs.isEmpty()) return null
        val sorted = samplesMs.sorted()
        val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE

        /**
         * Beyond this many cycles in one jump, the gap is treated as "we were not running" rather
         * than as misses. 5 cycles is 5 minutes at [SpikeTiming.LATENCY_CYCLE_MS] — far longer than
         * any scheduling delay on a foreground service, and far shorter than a Doze window or an
         * OEM kill. A jump this large means the timer did not fire, and the timer not firing is not
         * evidence about the peer.
         */
        const val MAX_CATCHUP_CYCLES = 5L

        /** Queue bound. Only reachable if the drain stopped; overflow is counted, never silent. */
        const val MAX_PENDING_MISSES = 1_024
    }
}

/**
 * One peer-cycle in which an expected peer produced nothing.
 *
 * WRITTEN TO `latency.csv` AS ITS OWN ROW, with an EMPTY latency column. A miss is not a slow
 * discovery and must not share a numeric column with one — an absent measurement rendered as a zero
 * is an excellent measurement to anything that later takes a mean.
 */
internal data class LatencyMiss(
    val cycleIndex: Long,
    val cycleStartUtcMs: Long,
    val peerKey: String,
    /** How many cycles in a row this peer has now missed, this one included. */
    val consecutiveMisses: Int,
    /** True on the miss that crossed [SpikeTiming.LATENCY_PEER_DEPARTURE_MISSES]. Fires once. */
    val departed: Boolean,
)

/** One recorded first-sighting. Written straight to `latency.csv`; nothing is buffered per cycle. */
internal data class LatencySample(
    val cycleIndex: Long,
    val epochCycleIndex: Long,
    val cycleStartUtcMs: Long,
    val peerKey: String,
    val firstSeenUtcMs: Long,
    val latencyMs: Long,
)
