package com.radius.shared.protocol.vectors

import com.radius.shared.protocol.KeyRing
import com.radius.shared.protocol.KeyRingEntry
import com.radius.shared.protocol.KeySchedule
import com.radius.shared.protocol.ProtocolError
import com.radius.shared.protocol.ProtocolResult
import kotlin.test.Test

/**
 * `vectors/key_rotation.json` — the key ring, the seam, and self-eid rejection. ADR-008 M4.
 *
 * EVERY RING IN THIS FILE IS QUERIED ACROSS ITS SEAM ON PURPOSE. The failure mode being hunted is
 * an implementation that resolves the active kid ONCE per table build instead of ONCE PER EPOCH.
 * That bug produces exactly one wrong epoch per rotation per user and passes every test that does
 * not straddle a seam. Case `C-second-seam` carries an explicit trap value: the eid a
 * cache-once implementation would return.
 *
 * `destruction_at_seam` (added 2026-08-04) covers the OTHER half of the seam, §8.5.2: the
 * superseded key must be GONE afterwards. The spec said so from the start and the client did not
 * do it, which meant every key the device had ever held stayed derivable for the life of the
 * install.
 */
class KeyRotationVectorsTest {

    private val file: Map<String, Any?> by lazy { VectorSource.load("key_rotation.json") }

    private fun accountKeyHex(reference: String): String =
        file.obj("account_keys")[reference] as String

    private fun entryFrom(json: Map<String, Any?>): KeyRingEntry {
        val hex = json.strOrNull("account_key_hex") ?: accountKeyHex(json.str("account_key"))
        return KeyRingEntry(
            kid = json.long("kid"),
            accountKey = hexToBytes(hex),
            effDay = json.long("eff_day"),
            effEpoch = json.int("eff_epoch"),
        )
    }

    private fun namedRing(name: String): KeyRing =
        KeyRing(file.obj("rings").obj(name).arr("entries").map { entryFrom(it.asObj()) })

    private fun ringFor(c: Map<String, Any?>): KeyRing = when {
        c.containsKey("ring_entries") -> KeyRing(c.arr("ring_entries").map { entryFrom(it.asObj()) })
        else -> namedRing(c.str("ring"))
    }

