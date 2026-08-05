package com.radius.android.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the PURE parts of the three new Phase 0 measurements.
 *
 * ## What this can and cannot establish — say it before the first assertion
 *
 * These are arithmetic tests. They prove the cycle schedule is the same schedule on both handsets,
 * that the ledger integrates intervals correctly, that empty buckets are emitted, and that a
 * negative latency survives to the caller instead of being clamped away. **None of that is a radio
 * result.** `mobile/CLAUDE.md`: a simulator or JVM BLE result is never a pass, and nothing here is
 * evidence that discovery latency, battery drain or peer density are anything in particular.
 *
 * They exist for one reason: this is measurement code, and measurement code that is silently wrong
 * produces confident wrong answers that nobody can distinguish from findings. The cycle arithmetic
 * in particular is the whole basis of the P2 method — if `cycleStartMs` disagreed by one millisecond
 * between two builds, every latency figure would be wrong and nothing about the run would look
 * unusual.
 *
 * Runs under `:android:testDebugUnitTest` — the debug source set is on the classpath of the debug
 * unit-test variant, which is why the harness is reachable from here at all.
 */
class SpikeMeasurementTest {

    // ---------------------------------------------------------------------------------------------
    // LatencyCycle — the shared schedule two phones agree on with no pairing step.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `cycle start is absolute, not relative to when the run began`() {
        // THE PROPERTY THE WHOLE P2 METHOD RESTS ON. Two handsets started 40 minutes apart must
        // compute the same cycle boundary for the same instant, or "time since the peer started
        // advertising" means two different things on the two devices.
        val instant = 1_754_000_123_456L
        assertEquals(
            LatencyCycle.cycleStartMs(instant),
            LatencyCycle.cycleStartMs(instant + 1),
        )
        assertEquals(0L, LatencyCycle.cycleStartMs(instant) % SpikeTiming.LATENCY_CYCLE_MS)
    }

    @Test
    fun `ON window covers exactly LATENCY_ON_MS from the cycle boundary`() {
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        assertTrue(LatencyCycle.inOnWindow(start))
        assertTrue(LatencyCycle.inOnWindow(start + SpikeTiming.LATENCY_ON_MS - 1))
        assertTrue(!LatencyCycle.inOnWindow(start + SpikeTiming.LATENCY_ON_MS))
        assertTrue(!LatencyCycle.inOnWindow(start + SpikeTiming.LATENCY_CYCLE_MS - 1))
        assertTrue(LatencyCycle.inOnWindow(start + SpikeTiming.LATENCY_CYCLE_MS))
    }

    @Test
    fun `next transition is always strictly positive so the emitter loop cannot spin`() {
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        for (offset in longArrayOf(0, 1, 19_999, 20_000, 20_001, 59_999)) {
            val wait = LatencyCycle.millisUntilNextTransition(start + offset)
            assertTrue("offset=$offset wait=$wait", wait > 0L)
            assertTrue("offset=$offset wait=$wait", wait <= SpikeTiming.LATENCY_CYCLE_MS)
        }
    }

    @Test
    fun `cycle 0 of every protocol epoch coincides with a key rotation boundary`() {
        // Recorded rather than hidden: that cycle measures a doubled advertising restart (ours plus
        // the radio's own epoch rotation) and is a different experiment from cycles 1-14. The
        // analysis is expected to drop or separate it, which it can only do if the column is right.
        val epochMs = 900_000L
        val epochBoundary = (1_754_000_000_000L / epochMs) * epochMs
        assertEquals(0L, LatencyCycle.epochCycleIndex(epochBoundary))
        assertEquals(1L, LatencyCycle.epochCycleIndex(epochBoundary + 60_000L))
        assertEquals(14L, LatencyCycle.epochCycleIndex(epochBoundary + 14 * 60_000L))
        assertEquals(0L, LatencyCycle.epochCycleIndex(epochBoundary + epochMs))
    }

    @Test
    fun `pre-epoch timestamps do not wrap the cycle index`() {
        // floorDiv/floorMod, not / and %. Kotlin's % is negative for negative operands, which would
        // put a pre-1970 or clock-skewed-backwards timestamp in a cycle it does not belong to and
        // silently corrupt one sample. Cheap to get wrong, invisible in the output.
        assertEquals(-1L, LatencyCycle.cycleIndex(-1L))
        assertTrue(LatencyCycle.cycleStartMs(-1L) <= -1L)
        assertTrue(LatencyCycle.millisUntilNextTransition(-1L) > 0L)
    }

    // ---------------------------------------------------------------------------------------------
    // LatencyTracker — one sample per peer per cycle, and the free skew detector.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `only the first sighting of a peer in a cycle is a latency sample`() {
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)

        val first = t.onSighting("slot:2", start + 1_200L)
        assertEquals(1_200L, first?.latencyMs)
        // Every subsequent packet in the same cycle is density data, not latency data. Counting it
        // would make the percentile a function of how chatty the peer is.
        assertNull(t.onSighting("slot:2", start + 1_300L))
        assertNull(t.onSighting("slot:2", start + 9_000L))
        assertEquals(1L, t.totalSamples)

        // A different peer in the same cycle is its own sample.
        assertEquals(2_000L, t.onSighting("slot:3", start + 2_000L)?.latencyMs)
        assertEquals(2L, t.totalSamples)

        // Next cycle, the same peer counts again.
        assertEquals(
            500L,
            t.onSighting("slot:2", start + SpikeTiming.LATENCY_CYCLE_MS + 500L)?.latencyMs,
        )
        assertEquals(3L, t.totalSamples)
    }

    @Test
    fun `an arrival just before a boundary reads NEGATIVE, which is proof of clock skew`() {
        // METHOD 4 in SpikeLatency.kt, and the reason `attributedCycleIndex` exists at all.
        //
        // The obvious implementation — `observedAt mod CYCLE_MS` — is floorMod, range [0, CYCLE).
        // It CANNOT return a negative number, so a packet arriving 300 ms before our notion of the
        // boundary would read as 59_700 ms: a plausible-looking enormous latency instead of a
        // 300 ms clock disagreement. The detector would have been dead and nobody would have
        // noticed, because the failure looks like a disappointing result rather than a broken one.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)

        val sample = t.onSighting("slot:2", start - 300L)
        assertEquals(-300L, sample?.latencyMs)
        assertEquals(start, sample?.cycleStartUtcMs)
        assertEquals(-300L, t.minLatencyMs)
    }

    @Test
    fun `an arrival deep in the OFF window is NOT reported as tens of seconds of skew`() {
        // A plain nearest-boundary rule would claim -25s of skew for this, which is nonsense: it is
        // far more likely a batched scan result or a peer not running the probe. Bounded
        // attribution keeps it a large positive offset and flags it, so the method's own breakdown
        // is visible as a climbing after_on_window counter rather than as invented skew.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val sample = t.onSighting("slot:2", start + 35_000L)
        assertEquals(35_000L, sample?.latencyMs)
        assertEquals(1L, t.afterOnWindow)
    }

    @Test
    fun `first sighting after the ON window is flagged, not discarded`() {
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        t.onSighting("slot:2", start + SpikeTiming.LATENCY_ON_MS + 5_000L)
        assertEquals(1L, t.afterOnWindow)
        assertEquals(1L, t.totalSamples)
    }

    // ---------------------------------------------------------------------------------------------
    // LatencyTracker — THE MISS COUNTER. It had no test at all, and it was wrong in both of the
    // ways that flatter the result. These are the tests that would have caught it.
    //
    // Every one of these is pure arithmetic over a cycle schedule, which is exactly the kind of
    // thing a JVM test can settle. None of it is a radio result and none of it says anything about
    // what discovery latency actually is.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a total blackout is counted, not frozen`() {
        // THE BUG THIS EXISTS FOR. The old tracker advanced the cycle only when a sighting arrived,
        // so a cycle in which NOTHING was heard never ended and no miss was ever attributed to it.
        // A peer handset dying at minute 10 of a 90-minute run froze the counter at minute 10 and
        // the p50 over those first ten good minutes read perfectly clean for the whole run.
        //
        // The instrument's most flattering possible failure, in the exact place its KDoc promised
        // it could not happen. The cycle now ends because TIME PASSED.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        // Two cycles in which the peer is heard normally.
        t.onSighting("slot:2", start + 1_000L)
        t.onSighting("slot:2", start + cycle + 1_000L)

        // The peer dies. Nothing is heard again, ever. Only the timer advances.
        t.advanceTo(start + 2 * cycle + 500L)
        assertEquals("cycle 0 closed, peer was seen in it", 0L, t.missedCycles)
        t.advanceTo(start + 3 * cycle + 500L)
        assertEquals("cycle 1 closed, peer was seen in it", 0L, t.missedCycles)
        t.advanceTo(start + 4 * cycle + 500L)
        // Cycle 2 closes with the peer expected and absent. THIS is the row the old code could
        // never produce.
        assertEquals(1L, t.missedCycles)
        t.advanceTo(start + 5 * cycle + 500L)
        assertEquals(2L, t.missedCycles)
    }

    @Test
    fun `a peer that leaves is not counted missing forever`() {
        // The other half of the same defect. `lastCycleExpectedPeers` was a monotone high-water
        // mark, so one peer walking away at minute 20 of a 90-minute run manufactured seventy
        // misses out of a single departure — and a miss count inflated by an order of magnitude is
        // just as useless as one frozen at zero, in the opposite direction.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        assertEquals(0L, t.peersDeparted)

        // Advance far past any plausible return. Because the timer fires every cycle, walk it.
        for (i in 1..40) t.advanceTo(start + i * cycle + 500L)

        // Exactly the departure threshold in misses, then it stops. Not 39.
        assertEquals(
            SpikeTiming.LATENCY_PEER_DEPARTURE_MISSES.toLong(),
            t.missedCycles,
        )
        assertEquals(1L, t.peersDeparted)
        assertEquals(0, t.expectedPeers)
    }

    @Test
    fun `a peer that comes back is expected again`() {
        // A departure is not a permanent verdict. Re-appearing re-enters the expected set, so an
        // intermittent peer produces a run of misses, a departure, and then more misses — which is
        // a visibly different shape in the file from a peer that left once.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        for (i in 1..5) t.advanceTo(start + i * cycle + 500L)
        assertEquals(1L, t.peersDeparted)

        t.onSighting("slot:2", start + 5 * cycle + 1_000L)
        // Cycle 5 is accounted when it falls out of the retention window, i.e. as cycle 7 opens.
        t.advanceTo(start + 7 * cycle + 500L)
        assertEquals("re-joined the expected set", 1, t.expectedPeers)
        val before = t.missedCycles
        t.advanceTo(start + 8 * cycle + 500L)
        assertEquals("and can be missed again", before + 1L, t.missedCycles)
    }

    @Test
    fun `only peers we have actually heard are expected`() {
        // A peer cannot be missed in the cycles before we knew it existed. Otherwise the first
        // sighting of a peer forty minutes into a run would retroactively invent forty misses.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        for (i in 1..3) t.advanceTo(start + i * cycle + 500L)
        assertEquals(0L, t.missedCycles)
        assertEquals(0, t.expectedPeers)

        t.onSighting("slot:7", start + 3 * cycle + 1_000L)
        t.advanceTo(start + 5 * cycle + 500L)
        assertEquals("heard in the cycle that closed, so no miss", 0L, t.missedCycles)
        assertEquals(1, t.expectedPeers)
    }

    @Test
    fun `misses are counted per peer per cycle`() {
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        t.onSighting("slot:3", start + 2_000L)
        t.onSighting("slot:4", start + 3_000L)

        // Next cycle only slot:2 is heard. Two peers missed one cycle each — accounted when cycle 1
        // falls out of the retention window, as cycle 3 opens.
        t.onSighting("slot:2", start + cycle + 1_000L)
        t.advanceTo(start + 3 * cycle + 500L)
        assertEquals(2L, t.missedCycles)
    }

    @Test
    fun `miss accounting lags one cycle and the end of a run settles it`() {
        // THE COST OF NOT COUNTING LATE PACKETS AS MISSES, pinned so it is a known property.
        //
        // A cycle is accounted when it falls out of the retention window, not when it ends. That is
        // deliberate — a wrongly-counted miss is a false failure report, a late-counted miss is
        // only late — but it means the last cycles of a run would go unaccounted if nothing settled
        // them. SpikeController.stop calls closeElapsedCycles for exactly this reason, and on an
        // OEM-kill capture the final cycles are the finding.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        t.advanceTo(start + 2 * cycle + 500L)   // closes cycle 0: peer heard, joins expected
        // Cycle 1 has ended and the peer was never heard in it, but it is still in the window.
        assertEquals(0L, t.missedCycles)

        // End of run, part way through cycle 2.
        t.closeElapsedCycles(start + 2 * cycle + 30_000L)
        assertEquals("cycle 1 had fully elapsed and is now settled", 1L, t.missedCycles)
        assertEquals("cycle 2 is UNFINISHED and must not count as a miss", 1L, t.missedCycles)
        assertEquals(1, t.drainMisses().size)
    }

    @Test
    fun `a miss row is produced for every counted miss`() {
        // The counter dies with the process; the file is the measurement. A miss that is counted
        // but not written cannot tell the analysis WHERE it was, and fifteen consecutive misses at
        // minute 40 is a different finding from fifteen scattered over ninety minutes.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        t.drainMisses()
        // Cycle 0 is accounted as cycle 2 opens (peer heard, no miss); cycle 1 as cycle 3 opens.
        t.advanceTo(start + 3 * cycle + 500L)

        val misses = t.drainMisses()
        assertEquals(1, misses.size)
        assertEquals("slot:2", misses[0].peerKey)
        assertEquals(1, misses[0].consecutiveMisses)
        assertTrue(!misses[0].departed)
        // Drained means drained. A second call must not re-emit the same rows into the file.
        assertTrue(t.drainMisses().isEmpty())
    }

    @Test
    fun `the departure row fires exactly once`() {
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        for (i in 1..20) t.advanceTo(start + i * cycle + 500L)

        val departures = t.drainMisses().count { it.departed }
        assertEquals(1, departures)
    }

    @Test
    fun `cycles when the process was not running are not misses`() {
        // Doze, or an OEM battery manager suspending us. We did not fail to hear the peer — we were
        // not listening, and "the peer was not heard" says nothing about the peer. Manufacturing a
        // miss per peer per cycle for that window would blame the radio for the OEM, which is the
        // single most likely misreading of a Phase 0 result.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        // Nothing for forty minutes, then the timer fires again in one jump.
        t.advanceTo(start + 40 * cycle + 500L)

        // The peer is expected, and one cycle — the one that was open when we stopped running —
        // legitimately closes. The other 38 are NOT misses.
        assertTrue("misses must not be manufactured for a dead process", t.missedCycles <= 1L)
        // And the gap is not swallowed either. It is its own counter, and on an OEM-kill run it is
        // the headline finding rather than a footnote.
        assertEquals(39L, t.unaccountedCycles)
    }

    @Test
    fun `a sighting for an already-closed cycle cannot un-miss a peer`() {
        // A batched scan result delivered late must not retroactively cancel a miss that has
        // already been counted and written. The counter may only ever move in one direction.
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        val cycle = SpikeTiming.LATENCY_CYCLE_MS

        t.onSighting("slot:2", start + 1_000L)
        t.advanceTo(start + 3 * cycle + 500L)
        val missedBefore = t.missedCycles
        assertTrue(missedBefore > 0L)

        // A packet from cycle 1, arriving now.
        assertNull(t.onSighting("slot:2", start + cycle + 1_000L))
        assertEquals(missedBefore, t.missedCycles)
        assertEquals(1L, t.lateArrivals)
    }

    @Test
    fun `reset clears the miss accounting with everything else`() {
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        t.onSighting("slot:2", start + 1_000L)
        for (i in 1..10) t.advanceTo(start + i * SpikeTiming.LATENCY_CYCLE_MS + 500L)
        assertTrue(t.missedCycles > 0L)

        t.reset()
        assertEquals(0L, t.missedCycles)
        assertEquals(0L, t.peersDeparted)
        assertEquals(0L, t.cyclesClosed)
        assertEquals(0, t.expectedPeers)
        assertEquals(0L, t.totalSamples)
        assertTrue(t.drainMisses().isEmpty())
    }

    @Test
    fun `nearest-rank percentile does not invent values between observations`() {
        val t = LatencyTracker()
        val start = LatencyCycle.cycleStartMs(1_754_000_000_000L)
        listOf(100L, 200L, 300L, 400L).forEachIndexed { i, v ->
            t.onSighting("slot:$i", start + v)
        }
        assertEquals(200L, t.percentileMs(50))
        assertEquals(400L, t.percentileMs(95))
        assertEquals(100L, t.percentileMs(1))
    }

    @Test
    fun `percentile is null with no samples rather than zero`() {
        // A suppressed statistic must never render as a very good one.
        assertNull(LatencyTracker().percentileMs(50))
    }

    // ---------------------------------------------------------------------------------------------
    // SpikeDutyLedger — the thing that makes a battery number attributable.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `ledger integrates scan intervals and ignores redundant opens`() {
        val d = SpikeDutyLedger()
        d.start(nowElapsedMs = 0L)
        d.onScanOpened(1_000L)
        // A SCAN_STARTED while already open is the radio's own "restart" path. It did not gain a
        // second receiver, and counting it would double the numerator of every %/hr figure.
        d.onScanOpened(2_000L)
        d.onScanClosed(6_000L)
        assertEquals(5_000L, d.scanOnMs(10_000L))
        assertEquals(1L, d.scanOpenTransitions)

        d.onScanOpened(8_000L)
        // 5 000 accumulated + 2 000 still open, against 10 000 ms of run.
        assertEquals(7_000L, d.scanOnMs(10_000L))
        assertEquals(70, d.scanOnPct(10_000L))
    }

    @Test
    fun `a failed scan is a closed scan`() {
        // Counting a failed scan as open credits the radio with receive time it never spent, in the
        // exact direction that flatters us against the 4%/hr budget.
        val d = SpikeDutyLedger()
        d.start(0L)
        d.onScanOpened(0L)
        d.onScanClosed(1_000L)
        assertTrue(!d.scanning)
        assertEquals(1_000L, d.scanOnMs(50_000L))
    }

    @Test
    fun `closing a scan that was never open is a no-op`() {
        val d = SpikeDutyLedger()
        d.start(0L)
        d.onScanClosed(5_000L)
        assertEquals(0L, d.scanOnMs(10_000L))
    }

    // ---------------------------------------------------------------------------------------------
    // BatteryDrainEstimator — WHICH CLOCK THE %/hr FIGURE IS DIVIDED BY.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the drain rate is divided by the monotonic clock, not the wall clock`() {
        // THE BUG: the denominator used to be `wallUtcMs` deltas. An NTP re-sync mid-capture moves
        // the wall clock by an arbitrary amount in an arbitrary direction and silently rewrites the
        // rate for the WHOLE run — a 4.0 %/hr result quietly becomes 3.6 or 4.4, with nothing
        // anywhere indicating that anything happened. SpikeDutyLedger already makes this argument
        // for the numerator; the two have to be on the same clock or the ratio is not a ratio.
        //
        // Here: one hour of real time, during which the wall clock jumps forward six minutes.
        val hour = 3_600_000L
        val d = BatteryDrainEstimator()
        d.add(batterySample(wall = 1_754_000_000_000L, elapsed = 10_000_000L, level = 80))
        d.add(
            batterySample(
                wall = 1_754_000_000_000L + hour + 360_000L,
                elapsed = 10_000_000L + hour,
                level = 76,
            ),
        )

        assertEquals(hour, d.elapsedMs)
        // 4 % over exactly one monotonic hour. Against the wall clock it would read 3.43 %/hr and
        // a 4 %/hr budget would look met.
        assertEquals(4.0, d.percentPerHourFromLevel()!!, 0.0001)
    }

    @Test
    fun `a wall-clock step is recorded rather than corrected away`() {
        // The step does not damage the %/hr figure any more, but it DOES damage every latency_ms in
        // latency.csv, which is on the wall clock by necessity (two handsets, one shared schedule).
        // So it is surfaced instead of silently absorbed: an unexplained 90-second outlier in the
        // latency file is a mystery, and the same outlier next to `wall_clock_step_ms=90000` is an
        // explanation.
        val d = BatteryDrainEstimator()
        d.add(batterySample(wall = 1_000_000L, elapsed = 500_000L, level = 90))
        d.add(batterySample(wall = 1_000_000L + 700_000L, elapsed = 500_000L + 600_000L, level = 88))
        assertEquals(100_000L, d.wallClockStepMs)
    }

    @Test
    fun `a clean run reports no wall-clock step`() {
        val d = BatteryDrainEstimator()
        d.add(batterySample(wall = 1_000_000L, elapsed = 500_000L, level = 90))
        d.add(batterySample(wall = 1_600_000L, elapsed = 1_100_000L, level = 88))
        assertEquals(0L, d.wallClockStepMs)
    }

    @Test
    fun `no projection before the minimum run length, whichever clock says so`() {
        // A slope through four minutes of a four-hour discharge curve is a rounding error with a
        // decimal point on it. The guard is on the monotonic clock too — otherwise a backwards NTP
        // step could make a long run look short enough to suppress its own result.
        val d = BatteryDrainEstimator()
        d.add(batterySample(wall = 1_000_000L, elapsed = 500_000L, level = 90))
        d.add(batterySample(wall = 9_000_000L, elapsed = 500_000L + 60_000L, level = 80))
        assertNull(d.percentPerHourFromLevel())
    }

    /** Minimal sample: only the fields these tests reason about carry meaning. */
    private fun batterySample(wall: Long, elapsed: Long, level: Int) = SpikeBatterySample(
        wallUtcMs = wall,
        elapsedRealtimeMs = elapsed,
        levelPct = level,
        rawLevel = level,
        scale = 100,
        chargeCounterUah = 4_000_000L * level / 100,
        currentNowUa = -300_000,
        energyCounterNwh = 0L,
        voltageMv = 3_900,
        temperatureDeciC = 300,
        pluggedRaw = 0,
        statusRaw = 3,
        healthRaw = 2,
        screenInteractive = false,
        powerSaveMode = false,
        deviceIdleMode = false,
    )

    // ---------------------------------------------------------------------------------------------
    // DensityAccumulator — SPEC §5.0. The empty-bucket rule is the load-bearing one.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `buckets with zero packets are emitted`() {
        // A success rate computed only over buckets in which something succeeded is 100% by
        // construction. This is the single most important behaviour in the density accumulator.
        val a = DensityAccumulator()
        val t0 = Math.floorDiv(1_754_000_000_000L, SpikeTiming.DENSITY_BUCKET_MS) *
            SpikeTiming.DENSITY_BUCKET_MS

        a.advanceTo(t0, 0L, 0L, 0L)
        a.onSighting(
            nowMs = t0 + 1_000L,
            peerKey = "slot:2",
            advertiserAddress = "AA:BB:CC:DD:EE:FF",
            eidHex = "abcd",
            resolvedSlot = 2,
            decodeFailed = false,
            isCarrierBCandidate = false,
            droppedTotal = 0L,
            writeFailuresTotal = 0L,
            scanOnMsTotal = 0L,
        )

        // Jump three buckets forward with nothing in between.
        val closed = a.advanceTo(t0 + 3 * SpikeTiming.DENSITY_BUCKET_MS + 10L, 0L, 0L, 0L)
        assertEquals(3, closed.size)
        assertEquals(1L, closed[0].packets)
        assertEquals(0L, closed[1].packets)
        assertEquals(0L, closed[2].packets)
        assertEquals(0, closed[1].distinctPeers)
    }

    @Test
    fun `the three distinct-peer counts are kept separately`() {
        // If distinct_slots says 1 and distinct_addresses says 9 over the same hour, the difference
        // is the RPA rotation rate, not a discrepancy. Collapsing them to one number would delete a
        // measurement.
        val a = DensityAccumulator()
        val t0 = Math.floorDiv(1_754_000_000_000L, SpikeTiming.DENSITY_BUCKET_MS) *
            SpikeTiming.DENSITY_BUCKET_MS
        a.advanceTo(t0, 0L, 0L, 0L)
        listOf("AA:00", "BB:11", "CC:22").forEach { addr ->
            a.onSighting(t0 + 100L, "slot:2", addr, "abcd", 2, false, false, 0L, 0L, 0L)
        }
        val closed = a.advanceTo(t0 + SpikeTiming.DENSITY_BUCKET_MS + 10L, 0L, 0L, 0L)
        assertEquals(1, closed.size)
        assertEquals(1, closed[0].distinctPeers)
        assertEquals(3, closed[0].distinctAddresses)
        assertEquals(1, closed[0].distinctEids)
        assertEquals(1, closed[0].distinctSlots)
    }

    @Test
    fun `loss deltas are per-bucket, not cumulative`() {
        // A bucket that looks empty because WE dropped the records must be distinguishable from a
        // bucket that looks empty because the room was. B8 and §5.0 are assertions about absence.
        val a = DensityAccumulator()
        val t0 = Math.floorDiv(1_754_000_000_000L, SpikeTiming.DENSITY_BUCKET_MS) *
            SpikeTiming.DENSITY_BUCKET_MS
        a.advanceTo(t0, droppedTotal = 5L, writeFailuresTotal = 0L, scanOnMsTotal = 0L)
        val closed = a.advanceTo(
            t0 + SpikeTiming.DENSITY_BUCKET_MS + 10L,
            droppedTotal = 12L,
            writeFailuresTotal = 2L,
            scanOnMsTotal = 30_000L,
        )
        assertEquals(1, closed.size)
        assertEquals(7L, closed[0].diagnosticsDroppedInBucket)
        assertEquals(2L, closed[0].writeFailuresInBucket)
        assertEquals(30_000L, closed[0].scanOnMsInBucket)
    }

    @Test
    fun `max gap separates a steady stream from a single burst`() {
        val a = DensityAccumulator()
        val t0 = Math.floorDiv(1_754_000_000_000L, SpikeTiming.DENSITY_BUCKET_MS) *
            SpikeTiming.DENSITY_BUCKET_MS
        a.advanceTo(t0, 0L, 0L, 0L)
        listOf(0L, 250L, 500L, 40_000L).forEach { offset ->
            a.onSighting(t0 + offset, "slot:2", "AA:00", "abcd", 2, false, false, 0L, 0L, 0L)
        }
        val closed = a.advanceTo(t0 + SpikeTiming.DENSITY_BUCKET_MS + 10L, 0L, 0L, 0L)
        val peer = closed[0].peers.single()
        assertEquals(4L, peer.packets)
        assertEquals(39_500L, peer.maxGapMs)
    }

    @Test
    fun `concurrent peers is a recency window and says so`() {
        val a = DensityAccumulator()
        val t0 = Math.floorDiv(1_754_000_000_000L, SpikeTiming.DENSITY_BUCKET_MS) *
            SpikeTiming.DENSITY_BUCKET_MS
        a.advanceTo(t0, 0L, 0L, 0L)
        a.onSighting(t0, "slot:2", "AA:00", "aa", 2, false, false, 0L, 0L, 0L)
        a.onSighting(t0 + 1_000L, "slot:3", "BB:00", "bb", 3, false, false, 0L, 0L, 0L)
        assertEquals(2, a.concurrentPeers(t0 + 1_000L))
        // slot:2 falls out of the window first.
        assertEquals(1, a.concurrentPeers(t0 + SpikeTiming.PEER_LIVENESS_MS + 500L))
        assertEquals(0, a.concurrentPeers(t0 + SpikeTiming.PEER_LIVENESS_MS + 60_000L))
        assertEquals(2, a.peakConcurrentPeers)
    }

    // ---------------------------------------------------------------------------------------------
    // The honesty flags. These are assertions about the instrument's own claims, and they are the
    // reason a founder can read the output six weeks later without re-deriving the caveats.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a max-capture run can never report a battery figure`() {
        val d = BatteryDrainEstimator()
        val reason = d.invalidReason(maxCapture = true, mode = SpikeMode.CAPTURE)
        assertTrue(reason.contains("VOID"))
    }

    @Test
    fun `a latency-probe run is void for the bijection screen`() {
        assertTrue(!SpikeMode.LATENCY_PROBE.bijectionValid)
        assertTrue(SpikeMode.CAPTURE.bijectionValid)
        val stats = SpikeStats(mode = SpikeMode.LATENCY_PROBE, bridgedEids = 3, sightings = 100)
        assertTrue(stats.integrityNote.contains("self-inflicted"))
    }

    @Test
    fun `our own losses outrank every other verdict`() {
        // A run with holes we made cannot support any conclusion of the form "X never happened",
        // which is the shape of both B8 and §5.0. That has to be the FIRST thing the note says.
        val stats = SpikeStats(
            mode = SpikeMode.CAPTURE,
            diagnosticsDropped = 1L,
            bridgedEids = 4,
            sightings = 1_000,
        )
        assertTrue(stats.integrityNote.startsWith("DEGRADED"))
    }

    @Test
    fun `baseline mode never reports itself as an answer`() {
        val d = BatteryDrainEstimator()
        val reason = d.invalidReason(maxCapture = false, mode = SpikeMode.BATTERY_BASELINE)
        assertTrue(reason.contains("SUBTRAHEND"))
    }

    @Test
    fun `every timing constant is written into the run header`() {
        // A capture whose parameters are only knowable by reading the source at the commit it was
        // built from is a capture nobody can re-derive in six weeks.
        val meta = SpikeTiming.describeForMeta()
        listOf(
            SpikeTiming.BATTERY_SAMPLE_MS,
            SpikeTiming.LATENCY_CYCLE_MS,
            SpikeTiming.LATENCY_ON_MS,
            SpikeTiming.DENSITY_BUCKET_MS,
            SpikeTiming.PEER_LIVENESS_MS,
        ).forEach { value ->
            assertTrue(
                "constant $value missing from meta",
                meta.values.any { it == value.toString() },
            )
        }
        assertTrue(meta["timing_constants_status"]!!.contains("UNMEASURED"))
    }

    @Test
    fun `every measurement carries a trust label and a caveat`() {
        SpikeProcedure.MEASUREMENTS.forEach { m ->
            assertTrue("${m.name} has no caveat", m.caveat.length > 40)
            assertTrue("${m.name} has no answer", m.answers.isNotEmpty())
        }
        // The two things a phone physically cannot decide must be labelled as such, forever.
        val sniffer = SpikeProcedure.MEASUREMENTS.filter {
            it.trust == SpikeProcedure.Trust.NEEDS_SNIFFER
        }
        assertTrue(sniffer.any { it.name.contains("B8") })
        assertTrue(sniffer.any { it.name.contains("Address type") })
    }

    // ---------------------------------------------------------------------------------------------
    // SpikeWriter — the run id IS the directory name, and the CSV header IS the column contract.
    //
    // The writer itself needs a Context and Robolectric is deliberately absent from this module, so
    // what is tested here is the part that is pure and the part whose breakage is silent.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `two runs started in the same second get different directories`() {
        // THE BUG: the run id was second-resolution, and the run id is the directory name. Two
        // starts inside one second — a double tap, or a service restart racing the screen —
        // produced the same path, and the second run's bufferedWriter() TRUNCATED the first run's
        // files. The operator would be left with one directory holding the tail of one capture and
        // the head of another, with a meta.json describing whichever wrote last, and nothing
        // anywhere saying so.
        val ids = (1..200).map { SpikeWriter.newRunId() }.toSet()
        assertEquals("run ids must be unique within a second", 200, ids.size)
    }

    @Test
    fun `run ids still sort chronologically`() {
        // The entropy goes on the END, after a millisecond-resolution UTC timestamp. A directory
        // listing is how a person finds the run they just did, and putting the random part first
        // would have solved the collision by making the output unnavigable.
        //
        // NOTE WHAT IS ASSERTED: ordering holds on the TIMESTAMP PREFIX. Two ids minted in the same
        // millisecond are ordered by their random suffix, which is meaningless — and that is
        // correct rather than a gap, because within one millisecond there is no chronology to
        // preserve. Asserting `first <= second` on the whole string would be asserting something
        // untrue that happens to pass most of the time.
        val ids = (1..50).map { SpikeWriter.newRunId() }
        val stamps = ids.map { it.substringBeforeLast('-') }
        assertEquals("timestamp prefix must be non-decreasing", stamps.sorted(), stamps)
        ids.forEach {
            assertTrue("$it should start with a UTC date", it.startsWith("20"))
            // yyyyMMdd-HHmmss.SSS-xxxxxx
            assertEquals("$it has the wrong shape", 26, it.length)
        }
    }

    @Test
    fun `the battery header carries the monotonic clock next to the wall clock`() {
        // The %/hr denominator. Adjacent and in this order so that `wall_utc_ms −
        // elapsed_realtime_ms` — the expression that makes an NTP re-sync visible — is something a
        // reader trips over rather than something they have to be told about.
        val columns = SpikeWriter.BATTERY_CSV_HEADER.split(",")
        assertTrue("elapsed_realtime_ms missing", columns.contains("elapsed_realtime_ms"))
        assertEquals(
            columns.indexOf("elapsed_ms") + 1,
            columns.indexOf("elapsed_realtime_ms"),
        )
        assertTrue(columns.contains("wall_utc_ms"))
    }

    @Test
    fun `csv cells containing separators are quoted`() {
        // A miss row carries a peer key and an empty latency cell. If quoting were wrong, one comma
        // in one field would shift every subsequent column in that row and the analysis would read
        // a duty profile as a timestamp.
        assertEquals("plain", SpikeController.csvCell("plain"))
        assertEquals("\"a,b\"", SpikeController.csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SpikeController.csvCell("say \"hi\""))
    }

    @Test
    fun `every mode has a procedure a human can follow`() {
        SpikeMode.entries.forEach { mode ->
            val steps = SpikeProcedure.steps(mode)
            assertTrue("$mode has no steps", steps.size >= 5)
            assertTrue("$mode headline is empty", SpikeProcedure.headline(mode, false).isNotEmpty())
        }
    }
}
