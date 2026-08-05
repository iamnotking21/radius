package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHEN THE EPOCH TICKER RUNS — the predicate both `actual` radios now share.
 *
 * This test exists because the predicate used to be a boolean expression written twice, in two
 * files neither of which can be instantiated without a platform, and the two copies disagreed. The
 * Android copy said `desiredScan != null || desiredAdvertise != null`; the iOS copy said
 * `epochBoundaryListener != null || desiredAdvertise != null`. Both compiled, both looked
 * reasonable in isolation, and one of them let a one-tap privacy control switch off a key
 * destruction obligation. Nothing could have caught that except reading both files side by side.
 *
 * A pure function of three booleans needs no radio, so the decision is now covered by tests that
 * mean something — the same argument that put [AdvertiseGuard] in `commonMain`.
 */
class EpochTickerPolicyTest {

    @Test
    fun holding_a_ring_is_enough_on_its_own() {
        // THE CORRECTED RULE. The obligation's trigger is HOLDING KEYS, not doing radio work. Under
        // decision 35 the scan-only, non-advertising device is the MAJORITY case; if this returned
        // false, superseded keys would stay alive on most of the fleet and ADR-008 M4's rotation
        // would bound nothing.
        assertTrue(
            EpochTickerPolicy.wanted(
                isShutdown = false,
                advertising = false,
                hasEpochListener = true,
            ),
        )
    }

    @Test
    fun ghost_mode_cannot_switch_off_key_destruction() {
        // THE DEFECT THIS RULING CORRECTED, stated as the product behaviour it caused.
        //
        // Ghost mode is one tap from the Radar screen (safety invariant 10) and it stops the radio.
        // Under the old Android predicate that also stopped the boundary announcement, so the
        // highest-signal privacy assertion a user can make SUSPENDED a privacy obligation — while a
        // low-signal event like toggling Bluetooth in Quick Settings kept it running, because
        // desired state survived an adapter loss. The asymmetry ran precisely against user intent.
        //
        // A device in ghost mode is not advertising and not scanning. It still holds its ring.
        val ghosted = EpochTickerPolicy.wanted(
            isShutdown = false,
            advertising = false,
            hasEpochListener = true,
        )
        assertTrue(ghosted, "a privacy control must never suspend a privacy obligation")
    }

    @Test
    fun wanting_to_advertise_is_enough_on_its_own() {
        // The other job: the advertising restart, which is the only lever an app has on RPA
        // rotation (KEY_SCHEDULE.md §4.2). A device advertising without a registered listener is
        // not a configuration we ship, but the predicate must not depend on that.
        assertTrue(
            EpochTickerPolicy.wanted(
                isShutdown = false,
                advertising = true,
                hasEpochListener = false,
            ),
        )
    }

    @Test
    fun neither_job_wanted_means_no_ticker() {
        // 96 wakeups a day for nothing is not free even on a `delay`, and a ticker running with no
        // listener and nothing to advertise announces boundaries into the void.
        assertFalse(
            EpochTickerPolicy.wanted(
                isShutdown = false,
                advertising = false,
                hasEpochListener = false,
            ),
        )
    }

    @Test
    fun shutdown_beats_everything() {
        // Unconditional. A shut-down radio runs nothing, whatever is still registered on it — and
        // `shutdown()` drops the listener reference anyway (ruling R-D), so this is the belt to
        // that brace.
        assertFalse(EpochTickerPolicy.wanted(true, advertising = true, hasEpochListener = true))
        assertFalse(EpochTickerPolicy.wanted(true, advertising = false, hasEpochListener = true))
        assertFalse(EpochTickerPolicy.wanted(true, advertising = true, hasEpochListener = false))
    }

    @Test
    fun scanning_is_not_an_input_at_all() {
        // Pinned by the SHAPE of the function rather than by a value: there is no `scanning`
        // parameter, so a future edit that reintroduces the term has to change this signature and
        // this test, instead of adding one innocuous-looking `||` to a line in one platform.
        //
        // Scanning causes neither of the ticker's two jobs. Leaving the term in was what invited
        // the belief that the ticker runs because the radio is busy, which is the wrong mental
        // model of a destruction obligation and is how the ghost-mode inversion got written.
        val everyCombination = listOf(
            Triple(false, false, false) to false,
            Triple(false, false, true) to true,
            Triple(false, true, false) to true,
            Triple(false, true, true) to true,
            Triple(true, false, false) to false,
            Triple(true, false, true) to false,
            Triple(true, true, false) to false,
            Triple(true, true, true) to false,
        )
        everyCombination.forEach { (input, expected) ->
            val (shutdown, advertising, listener) = input
            val actual = EpochTickerPolicy.wanted(shutdown, advertising, listener)
            assertTrue(
                actual == expected,
                "wanted(isShutdown=$shutdown, advertising=$advertising, " +
                    "hasEpochListener=$listener) should be $expected",
            )
        }
    }
}
