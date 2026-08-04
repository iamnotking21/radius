package com.radius.shared.domain.discover

import com.radius.shared.domain.UlidString
import kotlinx.coroutines.flow.Flow

/**
 * DISCOVER — online dating, worldwide.
 *
 * Product shape that these types encode and must keep encoding:
 *  - a FINITE daily set. Not an infinite feed.
 *  - NO swipe deck. Banned in root CLAUDE.md. There is no `swipe()`, no `pass()` stack, no
 *    card-index cursor on this model, and there must never be one.
 *  - no expiry countdown, no streak, no "someone viewed you". All banned dark patterns.
 *    If a field here would only exist to manufacture urgency, it does not go in.
 */
public class DiscoverCandidate(
    public val id: UlidString,
    public val displayName: String,
    public val ageYears: Int,
    /**
     * Coarse location, geohash precision 5 (~5km cell) — the maximum the product permits.
     * Safety invariant 1: no latitude, no longitude, no bearing, anywhere, ever.
     */
    public val cityGeohash5: String,
    /** Opaque media references. Resolution and EXIF handling happen server-side (invariant 8). */
    public val photoRefs: List<String>,
    public val bio: String,
    /** Radar requires verified accounts (invariant 6); Discover surfaces the badge. */
    public val isVerified: Boolean,
)

/**
 * One day's finite set.
 *
 * @property dateIso the local calendar date the set was issued for, `YYYY-MM-DD`.
 * @property candidates the whole set. Small enough to hold in memory. Finite, by design.
 * @property decidedCount how many the user has already acted on. Used for honest, factual
 *   endowed-progress framing ("3 of 12 seen") — never to imply scarcity that does not exist.
 */
public class DailyDiscoverSet(
    public val dateIso: String,
    public val candidates: List<DiscoverCandidate>,
    public val decidedCount: Int,
)

/** The user's decision on one candidate. Deliberately two-valued: there is no super-like ladder. */
public enum class DiscoverDecision {
    WAVE,
    PASS,
}

/**
 * Read model for the Discover mode. Implementation lands with the Discover task; there is none yet.
 *
 * SWIFT: `Flow` here is for Android. iOS consumes these through
 * [com.radius.shared.core.FlowAdapter]-returning accessors on the core.
 */
public interface DiscoverFeed {
    /** Today's set, re-emitting when the server issues a new one or a decision is recorded. */
    public fun dailySet(): Flow<DailyDiscoverSet>

    /** Record a decision. Idempotent per candidate. */
    public suspend fun decide(candidateId: UlidString, decision: DiscoverDecision)
}
