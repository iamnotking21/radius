package com.radius.shared.protocol

/*
 * SHA-256 / HMAC-SHA256 / HKDF-SHA256 — hand-implemented, commonMain, zero dependencies.
 *
 * WHY THIS EXISTS AT ALL. KEY_SCHEDULE.md §1 mandates HKDF-SHA256 in the shared module. The
 * shared module is commonMain Kotlin, which has no crypto in its stdlib, and the version catalog
 * carries no multiplatform crypto library. Adding one is an ORCHESTRATION §8 escalation and not
 * this agent's call. So this file exists.
 *
 * WHY IT IS ACCEPTABLE HERE, AND WHERE THE LINE IS.
 *   - SHA-256 is a fixed, fully specified, unkeyed compression function with published test
 *     vectors. There is no key material inside it, no branch on a secret, no modular arithmetic
 *     to get subtly wrong, and no randomness. HMAC and HKDF are likewise pure byte plumbing over
 *     it (RFC 2104, RFC 5869).
 *   - It is validated against published KATs (FIPS 180-4 / RFC 4231 / RFC 5869) in
 *     CryptoKatTest, AND against the OpenSSL-generated protocol vectors in
 *     vectors/key_schedule.json and vectors/display_jitter.json. Two independent generators.
 *   - THE LINE: this file must never grow a cipher, a signature scheme, a key exchange, or a
 *     ratchet. Those are vodozemac's job (root CLAUDE.md). If anyone extends this file with
 *     anything that has a secret-dependent branch or a big-integer, that is a review block.
 *
 * SIDE CHANNELS. Not constant-time by construction beyond what the algorithm already is: SHA-256
 * has no data-dependent branches or table lookups, so the compression function is naturally
 * uniform. `constantTimeEquals` below is used for every comparison against derived identity
 * material. Do not replace it with `contentEquals`.
 *
 * ZEROING POLICY. Every heap buffer in this file that holds key material or output keying
 * material is filled with zeros as soon as it is dead — including `outer` (key XOR 0x5C),
 * `innerHash`, the HKDF `input`/`previous` blocks, and SHA-256's `padded`/`w`, which carry HMAC's
 * key-derived first block. This is BEST EFFORT, NOT A GUARANTEE: a moving garbage collector may
 * have relocated any of these arrays already, and a copy we cannot name is a copy we cannot zero.
 * It is required anyway — it removes from a heap dump every buffer we CAN name, and the cost is a
 * few hundred byte-writes per derivation.
 */

/** SHA-256, FIPS 180-4. Internal: nothing outside this module derives its own hashes. */
internal object Sha256 {

    internal const val BLOCK_SIZE_BYTES: Int = 64
    internal const val DIGEST_SIZE_BYTES: Int = 32

    private val K: IntArray = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    internal fun digest(message: ByteArray): ByteArray {
        var h0 = 0x6a09e667.toInt()
        var h1 = 0xbb67ae85.toInt()
        var h2 = 0x3c6ef372.toInt()
        var h3 = 0xa54ff53a.toInt()
        var h4 = 0x510e527f.toInt()
        var h5 = 0x9b05688c.toInt()
        var h6 = 0x1f83d9ab.toInt()
        var h7 = 0x5be0cd19.toInt()

        val padded = pad(message)
        val w = IntArray(64)

        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val b = offset + i * 4
                w[i] = ((padded[b].toInt() and 0xFF) shl 24) or
                    ((padded[b + 1].toInt() and 0xFF) shl 16) or
                    ((padded[b + 2].toInt() and 0xFF) shl 8) or
                    (padded[b + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0
            var b2 = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (i in 0 until 64) {
                val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + bigS1 + ch + K[i] + w[i]
                val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b2) xor (a and c) xor (b2 and c)
                val temp2 = bigS0 + maj

                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b2
                b2 = a
                a = temp1 + temp2
            }

            h0 += a; h1 += b2; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            offset += BLOCK_SIZE_BYTES
        }

        val out = ByteArray(DIGEST_SIZE_BYTES)
        writeIntBe(out, 0, h0); writeIntBe(out, 4, h1)
        writeIntBe(out, 8, h2); writeIntBe(out, 12, h3)
        writeIntBe(out, 16, h4); writeIntBe(out, 20, h5)
        writeIntBe(out, 24, h6); writeIntBe(out, 28, h7)
        // SHA-256 alone is unkeyed, but HMAC hands us `key XOR ipad/opad` as the FIRST BLOCK of
        // the message. `padded` is a full copy of it and `w` is its message schedule, so both hold
        // key-derived bytes on every derivation in this protocol. Zeroed for the same reason and
        // with the same best-effort caveat as hmacSha256; the local ints h0..h7/a..h are beyond
        // our reach and are state, not key.
        padded.fill(0)
        w.fill(0)
        return out
    }

