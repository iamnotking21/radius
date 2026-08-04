package com.radius.shared.protocol.vectors

import com.radius.shared.protocol.Sha256
import com.radius.shared.protocol.constantTimeEquals
import com.radius.shared.protocol.hkdfSha256
import com.radius.shared.protocol.hmacSha256
import kotlin.test.Test

/**
 * PUBLISHED known-answer tests for the hand-written primitives in `Crypto.kt`.
 *
 * These are NOT protocol vectors — they are FIPS 180-4, RFC 4231 and RFC 5869, i.e. answers
 * produced by the standards bodies, not by us. They exist because "we implemented SHA-256 in
 * commonMain to avoid a dependency escalation" is only defensible if the implementation is proved
 * against something we did not generate.
 *
 * The protocol vectors in `key_schedule.json` (OpenSSL 3.5.6 + Node `crypto.hkdfSync`) are the
 * second, independent check. Both must pass. If either fails, the correct fix is to request a
 * crypto dependency from the orchestrator, NOT to tune this file until it agrees.
 */
class CryptoKatTest {

    @Test
    fun sha256FipsKnownAnswers() {
        val run = VectorRun("kat/sha256")

        run.case("empty") {
            expectEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                bytesToHex(Sha256.digest(ByteArray(0))),
                "sha256(\"\")",
            )
        }
        run.case("abc") {
            expectEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                bytesToHex(Sha256.digest("abc".encodeToByteArray())),
                "sha256(\"abc\")",
            )
        }
        run.case("two-block") {
            expectEquals(
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                bytesToHex(
                    Sha256.digest(
                        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray(),
                    ),
                ),
                "sha256(two-block message)",
            )
        }
        // Exercises the length-encoding path and multi-chunk accumulation.
        run.case("million-a") {
            val block = ByteArray(1000) { 'a'.code.toByte() }
            val message = ByteArray(1_000_000)
            for (i in 0 until 1000) block.copyInto(message, i * 1000)
            expectEquals(
                "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
                bytesToHex(Sha256.digest(message)),
                "sha256(\"a\" x 1000000)",
            )
        }
        // Length 55/56/64 straddle the padding boundary — the classic off-by-one in a hand
        // implementation, and invisible if you only ever hash short strings.
        run.case("padding-boundaries") {
            expectEquals(
                "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
                bytesToHex(Sha256.digest(ByteArray(55) { 'a'.code.toByte() })),
                "sha256(55 x 'a')",
            )
            expectEquals(
                "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
                bytesToHex(Sha256.digest(ByteArray(56) { 'a'.code.toByte() })),
                "sha256(56 x 'a')",
            )
            expectEquals(
                "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
                bytesToHex(Sha256.digest(ByteArray(64) { 'a'.code.toByte() })),
                "sha256(64 x 'a')",
            )
        }
        run.finish(5)
    }

    @Test
    fun hmacSha256Rfc4231KnownAnswers() {
        val run = VectorRun("kat/hmac-sha256")

        run.case("rfc4231-tc1") {
            expectEquals(
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                bytesToHex(hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())),
                "TC1",
            )
        }
        run.case("rfc4231-tc2") {
            expectEquals(
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                bytesToHex(
                    hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()),
                ),
                "TC2",
            )
        }
        // Key LONGER than the 64-byte block: exercises the hash-the-key branch.
        run.case("rfc4231-tc6-long-key") {
            expectEquals(
                "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
                bytesToHex(
                    hmacSha256(
                        ByteArray(131) { 0xaa.toByte() },
                        "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                    ),
                ),
                "TC6",
            )
        }
        run.finish(3)
    }

    @Test
    fun hkdfSha256Rfc5869KnownAnswers() {
        val run = VectorRun("kat/hkdf-sha256")

        run.case("rfc5869-tc1") {
            val okm = hkdfSha256(
                ikm = ByteArray(22) { 0x0b },
                salt = hexToBytes("000102030405060708090a0b0c"),
                info = hexToBytes("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            )
            expectEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                bytesToHex(okm),
                "TC1 OKM",
            )
        }
        // Long IKM / salt / info, 82 bytes of output => multiple expand rounds, which is the part
        // of HKDF that is easy to get wrong and that our own 16/32-byte derivations never exercise.
        run.case("rfc5869-tc2-multi-round") {
            val okm = hkdfSha256(
                ikm = ByteArray(80) { it.toByte() },
                salt = ByteArray(80) { (0x60 + it).toByte() },
                info = ByteArray(80) { (0xb0 + it).toByte() },
                length = 82,
            )
            expectEquals(
                "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
                bytesToHex(okm),
                "TC2 OKM",
            )
        }
        run.case("rfc5869-tc3-empty-salt-and-info") {
            val okm = hkdfSha256(
                ikm = ByteArray(22) { 0x0b },
                salt = ByteArray(0),
                info = ByteArray(0),
                length = 42,
            )
            expectEquals(
                "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                bytesToHex(okm),
                "TC3 OKM",
            )
        }
        run.finish(3)
    }

    @Test
    fun constantTimeEqualsBehavesLikeEquality() {
        val a = hexToBytes("00112233445566778899aabbccddeeff")
        expectTrue(constantTimeEquals(a, a.copyOf()), "identical arrays must compare equal")
        expectTrue(!constantTimeEquals(a, ByteArray(16)), "different arrays must not compare equal")
        expectTrue(!constantTimeEquals(a, ByteArray(15)), "different lengths must not compare equal")
        val nearlyIdentical = a.copyOf().also { it[15] = (it[15].toInt() xor 0x01).toByte() }
        expectTrue(!constantTimeEquals(a, nearlyIdentical), "last-byte difference must be detected")
    }
}
