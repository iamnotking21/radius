package com.radius.shared.protocol

/**
 * How a peer's frame reached us. `SPEC.md` §5, decision 23.
 *
 * ONE frame format, TWO carriers. The frame does not degrade; it changes carrier. Under
 * [GATT_PULL] the *entire* 19-byte frame lives byte-identical in a GATT characteristic, so
 * `version`, `tx_power_cal` and `flags` all keep their home and invariant 4 holds exactly as
 * written.
 */
public enum class Carrier {
    /** Carrier A. Frame is in the Service Data AD structure. Passive. Android peripherals. */
    SERVICE_DATA,

    /**
     * Carrier B. Only the service UUID is on air; the frame must be fetched by connecting and
     * reading `beacon_payload`. iOS peripherals — `CoreBluetooth` cannot transmit Service Data or
     * Manufacturer Data in ANY app state. This is an API limit, not a background restriction.
     *
     * COST, not yet priced on hardware: a GATT connection per peer per epoch, and the SCANNER has
     * to transmit, which costs the scanner its anonymity. DEFERRED with iOS (decision 33) — which
     * does not make it cheaper, only later.
     */
    GATT_PULL,

    /** Not a Radius advertisement. Ignore it entirely; do not create a peer instance. */
    NONE,
}

/**
 * What a scanner learned from one advertisement.
 *
 * @property carrier how (or whether) a frame is obtainable.
 * @property frame the decoded frame, present only for [Carrier.SERVICE_DATA].
 */
public class AdvScanResult(
    public val carrier: Carrier,
    public val frame: BleFrame?,
) {
    override fun toString(): String = "AdvScanResult(carrier=$carrier, frame=${if (frame == null) "none" else "present"})"
}

/**
 * Advertising-data assembly and parsing. `SPEC.md` §5.1.
 *
 * BUDGET (legacy advertisement, 31 bytes):
 * ```
 *  02 01 06                    Flags AD
 *  03 03 A9 FD                 Complete List of 16-bit Service UUIDs (little-endian on air)
 *  16 16 A9 FD <19 bytes>      Service Data - 16-bit UUID (len 0x16 = 22)
 *  ------------------------
 *  30 of 31 bytes used. The 7 bytes between the 19-byte frame and root CLAUDE.md's 26 B ceiling
 *  are AD-header margin. They are NOT spendable.
 * ```
 */
public object AdvertisementCodec {

    /**
     * PROVISIONAL. NOT SIG-ALLOCATED. MUST NOT SHIP (decision 34, blocker B9).
     *
     * A 128-bit self-allocated UUID cannot substitute here: Carrier A already uses 30 of 31
     * advertisement bytes with a 16-bit UUID, and 128-bit costs 16 more. A SIG 16-bit allocation
     * is therefore mandatory, not preferred, and the sequence is legal entity → SIG Adopter →
     * allocation. The CI release gate is what stops this value shipping while we wait.
     */
    public const val SERVICE_UUID16: Int = 0xFDA9

    private const val AD_TYPE_FLAGS = 0x01
    private const val AD_TYPE_INCOMPLETE_UUID16 = 0x02
    private const val AD_TYPE_COMPLETE_UUID16 = 0x03
    private const val AD_TYPE_SERVICE_DATA_UUID16 = 0x16

    private const val AD_FLAGS_VALUE = 0x06 // LE General Discoverable | BR/EDR Not Supported

    /** Carrier A advertisement length: 3 (flags) + 4 (uuid list) + 23 (service data) = 30 bytes. */
    public const val CARRIER_A_LENGTH_BYTES: Int = 30

    /** Legacy advertising payload budget. */
    public const val LEGACY_ADV_MAX_BYTES: Int = 31

