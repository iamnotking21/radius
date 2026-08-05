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

    @Test
    fun `every mode has a procedure a human can follow`() {
        SpikeMode.entries.forEach { mode ->
            val steps = SpikeProcedure.steps(mode)
            assertTrue("$mode has no steps", steps.size >= 5)
            assertTrue("$mode headline is empty", SpikeProcedure.headline(mode, false).isNotEmpty())
        }
    }
}
