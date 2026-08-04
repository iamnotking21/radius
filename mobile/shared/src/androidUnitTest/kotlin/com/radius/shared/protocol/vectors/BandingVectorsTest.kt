package com.radius.shared.protocol.vectors

import com.radius.shared.protocol.Band
import com.radius.shared.protocol.BandingPipeline
import com.radius.shared.protocol.Confidence
import com.radius.shared.protocol.DisplayJitter
import kotlin.test.Test

/**
 * `vectors/banding.json` and `vectors/display_jitter.json`.
 *
 * TWO OF THESE CASES ARE REGRESSION TESTS FOR BUGS THAT HAVE ALREADY BEEN MADE ONCE, in the
 * reference run that produced these vectors:
 *
 *  - `step-change-reinit-edge-to-here` pins the OUTLIER-GATE LIVELOCK. Reject-three-then-accept-one
 *    crawls toward the truth at one sample in four; the third consecutive outlier must RE-INITIALISE
 *    the filter instead.
 *  - `warmup-to-close` and `boundary-no-flap` pin the WARM-UP MARGIN bug. Applying the promotion
 *    margin at acquisition shifts every ADR-004 threshold by 3 dB and creates a one-way ratchet a
 *    peer can never escape.
 *
 * None of this says anything about band accuracy versus true distance. That needs a radio.
 */
class BandingVectorsTest {

    private val salt = hexToBytes("8ef5bbe99212d66cc2b6c1076493aa84c642566ffa107dd41febfeffb2467d81")
    private val peer = hexToBytes("0123456789abcdef0123456789abcdef")

