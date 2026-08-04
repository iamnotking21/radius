package com.radius.shared.protocol

/**
 * One entry in the device's key ring. `KEY_SCHEDULE.md` §8.2.
 *
 * @property kid bookkeeping only. NEVER transmitted, NEVER derived from, NEVER on the air.
 *   Monotonically increasing from 0.
 * @property effDay UTC day index at which this key becomes active.
 * @property effEpoch epoch index (0..95) within [effDay] at which it becomes active. INCLUSIVE.
 *
 * `effective_from` is an EPOCH INDEX, so mid-epoch rotation is UNREPRESENTABLE by construction.
 * That is the design, not a limitation: a mid-epoch rotation IS a staggered rotation, and a
 * staggered rotation shrinks the anonymity set from the whole population to one and makes the
 * change a trivially linkable hinge (decision 27). The illegal state is unconstructible rather
 * than merely forbidden.
 */
public class KeyRingEntry(
    public val kid: Long,
    accountKey: ByteArray,
    public val effDay: Long,
    public val effEpoch: Int,
) {
    private val key: ByteArray = accountKey.copyOf()
    private var keyDestroyed: Boolean = false

    /**
     * True once [destroyKeyMaterial] has run. The entry survives as ORDERING METADATA — `kid` and
     * `effective_from` stay readable so the ring keeps its total order — but the key is gone and
     * this entry can no longer derive anything. `KeySchedule.activeKid` and
     * `KeySchedule.ephemeralId` both report `E_NO_ACTIVE_KEY` for an epoch that resolves here.
     */
    public val isDestroyed: Boolean get() = keyDestroyed

    /**
     * Destroy this entry's `account_key`. `KEY_SCHEDULE.md` §8.5.2, and IRREVERSIBLE.
     *
     * Normally reached through [KeyRing.pruneSupersededAt] at a rotation seam rather than called
     * directly. Idempotent.
     *
     * WHY THIS EXISTS AND WHY IT IS NOT HYGIENE. §2.3 concedes that the Keystore wrap gives no
     * protection against a compromised running process or a rooted device: to run HKDF-Extract we
     * must hold the raw key in process memory. If the ring keeps every key it was ever issued,
     * then ONE in-process compromise yields RETROACTIVE derivation across the device's entire
     * rotation history — every eid the device ever emitted, back to `kid` 0. Bounding exactly that
     * blast radius is what ADR-008 M4's rotation was bought for (§8.1), so a client that retains
     * superseded keys silently throws the purchase away and ends up weaker than the server on a
     * property §8.1 states honestly.
     *
     * BEST EFFORT, NOT A GUARANTEE. `fill(0)` overwrites the array we hold. On a moving-collector
     * runtime (ART, HotSpot) the GC may already have copied this array elsewhere, and a copy we
     * cannot name is a copy we cannot zero; a `String` would have been worse still, which is why
     * this is a `ByteArray` (ruling R-C). Required anyway: it removes the buffer we CAN name from
     * a heap dump, and it makes the intent unambiguous to the next reader.
     */
    public fun destroyKeyMaterial() {
        key.fill(0)
        keyDestroyed = true
    }

    /**
     * Run [block] against a SHORT-LIVED COPY of the 32-byte account key, zeroed on exit by any
     * path including a throw. Returns `null` if the material has been destroyed.
     *
     * This is the ONLY way to reach key material, and the shape is the point: the caller never
     * receives a reference it can outlive the call with, cannot stash one in a field, and cannot
     * forget to clear it. `ByteArray`, never a hex `String` — this is the single most sensitive
     * value in the system and must be the most awkward to print (ruling R-C).
     *
     * Server-issued and rotatable (ADR-008, decision 32). Rotation bounds FUTURE exposure only;
     * it does not undo past exposure and must never be described as doing so.
     *
     * NOT `inline`, deliberately: an `internal inline` function may not touch a `private` field,
     * and the fix for that — `@PublishedApi internal val key` — would widen the raw array to the
     * whole module's ABI, which is the opposite of the point here.
     */
    internal fun <T> useKey(block: (ByteArray) -> T): T? {
        if (keyDestroyed) return null
        val scratch = key.copyOf()
        try {
            return block(scratch)
        } finally {
            scratch.fill(0)
        }
    }

    /**
     * A COPY of the account key. Prefer [useKey], which zeroes for you; this exists for callers
     * that genuinely need to own the bytes, and they own the clearing too.
     *
     * MUST NOT return the live array. It used to, which handed every caller a live alias to the
     * ring's key material — one stray `fill`, `sort` or retained reference away from either
     * corrupting the schedule or extending the key's lifetime past [destroyKeyMaterial].
     */
    internal fun accountKey(): ByteArray? = if (keyDestroyed) null else key.copyOf()

    internal fun keyLength(): Int = key.size

    /** REDACTED. Never print key material, not even a prefix. */
    override fun toString(): String =
        "KeyRingEntry(kid=$kid, effFrom=($effDay,$effEpoch), key=" +
            (if (keyDestroyed) "<destroyed>" else "<32B redacted>") + ")"
}

