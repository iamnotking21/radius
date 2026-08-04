package com.radius.shared.protocol

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * FOUR BANDS. Never a number, never a continuum. Safety invariant 2, ADR-004.
 *
 * Thresholds are applied to the NORMALISED value (`BANDING.md` §1):
 * ```
 * HERE         adjusted >= -55     "in this room"
 * CLOSE        adjusted >= -70     "very close"
 * AROUND       adjusted >= -82     "nearby"
 * EDGE         adjusted >= -95     "somewhere around"
 * OUT_OF_RANGE adjusted <  -95     not displayed
 * UNKNOWN      fewer than 3 accepted samples — MUST NOT be displayed
 * ```
 *
 * Ordinals are used by the hysteresis comparison; LOWER = CLOSER.
 *
 * DUPLICATE-TYPE NOTE, needs an orchestrator ruling: `com.radius.shared.domain.radar.
 * ProximityBand` carries only the four displayable bands and has no `UNKNOWN` or `OUT_OF_RANGE`.
 * Two band enums in one module is exactly the divergence ADR-007 exists to prevent. The right fix
 * is to delete `ProximityBand` and have the radar domain use this type — that is a change to
 * android-kotlin's files, so it is a HANDOFF, not something this agent does.
 */
public enum class Band(public val ordinal4: Int) {
    HERE(0),
    CLOSE(1),
    AROUND(2),
    EDGE(3),
    OUT_OF_RANGE(4),
    UNKNOWN(-1),
}

/** `BANDING.md` §5. A band MUST NEVER be derived from a single reading. */
public enum class Confidence {
    /** `band == UNKNOWN`. MUST NOT be displayed. */
    WARMING,

    /** Displayable, but hedge the copy harder. */
    COARSE,

    /** Displayable. */
    STABLE,

    /** No advertisement for longer than `STALE_MS`. Displayable, MUST be visibly marked. */
    STALE,
}

/**
 * What a consumer is allowed to know about a peer's distance.
 *
 * THERE IS NO RSSI ACCESSOR AND THERE NEVER WILL BE. Not raw, not filtered, not adjusted, not a
 * "debug" variant behind a flag or a build type. `BANDING.md` §6.3 and decision 28:
 *
 *  - Three receivers with raw RSSI MULTILATERATE TO A POSITION. Not a band — a position. A
 *    "nearby people" feature returning raw RSSI to three colluding phones is a location API with
 *    extra steps, and every individual component looks innocent.
 *  - One receiver plus time and motion is nearly as good.
 *  - Sub-band resolution is a fingerprint stable enough to link identities ACROSS an ephemeral-id
 *    rotation, partially undoing the whole key schedule.
 *
 * @property displayMetres MANUFACTURED, not measured. Band midpoint plus a deterministic offset
 *   derived from `(session_salt, local_peer_id, band)` and from NOTHING ELSE. `null` when the band
 *   is not displayable. UI copy MUST hedge it ("about 6 m"), never state it as fact.
 */
public class PeerReading internal constructor(
    public val band: Band,
    public val confidence: Confidence,
    public val displayMetres: Int?,
) {
    override fun toString(): String =
        "PeerReading(band=$band, confidence=$confidence, displayMetres=$displayMetres)"
}

/**
 * RSSI -> validity gate -> outlier gate -> Kalman -> normalise -> band + hysteresis -> display.
 * `BANDING.md` §2-§6. One instance PER PEER PER SESSION.
 *
 * THIS CLASS IS THE RSSI CONTAINMENT BOUNDARY. Raw, filtered and adjusted RSSI live in its private
 * fields and leave only as a [Band]. There is no accessor, no getter, no property, and `toString`
 * carries none of it. If a future change needs "just the dBm for a moment", the answer is no.
 *
 * @param txPowerCalDbm the peer's calibrated 1 m RSSI from the frame, or `null` when unavailable
 *   (fallback F3a). `null` substitutes `TX_REF_DBM` and DEGRADES CONFIDENCE ONE LEVEL, per
 *   `BANDING.md` §3. It MUST NOT be guessed from the platform or from device-model inference —
 *   that would re-create exactly the fingerprint quantisation exists to suppress.
 * @param sessionSalt 32 CSPRNG bytes for the Radar session. Never persisted, never transmitted.
 * @param localPeerId the resolved LOCAL account handle (16 bytes), NOT the ephemeral id. Seeding
 *   the display on the eid would make the number jump every 15 minutes while the peer stood still.
 */