    @Test
    fun bandingSequences() {
        val file = VectorSource.load("banding.json")
        val tolerance = file.obj("tolerance").dbl("adjusted_dbm")
        val cases = file.arr("cases").map { it.asObj() }
        val run = VectorRun("banding")

        // The constants in the vector file must be the constants in the implementation. If these
        // ever disagree, every expectation below is being checked against the wrong pipeline.
        val k = file.obj("constants")
        expectEquals(BandingPipeline.TX_REF_DBM, k.int("TX_REF_DBM"), "TX_REF_DBM")
        expectEquals(BandingPipeline.Q, k.dbl("Q"), "Q")
        expectEquals(BandingPipeline.R, k.dbl("R"), "R")
        expectEquals(BandingPipeline.WARMUP_SAMPLES, k.int("WARMUP_SAMPLES"), "WARMUP_SAMPLES")
        expectEquals(BandingPipeline.STABLE_SAMPLES, k.int("STABLE_SAMPLES"), "STABLE_SAMPLES")
        expectEquals(BandingPipeline.OUTLIER_GATE_DB, k.dbl("OUTLIER_GATE_DB"), "OUTLIER_GATE_DB")
        expectEquals(BandingPipeline.MAX_CONSEC_REJECT, k.int("MAX_CONSEC_REJECT"), "MAX_CONSEC_REJECT")
        expectEquals(BandingPipeline.H_IN_DB, k.dbl("H_IN_DB"), "H_IN_DB")
        expectEquals(BandingPipeline.H_OUT_DB, k.dbl("H_OUT_DB"), "H_OUT_DB")
        expectEquals(BandingPipeline.K_IN, k.int("K_IN"), "K_IN")
        expectEquals(BandingPipeline.K_OUT, k.int("K_OUT"), "K_OUT")

        for (c in cases) {
            run.case(c.str("name")) {
                val pipeline = BandingPipeline(c.int("tx_power_cal_dbm"), salt, peer)
                for (rawStep in c.arr("steps")) {
                    val s = rawStep.asObj()
                    val label = "${c.str("name")} step ${s.int("step")}"

                    pipeline.update(s.int("rssi_dbm"))
                    val state = pipeline.conformanceState()

                    expectEquals(s.bool("accepted"), state.accepted, "$label accepted")
                    expectEquals(s.bool("filter_reinit"), state.filterReinit, "$label filter_reinit")
                    expectEquals(s.int("samples"), state.samples, "$label samples")
                    expectClose(s.dbl("adjusted_dbm"), state.adjustedDbm, tolerance, "$label adjusted_dbm")
                    expectEquals(Band.valueOf(s.str("band")), state.band, "$label band")
                    expectEquals(Confidence.valueOf(s.str("confidence")), state.confidence, "$label confidence")
                }
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun displayJitterKnownAnswers() {
        val file = VectorSource.load("display_jitter.json")
        val sessionSalt = hexToBytes(file.str("session_salt_hex"))
        val tolerance = file.obj("tolerance").dbl("u")
        val cases = file.arr("cases").map { it.asObj() }
        val run = VectorRun("display_jitter/cases")

        for (c in cases) {
            run.case(c.str("name")) {
                val peerId = hexToBytes(c.str("local_peer_id_hex"))
                val band = Band.valueOf(c.str("band"))

                val seed = DisplayJitter.seedFor(sessionSalt, peerId, band)
                expectEquals(c.str("expect_hmac_hex"), bytesToHex(seed), "hmac")

                val u32 = DisplayJitter.u32Of(seed)
                expectEquals(c.long("expect_u32"), u32, "u32")
                expectClose(c.dbl("expect_u"), DisplayJitter.uOf(u32), tolerance, "u")

                expectEquals(
                    c.int("expect_display_metres"),
                    DisplayJitter.metresFor(sessionSalt, peerId, band),
                    "display_metres",
                )
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun displayJitterPropertyAssertions() {
        val file = VectorSource.load("display_jitter.json")
        val sessionSalt = hexToBytes(file.str("session_salt_hex"))
        val display = file.obj("band_display")
        val assertions = file.arr("property_assertions").map { it.asObj() }
        val run = VectorRun("display_jitter/property_assertions")

        val bands = listOf(Band.HERE, Band.CLOSE, Band.AROUND, Band.EDGE)
        val peerA = hexToBytes("0123456789abcdef0123456789abcdef")
        val peerB = hexToBytes("ffeeddccbbaa998877665544332211ff")

        for (a in assertions) {
            run.case(a.str("name")) {
                when (a.str("name")) {
                    "deterministic" ->
                        for (b in bands) {
                            val first = DisplayJitter.metresFor(sessionSalt, peerA, b)
                            repeat(50) {
                                expectEquals(first, DisplayJitter.metresFor(sessionSalt, peerA, b), "stability for $b")
                            }
                        }

                    // THE ANTI-AVERAGING PROPERTY. Feed wildly different RSSI values that land in
                    // the same band and the displayed number MUST NOT move. If it moved, an
                    // attacker observing it repeatedly could average the noise away and recover a
                    // real distance. Ours contains no real distance to recover.
                    "independent-of-rssi" -> {
                        val samples = listOf(-56, -60, -64, -68, -69, -57, -61, -66)
                        var seen: Int? = null
                        val pipeline = BandingPipeline(-60, sessionSalt, peerA)
                        for (rssi in samples) {
                            val reading = pipeline.update(rssi)
                            if (reading.band == Band.CLOSE) {
                                if (seen == null) seen = reading.displayMetres
                                expectEquals(seen, reading.displayMetres, "displayed metres moved with RSSI")
                            }
                        }
                        expectTrue(seen != null, "test did not reach the CLOSE band")
                    }

                    "within-band-range" ->
                        for (b in bands) {
                            val spec = display.obj(b.name)
                            val range = spec.arr("range_m").map { (it as Long).toInt() }
                            for (peerId in listOf(peerA, peerB)) {
                                val m = DisplayJitter.metresFor(sessionSalt, peerId, b)!!
                                expectTrue(m >= range[0] && m <= range[1], "$b produced $m, outside $range")
                            }
                            expectEquals(spec.int("ordinal"), b.ordinal4, "$b ordinal")
                            expectEquals(spec.int("midpoint_m"), DisplayJitter.midpointOf(b), "$b midpoint")
                            expectEquals(spec.int("span_m"), DisplayJitter.spanOf(b), "$b span")
                        }

                    "differs-across-sessions" -> {
                        // HERE has span 0 by design, so it is constant across sessions and excluded.
                        var differences = 0
                        for (b in listOf(Band.CLOSE, Band.AROUND, Band.EDGE)) {
                            val base = DisplayJitter.metresFor(sessionSalt, peerA, b)
                            for (i in 1..32) {
                                val otherSalt = sessionSalt.copyOf().also { it[0] = (it[0] + i).toByte() }
                                if (DisplayJitter.metresFor(otherSalt, peerA, b) != base) differences++
                            }
                        }
                        expectTrue(differences > 0, "displayed value never changed across 96 different session salts")
                    }

                    else -> throw AssertionError("unhandled property assertion '${a.str("name")}'")
                }
            }
        }
        run.finish(assertions.size)
    }

    /**
     * A LINT, NOT A PROOF. `BANDING.md` §6.3 / decision 28.
     *
     * WHAT IT ACTUALLY DOES: walks the PUBLIC members of
     * [com.radius.shared.protocol.PeerReading] and [BandingPipeline] and fails if a new one is
     * NAMED like an RSSI accessor — `rssi`, `dbm`, `adjusted`, `filtered`, `raw`, `signal`.
     *
     * WHAT IT DOES NOT DO, stated plainly because the previous comment here claimed otherwise:
     *   - `Class.getMethods()` and `getFields()` return PUBLIC members only, so this test is
     *     structurally incapable of seeing a private instance field. It never checked one.
     *   - It matches six substrings. A member called `snapshot()`, `level()`, `estimate()` or
     *     `strength()` returning a raw dBm sails straight through.
     *   - `internal` members ARE visible to it (they compile to public + mangled name on the JVM),
     *     which is why the `conformanceState` skip below is needed at all — and is a reminder that
     *     `internal` is not JVM access control.
     * Containment of RSSI is therefore enforced by REVIEW. This test only catches the specific
     * regression of someone adding an obviously-named accessor, which is the common one.
     */
    @Test
    fun noNewRssiShapedAccessorAppears() {
        val forbidden = listOf("rssi", "dbm", "adjusted", "filtered", "raw", "signal")
        val leaks = ArrayList<String>()

        for (type in listOf(com.radius.shared.protocol.PeerReading::class.java, BandingPipeline::class.java)) {
            for (m in type.methods) {
                if (m.declaringClass == Any::class.java) continue
                // `conformanceState` is internal, which on the JVM means public + a mangled name,
                // so it shows up here. Skipped by ruling (decision 42): the adjusted_dbm assertions
                // that caught two real bugs are unexecutable without it. Everything else is fair
                // game.
                if (m.name.startsWith("conformanceState")) continue
                val lower = m.name.lowercase()
                if (forbidden.any { lower.contains(it) }) leaks.add("${type.simpleName}.${m.name}")
            }
            for (f in type.fields) {
                // PUBLIC fields only — `getFields()` cannot see a private one, so this loop is a
                // check on what a future edit might EXPOSE, not on what the class stores.
                // `const val`s in the companion compile to public static final fields. A named
                // CONSTANT (TX_REF_DBM, the plausibility bounds) is not a per-peer measurement and
                // cannot leak anyone's position; an INSTANCE field can. Only instance state counts.
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                val lower = f.name.lowercase()
                if (forbidden.any { lower.contains(it) }) leaks.add("${type.simpleName}.${f.name} (field)")
            }
        }

        expectTrue(
            leaks.isEmpty(),
            "a new RSSI-shaped PUBLIC accessor appeared on the shared/protocol boundary: $leaks — " +
                "see BANDING.md §6.3, decision 28. RSSI is location data: three receivers " +
                "multilaterate to a position. (This lint sees names, not semantics — an accessor " +
                "that leaks RSSI under an innocent name is caught in review or not at all.)",
        )
    }

    /** The redaction contract. An ephemeral id or an RSSI must not survive into a log line. */
    @Test
    fun readingToStringCarriesNoRssi() {
        val pipeline = BandingPipeline(-60, salt, peer)
        repeat(5) { pipeline.update(-68) }
        val text = pipeline.current().toString()
        expectTrue(!text.contains("68"), "PeerReading.toString leaked an RSSI-shaped value: $text")
    }
}
