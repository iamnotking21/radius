package com.radius.shared.protocol

/**
 * Every rejection code the BLE protocol can produce, in ONE flat namespace.
 *
 * `SPEC.md` §8 names two enums (`DecodeError`, `KeyError`) and `vectors/index.json` names a single
 * flat `error_codes` list of thirteen. The vectors win: a conformance case says
 * `"expect_error": "E_SHORT_FRAME"` and that string must map to exactly one constant with no
 * ambiguity about which enum it lives in. Two enums would make the vector runner guess.
 *
 * Codes are the wire-stable contract, not the ordinals. Never reorder for cosmetics — the runner
 * matches on `name`.
 *
 * DIAGNOSTIC RULE (SPEC.md §3.6, KEY_SCHEDULE.md §9.6): an implementation MAY keep a per-epoch
 * COUNTER per code. It MUST NOT log, transmit, or attach the payload that produced it. A rejected
 * frame is dropped silently: no peer instance, no UI, no analytics event.
 */
public enum class ProtocolError {
    // --- frame decode (SPEC.md §3.6), in validation order ---
    E_SHORT_FRAME,
    E_LONG_FRAME,
    E_UNSUPPORTED_VERSION,
    E_TX_POWER_NOT_ALLOWED,
    E_RESERVED_FLAG_SET,
    E_EPHEMERAL_ID_RESERVED,

    // --- advertisement assembly (SPEC.md §5) ---
    E_AD_MALFORMED,
    E_MTU_TOO_SMALL,

    // --- key schedule + key ring (KEY_SCHEDULE.md §1, §8.2, §9.6) ---
    E_EPOCH_INDEX_OUT_OF_RANGE,
    E_ACCOUNT_KEY_LENGTH,
    E_NO_ACTIVE_KEY,
    E_KEY_RING_NOT_MONOTONIC,
    E_SELF_EID,
}

/**
 * Result of a protocol operation. Deliberately NOT `kotlin.Result` and deliberately not an
 * exception.
 *
 * Malformed advertisements are ORDINARY, not exceptional — a scan in a busy place produces a
 * continuous stream of other vendors' beacons and truncated frames. Throwing on each one would be
 * both slow and semantically wrong, and an exception crossing the Kotlin/Native boundary is a hard
 * crash rather than a `throws`.
 *
 * IOS NOTE (owed at iOS un-deferral, decision 33): a generic sealed class exports to ObjC as
 * `ProtocolResult<AnyObject>`, which is usable but ugly. If ios-swift wants concrete non-generic
 * result types instead, that is a gated contract change, not a unilateral one.
 */
public sealed class ProtocolResult<out T> {

    public class Success<out T>(public val value: T) : ProtocolResult<T>()

    public class Failure(public val error: ProtocolError) : ProtocolResult<Nothing>()
}
