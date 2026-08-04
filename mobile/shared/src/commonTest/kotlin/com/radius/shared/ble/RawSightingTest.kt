package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These are not placeholder tests. They pin two things that are easy to break by accident and
 * expensive to notice later.
 *
 * !! UNVERIFIED !! Never executed — no JDK on the authoring machine (blocker B5).
 */
class RawSightingTest {

    private val payload = byteArrayOf(
        0x01,                                                                     // ver
        0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,                           // ephemeral_id[0..7]
        0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19,                           // ephemeral_id[8..15]
        0xF4.toByte(),                                                            // txpower
        0x00,                                                                     // flags
    )

    @Test
    fun toString_never_leaks_the_ephemeral_id() {
        // An ephemeral id is a 15-minute pseudonym. The moment it lands in a log line, a crash
        // report or a bug attachment it stops being ephemeral and becomes a durable record that
        // links sessions to a person. Safety invariants 4 and 5 depend on it staying out of text.
        val rendered = RawSighting(payload, rssiDbm = -63, observedAtEpochMs = 1_754_000_000_000L)
            .toString()

        assertFalse(rendered.contains("0A"), "raw payload byte leaked into toString(): $rendered")
        assertFalse(rendered.contains("10, 11"), "raw payload leaked into toString(): $rendered")
        assertTrue(rendered.contains("redacted"), "toString() must state that it redacted: $rendered")
        assertTrue(rendered.contains("-63"), "rssi is not sensitive and is useful in logs")
    }

    @Test
    fun equality_compares_payload_contents_not_array_identity() {
        // ByteArray uses reference equality by default. Dedupe and distinct() on the sighting
        // stream silently stop working if this regresses, and the symptom is a battery bug in the
        // field rather than a failing test.
        val a = RawSighting(payload.copyOf(), rssiDbm = -63, observedAtEpochMs = 1L)
        val b = RawSighting(payload.copyOf(), rssiDbm = -63, observedAtEpochMs = 1L)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun advertisement_payload_fits_the_26_byte_budget() {
        // root CLAUDE.md HARD NUMBERS: adv payload ≤26B. The real encoder lives in
        // mobile/shared/protocol/ (ble-protocol) and its conformance vectors are the authority.
        // This only guards the fixture used above from drifting into fantasy.
        assertTrue(payload.size <= 26, "advertisement payload budget exceeded: ${payload.size}B")
        assertEquals(19, payload.size, "[ver:1][ephemeral_id:16][txpower:1][flags:1] = 19 bytes")
    }
}
