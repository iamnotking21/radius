package com.radius.shared.protocol.vectors

/*
 * A ~150-line JSON reader for the conformance vector runner.
 *
 * WHY NOT A LIBRARY. The version catalog carries `kotlinx-serialization-core` but NOT
 * `kotlinx-serialization-json`, and `mobile/shared/build.gradle.kts` belongs to android-kotlin.
 * Adding a dependency is an ORCHESTRATION §8 escalation and not this agent's call, and it would
 * mean editing a file another agent is working in this session (§6). So the runner reads its own
 * JSON.
 *
 * SCOPE. Test-source-set only. It never ships, never touches the wire, and never sees key
 * material. It is exercised by every vector file on every CI run, so a parsing bug shows up as a
 * loud failure, not as a silent wrong answer. This is emphatically NOT the same category of risk
 * as the hand-written SHA-256 in commonMain, which is validated against published KATs.
 */

internal object MiniJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val v = p.readValue()
        p.skipWhitespace()
        require(p.atEnd()) { "trailing content at offset ${p.offset}" }
        return v
    }

    private class Parser(private val src: String) {
        var offset: Int = 0

        fun atEnd(): Boolean = offset >= src.length

        fun skipWhitespace() {
            while (offset < src.length && src[offset].isWhitespace()) offset++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(offset < src.length) { "unexpected end of input" }
            return when (val c = src[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(c == '-' || c.isDigit()) { "unexpected char '$c' at $offset" }
                    readNumber()
                }
            }
        }

        private fun readLiteral(token: String, value: Any?): Any? {
            require(src.startsWith(token, offset)) { "bad literal at $offset" }
            offset += token.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { offset++; return out }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return out
                    else -> throw IllegalArgumentException("expected , or } at ${offset - 1}, got '$c'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { offset++; return out }
            while (true) {
                out.add(readValue())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return out
                    else -> throw IllegalArgumentException("expected , or ] at ${offset - 1}, got '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val esc = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(offset, offset + 4)
                            offset += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("bad escape \\$esc at ${offset - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        /**
         * Integral values become `Long`, fractional/exponent values become `Double`. Keeping day
         * indices and epoch indices as exact integers matters — a Double round-trip of a large
         * `unix_seconds` is a silent conformance bug waiting to happen.
         */
        private fun readNumber(): Any {
            val start = offset
            if (peek() == '-') offset++
            var fractional = false
            var previous = ' '
            while (offset < src.length) {
                val c = src[offset]
                if (c.isDigit()) { previous = c; offset++; continue }
                if (c == '.' || c == 'e' || c == 'E') {
                    fractional = true
                    previous = c
                    offset++
                    continue
                }
                // '+'/'-' belong to the number ONLY as an exponent sign.
                if ((c == '+' || c == '-') && (previous == 'e' || previous == 'E')) {
                    previous = c
                    offset++
                    continue
                }
                break
            }
            val token = src.substring(start, offset)
            return if (fractional) token.toDouble() else token.toLong()
        }

        private fun peek(): Char = src[offset]

        private fun next(): Char = src[offset++]

        private fun expect(c: Char) {
            require(offset < src.length && src[offset] == c) { "expected '$c' at $offset" }
            offset++
        }
    }
}

// --- typed accessors ---------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.asArr(): List<Any?> = this as List<Any?>

internal fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key].asObj()

internal fun Map<String, Any?>.arr(key: String): List<Any?> = this[key].asArr()

internal fun Map<String, Any?>.arrOrEmpty(key: String): List<Any?> =
    if (this[key] == null) emptyList() else this[key].asArr()

internal fun Map<String, Any?>.str(key: String): String = this[key] as String

internal fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as String?

internal fun Map<String, Any?>.long(key: String): Long = when (val v = this[key]) {
    is Long -> v
    is Double -> v.toLong()
    else -> error("not a number: $key = $v")
}

internal fun Map<String, Any?>.int(key: String): Int = long(key).toInt()

internal fun Map<String, Any?>.dbl(key: String): Double = when (val v = this[key]) {
    is Long -> v.toDouble()
    is Double -> v
    else -> error("not a number: $key = $v")
}

internal fun Map<String, Any?>.bool(key: String): Boolean = this[key] as Boolean

internal fun Map<String, Any?>.has(key: String): Boolean = containsKey(key) && this[key] != null

// --- hex ---------------------------------------------------------------------------------------

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "odd-length hex: '$hex'" }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = ((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte()
    }
    return out
}

internal fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("not a hex digit: '$c'")
}