public class BandingPipeline(
    txPowerCalDbm: Int?,
    sessionSalt: ByteArray,
    localPeerId: ByteArray,
) {
    private val txCal: Int = txPowerCalDbm ?: TX_REF_DBM
    private val uncalibrated: Boolean = txPowerCalDbm == null
    private val salt: ByteArray = sessionSalt.copyOf()
    private val peerId: ByteArray = localPeerId.copyOf()

    // --- filter state. PRIVATE. Never widen this to internal-for-convenience. -----------------
    private var x: Double = 0.0
    private var p: Double = 0.0
    private var samples: Int = 0
    private var consecutiveRejects: Int = 0
    private var initialised: Boolean = false

    // --- hysteresis state ---------------------------------------------------------------------
    private var band: Band = Band.UNKNOWN
    private var inCount: Int = 0
    private var outCount: Int = 0

    // --- staleness -----------------------------------------------------------------------------
    private var stale: Boolean = false

    // --- last-step diagnostics, for the conformance runner ONLY --------------------------------
    private var lastAccepted: Boolean = false
    private var lastReinit: Boolean = false

    /**
     * Feed one observed RSSI in dBm. Returns the reading the UI is allowed to see.
     *
     * Returns the CURRENT reading even when the sample is dropped — a rejected outlier changes no
     * state and must not blank the peer out of the UI.
     */
    public fun update(rssiDbm: Int): PeerReading {
        stale = false
        lastAccepted = false
        lastReinit = false

        val z = rssiDbm.toDouble()

        // --- §2.1 validity gate. Some Android stacks report 127 or 0 as "unknown". ------------
        if (z < MIN_PLAUSIBLE_RSSI_DBM || z > MAX_PLAUSIBLE_RSSI_DBM) {
            return current()
        }

        if (!initialised) {
            initialiseFilter(z)
            lastAccepted = true
        } else if (abs(z - x) > OUTLIER_GATE_DB) {
            // --- §2.2 outlier gate, AND THE TRAP IN IT ----------------------------------------
            // The obvious implementation — reject up to three, then ACCEPT ONE and reset the
            // counter — LIVELOCKS. A genuine 38 dB step produces reject, reject, accept-one,
            // counter resets, reject, reject, accept-one... The filter crawls toward the truth at
            // one sample in four while reporting a stale band the whole time. In the reference run
            // that left a peer stuck in CLOSE for eleven samples after the signal had jumped to
            // HERE.
            //
            // The third consecutive outlier is not a sample to accept. It is EVIDENCE THAT THE
            // MODEL IS WRONG, so the filter re-initialises and recovery is bounded at
            // MAX_CONSEC_REJECT samples instead of unbounded.
            consecutiveRejects++
            if (consecutiveRejects < MAX_CONSEC_REJECT) {
                return current()
            }
            initialiseFilter(z)
            inCount = 0
            outCount = 0
            lastAccepted = true
            lastReinit = true
            // Band is deliberately NOT reset to UNKNOWN. Doing so would blank the peer out of the
            // Radar UI every time someone put a phone in a pocket. Confidence drops to COARSE
            // instead (n resets to 1), which is the honest signal without the flicker.
        } else {
            consecutiveRejects = 0
            // --- §2 Kalman, scalar constant-position. ORDER IS NORMATIVE. --------------------
            p += Q
            val k = p / (p + R)
            x += k * (z - x)
            p = (1.0 - k) * p
            samples++
            lastAccepted = true
        }

        applyHysteresis()
        return current()
    }

    /**
     * Report elapsed silence for this peer. `BANDING.md` §5. Past `STALE_MS` the reading is still
     * displayable but MUST be visibly marked; past `DROP_MS` the CALLER removes the peer entirely
     * (this class does not own peer lifetime).
     */
    public fun onSilence(millisSinceLastSighting: Long): PeerReading {
        stale = millisSinceLastSighting > STALE_MS
        return current()
    }

    /** True once silence has exceeded `DROP_MS`; the caller must drop the peer. */
    public fun shouldDrop(millisSinceLastSighting: Long): Boolean = millisSinceLastSighting > DROP_MS

    /** The current reading, recomputed from state. Cheap, deterministic, and free of RSSI. */
    public fun current(): PeerReading =
        PeerReading(band = band, confidence = confidence(), displayMetres = displayMetres())

    // ---------------------------------------------------------------------------------------

    private fun initialiseFilter(z: Double) {
        x = z
        p = R
        samples = 1
        consecutiveRejects = 0
        initialised = true
    }

    /** `adjusted = x + (TX_REF_DBM - tx_power_cal)`. Filter first, normalise second (§3). */
    private fun adjusted(): Double = x + (TX_REF_DBM - txCal).toDouble()

    private fun rawBand(adjusted: Double): Band = when {
        adjusted >= THRESHOLD_HERE -> Band.HERE
        adjusted >= THRESHOLD_CLOSE -> Band.CLOSE
        adjusted >= THRESHOLD_AROUND -> Band.AROUND
        adjusted >= THRESHOLD_EDGE -> Band.EDGE
        else -> Band.OUT_OF_RANGE
    }

    private fun thresholdOf(b: Band): Double = when (b) {
        Band.HERE -> THRESHOLD_HERE
        Band.CLOSE -> THRESHOLD_CLOSE
        Band.AROUND -> THRESHOLD_AROUND
        Band.EDGE -> THRESHOLD_EDGE
        Band.OUT_OF_RANGE -> Double.NEGATIVE_INFINITY
        Band.UNKNOWN -> Double.NEGATIVE_INFINITY
    }

    /**
     * §4. Hysteresis is a PRIVACY control as much as a UI one: a band flapping several times a
     * second is a high-resolution signal about position that banding was supposed to destroy.
     *
     * Note the asymmetry in which threshold each branch tests. Promotion tests the CANDIDATE
     * band's threshold, demotion tests the CURRENT band's. That puts the dead zone between the
     * bands rather than inside one, and lets a two-band jump promote straight to the right band.
     */
    private fun applyHysteresis() {
        val adj = adjusted()
        val raw = rawBand(adj)

        if (band == Band.UNKNOWN) {
            // §4.2 ACQUISITION USES THE RAW THRESHOLDS, WITH NO MARGIN.
            // An earlier draft applied the promotion margin here too. The reference run showed
            // that shifts every effective threshold by 3 dB — HERE would begin at -52, not -55 —
            // silently redefining ADR-004's numbers, and it is a one-way ratchet: a peer acquired
            // at a steady -68 lands in AROUND and can NEVER promote to CLOSE. Permanently wrong,
            // in the direction that looks conservative. Fidelity to the ADR wins.
            if (samples >= WARMUP_SAMPLES) {
                band = raw
                inCount = 0
                outCount = 0
            }
            return
        }

        if (raw.ordinal4 < band.ordinal4) {
            // candidate is CLOSER — promote. Deliberately harder than demote (§4.1): HERE means
            // "in this room", and over-claiming closeness is the error with product consequences.
            if (adj >= thresholdOf(raw) + H_IN_DB) inCount++ else inCount = 0
            outCount = 0
            if (inCount >= K_IN) {
                band = raw
                inCount = 0
            }
        } else if (raw.ordinal4 > band.ordinal4) {
            // candidate is FARTHER — demote
            if (adj < thresholdOf(band) - H_OUT_DB) outCount++ else outCount = 0
            inCount = 0
            if (outCount >= K_OUT) {
                band = raw
                outCount = 0
            }
        } else {
            inCount = 0
            outCount = 0
        }
    }

    private fun confidence(): Confidence {
        if (stale) return Confidence.STALE
        if (band == Band.UNKNOWN) return Confidence.WARMING
        val base = if (samples >= STABLE_SAMPLES) Confidence.STABLE else Confidence.COARSE
        // §3: an uncalibrated peer is degraded one level. It can land a full band wrong.
        return if (uncalibrated && base == Confidence.STABLE) Confidence.COARSE else base
    }

    private fun displayMetres(): Int? = DisplayJitter.metresFor(salt, peerId, band)

    /**
     * CONFORMANCE-RUNNER ONLY. `internal`, so it is off the contract, absent from the Swift/ObjC
     * header, and a compile error for `:android`, `:ios` and every ordinary consumer.
     *
     * WHAT IT IS NOT: unreachable. Kotlin `internal` is a COMPILE-TIME property of the Kotlin
     * compiler, NOT JVM access control. On Kotlin/Native it is enforced in the binary; on the JVM
     * this method is emitted as PUBLIC with a mangled name, and the 2026-08-04 review proved the
     * point by calling `conformanceState$shared_debug()` from a foreign compilation unit with no
     * reflection and no friend path. So: `internal` here means "not part of the contract, and loud
     * when you cross it". It does not mean "cannot be called from app code on Android".
     *
     * That distinction does not change the ruling that this accessor stays (decision 42). What it
     * changes is the JUSTIFICATION: this is safe to keep because what it returns is an RSSI-derived
     * value the app has no way to ATTRIBUTE — reaching it requires deliberately naming a mangled
     * symbol, which is a review-visible act, and the value is bounded to one already-observed
     * peer. It is not safe because the keyword forbids it. If something must be genuinely
     * unreachable, `internal` is the wrong tool.
     *
     * It exists because `vectors/banding.json` asserts `adjusted_dbm` per step, and those
     * assertions are what pin the two bugs the reference run caught (outlier-gate livelock,
     * warm-up margin shifting the ADR thresholds). A vector whose expectations cannot be checked
     * is not a vector.
     *
     * This is NOT the "debug accessor" `BANDING.md` §6.3 forbids. That rule is about an accessor
     * that is PART OF THE APP'S SURFACE — public, or build-flagged on, and therefore callable in
     * the ordinary course of writing UI code. This one is on nobody's completion list and cannot
     * be reached without naming a mangled symbol on purpose. DO NOT widen it to `public`, and do
     * not add a call to it from non-test code.
     */
    internal fun conformanceState(): ConformanceState = ConformanceState(
        accepted = lastAccepted,
        filterReinit = lastReinit,
        samples = samples,
        adjustedDbm = adjusted(),
        band = band,
        confidence = confidence(),
    )

    internal class ConformanceState(
        val accepted: Boolean,
        val filterReinit: Boolean,
        val samples: Int,
        val adjustedDbm: Double,
        val band: Band,
        val confidence: Confidence,
    )

    public companion object {
        /** Reference calibration the band thresholds are defined against. */
        public const val TX_REF_DBM: Int = -60

        internal const val Q: Double = 1.0
        internal const val R: Double = 16.0
        internal const val WARMUP_SAMPLES: Int = 3
        internal const val STABLE_SAMPLES: Int = 10
        internal const val OUTLIER_GATE_DB: Double = 20.0
        internal const val MAX_CONSEC_REJECT: Int = 3
        internal const val H_IN_DB: Double = 3.0
        internal const val H_OUT_DB: Double = 2.0
        internal const val K_IN: Int = 3
        internal const val K_OUT: Int = 2

        internal const val STALE_MS: Long = 30_000L
        internal const val DROP_MS: Long = 90_000L

        internal const val THRESHOLD_HERE: Double = -55.0
        internal const val THRESHOLD_CLOSE: Double = -70.0
        internal const val THRESHOLD_AROUND: Double = -82.0
        internal const val THRESHOLD_EDGE: Double = -95.0

        internal const val MIN_PLAUSIBLE_RSSI_DBM: Double = -127.0
        internal const val MAX_PLAUSIBLE_RSSI_DBM: Double = 20.0
    }
}