    private fun pad(message: ByteArray): ByteArray {
        val originalLen = message.size
        var withOneBit = originalLen + 1
        while (withOneBit % BLOCK_SIZE_BYTES != 56) withOneBit++
        val padded = ByteArray(withOneBit + 8)
        message.copyInto(padded)
        padded[originalLen] = 0x80.toByte()
        val bitLen = originalLen.toLong() * 8L
        for (i in 0 until 8) {
            padded[withOneBit + i] = ((bitLen ushr (56 - 8 * i)) and 0xFF).toByte()
        }
        return padded
    }

    private fun rotr(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))

    private fun writeIntBe(target: ByteArray, at: Int, value: Int) {
        target[at] = (value ushr 24).toByte()
        target[at + 1] = (value ushr 16).toByte()
        target[at + 2] = (value ushr 8).toByte()
        target[at + 3] = value.toByte()
    }
}

/** HMAC-SHA256, RFC 2104. */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val blockKey = ByteArray(Sha256.BLOCK_SIZE_BYTES)
    if (key.size > Sha256.BLOCK_SIZE_BYTES) {
        // Unreachable for this protocol — every key here is 32 bytes or the 13-byte salt — but
        // reachable from the RFC 4231 known-answer tests, and the temporary holds a hash OF THE
        // KEY, which is a valid HMAC key in its own right. Zero it too.
        val hashedKey = Sha256.digest(key)
        hashedKey.copyInto(blockKey)
        hashedKey.fill(0)
    } else {
        key.copyInto(blockKey)
    }

    val inner = ByteArray(Sha256.BLOCK_SIZE_BYTES + message.size)
    for (i in 0 until Sha256.BLOCK_SIZE_BYTES) {
        inner[i] = (blockKey[i].toInt() xor 0x36).toByte()
    }
    message.copyInto(inner, Sha256.BLOCK_SIZE_BYTES)
    val innerHash = Sha256.digest(inner)

    val outer = ByteArray(Sha256.BLOCK_SIZE_BYTES + Sha256.DIGEST_SIZE_BYTES)
    for (i in 0 until Sha256.BLOCK_SIZE_BYTES) {
        outer[i] = (blockKey[i].toInt() xor 0x5C).toByte()
    }
    innerHash.copyInto(outer, Sha256.BLOCK_SIZE_BYTES)
    val mac = Sha256.digest(outer)

    // ZERO EVERY INTERMEDIATE, AND `outer` ABOVE ALL. Its first 64 bytes are key XOR 0x5C, i.e.
    // the KEY under a public constant — trivially invertible. `inner` is the same key under 0x36.
    // In HKDF-Expand the key IS the PRK, so a retained `outer` yields every daily_key and every
    // ephemeral_id for that account_key: the whole schedule, not one message. `inner` and
    // `blockKey` were already zeroed; leaving the one buffer that carries the KEY rather than the
    // MESSAGE was an oversight, found in review 2026-08-04.
    //
    // BEST EFFORT, NOT A GUARANTEE. On a moving-collector JVM/ART the GC may have copied any of
    // these arrays before we reach this line, and we cannot zero a copy we cannot name. This costs
    // a few hundred byte-writes per HMAC and removes the buffers we CAN name from a heap dump.
    // Do not delete it because it is not airtight; nothing at this layer is.
    blockKey.fill(0)
    inner.fill(0)
    innerHash.fill(0)
    outer.fill(0)
    return mac
}

/**
 * HKDF-SHA256, RFC 5869, extract-then-expand. [length] is the output length in bytes.
 *
 * KEY_SCHEDULE.md §1 uses this for both derivations; the salt is the protocol domain separator
 * and is NOT secret.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length > 0 && length <= 255 * Sha256.DIGEST_SIZE_BYTES) { "HKDF L out of range" }

    val prk = hmacSha256(salt, ikm)

    val out = ByteArray(length)
    var previous = ByteArray(0)
    var produced = 0
    var counter = 1
    while (produced < length) {
        val input = ByteArray(previous.size + info.size + 1)
        previous.copyInto(input)
        info.copyInto(input, previous.size)
        input[input.size - 1] = counter.toByte()
        val block = hmacSha256(prk, input)
        // `input` carries T(n-1) and `previous` IS T(n-1) — both are output keying material, not
        // public parameters. Zero each as soon as it is dead, then adopt the new block. Same
        // best-effort caveat as hmacSha256.
        input.fill(0)
        previous.fill(0)
        previous = block
        val take = minOf(block.size, length - produced)
        block.copyInto(out, produced, 0, take)
        produced += take
        counter++
    }
    previous.fill(0)
    prk.fill(0)
    return out
}

/**
 * Length-independent, content-constant-time byte comparison.
 *
 * Used for EVERY comparison against derived identity material (ephemeral ids). `contentEquals`
 * short-circuits on the first differing byte, which is a timing oracle an on-device attacker can
 * use to grind an ephemeral id byte by byte. Cheap to do right; do not "simplify".
 */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