/**
 * The device's ordered key ring. NOT a single key — ADR-008 made `account_key` rotatable, so a
 * device holds a ring and resolves which entry is active per epoch.
 *
 * Validate with [KeySchedule.validateRing] once on load or update. Every derivation call
 * re-validates cheaply; the explicit call exists so a bad ring is rejected at delivery rather
 * than discovered at the next advertising boundary.
 */
public class KeyRing(entries: List<KeyRingEntry>) {

    /**
     * Entries sorted by effective boundary, ascending.
     *
     * The LIST is immutable and stays that way — its order is the ring's total order and nothing
     * outside may reorder it. Entries themselves are destroyable in place ([pruneSupersededAt]),
     * because §8.5.2 requires the ring to KEEP `(kid, effective_from)` for ordering while LOSING
     * the key. Removing the entry outright would lose the ordering metadata and turn a
     * deliberately-destroyed key into an indistinguishable "gap before the ring began".
     */
    public val entries: List<KeyRingEntry> =
        entries.sortedWith(compareBy({ it.effDay }, { it.effEpoch }))

    /**
     * Destroy the key material of every entry STRICTLY OLDER than the one active at
     * `(day, epoch)`. `KEY_SCHEDULE.md` §8.5.2. Returns the number of entries destroyed.
     * Idempotent; safe to call on every epoch boundary and cheap when there is nothing to do.
     *
     * Call this at the §4.2 advertising restart, with the device's own wall clock. It performs no
     * radio-visible work, which is required: §8.5.1 says a rotation MUST NOT be observable as a
     * change in radio behaviour, and that includes not stalling the restart.
     *
     * THE ACTIVE ENTRY IS NEVER DESTROYED, and neither is anything after it. That is what makes
     * this safe to drive from a clock: a device whose clock jumps far forward destroys only
     * history it was required to destroy anyway, and remains able to advertise. A clock that then
     * jumps BACK leaves the device undiscoverable for the epochs it can no longer derive — which
     * is §8.6's failure direction (undiscoverable, not insecure), and is the correct trade.
     *
     * NO-OPS on a ring that does not validate, and on a query before the ring's first entry.
     * Destroying key material on the strength of a ring we cannot order, or of a timestamp that
     * predates the ring, would be acting irreversibly on input we have just declared untrustworthy.
     *
     * ONE HONEST CONSEQUENCE, at the seam. The receiver's accepted window is `{e-1, e, e+1}`
     * (§6.2) and at the seam epoch `e-1` belongs to the superseded key. Pruning exactly at the
     * seam means that for one epoch this device cannot re-derive its own previous-epoch eid, so
     * `KeySchedule.isOwnEphemeralId` cannot recognise it and a reflected/relayed copy of it would
     * be passed to resolution instead of dropped by §9.6. That is accepted deliberately: §9.6 is
     * defence in depth against a rare event, §8.5.2 is a normative destruction rule, and holding
     * the superseded key 15 minutes longer to improve a duplicate-broadcast detector would be
     * paying with the exact property this method exists to buy. Written into `KEY_SCHEDULE.md`
     * §8.5.2 so the two rules are reconciled in the spec text rather than silently in code.
     */
    public fun pruneSupersededAt(day: Long, epoch: Int): Int {
        if (KeySchedule.validateRing(this) != null) return 0
        if (epoch < 0 || epoch >= KeySchedule.EPOCHS_PER_DAY) return 0

        // THE SAME active() THE RESOLVER USES — deliberately the same function, not a second copy.
        // Two implementations of "which entry is active at (d,e)" is the shape of the bug §8.4
        // spends a page on, and a divergent copy HERE would destroy the wrong key.
        val activeIndex = KeySchedule.activeIndex(this, day, epoch)
        if (activeIndex <= 0) return 0

        var destroyed = 0
        for (i in 0 until activeIndex) {
            if (!entries[i].isDestroyed) {
                entries[i].destroyKeyMaterial()
                destroyed++
            }
        }
        return destroyed
    }