/**
 * `BANDING.md` §6.2. The displayed metre value.
 *
 * ```
 * seed   = HMAC-SHA256(key = session_salt, message = local_peer_id || band_ordinal_byte)
 * u32    = big-endian uint32 of seed[0..3]
 * u      = u32 / 2^32
 * offset = (u * 2 - 1) * span(band)
 * metres = round_half_away_from_zero(midpoint(band) + offset)
 * metres = clamp(metres, midpoint - span, midpoint + span)
 * ```
 *
 * IT TAKES NO RSSI ARGUMENT. That is the entire security property, and it is the part that is easy
 * to get wrong. The naive design — take a real distance estimate and add random noise — is broken:
 * an attacker who observes the displayed value repeatedly just averages, the noise cancels as
 * 1/sqrt(n), and the true value falls out. Jitter added to a real value is a delay, not a defence.
 * Because our number never contains a real distance, averaging it converges on the band midpoint,
 * which the attacker already knew from the band. There is nothing to recover.
 *
 * INTERNAL on purpose: the only supported way to obtain a metre value is through
 * [BandingPipeline], which cannot be handed an RSSI-derived seed.
 */
internal object DisplayJitter {

    internal fun midpointOf(band: Band): Int? = when (band) {
        Band.HERE -> 1
        Band.CLOSE -> 6
        Band.AROUND -> 20
        Band.EDGE -> 65
        Band.OUT_OF_RANGE, Band.UNKNOWN -> null
    }

