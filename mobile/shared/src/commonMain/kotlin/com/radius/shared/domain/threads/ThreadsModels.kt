package com.radius.shared.domain.threads

import com.radius.shared.domain.UlidString
import kotlinx.coroutines.flow.Flow

/**
 * THREADS — one inbox, both origins, transport labelled. Calls live here too.
 *
 * The single-inbox rule matters: a user must never have to remember "was that person online or
 * nearby". Origin is a label on the thread, not a separate app section.
 */

/** Where a thread came from, and how a given message travelled. Always shown, never inferred. */
public enum class Transport {
    /** Delivered over BLE GATT, peer-to-peer, no internet involved. */
    RADAR_BLE,

    /** Delivered through the Radius gateway. Still E2EE; the server holds ciphertext only. */
    ONLINE,
}

public enum class Direction {
    INBOUND,
    OUTBOUND,
}

public class ThreadSummary(
    public val id: UlidString,
    public val peerAccountId: UlidString,
    public val peerDisplayName: String,
    /** How this thread started. A thread may later carry messages over the other transport. */
    public val origin: Transport,
    public val lastActivityEpochMs: Long,
    public val unreadCount: Int,
    public val isPeerVerified: Boolean,
)

/** Anything that can appear on a thread timeline. */
public sealed class ThreadItem {
    public abstract val id: UlidString
    public abstract val threadId: UlidString
    public abstract val atEpochMs: Long

    /**
     * A message.
     *
     * [body] is plaintext ON DEVICE ONLY, after local decryption. Safety invariant 9: the server
     * holds ciphertext and nothing else. Keys live in Keystore/Keychain, never sync, never leave
     * the device. Do not add a field that would let a plaintext body reach a network call.
     */
    public class Message(
        override val id: UlidString,
        override val threadId: UlidString,
        override val atEpochMs: Long,
        public val direction: Direction,
        public val transport: Transport,
        public val body: String,
        public val deliveryState: DeliveryState,
    ) : ThreadItem()

    /**
     * A call, as a timeline entry.
     *
     * THERE IS NO CONTENT FIELD AND THERE NEVER WILL BE. Calling invariant C2: calls are never
     * recorded, no content column exists by design. This is a wiretap-law and trust position, not
     * a backlog item. If a ticket asks for call recording, escalate to a human (ORCHESTRATION §8).
     *
     * Phone numbers are never exchanged (C1) — hence no number field either.
     */
    public class CallEvent(
        override val id: UlidString,
        override val threadId: UlidString,
        override val atEpochMs: Long,
        public val direction: Direction,
        public val outcome: CallOutcome,
        public val durationSeconds: Int,
        public val wasVideo: Boolean,
        /** True when TURN relayed the media. Cost signal and a transparency signal. */
        public val wasRelayed: Boolean,
    ) : ThreadItem()
}

public enum class DeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}

/**
 * Note [REQUESTED] and [DECLINED]: calling invariant C3 — invited, never cold-rung. A call request
 * is an explicit, acceptable, declinable event that precedes any ring or SDP exchange. The
 * timeline shows the request, so "they called me out of nowhere" is not a state that can occur.
 */
public enum class CallOutcome {
    REQUESTED,
    DECLINED,
    MISSED,
    COMPLETED,
    FAILED,
}

/**
 * Read/command model for Threads. Implementation lands with the Messaging task; there is none yet.
 */
public interface ThreadsInbox {
    public fun threads(): Flow<List<ThreadSummary>>

    public fun items(threadId: UlidString): Flow<List<ThreadItem>>

    /**
     * Send over [preferred] when available, otherwise the other transport. The chosen transport is
     * always reflected back on the resulting [ThreadItem.Message] and labelled in the UI.
     */
    public suspend fun send(threadId: UlidString, body: String, preferred: Transport)

    public suspend fun markRead(threadId: UlidString)
}