    override fun toString(): String =
        "KeyRing(${entries.size} entries, ${entries.count { it.isDestroyed }} destroyed)"
}

/**
 * Whether this device may BROADCAST. Defaults to the safe value.
 *
 * EXACTLY ONE ADVERTISING DEVICE PER ACCOUNT (decision 35). Two devices sharing an `account_key`
 * emit a BYTE-IDENTICAL ephemeral id from two locations, which (a) turns a stationary second
 * device into a live-eid oracle at a known address — a stalking primitive requiring no tailing —
 * and (b) permanently bridges both MACs, defeating invariant 5 structurally.
 *
 * Multi-device is REAL for account access and FALSE for Radar broadcast. Enum, not a boolean, and
 * defaulting to [SCAN_ONLY]: the dangerous state must be the one you have to ask for.
 */
public enum class AdvertiseRole {
    SCAN_ONLY,
    ADVERTISE,
}

/**
 * HKDF-SHA256 identity derivation and key-ring resolution. `KEY_SCHEDULE.md` §1, §6, §8, §9.6.
 *
 * ```
 * account_key --HKDF--> daily_key(d) --HKDF--> ephemeral_id(d,e)
 * salt = "radius/ble/v0"
 * info = "daily-key"   || u32be(d)                 L=32
 * info = "ephemeral-id"|| u32be(d) || u16be(e)     L=16
 * ```
 *
 * ROTATION IS SYNCHRONISED TO THE GLOBAL UTC BOUNDARY. There is deliberately no jitter parameter
 * anywhere in this object, and adding one would be a safety regression (decision 27).
 */
public object KeySchedule {

    /** Epochs per UTC day. 15 minutes each, day-relative, 0..95. */
    public const val EPOCHS_PER_DAY: Int = 96

    /** Epoch duration in seconds. */
    public const val EPOCH_SECONDS: Int = 900

    /** Seconds per UTC day. */
    public const val DAY_SECONDS: Int = 86_400

    /** `account_key` length in bytes. Exactly 32, never negotiable. */
    public const val ACCOUNT_KEY_LENGTH_BYTES: Int = 32

    /**
     * Receivers accept `e-1, e, e+1` (`KEY_SCHEDULE.md` §6.2). The skew window IS the replay
     * window (§6.3): +/-1 epoch means a captured ephemeral id replays for up to 30 minutes. That
     * one-for-one trade is why it stays at 1 and is not configurable here.
     */
    public const val ACCEPTED_EPOCH_SKEW: Int = 1

    private val SALT: ByteArray = "radius/ble/v0".encodeToByteArray()
    private val INFO_DAILY_KEY: ByteArray = "daily-key".encodeToByteArray()
    private val INFO_EPHEMERAL_ID: ByteArray = "ephemeral-id".encodeToByteArray()

    private const val DAILY_KEY_LENGTH = 32
    private const val EPHEMERAL_ID_LENGTH = 16

    // ---------------------------------------------------------------------------------------
    // Time
    // ---------------------------------------------------------------------------------------

    /** `d = floor(unix_seconds / 86400)`, UTC days since the Unix epoch. */
    public fun dayIndex(unixSeconds: Long): Long = floorDiv(unixSeconds, DAY_SECONDS.toLong())

    /**
     * `e = floor((unix_seconds mod 86400) / 900)`, 0..95.
     *
     * Epoch index is UTC-DAY-RELATIVE, not absolute. It resets to 0 at 00:00 UTC. `e = 96` is
     * impossible and means a clock bug; it MUST fault, never be clamped.
     */
    public fun epochIndex(unixSeconds: Long): Int =
        (floorMod(unixSeconds, DAY_SECONDS.toLong()) / EPOCH_SECONDS.toLong()).toInt()

    private fun floorDiv(a: Long, b: Long): Long {
        var q = a / b
        if ((a xor b) < 0L && q * b != a) q--
        return q
    }

    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    // ---------------------------------------------------------------------------------------
    // Ring validation
    // ---------------------------------------------------------------------------------------

