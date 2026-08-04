package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * JVM-side unit tests for the Android radio's pure helpers.
 *
 * SCOPE WARNING, read before adding to this file: nothing about actual radio behaviour can be
 * tested here. There is no Robolectric in this module on purpose — a BLE "pass" on a JVM harness
 * is worthless (root CLAUDE.md), and having one invites someone to cite it. Advertising, scanning,
 * RPA rotation, duty cycling and battery are proven on real handsets, Samsung + Pixel minimum,
 * or they are not proven.
 *
 * B5 is closed; these run.
 */
class ServiceUuidTest {

    @Test
    fun the_provisional_spike_uuid_expands_to_the_value_in_the_spec() {
        // SPEC.md §4.1. 0xFDA9 is PROVISIONAL, NOT SIG-ALLOCATED and MUST NOT SHIP — it is legal
        // for the lab only. Pinned here so that swapping it for the real allocation is a visible,
        // deliberate diff rather than something someone does in passing.
        assertEquals("0000FDA9-0000-1000-8000-00805F9B34FB", uuid128From16("FDA9"))
    }

    @Test
    fun the_android_address_type_screen_reads_the_top_two_bits() {
        // KEY_SCHEDULE.md §4.3.2. 0b01 = resolvable private (the only good answer),
        // 0b00 = non-resolvable private (acceptable), 0b11 = static random (FAIL).
        assertEquals(0b01, addressTypeBitsOf("6A:1B:2C:3D:4E:5F"))
        assertEquals(0b00, addressTypeBitsOf("2A:1B:2C:3D:4E:5F"))
        assertEquals(0b11, addressTypeBitsOf("DA:1B:2C:3D:4E:5F"))
        assertEquals(-1, addressTypeBitsOf(""))

        // AND THE CAVEAT, pinned so nobody forgets it: a PUBLIC address with an OUI in 0x40-0x7F
        // is indistinguishable from a resolvable private address by these bits alone, because
        // Android never exposes the TxAdd bit. The catastrophic case is the one this cannot see.
        // 00:1A:11:… is a real public OUI (Google) and reads as 0b00 here, i.e. "acceptable".
        assertEquals(0b00, addressTypeBitsOf("00:1A:11:00:00:00"))
    }

    @Test
    fun expands_a_16_bit_uuid_into_the_sig_base_uuid() {
        // Getting the offset wrong here produces an app that advertises on a UUID nobody scans
        // for. It fails silently and looks exactly like "BLE is unreliable".
        assertEquals("0000FD6F-0000-1000-8000-00805F9B34FB", uuid128From16("FD6F"))
        assertEquals("0000FEED-0000-1000-8000-00805F9B34FB", uuid128From16("feed"))
    }

    @Test
    fun rejects_anything_that_is_not_a_16_bit_uuid() {
        assertFailsWith<IllegalArgumentException> { uuid128From16("FD6") }
        assertFailsWith<IllegalArgumentException> { uuid128From16("FD6FA") }
        assertFailsWith<IllegalArgumentException> { uuid128From16("ZZZZ") }
    }
}
