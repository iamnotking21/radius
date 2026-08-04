package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The advertising restart schedule.
 *
 * This is arithmetic, so a desk can prove it, and it is worth proving: an off-by-one here means
 * the radio rotates at the wrong instant, which does not fail loudly — it produces a device that
 * rotates 15 minutes out of phase with the rest of the population. `KEY_SCHEDULE.md` §3 is
 * explicit that a device rotating on its own schedule is "a population of one at every boundary",
 * i.e. WORSE than rotating slowly. Silent, and the exact opposite of the intended effect.
 */
class EpochClockTest {

    // 2026-08-04T00:00:00Z, a UTC midnight, verified independently: 20669 days since 1970-01-01.
    private val midnight = 20_669L * 86_400_000L

    @Test
    fun day_index_is_days_since_the_unix_epoch_utc() {
        assertEquals(20_669L, EpochClock.dayIndex(midnight))
        assertEquals(20_669L, EpochClock.dayIndex(midnight + 86_399_999L))
        assertEquals(20_670L, EpochClock.dayIndex(midnight + 86_400_000L))
    }

    @Test
    fun epoch_index_runs_0_to_95_within_a_utc_day() {
        assertEquals(0, EpochClock.epochIndex(midnight))
        assertEquals(0, EpochClock.epochIndex(midnight + 899_999L))
        assertEquals(1, EpochClock.epochIndex(midnight + 900_000L))
        assertEquals(95, EpochClock.epochIndex(midnight + 86_400_000L - 1L))
        assertEquals(0, EpochClock.epochIndex(midnight + 86_400_000L))
    }

    @Test
    fun there_are_ninety_six_epochs_in_a_day() {
        assertEquals(96, EpochClock.EPOCHS_PER_DAY)
        assertEquals(EpochClock.DAY_MILLIS, EpochClock.EPOCH_MILLIS * EpochClock.EPOCHS_PER_DAY)
        assertEquals(15L * 60L * 1000L, EpochClock.EPOCH_MILLIS)
        // Pinned against ble-protocol's constants so the millisecond restatement here can never
        // drift from the seconds the key schedule and its vectors are defined in.
        assertEquals(
            com.radius.shared.protocol.KeySchedule.EPOCH_SECONDS * 1000L,
            EpochClock.EPOCH_MILLIS,
        )
    }

    @Test
    fun the_wait_to_the_next_boundary_is_never_zero() {
        // A 0 would spin the rotation loop at 100% CPU on the one instant per epoch that matters.
        assertEquals(EpochClock.EPOCH_MILLIS, EpochClock.millisUntilNextBoundary(midnight))
        assertEquals(1L, EpochClock.millisUntilNextBoundary(midnight + 899_999L))
        assertEquals(900_000L, EpochClock.millisUntilNextBoundary(midnight + 900_000L))
    }

    @Test
    fun boundaries_are_utc_aligned_not_relative_to_start_time() {
        // The whole population rotates at the same instant (§3). If this ever became
        // "start + 15 minutes", every device would rotate at a different moment, the anonymity set
        // at each boundary would collapse to one, and every rotation would be individually
        // linkable — the failure the synchronisation rule exists to prevent.
        val oddMoment = midnight + 7L * 60_000L + 13_456L
        assertEquals(midnight, EpochClock.epochStartMillis(oddMoment))
        // The next boundary is midnight + one epoch, NOT oddMoment + one epoch.
        assertEquals(
            midnight + EpochClock.EPOCH_MILLIS,
            oddMoment + EpochClock.millisUntilNextBoundary(oddMoment),
        )
    }

    @Test
    fun absolute_epoch_keeps_increasing_across_midnight() {
        // epochIndex wraps 95 -> 0 at midnight, which reads as "no change" to a staleness check.
        val lastOfDay = midnight + 86_400_000L - 1L
        val firstOfNext = midnight + 86_400_000L
        assertEquals(95, EpochClock.epochIndex(lastOfDay))
        assertEquals(0, EpochClock.epochIndex(firstOfNext))
        assertTrue(EpochClock.absoluteEpoch(firstOfNext) > EpochClock.absoluteEpoch(lastOfDay))
        assertEquals(1L, EpochClock.absoluteEpoch(firstOfNext) - EpochClock.absoluteEpoch(lastOfDay))
    }

    @Test
    fun a_pre_1970_clock_does_not_produce_a_negative_epoch_index() {
        // Not hypothetical: a handset with a flat battery and no network boots in 1970 or earlier,
        // and `%` would hand back a negative index that indexes nothing sane.
        val before = -1L
        assertTrue(EpochClock.epochIndex(before) in 0..95)
        assertEquals(95, EpochClock.epochIndex(before))
        assertTrue(EpochClock.millisUntilNextBoundary(before) > 0L)
    }
}
