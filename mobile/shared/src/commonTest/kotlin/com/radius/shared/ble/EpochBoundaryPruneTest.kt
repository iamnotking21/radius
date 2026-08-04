package com.radius.shared.ble

import com.radius.shared.protocol.KeyRing
import com.radius.shared.protocol.KeyRingEntry
import com.radius.shared.protocol.KeySchedule
import com.radius.shared.protocol.ProtocolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The caller side of `KEY_SCHEDULE.md` §8.5.2 key destruction.
 *
 * `KeyRing.pruneSupersededAt` is a CALLER OBLIGATION — ble-protocol implemented the destruction,
 * but a platform that never calls it retains every key the device has ever held and ADR-008 M4's
 * rotation bounds nothing. The library half is tested by ble-protocol's vectors. THIS file tests
 * the half that is mine: that the obligation is actually driven, and that the accepted seam
 * consequence is what the spec says it is rather than what I assumed.
 *
 * The [BleRadio] wiring itself cannot be tested here — neither `actual` instantiates without a
 * platform and Robolectric is deliberately absent from this module. What IS testable, and is the
 * part most likely to be wrong, is the behavioural contract these tests pin below.
 */
class EpochBoundaryPruneTest {

    private fun key(fill: Byte) = ByteArray(32) { fill }

    /** Two entries, so there is a real seam to prune across. */
    private fun rotatingRing() = KeyRing(
        listOf(
            KeyRingEntry(kid = 1L, accountKey = key(0x11), effDay = 100L, effEpoch = 0),
            KeyRingEntry(kid = 2L, accountKey = key(0x22), effDay = 200L, effEpoch = 0),
        ),
    )

    @Test
    fun pruning_at_a_seam_destroys_the_superseded_key_and_never_the_active_one() {
        val ring = rotatingRing()

        // Before the second entry is effective, there is nothing superseded to destroy.
        assertEquals(0, ring.pruneSupersededAt(day = 150L, epoch = 0))
        assertFalse(ring.entries[0].isDestroyed)

        // On the far side of the seam, the older key goes.
        assertEquals(1, ring.pruneSupersededAt(day = 200L, epoch = 0))
        assertTrue(ring.entries[0].isDestroyed, "superseded key must be destroyed")
        assertFalse(ring.entries[1].isDestroyed, "ACTIVE key must survive — pruning must not brick")
    }

    @Test
    fun pruning_is_idempotent_so_driving_it_from_every_boundary_is_safe() {
        // This is what makes it correct to call on all 96 boundaries a day rather than detecting
        // seams in the radio — seam detection there would be a second copy of the ring logic in
        // the wrong module, which is the bug shape §8.4 spends a page on.
        val ring = rotatingRing()
        assertEquals(1, ring.pruneSupersededAt(200L, 0))
        repeat(10) { assertEquals(0, ring.pruneSupersededAt(200L, it + 1)) }
        assertTrue(ring.entries[0].isDestroyed)
        assertFalse(ring.entries[1].isDestroyed)
    }

    @Test
    fun advertising_still_works_after_a_prune() {
        // The failure that would matter most: destroying key material and finding the device can
        // no longer derive an ephemeral id, i.e. a privacy fix that silently ends discoverability.
        val ring = rotatingRing()
        ring.pruneSupersededAt(200L, 10)

        val eid = KeySchedule.ephemeralId(ring, day = 200L, epoch = 10)
        val success = assertIs<ProtocolResult.Success<ByteArray>>(eid, "prune must not brick advertising")
        assertEquals(16, success.value.size)
    }

    @Test
    fun after_a_prune_the_device_can_no_longer_recognise_its_own_pre_seam_eid() {
        // THE ACCEPTED CONSEQUENCE, pinned as a test so it is a known property rather than a
        // surprise in the field. The accepted window is {e-1, e, e+1}; at a seam `e-1` belongs to
        // the key just destroyed, so for exactly one epoch isOwnEphemeralId cannot recognise our
        // own previous-epoch id and a reflected copy falls through to ordinary resolution.
        //
        // Taken deliberately: §9.6 self-eid rejection is defence in depth against a rare event,
        // while §8.5.2 destruction addresses a permanent exposure — and keeping the old key one
        // more epoch means still holding it during every rotation, the moment it is most worth not
        // having.
        val ring = rotatingRing()

        // The last eid emitted under the OLD key, captured before destruction.
        val preSeam = KeySchedule.ephemeralId(ring, day = 199L, epoch = 95)
        val preSeamEid = assertIs<ProtocolResult.Success<ByteArray>>(preSeam).value

        assertTrue(
            KeySchedule.isOwnEphemeralId(ring, day = 199L, epoch = 95, observed = preSeamEid),
            "before the prune, the device recognises its own id",
        )

        ring.pruneSupersededAt(day = 200L, epoch = 0)

        assertFalse(
            KeySchedule.isOwnEphemeralId(ring, day = 199L, epoch = 95, observed = preSeamEid),
            "after the prune this MUST fail — if it starts passing, destruction has stopped " +
                "happening and the ADR-008 M4 HIGH is back",
        )
    }

    @Test
    fun a_clock_rewind_does_not_destroy_anything() {
        // The timestamp is the one input an attacker might influence, and destruction is
        // irreversible. Driving prune from a wall clock is only safe because of this.
        val ring = rotatingRing()
        assertEquals(0, ring.pruneSupersededAt(day = 0L, epoch = 0))
        assertEquals(0, ring.pruneSupersededAt(day = -5L, epoch = 3))
        assertFalse(ring.entries[0].isDestroyed)
        assertFalse(ring.entries[1].isDestroyed)
    }

    @Test
    fun an_out_of_range_epoch_index_destroys_nothing() {
        val ring = rotatingRing()
        assertEquals(0, ring.pruneSupersededAt(day = 200L, epoch = -1))
        assertEquals(0, ring.pruneSupersededAt(day = 200L, epoch = 96))
        assertFalse(ring.entries[0].isDestroyed)
    }

    @Test
    fun a_single_entry_ring_is_never_pruned() {
        // The spike harness's case, and the common case for any device that has never rotated.
        // If this ever destroyed the only key, every such device would go silently undiscoverable.
        val ring = KeyRing(
            listOf(KeyRingEntry(kid = 1L, accountKey = key(0x33), effDay = 0L, effEpoch = 0)),
        )
        repeat(5) { assertEquals(0, ring.pruneSupersededAt(day = 20_669L, epoch = it * 10)) }
        assertFalse(ring.entries[0].isDestroyed)
        assertIs<ProtocolResult.Success<ByteArray>>(KeySchedule.ephemeralId(ring, 20_669L, 40))
    }
}