    internal fun spanOf(band: Band): Int? = when (band) {
        // span 0: at this range a metre figure is theatre. The band copy carries the meaning.
        Band.HERE -> 0
        Band.CLOSE -> 2
        Band.AROUND -> 6
        Band.EDGE -> 20
        Band.OUT_OF_RANGE, Band.UNKNOWN -> null
    }

    internal fun seedFor(sessionSalt: ByteArray, localPeerId: ByteArray, band: Band): ByteArray {
        val message = ByteArray(localPeerId.size + 1)
        localPeerId.copyInto(message)
        message[localPeerId.size] = band.ordinal4.toByte()
        return hmacSha256(sessionSalt, message)
    }

    /** Big-endian uint32 of `seed[0..3]`, as a Long so it stays unsigned. */
    internal fun u32Of(seed: ByteArray): Long =
        ((seed[0].toLong() and 0xFF) shl 24) or
            ((seed[1].toLong() and 0xFF) shl 16) or
            ((seed[2].toLong() and 0xFF) shl 8) or
            (seed[3].toLong() and 0xFF)

    internal fun uOf(u32: Long): Double = u32.toDouble() / 4294967296.0

    internal fun metresFor(sessionSalt: ByteArray, localPeerId: ByteArray, band: Band): Int? {
        val midpoint = midpointOf(band) ?: return null
        val span = spanOf(band) ?: return null
        if (span == 0) return midpoint
        val u = uOf(u32Of(seedFor(sessionSalt, localPeerId, band)))
        val offset = (u * 2.0 - 1.0) * span.toDouble()
        val rounded = roundHalfAwayFromZero(midpoint.toDouble() + offset)
        return rounded.coerceIn(midpoint - span, midpoint + span)
    }

    private fun roundHalfAwayFromZero(v: Double): Int =
        if (v >= 0.0) floor(v + 0.5).toInt() else ceil(v - 0.5).toInt()
}
