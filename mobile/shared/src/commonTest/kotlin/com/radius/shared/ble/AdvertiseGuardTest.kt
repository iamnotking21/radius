package com.radius.shared.ble

import com.radius.shared.protocol.AdvertiseRole

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The one-advertiser-per-account gate, decision 35 / `KEY_SCHEDULE.md` §9.3.
 *
 * WHY THESE TESTS EXIST AND WHY THEY ARE IN `commonTest`: the gate cannot be tested through
 * `BleRadio`, because neither `actual` can be instantiated without a platform, and Robolectric is
 * deliberately absent from this module (a BLE "pass" on a JVM harness is worthless and having one
 * invites someone to cite it). Pulling the DECISION out of the plumbing is what makes the safety
 * rule testable at all — and it runs on both targets, so the two platforms provably enforce the
 * same rule rather than two similar-looking ones.
 *
 * These tests prove the rules. They prove nothing about radios.
 */
class AdvertiseGuardTest {

    private val validFrame = ByteArray(19).also { it[0] = 0x01 }

    @Test
    fun scan_only_is_the_default_ordinal() {
        // Ordinal 0 is the value a zeroed struct, a defaulted field, a fresh enum decode and the
        // ObjC bridge all land on. Fail-closed means the SAFE value must be the accidental one.
        // If someone reorders this enum "alphabetically", ADVERTISE becomes the default state of
        // every uninitialised path in the system. That is a one-line diff with a very large blast
        // radius, so it is pinned here.
        assertEquals(AdvertiseRole.SCAN_ONLY, AdvertiseRole.entries[0])
        assertEquals(0, AdvertiseRole.SCAN_ONLY.ordinal)
    }

    @Test
    fun scan_only_refuses_to_transmit() {
        val outcome = AdvertiseGuard.checkRole(AdvertiseRole.SCAN_ONLY)
        val rejected = assertIs<BleOutcome.Rejected>(outcome)
        assertEquals(BleOutcome.Reason.ROLE_SCAN_ONLY, rejected.reason)
    }

    @Test
    fun advertise_authority_permits_transmission() {
        assertEquals(BleOutcome.Ok, AdvertiseGuard.checkRole(AdvertiseRole.ADVERTISE))
    }

    @Test
    fun a_missing_frame_produces_silence_not_a_stale_broadcast() {
        // E_NO_ACTIVE_KEY. Repeating the previous epoch's identity against a MAC that may already
        // have rotated is the KEY_SCHEDULE §4.1 bridging attack performed by our own code. Being
        // invisible for fifteen minutes is strictly better.
        val outcome = AdvertiseGuard.checkFrame(null, dayIndex = 20_669, epochIndex = 41)
        val rejected = assertIs<BleOutcome.Rejected>(outcome)
        assertEquals(BleOutcome.Reason.NO_PAYLOAD_FOR_EPOCH, rejected.reason)
        assertTrue(rejected.detail?.contains("epoch=41") == true)
    }

    @Test
    fun the_frame_is_exactly_nineteen_bytes_not_eighteen_not_twenty() {
        // SPEC.md §3, quoted: "exactly 19 bytes. Not 18, not 20."
        assertEquals(BleOutcome.Ok, AdvertiseGuard.checkFrame(validFrame, 0, 0))

        for (size in intArrayOf(0, 1, 18, 20, 26)) {
            val rejected = assertIs<BleOutcome.Rejected>(
                AdvertiseGuard.checkFrame(ByteArray(size), 0, 0),
                "a ${size}B frame must not reach the air",
            )
            assertEquals(BleOutcome.Reason.FRAME_LENGTH, rejected.reason)
        }
    }

    @Test
    fun connectable_is_read_from_flags_bit0_and_nothing_else() {
        // SPEC.md §3.3. The PDU type is derived from the frame's own claim so the two cannot
        // disagree on air.
        val nonConnectable = ByteArray(19).also { it[18] = 0x00 }
        val connectable = ByteArray(19).also { it[18] = 0x01 }

        assertFalse(AdvertiseGuard.connectable(nonConnectable))
        assertTrue(AdvertiseGuard.connectable(connectable))

        // Reserved bits 1-7 must not be mistaken for CONNECTABLE. The codec rejects a frame with
        // them set (E_RESERVED_FLAG_SET); this layer must at least not misread one.
        assertFalse(AdvertiseGuard.connectable(ByteArray(19).also { it[18] = 0xFE.toByte() }))
        assertTrue(AdvertiseGuard.connectable(ByteArray(19).also { it[18] = 0xFF.toByte() }))
    }
}
