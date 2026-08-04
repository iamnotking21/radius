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
 * !! UNVERIFIED !! Never executed — no JDK on the authoring machine (blocker B5).
 */
class ServiceUuidTest {

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
