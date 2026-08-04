package com.radius.shared.ble

import com.radius.shared.protocol.KeySchedule

/**
 * WHEN the radio must restart advertising. Not WHAT it broadcasts.
 *
 * SCOPE, and read this before extending it: this is a CALENDAR. It answers "how long until the
 * next 15-minute UTC boundary" so the radio can run the stop → re-derive → rebuild → start cycle
 * that `KEY_SCHEDULE.md` §4.2 mandates — the only lever either platform has on RPA rotation.
 *
 * It derives no key and holds no key material. The day/epoch arithmetic itself is DELEGATED to
 * [KeySchedule], which is ble-protocol's and is pinned by `vectors/key_schedule.json`. An earlier
 * draft of this file reimplemented `dayIndex`/`epochIndex` locally, before
 * `mobile/shared/protocol/` landed. That was a second implementation of a value the vectors exist
 * to pin, and it is exactly the divergence ADR-007 was written to prevent — so it is gone. What is
 * left here is only the part [KeySchedule] does not do: unit conversion, and the countdown.
 *
 * NO JITTER, EVER. `KEY_SCHEDULE.md` §3: rotation is synchronised to the global UTC boundary,
 * because staggering shrinks the anonymity set at a boundary from the whole population to one and
 * makes every rotation individually linkable. If someone proposes spreading restart load by
 * jittering the boundary, that is a safety change, not a performance tweak.
 */
internal object EpochClock {

    /** `KEY_SCHEDULE.md` §1, restated in milliseconds. 15 minutes. */
    const val EPOCH_MILLIS: Long = KeySchedule.EPOCH_SECONDS * 1000L

    const val DAY_MILLIS: Long = KeySchedule.DAY_SECONDS * 1000L

    /** 96 epochs per UTC day. Mirrors [KeySchedule.EPOCHS_PER_DAY]; pinned by a test. */
    const val EPOCHS_PER_DAY: Int = KeySchedule.EPOCHS_PER_DAY

    /**
     * `floorDiv`, not `/`. A handset with a flat battery and no network genuinely boots with a
     * pre-1970 clock, and truncating division there yields a negative second count that walks the
     * epoch index off the end of its range.
     */
    private fun unixSeconds(utcMillis: Long): Long = utcMillis.floorDiv(1000L)

    /** Days since the Unix epoch, UTC. `u32 day` in `SPEC.md` §8.1. */
    fun dayIndex(utcMillis: Long): Long = KeySchedule.dayIndex(unixSeconds(utcMillis))

    /** 15-minute index within the UTC day, 0..95. */
    fun epochIndex(utcMillis: Long): Int = KeySchedule.epochIndex(unixSeconds(utcMillis))

    /**
     * Monotonically increasing epoch counter across day boundaries. Not on the wire — used only to
     * decide whether the live advertisement is stale, where [epochIndex] wrapping 95 → 0 at
     * midnight would otherwise read as "no change".
     */
    fun absoluteEpoch(utcMillis: Long): Long = utcMillis.floorDiv(EPOCH_MILLIS)

    fun epochStartMillis(utcMillis: Long): Long = absoluteEpoch(utcMillis) * EPOCH_MILLIS

    /**
     * Milliseconds from [utcMillis] to the next boundary. Never 0 and never negative: exactly on a
     * boundary this returns a full [EPOCH_MILLIS], because a 0 would spin the restart loop at the
     * one instant per epoch that matters.
     */
    fun millisUntilNextBoundary(utcMillis: Long): Long =
        epochStartMillis(utcMillis) + EPOCH_MILLIS - utcMillis
}
