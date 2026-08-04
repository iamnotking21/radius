package com.radius.android.spike

import com.radius.shared.protocol.KeyRing
import com.radius.shared.protocol.KeyRingEntry
import java.security.MessageDigest

/**
 * TEST KEY MATERIAL FOR THE PHASE 0 SPIKE. DEBUG SOURCE SET ONLY.
 *
 * ## Why there are keys in source at all
 *
 * `account_key` is server-issued (ADR-008) and there is no server (blocker B3). The spike still
 * has to put a real, correctly-derived `ephemeral_id` on air, because the whole point of B8 is to
 * observe a REAL rotation against a real MAC. So the harness needs a root, and a root that two
 * handsets in a car park can agree on without a network.
 *
 * ## Why it is a slot, not a random key
 *
 * A random per-device key would work for the co-rotation capture, but it would make the field
 * session unverifiable: two people standing in a car park would have no way to confirm that the
 * eid phone A sees is the eid phone B is emitting, as opposed to a stray beacon or a stale packet.
 * With slots, each device knows the other's root, builds the same three-epoch resolution table
 * (`KEY_SCHEDULE.md` §6.1), and the screen can say "resolved: slot 2" instead of "saw something".
 *
 * That check is worth having for a second reason: it is the only test that proves the KEY SCHEDULE
 * AGREES ACROSS TWO PHYSICAL DEVICES over the air. The conformance vectors prove arithmetic on one
 * machine and are explicitly silent about on-air behaviour.
 *
 * ## What must be true about these values, and is
 *
 *  - **They are public by construction.** A SHA-256 of a printable constant. Nobody should ever
 *    mistake one for a real account key, and printing the derivation string in this comment is
 *    deliberate: a secret that looks like a secret is a secret someone will treat as one.
 *  - **They exist only in `src/debug`.** Not behind a flag, not behind a BuildConfig check —
 *    absent from a release APK because the source file is not compiled into it.
 *  - **They are not the production derivation.** The real path is `KEY_SCHEDULE.md` §2.2: issued
 *    once, over a pinned TLS channel, hardware-Keystore-wrapped, never re-fetchable.
 *
 * DELETE WITH THE HARNESS. This file and `mobile/android/src/debug/kotlin/com/radius/android/spike`
 * come out together the day Phase 0 closes.
 */
internal object SpikeKeys {

    /** Slots a field session can use. Two devices minimum; eight is more than the rig has. */
    val SLOTS: IntRange = 1..8

    private const val DERIVATION_PREFIX = "radius-spike-v0/PUBLIC-TEST-KEY/device-slot/"

    /**
     * A deterministic, PUBLIC 32-byte root for [slot].
     *
     * Not an account key. Not secret. Not related to any real account. It exists so two handsets
     * derive matching ephemeral ids with no server and no pairing step.
     */
    fun accountKeyForSlot(slot: Int): ByteArray {
        require(slot in SLOTS) { "spike slot out of range: $slot" }
        return MessageDigest.getInstance("SHA-256")
            .digest("$DERIVATION_PREFIX$slot".toByteArray(Charsets.UTF_8))
    }

    /**
     * A single-entry ring effective from the beginning of time.
     *
     * ONE ENTRY, NOT ZERO AND NOT TWO, and the reason is worth stating: the ring's interesting
     * behaviour is the SEAM (`KEY_SCHEDULE.md` §8.4), and a seam is a rotation event we cannot
     * schedule without a server. Faking one here would exercise our own fiction rather than the
     * real path. `vectors/key_rotation.json` covers the seam arithmetically; the spike covers the
     * radio. Neither pretends to cover the other.
     */
    fun ringForSlot(slot: Int): KeyRing = KeyRing(
        listOf(
            KeyRingEntry(
                kid = slot.toLong(),
                accountKey = accountKeyForSlot(slot),
                effDay = 0L,
                effEpoch = 0,
            ),
        ),
    )
}
