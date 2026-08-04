package com.radius.shared.protocol.vectors

import com.radius.shared.protocol.AdvertisementCodec
import com.radius.shared.protocol.BleFrame
import com.radius.shared.protocol.BleFrameCodec
import com.radius.shared.protocol.Carrier
import com.radius.shared.protocol.ProtocolError
import com.radius.shared.protocol.ProtocolResult
import kotlin.test.Test

/**
 * `vectors/frame_valid.json`, `vectors/frame_invalid.json`, `vectors/adv_assembly.json`.
 *
 * A GREEN RUN HERE PROVES THE ARITHMETIC AND THE PARSER. It says NOTHING about on-air behaviour —
 * not discovery latency, not battery, not band accuracy, and above all not RPA co-rotation, which
 * is the measurement that actually decides Phase 0. Do not report a vector pass as spike validation.
 */
class FrameVectorsTest {

    @Test
    fun frameValidVectorsRoundTrip() {
        val file = VectorSource.load("frame_valid.json")
        val cases = file.arr("cases")
        val run = VectorRun("frame_valid")

        for (raw in cases) {
            val c = raw.asObj()
            run.case(c.str("name")) {
                val fields = c.obj("fields")
                val hex = c.str("hex")

                // --- decode direction ---
                val decoded = BleFrameCodec.decode(hexToBytes(hex))
                expectTrue(decoded is ProtocolResult.Success, "decode failed: $decoded")
                val frame = (decoded as ProtocolResult.Success).value
                expectEquals(fields.int("version"), frame.version, "version")
                expectEquals(fields.str("ephemeral_id_hex"), bytesToHex(frame.ephemeralId()), "ephemeral_id")
                expectEquals(fields.int("tx_power_cal_dbm"), frame.txPowerCalDbm, "tx_power_cal")
                expectEquals(fields.bool("connectable"), frame.connectable, "connectable")

                // --- encode direction: MUST reproduce the hex byte-for-byte ---
                val rebuilt = BleFrameCodec.encode(
                    BleFrame(
                        version = fields.int("version"),
                        ephemeralId = hexToBytes(fields.str("ephemeral_id_hex")),
                        txPowerCalDbm = fields.int("tx_power_cal_dbm"),
                        connectable = fields.bool("connectable"),
                    ),
                )
                expectTrue(rebuilt is ProtocolResult.Success, "encode failed: $rebuilt")
                expectEquals(hex, bytesToHex((rebuilt as ProtocolResult.Success).value), "re-encoded hex")
                expectEquals(19, rebuilt.value.size, "frame length")
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun frameInvalidVectorsAreRejectedWithTheStatedCode() {
        val file = VectorSource.load("frame_invalid.json")
        val cases = file.arr("cases")
        val run = VectorRun("frame_invalid")

        for (raw in cases) {
            val c = raw.asObj()
            run.case(c.str("name")) {
                val expected = ProtocolError.valueOf(c.str("expect_error"))
                val result = BleFrameCodec.decode(hexToBytes(c.str("hex")))
                expectTrue(result is ProtocolResult.Failure, "expected rejection $expected, got $result")
                // The CODE matters, not just the rejection. Validation order is normative
                // (SPEC.md §3.6) so that a multi-fault frame produces the same error on both
                // platforms; a decoder that rejects for the wrong reason fails conformance.
                expectEquals(expected, (result as ProtocolResult.Failure).error, "error code")
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun advAssemblyVectors() {
        val file = VectorSource.load("adv_assembly.json")
        val cases = file.arr("cases")
        val run = VectorRun("adv_assembly")

        for (raw in cases) {
            val c = raw.asObj()
            run.case(c.str("name")) {
                val expect = c.obj("expect")
                val result = AdvertisementCodec.parseAdvertisement(hexToBytes(c.str("adv_hex")))

                if (expect.has("expect_error")) {
                    val expected = ProtocolError.valueOf(expect.str("expect_error"))
                    expectTrue(result is ProtocolResult.Failure, "expected $expected, got $result")
                    expectEquals(expected, (result as ProtocolResult.Failure).error, "error code")
                    return@case
                }

                expectTrue(result is ProtocolResult.Success, "expected success, got $result")
                val scan = (result as ProtocolResult.Success).value
                expectEquals(Carrier.valueOf(expect.str("carrier")), scan.carrier, "carrier")

                val frameHex = expect.strOrNull("frame_hex")
                if (frameHex == null) {
                    expectTrue(scan.frame == null, "expected no frame on air, got one")
                } else {
                    val frame = scan.frame ?: throw AssertionError("expected a frame, got none")
                    val reencoded = BleFrameCodec.encode(frame)
                    expectTrue(reencoded is ProtocolResult.Success, "re-encode failed: $reencoded")
                    expectEquals(frameHex, bytesToHex((reencoded as ProtocolResult.Success).value), "frame_hex")

                    val fields = expect.obj("fields")
                    expectEquals(fields.int("version"), frame.version, "version")
                    expectEquals(fields.str("ephemeral_id_hex"), bytesToHex(frame.ephemeralId()), "ephemeral_id")
                    expectEquals(fields.int("tx_power_cal_dbm"), frame.txPowerCalDbm, "tx_power_cal")
                    expectEquals(fields.bool("connectable"), frame.connectable, "connectable")
                }

                // Carrier A cases must also ASSEMBLE to the exact advertisement in the vector.
                if (scan.carrier == Carrier.SERVICE_DATA && scan.frame != null) {
                    val built = AdvertisementCodec.buildAdvertisement(scan.frame!!)
                    expectTrue(built is ProtocolResult.Success, "build failed: $built")
                    expectEquals(c.str("adv_hex"), bytesToHex((built as ProtocolResult.Success).value), "adv_hex")
                    expectEquals(c.int("adv_length_bytes"), built.value.size, "adv_length_bytes")
                }
            }
        }
        run.finish(cases.size)
    }
}