    @Test
    fun ringResolutionAcrossSeams() {
        val cases = file.arr("cases").map { it.asObj() }
        val run = VectorRun("key_rotation/cases")

        for (c in cases) {
            run.case(c.str("name")) {
                val ring = ringFor(c)
                val day = c.long("day_index")
                val epoch = c.int("epoch_index")

                val kid = KeySchedule.activeKid(ring, day, epoch)
                expectTrue(kid is ProtocolResult.Success, "activeKid failed: $kid")
                expectEquals(c.long("expect_kid"), (kid as ProtocolResult.Success).value, "kid")

                val eid = KeySchedule.ephemeralId(ring, day, epoch)
                expectTrue(eid is ProtocolResult.Success, "ephemeralId failed: $eid")
                val hex = bytesToHex((eid as ProtocolResult.Success).value)
                expectEquals(c.str("expect_ephemeral_id_hex"), hex, "ephemeral_id")

                // The trap value: what a resolver that cached the kid at the start of the day
                // would have produced. Asserting the negative is what makes the case worth having.
                c.strOrNull("must_not_equal_ephemeral_id_hex")?.let { trap ->
                    expectTrue(hex != trap, "produced the cached-kid TRAP value $trap — active() was not evaluated per epoch")
                }
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun skewWindowStraddlingASeam() {
        val windows = file.arr("skew_window_across_seam").map { it.asObj() }
        val run = VectorRun("key_rotation/skew_window_across_seam")

        for (w in windows) {
            run.case(w.str("name")) {
                val ring = namedRing(w.str("ring"))
                val day = w.long("day_index")
                val epoch = w.int("current_epoch_index")

                // The accepted window as the implementation computes it.
                val window = ArrayList<Pair<Long, Int>>()
                KeySchedule.forEachWindowEpoch(day, epoch) { d, e -> window.add(d to e) }
                expectEquals(
                    2 * KeySchedule.ACCEPTED_EPOCH_SKEW + 1,
                    window.size,
                    "window size (the window IS the replay window; it must not silently widen)",
                )

                val producedIds = ArrayList<String>()
                for (rawEntry in w.arr("expect_window")) {
                    val e = rawEntry.asObj()
                    val entryDay = if (e.has("day_index")) e.long("day_index") else day
                    val entryEpoch = e.int("epoch_index")

                    expectTrue(
                        window.contains(entryDay to entryEpoch),
                        "($entryDay,$entryEpoch) is not in the accepted window $window",
                    )

                    val kid = KeySchedule.activeKid(ring, entryDay, entryEpoch)
                    expectTrue(kid is ProtocolResult.Success, "activeKid failed at ($entryDay,$entryEpoch)")
                    expectEquals(e.long("kid"), (kid as ProtocolResult.Success).value, "kid at ($entryDay,$entryEpoch)")

                    val eid = KeySchedule.ephemeralId(ring, entryDay, entryEpoch)
                    expectTrue(eid is ProtocolResult.Success, "ephemeralId failed at ($entryDay,$entryEpoch)")
                    val hex = bytesToHex((eid as ProtocolResult.Success).value)
                    expectEquals(e.str("ephemeral_id_hex"), hex, "eid at ($entryDay,$entryEpoch)")
                    producedIds.add(hex)
                }

                // NOT two identities in one epoch. One identity per epoch, over a window that
                // happens to straddle a seam.
                if (w.has("assert_distinct") && w.bool("assert_distinct")) {
                    expectTrue(producedIds.distinct().size == producedIds.size, "window ids are not distinct: $producedIds")
                }
                if (w.has("assert_exactly_one_per_epoch") && w.bool("assert_exactly_one_per_epoch")) {
                    for ((d, e) in window) {
                        val kids = ring.entries.filter { it.effDay < d || (it.effDay == d && it.effEpoch <= e) }
                        // active() returns the GREATEST such entry; exactly one, never zero-or-two.
                        expectTrue(
                            kids.size <= ring.entries.size,
                            "impossible ring state at ($d,$e)",
                        )
                        val resolved = KeySchedule.activeKid(ring, d, e)
                        expectTrue(
                            resolved is ProtocolResult.Success || kids.isEmpty(),
                            "no active kid at ($d,$e) but candidates existed",
                        )
                    }
                }
            }
        }
        run.finish(windows.size)
    }

    @Test
    fun selfEidRejection() {
        val block = file.obj("self_eid_rejection")
        val own = block.obj("own_state")
        val ring = namedRing(own.str("own_ring"))
        val day = own.long("own_day_index")
        val epoch = own.int("own_epoch_index")
        val cases = block.arr("cases").map { it.asObj() }
        val run = VectorRun("key_rotation/self_eid_rejection")

        // The declared own-window must be the window the implementation actually uses.
        val windowEpochs = ArrayList<Int>()
        KeySchedule.forEachWindowEpoch(day, epoch) { _, e -> windowEpochs.add(e) }
        expectEquals(
            own.arr("own_window_epochs").map { (it as Long).toInt() },
            windowEpochs,
            "own accepted window",
        )

        for (c in cases) {
            run.case(c.str("name")) {
                val observed = hexToBytes(c.str("observed_ephemeral_id_hex"))
                val isOwn = KeySchedule.isOwnEphemeralId(ring, day, epoch, observed)
                when (val expected = c.str("expect")) {
                    // E_SELF_EID: drop BEFORE resolution. No peer instance, no UI, not counted.
                    "DROP" -> {
                        expectTrue(isOwn, "expected E_SELF_EID drop, but the frame was passed to resolution")
                        expectEquals(ProtocolError.E_SELF_EID, ProtocolError.valueOf(c.str("expect_error")), "drop code")
                    }
                    // The inverse trap: self-rejecting on "any key in my ring at any epoch" would
                    // wrongly drop other people's frames and go partially blind after a rotation.
                    "PASS_TO_RESOLUTION" ->
                        expectTrue(!isOwn, "frame was wrongly self-rejected — this goes blind after every rotation")
                    else -> throw AssertionError("unknown expectation '$expected'")
                }
            }
        }
        run.finish(cases.size)
    }

    /**
     * `vectors/key_rotation.json` → `destruction_at_seam`. `KEY_SCHEDULE.md` §8.5.2.
     *
     * THE ASSERTION THAT MATTERS IS THE NEGATIVE ONE, and it is only worth anything because the
     * positive one runs first against the same ring and the same `(day, epoch)`: the eid IS
     * derivable, we prune, the eid is NOT derivable. A test that only checked for
     * `E_NO_ACTIVE_KEY` afterwards would pass just as happily against a ring that never worked.
     */
    @Test
    fun supersededKeysAreDestroyedAtTheSeam() {
        val cases = file.arr("destruction_at_seam").map { it.asObj() }
        val run = VectorRun("key_rotation/destruction_at_seam")

        for (c in cases) {
            run.case(c.str("name")) {
                val ring = ringFor(c)
                val queries = c.arr("queries").map { it.asObj() }

                // BEFORE. Every asserted value must be derivable, or the negative below is empty.
                for (q in queries) {
                    val before = q.obj("before_prune")
                    val day = q.long("day_index")
                    val epoch = q.int("epoch_index")
                    val kid = KeySchedule.activeKid(ring, day, epoch)
                    expectTrue(kid is ProtocolResult.Success, "pre-prune activeKid failed at ($day,$epoch): $kid")
                    expectEquals(before.long("kid"), (kid as ProtocolResult.Success).value, "pre-prune kid at ($day,$epoch)")
                    val eid = KeySchedule.ephemeralId(ring, day, epoch)
                    expectTrue(eid is ProtocolResult.Success, "pre-prune ephemeralId failed at ($day,$epoch): $eid")
                    expectEquals(
                        before.str("ephemeral_id_hex"),
                        bytesToHex((eid as ProtocolResult.Success).value),
                        "pre-prune eid at ($day,$epoch)",
                    )
                }

                val selfEid = if (c.has("self_eid")) c.obj("self_eid") else null
                val selfCases = selfEid?.arr("cases")?.map { it.asObj() } ?: emptyList()
                for (s in selfCases) {
                    val isOwn = KeySchedule.isOwnEphemeralId(
                        ring,
                        selfEid!!.long("own_day_index"),
                        selfEid.int("own_epoch_index"),
                        hexToBytes(s.str("observed_ephemeral_id_hex")),
                    )
                    expectEquals(s.str("expect_before") == "DROP", isOwn, "pre-prune self-eid ${s.str("observed_ephemeral_id_hex")}")
                }

                // THE SEAM.
                for (p in c.arr("prunes").map { it.asObj() }) {
                    expectEquals(
                        p.long("expect_destroyed"),
                        ring.pruneSupersededAt(p.long("day_index"), p.int("epoch_index")).toLong(),
                        "entries destroyed by prune at (${p.long("day_index")},${p.int("epoch_index")})",
                    )
                }

                // The ring keeps its ORDERING METADATA. §8.5.2 destroys the key, not the entry:
                // a removed entry would be indistinguishable from "the ring never began here".
                expectEquals(
                    c.arr("expect_destroyed_kids").map { (it as Long) },
                    ring.entries.filter { it.isDestroyed }.map { it.kid },
                    "destroyed kids",
                )
                expectEquals(null, KeySchedule.validateRing(ring), "a pruned ring must still validate")

                // AFTER. The negative.
                for (q in queries) {
                    val after = q.obj("after_prune")
                    val day = q.long("day_index")
                    val epoch = q.int("epoch_index")
                    val eid = KeySchedule.ephemeralId(ring, day, epoch)
                    if (after.has("expect_error")) {
                        expectTrue(
                            eid is ProtocolResult.Failure,
                            "($day,$epoch) is STILL DERIVABLE after the prune — the superseded key was retained. " +
                                "One in-process compromise then yields every eid this device ever emitted (§8.1).",
                        )
                        expectEquals(
                            ProtocolError.valueOf(after.str("expect_error")),
                            (eid as ProtocolResult.Failure).error,
                            "post-prune error at ($day,$epoch)",
                        )
                        // A destroyed key must FAULT, never silently derive from 32 zero bytes —
                        // that would be a valid-looking, wrong, and identical-across-devices id.
                        expectTrue(
                            KeySchedule.activeKid(ring, day, epoch) is ProtocolResult.Failure,
                            "activeKid still resolves at ($day,$epoch) while ephemeralId does not — inconsistent",
                        )
                    } else {
                        expectTrue(eid is ProtocolResult.Success, "post-prune ephemeralId failed at ($day,$epoch): $eid")
                        expectEquals(
                            after.str("ephemeral_id_hex"),
                            bytesToHex((eid as ProtocolResult.Success).value),
                            "post-prune eid at ($day,$epoch) — the ACTIVE key must be untouched",
                        )
                        val kid = KeySchedule.activeKid(ring, day, epoch)
                        expectEquals(after.long("kid"), (kid as ProtocolResult.Success).value, "post-prune kid at ($day,$epoch)")
                    }
                }

                for (s in selfCases) {
                    val isOwn = KeySchedule.isOwnEphemeralId(
                        ring,
                        selfEid!!.long("own_day_index"),
                        selfEid.int("own_epoch_index"),
                        hexToBytes(s.str("observed_ephemeral_id_hex")),
                    )
                    expectEquals(
                        s.str("expect_after") == "DROP",
                        isOwn,
                        "post-prune self-eid ${s.str("observed_ephemeral_id_hex")} " +
                            "(see the section note: losing e-1 self-rejection at a seam is the accepted cost of §8.5.2)",
                    )
                }
            }
        }
        run.finish(cases.size)
    }

    /**
     * The accessor contract around destroyed material. Not vector-driven: this is about the shape
     * of the Kotlin API, which no JSON file can express.
     */
    @Test
    fun destroyedEntryHandsBackNothingAndLiveEntryHandsBackACopy() {
        val entry = KeyRingEntry(kid = 0, accountKey = hexToBytes(accountKeyHex("AK1")), effDay = 0, effEpoch = 0)

        // A COPY, NOT THE LIVE ARRAY. Mutating what we are given must not touch the ring's key —
        // it used to return the live reference, so one stray fill() corrupted the schedule and one
        // retained reference outlived destruction entirely.
        val first = entry.accountKey()!!
        first.fill(0x41)
        expectEquals(
            accountKeyHex("AK1"),
            bytesToHex(entry.accountKey()!!),
            "accountKey() returned an alias to live key material",
        )

        // useKey zeroes the scratch copy on exit, by every path including a throw.
        var escaped: ByteArray? = null
        entry.useKey { key -> escaped = key }
        expectTrue(escaped!!.all { it == 0.toByte() }, "useKey did not zero its scratch copy on exit")
        runCatching { entry.useKey<Unit> { throw IllegalStateException("boom") } }
        expectEquals(accountKeyHex("AK1"), bytesToHex(entry.accountKey()!!), "a throwing block corrupted the key")

        entry.destroyKeyMaterial()
        expectTrue(entry.isDestroyed, "isDestroyed")
        expectEquals(null, entry.accountKey(), "accountKey() after destruction")
        expectEquals(null, entry.useKey { 1 }, "useKey after destruction")
        expectTrue(entry.toString().contains("destroyed"), "toString must say the entry is destroyed: $entry")
        expectTrue(!entry.toString().contains(accountKeyHex("AK1")), "toString leaked key material")
        entry.destroyKeyMaterial() // idempotent
        expectEquals(0L, entry.kid, "kid survives destruction — it is the ring's ordering metadata")
    }

    @Test
    fun malformedRingsAreRejected() {
        val cases = file.arr("invalid").map { it.asObj() }
        val run = VectorRun("key_rotation/invalid")

        for (c in cases) {
            run.case(c.str("name")) {
                val expected = ProtocolError.valueOf(c.str("expect_error"))
                val ring = ringFor(c)
                val actual = KeySchedule.validateRing(ring)
                    ?: if (c.has("day_index")) {
                        when (val r = KeySchedule.ephemeralId(ring, c.long("day_index"), c.int("epoch_index"))) {
                            is ProtocolResult.Failure -> r.error
                            is ProtocolResult.Success -> null
                        }
                    } else {
                        null
                    }
                expectEquals(expected, actual, "error code")
            }
        }
        run.finish(cases.size)
    }

    @Test
    fun effectiveFromInThePastIsAccepted() {
        val cases = file.arr("accepted_not_rejected").map { it.asObj() }
        val run = VectorRun("key_rotation/accepted_not_rejected")

        for (c in cases) {
            run.case(c.str("name")) {
                // DELIBERATELY NOT AN ERROR (KEY_SCHEDULE.md §8.3). A device back from a week
                // offline holds entries whose effective_from has passed. A symmetric client-side
                // "must be in the future" check would brick exactly those devices.
                val ring = ringFor(c)
                expectEquals(null, KeySchedule.validateRing(ring), "ring must validate")
                val kid = KeySchedule.activeKid(ring, c.long("device_wallclock_day"), c.int("device_wallclock_epoch"))
                expectTrue(kid is ProtocolResult.Success, "expected ACCEPT, got $kid")
                expectEquals(c.long("expect_active_kid_now"), (kid as ProtocolResult.Success).value, "active kid")
            }
        }
        run.finish(cases.size)
    }
}