    /** Build the full Carrier A advertising payload for [frame]. */
    public fun buildAdvertisement(frame: BleFrame): ProtocolResult<ByteArray> {
        val encoded = when (val r = BleFrameCodec.encode(frame)) {
            is ProtocolResult.Failure -> return r
            is ProtocolResult.Success -> r.value
        }
        val out = ByteArray(CARRIER_A_LENGTH_BYTES)
        var i = 0
        // Flags AD
        out[i++] = 0x02
        out[i++] = AD_TYPE_FLAGS.toByte()
        out[i++] = AD_FLAGS_VALUE.toByte()
        // Complete list of 16-bit service UUIDs, little-endian on air
        out[i++] = 0x03
        out[i++] = AD_TYPE_COMPLETE_UUID16.toByte()
        out[i++] = (SERVICE_UUID16 and 0xFF).toByte()
        out[i++] = ((SERVICE_UUID16 ushr 8) and 0xFF).toByte()
        // Service Data - 16-bit UUID: 1 (type) + 2 (uuid) + 19 (frame) = 22 = 0x16
        out[i++] = 0x16
        out[i++] = AD_TYPE_SERVICE_DATA_UUID16.toByte()
        out[i++] = (SERVICE_UUID16 and 0xFF).toByte()
        out[i++] = ((SERVICE_UUID16 ushr 8) and 0xFF).toByte()
        encoded.copyInto(out, i)
        return ProtocolResult.Success(out)
    }

    /**
     * Parse a received advertising payload.
     *
     * HOSTILE INPUT IS THE NORMAL CASE. This runs against every beacon in the room, including
     * other vendors' and including deliberately malformed ones. Rules:
     *
     *  - An AD length that runs past the end of the buffer is `E_AD_MALFORMED`, detected BEFORE
     *    any out-of-bounds read. This is the classic BLE parser overrun.
     *  - A zero length byte TERMINATES parsing (padding convention). It must not loop forever.
     *  - TWO Service Data structures for our UUID is `E_AD_MALFORMED`, not "pick one". A
     *    conformant peripheral never emits it, and picking one is an attacker-chosen branch.
     *  - Foreign service data is ignored entirely, never parsed as ours.
     */
    public fun parseAdvertisement(bytes: ByteArray): ProtocolResult<AdvScanResult> {
        var ourServiceData: ByteArray? = null
        var ourServiceDataCount = 0
        var ourUuidAdvertised = false

        var pos = 0
        while (pos < bytes.size) {
            val length = bytes[pos].toInt() and 0xFF
            if (length == 0) break // padding terminator — stop, do not treat as malformed
            // length counts the AD type byte plus the value bytes.
            if (pos + 1 + length > bytes.size) {
                return ProtocolResult.Failure(ProtocolError.E_AD_MALFORMED)
            }
            val adType = bytes[pos + 1].toInt() and 0xFF
            val valueStart = pos + 2
            val valueEnd = pos + 1 + length

            when (adType) {
                AD_TYPE_COMPLETE_UUID16, AD_TYPE_INCOMPLETE_UUID16 -> {
                    var u = valueStart
                    while (u + 1 < valueEnd) {
                        val uuid = (bytes[u].toInt() and 0xFF) or ((bytes[u + 1].toInt() and 0xFF) shl 8)
                        if (uuid == SERVICE_UUID16) ourUuidAdvertised = true
                        u += 2
                    }
                }

                AD_TYPE_SERVICE_DATA_UUID16 -> {
                    if (valueEnd - valueStart < 2) {
                        return ProtocolResult.Failure(ProtocolError.E_AD_MALFORMED)
                    }
                    val uuid = (bytes[valueStart].toInt() and 0xFF) or
                        ((bytes[valueStart + 1].toInt() and 0xFF) shl 8)
                    if (uuid == SERVICE_UUID16) {
                        ourServiceDataCount++
                        ourServiceData = bytes.copyOfRange(valueStart + 2, valueEnd)
                    }
                }
            }
            pos = valueEnd
        }

        if (ourServiceDataCount > 1) {
            return ProtocolResult.Failure(ProtocolError.E_AD_MALFORMED)
        }

        val payload = ourServiceData
        if (payload != null) {
            return when (val decoded = BleFrameCodec.decode(payload)) {
                is ProtocolResult.Failure -> decoded
                is ProtocolResult.Success ->
                    ProtocolResult.Success(AdvScanResult(Carrier.SERVICE_DATA, decoded.value))
            }
        }
        if (ourUuidAdvertised) {
            // Carrier B: nothing on air but the UUID. Connect and read beacon_payload.
            return ProtocolResult.Success(AdvScanResult(Carrier.GATT_PULL, null))
        }
        return ProtocolResult.Success(AdvScanResult(Carrier.NONE, null))
    }
}
