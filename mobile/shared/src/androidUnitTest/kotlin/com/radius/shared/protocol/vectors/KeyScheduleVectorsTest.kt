package com.radius.shared.protocol.vectors

import com.radius.shared.protocol.KeyRing
import com.radius.shared.protocol.KeyRingEntry
import com.radius.shared.protocol.KeySchedule
import com.radius.shared.protocol.ProtocolError
import com.radius.shared.protocol.ProtocolResult
import kotlin.test.Test

/**
 * `vectors/key_schedule.json` — HKDF-SHA256 known answers generated with OpenSSL 3.5.6 and
 * re-derived independently with Node `crypto.hkdfSync`. Two generators, one expected value.
 *
 * These are the KATs that make the hand-written HKDF in `Crypto.kt` acceptable rather than merely
 * plausible. If any case here goes red, the correct response is to request a crypto dependency,
 * not to adjust the implementation until the numbers match.
 */
class KeyScheduleVectorsTest {

    /** A well-formed single-entry ring effective from the beginning of time. */
    private fun ringOf(accountKeyHex: String): KeyRing =
        KeyRing(listOf(KeyRingEntry(kid = 0, accountKey = hexToBytes(accountKeyHex), effDay = 0, effEpoch = 0)))

    @Test
    fun hkdfKnownAnswers() {
        val file = VectorSource.load("key_schedule.json")
        val cases = file.arr("cases").map { it.asObj() }
        val run = VectorRun("key_schedule/cases")

        for (c in cases) {
            run.case(c.str("name")) {
                val accountKey = hexToBytes(c.str("account_key_hex"))
                val unixSeconds = c.long("unix_seconds")
                val day = c.long("day_index")
                val epoch = c.int("epoch_index")

                // The day/epoch arithmetic itself is part of the contract.
                expectEquals(day, KeySchedule.dayIndex(unixSeconds), "day_index from unix_seconds")
                expectEquals(epoch, KeySchedule.epochIndex(unixSeconds), "epoch_index from unix_seconds")

                expectEquals(
                    c.str("expect_daily_key_hex"),
                    bytesToHex(KeySchedule.dailyKey(accountKey, day)),
                    "daily_key",
                )
                expectEquals(
                    c.str("expect_ephemeral_id_hex"),
                    bytesToHex(KeySchedule.ephemeralIdFor(accountKey, day, epoch)),
                    "ephemeral_id",
                )

                // And through the PUBLIC ring API, which is the only path production code has.
                val viaRing = KeySchedule.ephemeralId(ringOf(c.str("account_key_hex")), day, epoch)
                expectTrue(viaRing is ProtocolResult.Success, "ring derivation failed: $viaRing")
                expectEquals(
                    c.str("expect_ephemeral_id_hex"),
                    bytesToHex((viaRing as ProtocolResult.Success).value),
                    "ephemeral_id via ring",
                )
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun rotationPropertyAssertions() {
        val file = VectorSource.load("key_schedule.json")
        val byName = file.arr("cases").map { it.asObj() }.associateBy { it.str("name") }
        val assertions = file.arr("property_assertions").map { it.asObj() }
        val run = VectorRun("key_schedule/property_assertions")

        fun eidOf(name: String): String {
            val c = byName.getValue(name)
            return bytesToHex(
                KeySchedule.ephemeralIdFor(hexToBytes(c.str("account_key_hex")), c.long("day_index"), c.int("epoch_index")),
            )
        }

        fun dailyKeyOf(name: String): String {
            val c = byName.getValue(name)
            return bytesToHex(KeySchedule.dailyKey(hexToBytes(c.str("account_key_hex")), c.long("day_index")))
        }

        for (a in assertions) {
            run.case(a.str("name")) {
                var checks = 0
                a.arrOrEmpty("equal_ephemeral_id").map { it as String }.takeIf { it.isNotEmpty() }?.let { names ->
                    checks++
                    val values = names.map(::eidOf)
                    expectTrue(values.distinct().size == 1, "expected identical eids for $names, got $values")
                }
                a.arrOrEmpty("differ_ephemeral_id").map { it as String }.takeIf { it.isNotEmpty() }?.let { names ->
                    checks++
                    val values = names.map(::eidOf)
                    expectTrue(values.distinct().size == names.size, "expected distinct eids for $names, got $values")
                }
                a.arrOrEmpty("differ_daily_key").map { it as String }.takeIf { it.isNotEmpty() }?.let { names ->
                    checks++
                    val values = names.map(::dailyKeyOf)
                    expectTrue(values.distinct().size == names.size, "expected distinct daily keys for $names")
                }
                // A property assertion that asserts nothing is a silent hole in the net.
                expectTrue(checks > 0, "assertion '${a.str("name")}' declared no comparable key")
            }
        }
        run.finish(assertions.size)
    }

    @Test
    fun invalidInputsFault() {
        val file = VectorSource.load("key_schedule.json")
        val cases = file.arr("invalid").map { it.asObj() }
        val run = VectorRun("key_schedule/invalid")

        val validKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        for (c in cases) {
            run.case(c.str("name")) {
                val expected = ProtocolError.valueOf(c.str("expect_error"))
                val ring = ringOf(c.strOrNull("account_key_hex") ?: validKeyHex)
                val epoch = if (c.has("epoch_index")) c.int("epoch_index") else 0

                // MUST FAULT, MUST NOT CLAMP. An epoch index of 96 or -1 means a clock bug, and
                // clamping it would silently derive the wrong identity for that device.
                val ringError = KeySchedule.validateRing(ring)
                val actual = ringError ?: when (val r = KeySchedule.ephemeralId(ring, 0, epoch)) {
                    is ProtocolResult.Failure -> r.error
                    is ProtocolResult.Success -> null
                }
                expectEquals(expected, actual, "error code")
            }
        }
        run.finish(cases.size)
    }
}
