package com.radius.shared.protocol

/**
 * THE 19-BYTE FRAME. `SPEC.md` §3, `40-contracts` "BLE wire v0.1", safety invariant 4.
 *
 * ```
 *  offset  size  field
 *    0       1   version        uint8, v0 protocol => 0x01
 *    1      16   ephemeral_id   bytes, derivation order, NEVER byte-swapped
 *   17       1   tx_power_cal   int8, dBm, one of exactly seven legal values
 *   18       1   flags          uint8, bit0 CONNECTABLE, bits1-7 reserved MUST be 0
 * ```
 *
 * There are NO reserved bytes and there never will be. Reserved bytes are a covert channel: five
 * spare bytes is forty bits per frame of somewhere for a future engineer — or a compromised build
 * — to put a stable identifier, and invariant 4 would be dead without one line of the spec
 * changing. The version byte is the entire extension mechanism.
 */
public class BleFrame(
    public val version: Int,
    ephemeralId: ByteArray,
    public val txPowerCalDbm: Int,
    public val connectable: Boolean,
) {
    private val eid: ByteArray = ephemeralId.copyOf()

    /**
     * The 16-byte rotating pseudonym. A DEFENSIVE COPY is returned on every access so a consumer
     * cannot mutate the frame's identity in place.
     *
     * `ByteArray`, never `String` — ruling R-C in `40-contracts`. A hex `String` is log-shaped: it
     * survives interpolation, lands in crash reports, analytics and the debug console. `ByteArray`
     * is hostile to accidental logging and that hostility is the feature.
     */
    public fun ephemeralId(): ByteArray = eid.copyOf()

    /**
     * REDACTED ON PURPOSE. The ephemeral id is a 15-minute pseudonym; printing it turns a rotating
     * value into a durable record the moment anything captures stdout. Same reasoning as
     * `RawSighting.toString`. Do not "improve" this for debugging.
     */
    override fun toString(): String =
        "BleFrame(v=$version, eid=<16B redacted>, txCal=$txPowerCalDbm, connectable=$connectable)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleFrame) return false
        return version == other.version &&
            txPowerCalDbm == other.txPowerCalDbm &&
            connectable == other.connectable &&
            constantTimeEquals(eid, other.eid)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + eid.contentHashCode()
        result = 31 * result + txPowerCalDbm
        result = 31 * result + connectable.hashCode()
        return result
    }
}

/**
 * Encode and decode the 19-byte frame. One implementation, two platforms (ADR-007).
 *
 * Validation is IDENTICAL on transmit and receive, on purpose. `SPEC.md` §3.5 requires the
 * reserved ephemeral ids to be rejected in both directions: a device that broadcasts `00…00`
 * because it advertised before its key ring loaded is trivially recognisable on air, and it is
 * cheaper to make that unencodable than to explain it later.
 */
public object BleFrameCodec {

    public const val FRAME_LENGTH_BYTES: Int = 19
    public const val PROTOCOL_VERSION: Int = 0x01
    public const val EPHEMERAL_ID_LENGTH_BYTES: Int = 16

    private const val OFFSET_VERSION = 0
    private const val OFFSET_EPHEMERAL_ID = 1
    private const val OFFSET_TX_POWER = 17
    private const val OFFSET_FLAGS = 18

    private const val FLAG_CONNECTABLE = 0x01
    private const val FLAGS_RESERVED_MASK = 0xFE

    /**
     * `SPEC.md` §3.2. Exactly seven values. An allow-list, not a range and not a clamp — rejecting
     * off-grid values is what closes this byte as a covert channel. Fine-grained calibration is
     * also a device-model fingerprint that survives every ephemeral-id rotation, which would make
     * rotation decorative.
     */
    private val LEGAL_TX_POWER_DBM: IntArray = intArrayOf(-75, -70, -65, -60, -55, -50, -45)

    /** True if [dbm] is on the seven-value calibration grid. Transmitters MUST clamp before encoding. */
    public fun isLegalTxPowerCal(dbm: Int): Boolean {
        for (v in LEGAL_TX_POWER_DBM) if (v == dbm) return true
        return false
    }

    /** The seven legal `tx_power_cal` values, closest-first. Exposed so callers can clamp correctly. */
    public fun legalTxPowerCalDbm(): IntArray = LEGAL_TX_POWER_DBM.copyOf()

    /** Clamp a measured 1 m calibration to the nearest legal grid value (`SPEC.md` §3.2). */
    public fun clampTxPowerCal(measuredDbm: Int): Int {
        var best = LEGAL_TX_POWER_DBM[0]
        var bestDistance = Int.MAX_VALUE
        for (v in LEGAL_TX_POWER_DBM) {
            val d = if (v > measuredDbm) v - measuredDbm else measuredDbm - v
            if (d < bestDistance) {
                bestDistance = d
                best = v
            }
        }
        return best
    }

