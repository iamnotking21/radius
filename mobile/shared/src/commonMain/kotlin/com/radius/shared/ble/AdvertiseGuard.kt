package com.radius.shared.ble

import com.radius.shared.protocol.AdvertiseRole

/**
 * THE FAIL-CLOSED GATE ON TRANSMISSION. One implementation, called by BOTH `actual` radios.
 *
 * 40-contracts CONSISTENCY GAP 3 asks that one-advertiser-per-account (decision 35) be
 * *enforceable in code*. Two hand-written copies of "if role != ADVERTISE return" — one in
 * Kotlin/JVM, one in Kotlin/Native, neither tested because neither can be instantiated without a
 * platform — is not enforcement, it is two places to get it wrong. So the decision itself is a
 * pure function in `commonMain`: no `Context`, no `CBPeripheralManager`, no Robolectric, and
 * therefore actually covered by tests that mean something.
 *
 * The radios keep the plumbing. This object keeps the rules:
 *
 *  1. **No ADVERTISE role ⇒ no transmission.** The default (`SCAN_ONLY`) refuses.
 *  2. **No frame for this epoch ⇒ silence, not the last frame.** `E_NO_ACTIVE_KEY`
 *     (`KEY_SCHEDULE.md` §8) means the device has nothing legitimate to say. Repeating the
 *     previous epoch's identity against a possibly-rotated MAC is the §4.1 bridging attack
 *     committed by our own code — strictly worse than being invisible for fifteen minutes.
 *  3. **Wrong length ⇒ refuse.** `SPEC.md` §3: exactly 19 bytes, "not 18, not 20". The radio does
 *     not decode the frame, but it will not put a malformed one on air. GAP 1 of the same
 *     consistency check.
 *
 * Order matters and is fixed: role BEFORE frame. A `SCAN_ONLY` device must never invoke
 * [AdvertisePayloadSource] at all — deriving an ephemeral id it is forbidden to broadcast is
 * pointless work, and on a device that should be silent it is the wrong shape entirely.
 */
internal object AdvertiseGuard {

    /**
     * `SPEC.md` §3. Exactly nineteen — taken from ble-protocol's codec rather than restated, so
     * the radio's length check and the decoder's cannot disagree.
     */
    const val FRAME_BYTES: Int = com.radius.shared.protocol.BleFrameCodec.FRAME_LENGTH_BYTES

    fun checkRole(role: AdvertiseRole): BleOutcome = when (role) {
        AdvertiseRole.ADVERTISE -> BleOutcome.Ok
        AdvertiseRole.SCAN_ONLY ->
            BleOutcome.Rejected(BleOutcome.Reason.ROLE_SCAN_ONLY, null)
    }

    fun checkFrame(frame: ByteArray?, dayIndex: Long, epochIndex: Int): BleOutcome = when {
        frame == null -> BleOutcome.Rejected(
            BleOutcome.Reason.NO_PAYLOAD_FOR_EPOCH,
            "day=$dayIndex epoch=$epochIndex",
        )

        frame.size != FRAME_BYTES -> BleOutcome.Rejected(
            BleOutcome.Reason.FRAME_LENGTH,
            "${frame.size}B, expected $FRAME_BYTES",
        )

        else -> BleOutcome.Ok
    }

    /**
     * `flags` bit0, CONNECTABLE (`SPEC.md` §3.3).
     *
     * Read so that the link-layer PDU type is derived from the transmitter's own on-air claim
     * rather than configured separately beside it. A radio that advertises `CONNECTABLE = 1` in
     * the frame while emitting `ADV_NONCONN_IND` is lying on air, and §3.3 notes the flags byte is
     * already carrying about one bit of platform tell that we are consciously paying for — we do
     * not get to be sloppy with the rest of it.
     *
     * This is ONE BIT and it is not a decode. `mobile/shared/protocol/` owns frame parsing and a
     * second parser is the divergence ADR-007 exists to prevent. If this function ever wants to
     * look at byte 1, stop and call the codec instead.
     */
    fun connectable(frame: ByteArray): Boolean =
        frame.size == FRAME_BYTES && (frame[18].toInt() and 0x01) != 0
}