    /**
     * Validate a ring. Returns `null` when valid, otherwise the first violated rule.
     *
     * | condition | error |
     * |---|---|
     * | `account_key` not exactly 32 bytes | `E_ACCOUNT_KEY_LENGTH` |
     * | `eff_epoch` outside 0..95 | `E_EPOCH_INDEX_OUT_OF_RANGE` |
     * | two entries share `(eff_day, eff_epoch)` | `E_KEY_RING_NOT_MONOTONIC` |
     * | `kid` order disagrees with effective order | `E_KEY_RING_NOT_MONOTONIC` |
     * | ring is empty | `E_NO_ACTIVE_KEY` |
     *
     * NOT AN ERROR, DELIBERATELY: an `effective_from` already in the past. That is the normal
     * state for a device returning from a week offline. The server must issue in the future; the
     * CLIENT must accept the past. Do NOT add a symmetric "must be in the future" check — it
     * bricks exactly the devices that most need to catch up (`KEY_SCHEDULE.md` §8.3).
     */
    public fun validateRing(ring: KeyRing): ProtocolError? {
        val entries = ring.entries
        if (entries.isEmpty()) return ProtocolError.E_NO_ACTIVE_KEY

        for (e in entries) {
            if (e.keyLength() != ACCOUNT_KEY_LENGTH_BYTES) return ProtocolError.E_ACCOUNT_KEY_LENGTH
        }
        for (e in entries) {
            if (e.effEpoch < 0 || e.effEpoch >= EPOCHS_PER_DAY) {
                return ProtocolError.E_EPOCH_INDEX_OUT_OF_RANGE
            }
        }
        for (i in 1 until entries.size) {
            val prev = entries[i - 1]
            val cur = entries[i]
            // entries are sorted by effective boundary; equal boundaries mean two keys active at
            // one instant, which is exactly the state the seam rule forbids. Do not tie-break.
            if (prev.effDay == cur.effDay && prev.effEpoch == cur.effEpoch) {
                return ProtocolError.E_KEY_RING_NOT_MONOTONIC
            }
            if (cur.kid <= prev.kid) {
                return ProtocolError.E_KEY_RING_NOT_MONOTONIC
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Ring resolution
    // ---------------------------------------------------------------------------------------

    /**
     * `active(ring, d, e)` = the entry with the GREATEST `(eff_day, eff_epoch)` such that
     * `(eff_day, eff_epoch) <= (d, e)` lexicographically.
     *
     * On a validated ring the effective boundaries form a strict total order, so "greatest" is
     * unique: NO DUPLICATE is representable. `active()` is total from the ring's first entry
     * onward: NO GAP is representable. A query before the first entry is `E_NO_ACTIVE_KEY` — a
     * fault, never a silent empty result.
     */
    public fun activeKid(ring: KeyRing, day: Long, epoch: Int): ProtocolResult<Long> =
        when (val e = resolve(ring, day, epoch)) {
            is ProtocolResult.Failure -> e
            is ProtocolResult.Success -> ProtocolResult.Success(e.value.kid)
        }

    /**
     * Derive this device's ephemeral id for `(day, epoch)`.
     *
     * THE SIGNATURE IS THE POINT. This takes the RING and the EPOCH, not a pre-resolved
     * `account_key`. `KEY_SCHEDULE.md` §8.4's rule — evaluate the ring PER EPOCH, never once per
     * table build — is then structurally impossible to skip rather than a comment someone can
     * ignore. An API that accepted a resolved key would make the once-per-table-build bug easy to
     * write and invisible in review; that bug produces exactly one wrong epoch per rotation per
     * user and passes every test that does not straddle a seam.
     *
     * DO NOT ADD AN OVERLOAD THAT TAKES A RESOLVED KEY. Gated in `40-contracts` v0.1 for this
     * exact reason.
     */
    public fun ephemeralId(ring: KeyRing, day: Long, epoch: Int): ProtocolResult<ByteArray> {
        val entry = when (val e = resolve(ring, day, epoch)) {
            is ProtocolResult.Failure -> return e
            is ProtocolResult.Success -> e.value
        }
        // The key never escapes the entry: useKey hands the block a copy and zeroes it on exit.
        // null means the material was destroyed between resolve() and here — resolve() already
        // rejects destroyed entries, so this is a race guard, and it reports the same fault.
        val id = entry.useKey { key -> ephemeralIdFor(key, day, epoch) }
            ?: return ProtocolResult.Failure(ProtocolError.E_NO_ACTIVE_KEY)
        return ProtocolResult.Success(id)
    }

    /**
     * `E_SELF_EID` (`KEY_SCHEDULE.md` §9.6). True if [observed] is one of THIS device's own
     * ephemeral ids anywhere in its own accepted epoch window. Such a frame MUST be dropped
     * BEFORE resolution: no peer instance, no UI, not counted in any "people nearby" figure.
     *
     * Two reasons. Without it a device can discover ITSELF — via reflection, a relay, or its own
     * account's second device — and show it to the user as a stranger standing next to them.
     * And it is the cheapest local detector we have for duplicate broadcast and for replay of our
     * own id; a non-zero count means one or the other, and both are worth knowing about.
     *
     * THE WINDOW IS EVALUATED PER EPOCH UNDER THE RING. At a rotation seam our own previous-epoch
     * id was derived under a DIFFERENT kid, and this must still reject it. The inverse trap
     * matters just as much: an implementation that self-rejects on "any key in my ring at any
     * epoch in the window" wrongly drops OTHER people's frames and goes partially blind after
     * every rotation. Both directions are pinned by `vectors/key_rotation.json`.
     *
     * Epochs in the window with no active key contribute no candidate and are not an error — a
     * device simply has no own-id for an epoch before its ring begins, and, after
     * [KeyRing.pruneSupersededAt] has run at a seam, none for an epoch whose key it has destroyed.
     * See that method for why the one-epoch blind spot that creates is accepted deliberately.
     */
    public fun isOwnEphemeralId(ring: KeyRing, day: Long, epoch: Int, observed: ByteArray): Boolean {
        if (observed.size != EPHEMERAL_ID_LENGTH) return false
        if (validateRing(ring) != null) return false
        var hit = false
        forEachWindowEpoch(day, epoch) { d, e ->
            when (val own = ephemeralId(ring, d, e)) {
                is ProtocolResult.Failure -> Unit
                is ProtocolResult.Success -> if (constantTimeEquals(own.value, observed)) hit = true
            }
        }
        return hit
    }

    /**
     * Visit every `(day, epoch)` in the accepted skew window around `(day, epoch)`, in ascending
     * order, rolling correctly across UTC day boundaries. Epoch 95 + 1 is epoch 0 of the next day;
     * epoch 0 - 1 is epoch 95 of the previous day. Days before 0 are skipped.
     *
     * Internal: the window is a receiver-side detail. Exposing it would invite a consumer to
     * widen it, and the window IS the replay window.
     */
    internal fun forEachWindowEpoch(day: Long, epoch: Int, action: (Long, Int) -> Unit) {
        for (delta in -ACCEPTED_EPOCH_SKEW..ACCEPTED_EPOCH_SKEW) {
            val absolute = day * EPOCHS_PER_DAY + epoch + delta
            if (absolute < 0L) continue
            action(absolute / EPOCHS_PER_DAY, (absolute % EPOCHS_PER_DAY).toInt())
        }
    }

    /**
     * `active(ring, d, e)` as an INDEX into [KeyRing.entries], or `-1` when the query is before
     * the ring's first entry. THE ONE implementation of the selection rule — [resolve] and
     * [KeyRing.pruneSupersededAt] both go through it, because two copies of "which entry is active
     * at `(d, e)`" is the exact shape of the bug `KEY_SCHEDULE.md` §8.4 spends a page on.
     *
     * Assumes a validated ring; callers validate first. Says nothing about whether the entry it
     * points at still holds key material — that is [resolve]'s business.
     */
    internal fun activeIndex(ring: KeyRing, day: Long, epoch: Int): Int {
        var found = -1
        for (i in ring.entries.indices) {
            val e = ring.entries[i]
            val notAfter = e.effDay < day || (e.effDay == day && e.effEpoch <= epoch)
            if (notAfter) found = i else break // entries are sorted ascending
        }
        return found
    }

    private fun resolve(ring: KeyRing, day: Long, epoch: Int): ProtocolResult<KeyRingEntry> {
        validateRing(ring)?.let { return ProtocolResult.Failure(it) }
        if (epoch < 0 || epoch >= EPOCHS_PER_DAY) {
            return ProtocolResult.Failure(ProtocolError.E_EPOCH_INDEX_OUT_OF_RANGE)
        }
        val at = activeIndex(ring, day, epoch)
        val found = if (at < 0) null else ring.entries[at]
        // A DESTROYED entry resolves to E_NO_ACTIVE_KEY, not to a zero key (§8.5.2). Both the
        // ordering metadata surviving and the derivation failing are required: the entry still
        // orders the ring, and the epochs it used to cover are no longer derivable BY US EITHER.
        // That is the point of destroying it, and it must fault loudly rather than quietly derive
        // an eid from 32 zero bytes — which would be a valid-looking, WRONG, and identical-for-
        // every-pruned-device identifier. Same code as "before the ring began" on purpose: from
        // the resolver's side those two states are the same state, "no key here".
        return if (found == null || found.isDestroyed) {
            ProtocolResult.Failure(ProtocolError.E_NO_ACTIVE_KEY)
        } else {
            ProtocolResult.Success(found)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Derivation
    // ---------------------------------------------------------------------------------------

    /**
     * `daily_key(d)`. `internal` because nothing outside this module needs a daily key except the
     * resolver, and a `public` one would let a caller derive an arbitrary epoch's id off the
     * ring's per-epoch discipline.
     *
     * WHAT `internal` ACTUALLY BUYS, STATED CORRECTLY (see [ephemeralIdFor] for the proof).
     * `internal` is a KOTLIN COMPILE-TIME property, not JVM access control. On Kotlin/Native it is
     * a real boundary. On the JVM the compiler emits this as a PUBLIC method with a mangled name,
     * so foreign Kotlin or Java can call `dailyKey$shared_debug(...)` with no reflection and no
     * friend path. Treat `internal` here as "not part of the contract, and loud when you cross
     * it" — NOT as "unreachable". Nothing that must be unreachable may rely on it.
     */
    internal fun dailyKey(accountKey: ByteArray, day: Long): ByteArray {
        val info = ByteArray(INFO_DAILY_KEY.size + 4)
        INFO_DAILY_KEY.copyInto(info)
        writeU32Be(info, INFO_DAILY_KEY.size, day)
        return hkdfSha256(ikm = accountKey, salt = SALT, info = info, length = DAILY_KEY_LENGTH)
    }

    /**
     * `ephemeral_id(d, e)`.
     *
     * `d` appears in the ephemeral-id `info` as well as inside `daily_key`. That is redundant and
     * deliberate: it costs four bytes of `info` and means a mis-derived `daily_key` can never
     * silently produce a valid-looking id for a different day.
     *
     * `internal` IS NOT A SECURITY BOUNDARY ON THE JVM, AND THIS IS NOT AN ARGUMENT — IT WAS
     * DEMONSTRATED. In the 2026-08-04 review, a foreign compilation unit with no reflection and no
     * friend path called `accountKey$shared_debug()`, `dailyKey$shared_debug()` and
     * `ephemeralIdFor$shared_debug()` directly, read the raw `account_key`, and derived an
     * arbitrary epoch's eid. Kotlin `internal` compiles to `public` + name mangling on the JVM;
     * only Kotlin/Native enforces it in the binary. Any comment in this repo that claims
     * `internal` makes something INACCESSIBLE off-module is wrong on Android and must be corrected
     * rather than repeated. The real containment for key material is the useKey/copy discipline in
     * [KeyRingEntry] plus destruction at the seam — not this keyword.
     */
    internal fun ephemeralIdFor(accountKey: ByteArray, day: Long, epoch: Int): ByteArray {
        val dk = dailyKey(accountKey, day)
        val info = ByteArray(INFO_EPHEMERAL_ID.size + 4 + 2)
        INFO_EPHEMERAL_ID.copyInto(info)
        writeU32Be(info, INFO_EPHEMERAL_ID.size, day)
        writeU16Be(info, INFO_EPHEMERAL_ID.size + 4, epoch)
        val out = hkdfSha256(ikm = dk, salt = SALT, info = info, length = EPHEMERAL_ID_LENGTH)
        dk.fill(0)
        return out
    }

    private fun writeU32Be(target: ByteArray, at: Int, value: Long) {
        target[at] = ((value ushr 24) and 0xFF).toByte()
        target[at + 1] = ((value ushr 16) and 0xFF).toByte()
        target[at + 2] = ((value ushr 8) and 0xFF).toByte()
        target[at + 3] = (value and 0xFF).toByte()
    }

    private fun writeU16Be(target: ByteArray, at: Int, value: Int) {
        target[at] = ((value ushr 8) and 0xFF).toByte()
        target[at + 1] = (value and 0xFF).toByte()
    }
}
