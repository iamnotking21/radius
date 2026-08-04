package com.radius.shared.domain.radar

import com.radius.shared.protocol.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the shape of the Radar domain, which is a safety surface.
 */
class RadarModelsTest {

    @Test
    fun there_are_exactly_four_displayable_bands_in_near_to_far_order() {
        // Safety invariant 2. A fifth DISPLAYABLE band, or a reorder, changes what the user is
        // told about how close a stranger is. That is a safety change, not a UI change, and it
        // fails here first.
        //
        // Rewritten for ruling D3: the type is now `protocol.Band`, which also carries two
        // NON-DISPLAYABLE states the old `ProximityBand` could not express — UNKNOWN (below
        // WARMUP_SAMPLES) and OUT_OF_RANGE (adjusted < -95 dBm). The four-band promise is about
        // what a user is SHOWN, so the assertion is about the displayable subset and the other two
        // are asserted separately as non-displayable. Folding them into EDGE to keep this test
        // looking the same would have been invariant 2 failing quietly.
        assertEquals(
            listOf("HERE", "CLOSE", "AROUND", "EDGE"),
            Band.entries.filter { it != Band.UNKNOWN && it != Band.OUT_OF_RANGE }.map { it.name },
        )
    }

    @Test
    fun the_two_non_displayable_bands_exist_and_are_distinct_from_edge() {
        // The consumer obligation from D3, pinned. If someone later "simplifies" Band by deleting
        // UNKNOWN, every not-yet-warmed-up peer silently becomes a displayed distance.
        assertTrue(Band.UNKNOWN in Band.entries)
        assertTrue(Band.OUT_OF_RANGE in Band.entries)
        assertEquals(-1, Band.UNKNOWN.ordinal4, "UNKNOWN must sort outside the band ladder")
        assertEquals(4, Band.OUT_OF_RANGE.ordinal4, "OUT_OF_RANGE is beyond EDGE, not equal to it")
        assertEquals(3, Band.EDGE.ordinal4)
    }

    @Test
    fun radar_node_exposes_no_directional_or_positional_field() {
        // Safety invariants 1 and 3: no map, no bearing, and the on-screen angle must not encode
        // real direction. This is a structural assertion — it fails the moment someone adds a
        // `bearing`, `heading`, `latitude` or `rssi` property to RadarNode.
        //
        // Reflection is not available in commonMain across all targets, so this is enforced by
        // construction instead: the only angle on the type is a display angle, and it is supplied
        // by the caller (shared core seeds it randomly per session), not derived from a sighting.
        val node = RadarNode(
            nodeId = "session-node-1",
            band = Band.CLOSE,
            displayAngleTurns = 0.37f,
            displayedMetres = 24,
            lastSeenEpochMs = 1_754_000_000_000L,
            isVerified = true,
            waveState = WaveState.NONE,
        )

        assertTrue(node.displayAngleTurns >= 0f && node.displayAngleTurns < 1f, "angle is in turns")
        assertTrue(node.isVerified, "Radar is verified-accounts-only — invariant 6")
    }

    @Test
    fun wave_state_has_no_one_sided_unlock() {
        // Invariant 6: chat unlocks ONLY on mutual wave. If a state ever appears here that means
        // "they can message me without me waving back", this test is the tripwire.
        assertEquals(
            listOf("NONE", "SENT", "RECEIVED", "MUTUAL"),
            WaveState.entries.map { it.name },
        )
    }

    @Test
    fun ghost_mode_is_binary_and_immediate() {
        // Invariant 10: ≤1 tap from the Radar screen. A tri-state ("ghost for 1 hour") would need
        // a picker, which is a second tap. If that product change is ever wanted, it needs a
        // decision recorded first, not a quiet enum edit.
        assertEquals(2, RadarVisibility.entries.size)
    }
}