    /**
     * Serialise to exactly 19 bytes, applying the same validation as [decode].
     *
     * Fails rather than emits for: unsupported version, off-grid `tx_power_cal`, reserved
     * ephemeral id. There is no way to construct a non-conformant advertisement through this API.
     */
    public fun encode(frame: BleFrame): ProtocolResult<ByteArray> {
        if (frame.version != PROTOCOL_VERSION) {
            return ProtocolResult.Failure(ProtocolError.E_UNSUPPORTED_VERSION)
        }
        val eid = frame.ephemeralId()
        if (eid.size != EPHEMERAL_ID_LENGTH_BYTES) {
            return ProtocolResult.Failure(ProtocolError.E_EPHEMERAL_ID_RESERVED)
        }
        if (!isLegalTxPowerCal(frame.txPowerCalDbm)) {
            return ProtocolResult.Failure(ProtocolError.E_TX_POWER_NOT_ALLOWED)
        }
        if (isReservedEphemeralId(eid)) {
            return ProtocolResult.Failure(ProtocolError.E_EPHEMERAL_ID_RESERVED)
        }

        val out = ByteArray(FRAME_LENGTH_BYTES)
        out[OFFSET_VERSION] = frame.version.toByte()
        eid.copyInto(out, OFFSET_EPHEMERAL_ID)
        out[OFFSET_TX_POWER] = frame.txPowerCalDbm.toByte()
        out[OFFSET_FLAGS] = (if (frame.connectable) FLAG_CONNECTABLE else 0).toByte()
        return ProtocolResult.Success(out)
    }

    /**
     * Parse 19 bytes into a frame.
     *
     * VALIDATION ORDER IS NORMATIVE (`SPEC.md` §3.6). A multi-fault frame must produce the same
     * error on Android and on iOS, or the conformance vectors are meaningless as a divergence
     * detector. The order is: length, version, tx_power_cal, flags, ephemeral_id — return on
     * first failure.
     *
     * Bits 1-7 of `flags` are REJECTED, not ignored. "Ignore unknown bits" is normally good
     * protocol hygiene; here it is a seven-bit-per-frame covert channel that every conformant
     * receiver would silently tolerate. Forward compatibility comes from the version byte only.
     */
    public fun decode(bytes: ByteArray): ProtocolResult<BleFrame> {
        // 1. length
        if (bytes.size < FRAME_LENGTH_BYTES) {
            return ProtocolResult.Failure(ProtocolError.E_SHORT_FRAME)
        }
        if (bytes.size > FRAME_LENGTH_BYTES) {
            return ProtocolResult.Failure(ProtocolError.E_LONG_FRAME)
        }
        // 2. version
        val version = bytes[OFFSET_VERSION].toInt() and 0xFF
        if (version != PROTOCOL_VERSION) {
            return ProtocolResult.Failure(ProtocolError.E_UNSUPPORTED_VERSION)
        }
        // 3. tx_power_cal — signed, so `.toInt()` on the Byte is exactly right here.
        val txPowerCal = bytes[OFFSET_TX_POWER].toInt()
        if (!isLegalTxPowerCal(txPowerCal)) {
            return ProtocolResult.Failure(ProtocolError.E_TX_POWER_NOT_ALLOWED)
        }
        // 4. flags
        val flags = bytes[OFFSET_FLAGS].toInt() and 0xFF
        if ((flags and FLAGS_RESERVED_MASK) != 0) {
            return ProtocolResult.Failure(ProtocolError.E_RESERVED_FLAG_SET)
        }
        // 5. ephemeral_id sentinels
        val eid = bytes.copyOfRange(OFFSET_EPHEMERAL_ID, OFFSET_EPHEMERAL_ID + EPHEMERAL_ID_LENGTH_BYTES)
        if (isReservedEphemeralId(eid)) {
            return ProtocolResult.Failure(ProtocolError.E_EPHEMERAL_ID_RESERVED)
        }

        return ProtocolResult.Success(
            BleFrame(
                version = version,
                ephemeralId = eid,
                txPowerCalDbm = txPowerCal,
                connectable = (flags and FLAG_CONNECTABLE) != 0,
            ),
        )
    }

    /**
     * `00…00` and `FF…FF` are reserved sentinels (`SPEC.md` §3.5) — the two values a broken or
     * uninitialised implementation emits. Collision probability for a real derivation is 2/2^128.
     */
    private fun isReservedEphemeralId(eid: ByteArray): Boolean {
        var allZero = true
        var allOnes = true
        for (b in eid) {
            val v = b.toInt() and 0xFF
            if (v != 0x00) allZero = false
            if (v != 0xFF) allOnes = false
        }
        return allZero || allOnes
    }
}
