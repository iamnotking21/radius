package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android's 5-starts-per-30-seconds scan throttle, accounted on our side.
 *
 * WHY THIS IS WORTH A TEST when so little else here is: the platform's punishment for exceeding
 * the limit is SILENCE. `startScan` returns normally, `onScanFailed` frequently never arrives, and
 * results simply stop for the rest of the window. In this product that is indistinguishable from
 * "nobody is nearby", which is indistinguishable from "the moat does not work". A spike that trips
 * this and does not know it will report a false negative on the single question Phase 0 exists to
 * answer.
 */
class ScanStartGateTest {

    @Test
    fun allows_the_budget_then_defers() {
        val gate = ScanStartGate(budget = 4, windowMillis = 30_000L)
        repeat(4) { i ->
            assertEquals(0L, gate.tryStart(1_000L + i), "start ${i + 1} should be allowed")
        }
        assertTrue(gate.tryStart(1_004L) > 0L, "the fifth start inside the window must defer")
    }

    @Test
    fun the_deferral_says_how_long_to_wait() {
        val gate = ScanStartGate(budget = 2, windowMillis = 30_000L)
        gate.tryStart(0L)
        gate.tryStart(5_000L)
        // Oldest start was at t=0, so the window frees at t=30_000.
        assertEquals(20_000L, gate.tryStart(10_000L))
    }

    @Test
    fun the_window_rolls_it_does_not_reset_in_buckets() {
        // A fixed 30 s bucket would permit 8 starts across a boundary with budget 4 — exactly the
        // burst the platform punishes. The rolling window is the point of this class.
        val gate = ScanStartGate(budget = 4, windowMillis = 30_000L)
        gate.tryStart(0L)
        gate.tryStart(1_000L)
        gate.tryStart(2_000L)
        gate.tryStart(3_000L)

        assertTrue(gate.tryStart(29_999L) > 0L, "still inside the window")
        assertEquals(0L, gate.tryStart(30_000L), "the start at t=0 has now aged out")
        assertTrue(gate.tryStart(30_001L) > 0L, "and only that one has")
    }

    @Test
    fun a_deferred_attempt_consumes_nothing() {
        val gate = ScanStartGate(budget = 1, windowMillis = 10_000L)
        gate.tryStart(0L)
        repeat(5) { gate.tryStart(1_000L) }
        assertEquals(1, gate.startsInWindow(1_000L), "refusals must not extend the ban")
        assertEquals(0L, gate.tryStart(10_000L))
    }

    @Test
    fun default_budget_leaves_one_start_of_slack_below_the_platform_limit() {
        // The platform counts 5 per 30 s across process restarts; we cannot see its counter. Using
        // all 5 is how an app discovers it was already at 5. Spending the last start is how you
        // get an invisible radio, so the default is 4.
        val gate = ScanStartGate()
        repeat(4) { assertEquals(0L, gate.tryStart(it.toLong())) }
        assertTrue(gate.tryStart(5L) > 0L)
    }
}
