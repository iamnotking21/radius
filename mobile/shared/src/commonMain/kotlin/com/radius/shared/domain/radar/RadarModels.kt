package com.radius.shared.domain.radar

import com.radius.shared.domain.UlidString
import kotlinx.coroutines.flow.Flow

/**
 * RADAR — BLE proximity. The moat. Works with no internet.
 *
 * This file is a SAFETY SURFACE. Four of the ten invariants are enforced by the shape of these
 * types. Read the notes before adding a field.
 */

/**
 * Distance is FOUR BANDS. Never a number, never a continuum. Safety invariant 2.
 *
 * Thresholds (root CLAUDE.md HARD NUMBERS), applied with hysteresis so a node does not flicker
 * between bands on normal RSSI noise:
 *   HERE   ≥ -55 dBm
 *   CLOSE  ≥ -70 dBm
 *   AROUND ≥ -82 dBm
 *   EDGE   ≥ -95 dBm
 *
 * The banding + hysteresis implementation is shared core logic and lands with the Radar task.
 * It has exactly one implementation — that is the point of ADR-007. Do not reimplement it in
 * Swift or in Compose "just for the preview".
 */
public enum class ProximityBand {
    HERE,
    CLOSE,
    AROUND,
    EDGE,
}

/**
 * One person on the radar.
 *
 * DELIBERATELY ABSENT, AND A PR THAT ADDS ANY OF THEM IS BLOCKED:
 *  - bearing, heading, azimuth, direction (invariants 1 and 3)
 *  - latitude, longitude, altitude, accuracy (invariant 1)
 *  - raw RSSI (invariant 2 — the UI must not be able to derive a real distance)
 *  - anything that survives ephemeral-id rotation and links two sessions (invariants 4, 5)
 *
 * @property nodeId a per-session handle for UI identity only. It is NOT the peer's account id and
 *   it does not survive an app restart or an ephemeral-id rotation without a completed handshake.
 * @property displayAngleTurns position on the radar dial, in turns [0.0, 1.0). RANDOM PER SESSION.
 *   Invariant 3. It encodes nothing. It is reseeded every session precisely so that a user cannot
 *   triangulate by walking. Never derive it from signal strength, arrival order, or anything else
 *   correlated with the physical world.
 * @property displayedMetres what the UI shows. Band midpoint plus jitter, produced by shared core.
 *   It is a hedged illustration, not a measurement, and the copy around it must say so.
 * @property isVerified Radar is verified-accounts-only (invariant 6). An unverified node should
 *   never have been resolved in the first place; this flag exists so the UI can fail loudly rather
 *   than silently if one appears.
 */
public class RadarNode(
    public val nodeId: String,
    public val band: ProximityBand,
    public val displayAngleTurns: Float,
    public val displayedMetres: Int,
    public val lastSeenEpochMs: Long,
    public val isVerified: Boolean,
    public val waveState: WaveState,
)

/**
 * Chat unlocks ONLY on mutual wave. Safety invariant 6. There is no state in this enum that opens
 * a thread one-sidedly, and adding one would be a product-level safety regression, not a feature.
 */
public enum class WaveState {
    NONE,
    SENT,
    RECEIVED,
    MUTUAL,
}

/**
 * Ghost mode: visible or not. Must be reachable in ≤1 tap from the Radar screen (invariant 10).
 * Not buried in Settings. Not behind a confirmation dialog. One tap, from Radar.
 */
public enum class RadarVisibility {
    VISIBLE,
    GHOST,
}

/** Whether the radio is currently running, and why not if it is not. */
public enum class RadarRunState {
    STOPPED,
    STARTING,
    RUNNING,
    /** Adapter off, permission denied, or hardware unsupported — the UI must explain which. */
    BLOCKED,
}

/**
 * Read/command model for Radar. Implementation lands with the Radar task; there is none yet.
 *
 * A11Y CONTRACT for consumers: the animated dial is decorative. Every node in [nodes] must also be
 * reachable as a plain list item with a text label, on both platforms. TalkBack and VoiceOver users
 * get the same information, not a "radar unavailable" apology.
 */
public interface RadarController {
    public fun runState(): Flow<RadarRunState>

    /** Current visible nodes, already banded, jittered and angle-assigned. */
    public fun nodes(): Flow<List<RadarNode>>

    public fun visibility(): Flow<RadarVisibility>

    /** Start scanning + advertising. Platform is responsible for permissions and foreground service. */
    public suspend fun start()

    public suspend fun stop()

    /** The ≤1-tap control. Takes effect immediately: stops advertising, keeps nothing queued. */
    public suspend fun setVisibility(visibility: RadarVisibility)

    /** Send a wave. Chat unlocks only when the other side does the same. */
    public suspend fun wave(nodeId: String)

    /**
     * Block is enforced at the key-resolution layer (invariant 7): a blocked peer's ephemeral id
     * stops resolving, so they become literally invisible rather than merely hidden.
     */
    public suspend fun block(nodeId: String)
}

/**
 * An encounter, persisted locally.
 *
 * RETENTION IS A HARD RULE, NOT A PREFERENCE: 30 days, 24 hours on the free tier, then HARD delete.
 * "Hard" means rows gone, not a soft-delete flag. The purge job is shared core's responsibility.
 */
public class Encounter(
    public val id: UlidString,
    public val peerAccountId: UlidString?,
    public val firstSeenEpochMs: Long,
    public val lastSeenEpochMs: Long,
    public val closestBand: ProximityBand,
    public val expiresAtEpochMs: Long,
)
