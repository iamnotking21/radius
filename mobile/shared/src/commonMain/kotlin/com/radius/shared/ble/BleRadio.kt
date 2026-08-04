package com.radius.shared.ble

import com.radius.shared.protocol.AdvertiseRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform radio. ONE of the two things ADR-007 explicitly does NOT share.
 *
 * `CoreBluetooth` and `android.bluetooth.le` differ in threading model, permission model,
 * background execution, advertising API shape and scan-callback semantics. There is no honest
 * abstraction over them, so this is an `expect` class with two hand-written `actual`s.
 * KMP DID NOT SHRINK THE BLE WORK — see mobile/CLAUDE.md. Do not plan as if it did.
 *
 * SCOPE — deliberately tiny. This layer moves bytes and nothing else:
 *  - it does NOT parse the advertisement. Decoding `[ver:1][ephemeral_id:16][txpower:1][flags:1]`
 *    belongs to `mobile/shared/protocol/`, owned by ble-protocol. That module has landed;
 *    this layer calls its codec and does not have one of its own.
 *  - it does NOT band, jitter, or hysteresis. That is shared core logic, layered on top.
 *  - it does NOT decide duty cycle. It takes a [DutyProfile] and obeys it.
 *  - it does NOT know what an account is.
 *
 * THE TWO ACTUALS ARE NOT SYMMETRIC. Orchestrator ruling, decision row 21:
 *
 *  - ANDROID: the `actual` IS the radio. It calls `android.bluetooth.le` directly, in Kotlin.
 *    Constructor takes a `Context`.
 *  - iOS: the `actual` is a THIN ADAPTER over an injected [BleRadioPort] that is IMPLEMENTED IN
 *    SWIFT. Kotlin never touches CoreBluetooth. Constructor takes a [BleRadioPort].
 *
 * Why the asymmetry is correct rather than sloppy: the radio is the moat, so it gets written
 * where the tooling is best. On iOS that means Swift — Instruments Energy Log for the CI-gated
 * <4%/hr battery budget, a real debugger, and native `CBCentralManager` state-restoration
 * semantics. CoreBluetooth delegates written through Kotlin/Native objc-interop would be the
 * single hardest-to-debug code in the product, sitting exactly where we cannot afford mystery.
 * On Android none of that applies: Kotlin *is* the native language and the platform API is
 * already ours. Adding a port there would be indirection for symmetry's sake.
 *
 * Constructors therefore differ per platform, so none is declared here. The platform layer builds
 * the radio and injects it into [com.radius.shared.core.RadiusCore]. Constructor injection only —
 * ADR-007, no Koin.
 *
 * SWIFT INTEROP: Swift constructs `BleRadio(port: CoreBluetoothRadio())` and hands it to the core.
 * Swift is not expected to read [sightings]; if it ever needs to, wrap it in
 * [com.radius.shared.core.FlowAdapter] rather than exporting the `Flow`.
 */
public expect class BleRadio {

    /** Coarse radio availability. Drives UI state and the adaptive duty controller. */
    public val availability: StateFlow<RadioAvailability>

    /**
     * Whether this device is permitted to transmit at all. Starts at
     * [AdvertiseRole.SCAN_ONLY] on EVERY platform and can only be widened by an explicit
     * [setAdvertiseRole] call. See [AdvertiseRole] for why this is not a boolean and not
     * a constructor parameter.
     */
    public val advertiseRole: StateFlow<AdvertiseRole>

    /**
     * What the transmitter is actually doing right now, including why it stopped.
     *
     * This exists because a silent advertising failure is the single nastiest Radar bug: the user
     * sees everyone, nobody sees them, the app looks fine, and the only symptom is that they never
     * get waved at. `startAdvertising` returning [BleOutcome.Ok] is NOT proof of transmission —
     * `AdvertiseCallback.onStartFailure` arrives later, and the per-epoch restarts required by
     * `KEY_SCHEDULE.md` §4.2 can fail long after the original call returned.
     */
    public val advertiseState: StateFlow<AdvertiseState>

    /**
     * Every advertisement matching the Radius service UUID, as received. Hot, conflated per
     * device by the platform stack, never replayed to late subscribers.
     *
     * Emission rate under a crowd is high. Consumers must debounce; this layer does not.
     */
    public val sightings: Flow<RawSighting>

    /**
     * Grant or revoke this device's permission to transmit. DECISION 35, one advertiser per
     * account (`KEY_SCHEDULE.md` §9.3).
     *
     * Revoking to [AdvertiseRole.SCAN_ONLY] MUST stop any live advertisement IMMEDIATELY and
     * synchronously, not at the next epoch boundary. §9.3 is explicit about the ordering: the
     * losing device stops at once, the gaining device starts at the next boundary, so that the two
     * never emit the same ephemeral id even for one advertising interval.
     *
     * [source] is recorded, not validated — it exists so that `grep -rn "DEBUG_SPIKE_HARNESS"`
     * finds every non-production path that ever turned a transmitter on. That is a review tool,
     * not a security control; see the honest-limit note in `KEY_SCHEDULE.md` §9.3.
     */
    public fun setAdvertiseRole(
        role: AdvertiseRole,
        source: AdvertiseRoleSource,
    ): BleOutcome

    /**
     * Register the sink for 15-minute UTC epoch boundaries. Pass `null` to clear.
     *
     * The radio is the only component in the system that owns a UTC-boundary timer, so it is the
     * only thing that can drive per-epoch obligations. It does not perform them, and it must not
     * learn what a key is in order to trigger one. See [EpochBoundaryListener].
     */
    public fun setEpochBoundaryListener(listener: EpochBoundaryListener?)

    /**
     * Begin advertising [request]. Idempotent: a second call replaces the active advertisement.
     *
     * REJECTS with [BleOutcome.Reason.ROLE_SCAN_ONLY] unless [advertiseRole] is
     * [AdvertiseRole.ADVERTISE]. Fail closed, on every platform, including debug builds and
     * including offline.
     *
     * The implementation OWNS the epoch boundary from this point on: it must stop, re-derive from
     * [AdvertiseRequest.payloadSource], rebuild and restart at every 15-minute UTC boundary until
     * [stopAdvertising]. That restart is the only lever either platform has on RPA rotation
     * (`KEY_SCHEDULE.md` §4.2) and it is not optional.
     */
    public fun startAdvertising(request: AdvertiseRequest): BleOutcome

    /** Stop advertising. Safe to call when not advertising. */
    public fun stopAdvertising(): BleOutcome

    /** Begin scanning. Idempotent: a second call replaces the active scan. */
    public fun startScan(request: ScanRequest): BleOutcome

    /** Stop scanning. Safe to call when not scanning. */
    public fun stopScan(): BleOutcome

    /** Release all platform resources. The radio is unusable afterwards. */
    public fun shutdown()
}

/**
 * THE SWIFT-IMPLEMENTED RADIO PORT. Orchestrator ruling, decision row 21.
 *
 * `mobile/ios/Sources/Radar/CoreBluetoothRadio.swift` (ios-swift's file, not mine) conforms to
 * this protocol. The iOS `actual BleRadio` is a thin adapter that delegates to it and turns the
 * pushed callbacks into the `Flow`s that shared core consumes. Kotlin never imports CoreBluetooth.
 *
 * DESIGN RULES, all of them load-bearing for interop:
 *
 *  - COMMANDS PULL, EVENTS PUSH. Swift cannot practically implement a Kotlin `Flow`-valued
 *    property, so sightings are pushed into Kotlin through [BleRadioListener] instead of exposed
 *    as a stream Swift would have to produce.
 *  - NO SUSPEND FUNCTIONS. A Kotlin `suspend` function in a protocol Swift must conform to
 *    becomes a completion-handler signature with awkward cancellation semantics. Every method
 *    here is synchronous and returns a [BleOutcome] instead of throwing.
 *  - VALUE TYPES ONLY. [AdvertiseRequest], [ScanRequest], [RawSighting], [BleOutcome] and
 *    [RadioAvailability] are the entire vocabulary.
 *  - `ByteArray` crosses as Swift `KotlinByteArray`, NOT `Data`. ios-swift will need
 *    `Data` ⟷ `KotlinByteArray` helpers on their side. Flagged for the shared API v0 gate; if it
 *    proves painful the fix is a hex/base64 `String` payload, which is uglier but boring.
 *
 * THREADING: implementations may call [BleRadioListener] from any queue — CoreBluetooth delivers
 * on whatever queue the central manager was created with. The Kotlin adapter is safe under that.
 *
 * MEMORY, READ THIS: the adapter holds the port, and the port holds the listener, which holds the
 * adapter. That is a retain cycle straddling Kotlin/Native and Swift ARC, and it leaks the radio
 * for the life of the process. Break it one of two ways — hold the listener `weak` on the Swift
 * side, or drop it in [shutdown]. [shutdown] is contractually required to release it either way.
 */
public interface BleRadioPort {

    /**
     * NOTE, DELIBERATE ASYMMETRY WITH [BleRadio]: there is no `setAdvertiseRole` here, and
     * that is not an omission.
     *
     * The one-advertiser-per-account gate (decision 35) is enforced in SHARED KOTLIN, above this
     * interface, in both `actual`s. Swift is never handed the opportunity to implement the check
     * and get it wrong, and `mobile/ios` cannot start a transmitter by failing to consult a role
     * it was never given. A Swift port that receives `startAdvertising` may assume the gate has
     * already passed.
     *
     * The same argument applies to the epoch loop: [BleRadio] resolves the frame from
     * [AdvertisePayloadSource] and calls this method once per epoch with a fresh
     * [AdvertiseRequest]. A port MUST perform a full advertising restart on each such call
     * (`KEY_SCHEDULE.md` §4.2) and MUST NOT run a rotation timer of its own.
     *
     * ---
     * Register the sink for sightings and availability changes. Called once, at construction.
     */
    public fun attach(listener: BleRadioListener)

    /**
     * Put [frame] on air. **Takes resolved bytes, NOT an [AdvertiseRequest].**
     *
     * This is the narrowest signature that does the job, and narrow is the point: the Swift port
     * never sees [AdvertisePayloadSource], never computes an epoch index, and cannot cache a frame
     * across a boundary because it is handed a new one each time. Every rule that could be got
     * wrong — the authority gate, the epoch schedule, the 19-byte length check, the CONNECTABLE
     * cross-check — is enforced once, in shared Kotlin, above this line. The port's entire job is
     * "these bytes, this UUID, this cadence, go".
     *
     * A port MUST perform a FULL advertising restart on every call (`KEY_SCHEDULE.md` §4.2) — tear
     * the advertising set down and build a new one, never patch the payload of a live set. That
     * restart is the only lever either platform has on RPA rotation.
     *
     * @param frame exactly 19 bytes, already validated. Do not mutate.
     */
    public fun startAdvertising(
        frame: ByteArray,
        serviceUuid16: String,
        duty: DutyProfile,
    ): BleOutcome

    public fun stopAdvertising(): BleOutcome

    public fun startScan(request: ScanRequest): BleOutcome

    public fun stopScan(): BleOutcome

    /** Release all platform resources AND drop the listener reference. See the memory note above. */
    public fun shutdown()
}

/**
 * Events pushed from the platform radio into shared core. Implemented in KOTLIN, called from
 * SWIFT — the mirror image of [BleRadioPort].
 */
public interface BleRadioListener {

    /** One advertisement observed. May be called at high frequency from any queue. */
    public fun onSighting(sighting: RawSighting)

    /** Radio availability changed — adapter toggled, authorisation granted or revoked. */
    public fun onAvailabilityChanged(availability: RadioAvailability)
}

/**
 * One raw advertisement observation.
 *
 * Value type. Safe across the Swift boundary. Contains no name, no account id, no coordinate and
 * no stable identifier — safety invariant 4.
 *
 * @property payload the service-data bytes exactly as received, undecoded, ≤26 bytes. The wire
 *   shape is `[ver:1][ephemeral_id:16][txpower:1][flags:1]` per 40-contracts "BLE wire v0", but
 *   this layer deliberately does not parse it: `mobile/shared/protocol/` owns the codec and will
 *   turn this into a resolved sighting with an ephemeral id. Two parsers = the divergence bug
 *   ADR-007 exists to prevent.
 * @property rssiDbm received signal strength, dBm, as reported by the platform. Raw. Banding,
 *   hysteresis and display jitter happen upstream in shared core — never here, and the raw value
 *   never reaches the UI (safety invariant 2). IT IS ALSO REDACTED FROM [toString] — see below.
 * @property observedAtEpochMs monotonic-ish wall clock of observation, milliseconds since epoch.
 */
public class RawSighting(
    public val payload: ByteArray,
    public val rssiDbm: Int,
    public val observedAtEpochMs: Long,
) {
    /**
     * BOTH THE PAYLOAD AND THE RSSI ARE REDACTED. Decision 28.
     *
     * The payload carries a rotating ephemeral id; printing it turns a 15-minute pseudonym into a
     * durable record the moment anything captures stdout.
     *
     * **RSSI IS LOCATION DATA AND IS REDACTED FOR THE SAME REASON, NOT A WEAKER ONE.** An earlier
     * version of this method printed it, with a comment claiming it "is not sensitive and is
     * useful in logs". That was wrong on both counts. `BANDING.md` §6.3: three receivers with raw
     * RSSI multilaterate to a POSITION, not a band; one receiver plus time and motion is nearly as
     * good; and sub-band resolution is stable enough over short intervals to link identities
     * across an ephemeral-id rotation, partially undoing the whole key schedule. Redacting the
     * identifier and printing the distance-proxy next to it, on a millisecond timestamp, is a
     * distinction without a difference.
     *
     * This class in particular. It is the high-frequency object flowing through
     * [BleRadioListener.onSighting] — it is exactly what gets string-interpolated at 3am while
     * someone debugs discovery, and exactly what a crash reporter attaches as breadcrumb context.
     * The rule in `BANDING.md` §6.3 is explicit that a debug-only accessor "is not safer — it is
     * the same accessor with a comment on it", and the same is true of a debug-only log line.
     *
     * The TIMESTAMP survives deliberately, and the reasoning is worth stating rather than leaving
     * as an inconsistency: with the payload redacted there is no peer identity for it to attach
     * to, so it degrades to "the radio saw something at time T" — which carries no distance, no
     * direction and no pseudonym, and is the one field that makes a log usable for the ordering
     * and gap analysis these logs exist for. The §6.3 epoch-bucketing rule is about data LEAVING
     * the device; nothing here does.
     *
     * The Phase 0 spike harness is not an exception to this — it is a separate, debug-source-set,
     * field-by-field writer to a local file behind an `adb` cable, and it never calls [toString].
     */
    override fun toString(): String =
        "RawSighting(payload=<${payload.size}B redacted>, rssiDbm=<redacted>, at=$observedAtEpochMs)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawSighting) return false
        return rssiDbm == other.rssiDbm &&
            observedAtEpochMs == other.observedAtEpochMs &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = payload.contentHashCode()
        result = 31 * result + rssiDbm
        result = 31 * result + observedAtEpochMs.hashCode()
        return result
    }
}

/**
 * What to broadcast.
 *
 * @property payloadSource per-epoch frame supplier. NOT a `ByteArray` — see
 *   [AdvertisePayloadSource] for why that difference is load-bearing rather than stylistic.
 * @property serviceUuid16 the assigned 16-bit service UUID, hex without the base UUID, e.g.
 *   "FD6F"-shaped. REQUIRED — iOS background scanning does not function without a service filter.
 *   ble-protocol owns the real value; `SPEC.md` §4.1 currently says 0xFDA9, PROVISIONAL, MUST NOT
 *   SHIP.
 * @property duty advertising cadence.
 */
public class AdvertiseRequest(
    public val payloadSource: AdvertisePayloadSource,
    public val serviceUuid16: String,
    public val duty: DutyProfile,
)

/**
 * Supplies the 19-byte frame for one epoch, and only for one epoch.
 *
 * THIS IS THE KEY RING'S REPRESENTATION IN THE RADIO PORT — 40-contracts CONSISTENCY GAP 3, the
 * half of it that is not [AdvertiseRole]. It deliberately does NOT mention keys, rings, kids
 * or accounts: the radio must not learn what an account is. What it encodes is the one property
 * the radio is responsible for, which is that **a frame is scoped to an epoch and the radio cannot
 * hold one across a boundary** — because it never holds one at all, it holds a supplier.
 *
 * This is the port-level mirror of `ephemeralId(ring, day, epoch)` in `SPEC.md` §8.1, and for the
 * identical reason: 40-contracts requires per-epoch ring evaluation and warns that resolving the
 * active key once and caching it "yields exactly ONE wrong epoch per rotation per user and passes
 * every test that does not straddle a seam". A `payload: ByteArray` field on this class is exactly
 * that cached value, one layer down. Removing it makes the bug unwritable rather than merely
 * forbidden.
 *
 * CONTRACT ON THE IMPLEMENTER (`mobile/shared/protocol/`, once it lands):
 *  - MUST return the frame derived for `(dayIndex, epochIndex)` and no other epoch.
 *  - MUST return `null` rather than a stale or guessed frame when no active key covers that epoch
 *    (`E_NO_ACTIVE_KEY`). The radio then stops transmitting. Silence is the correct failure;
 *    broadcasting last epoch's identity against a possibly-rotated MAC is the §4.1 bridging attack
 *    performed by our own code.
 *  - MUST be cheap and non-blocking. It is called on a boundary, once, per advertisement restart.
 *    No `suspend` — 40-contracts bans `suspend` in the implemented direction.
 *
 * The parameter types are `(Long, Int)` to match
 * `KeySchedule.ephemeralId(ring, day: Long, epoch: Int)` EXACTLY, so the wiring is a pass-through
 * with no narrowing conversion in it. A conversion is somewhere a bug can live.
 *
 * @param dayIndex days since the Unix epoch, UTC (`u32 day` in `SPEC.md` §8.1).
 * @param epochIndex 15-minute index within that UTC day, 0..95.
 */
public fun interface AdvertisePayloadSource {
    public fun frameForEpoch(dayIndex: Long, epochIndex: Int): ByteArray?
}

/**
 * Called once at every 15-minute UTC epoch boundary, on every device that has a running radio.
 *
 * ## Why this exists rather than the radio just doing it
 *
 * `KeyRing.pruneSupersededAt(day, epoch)` (`KEY_SCHEDULE.md` §8.5.2) destroys the key material of
 * superseded ring entries. It is a CALLER OBLIGATION: the library cannot self-trigger, so a
 * platform that never calls it retains every key the device has ever held, and ADR-008 M4's
 * rotation then bounds nothing — one in-process compromise yields retroactive derivation across
 * the device's whole rotation history. The fix is inert without a caller.
 *
 * The radio is the natural caller because it owns the only UTC-boundary timer in the system. It is
 * emphatically NOT the natural owner of the ring — this layer "moves bytes and nothing else" and
 * must not learn what an account or a key is. So the radio announces the boundary and something
 * that holds a ring acts on it. That split is the whole point of this interface.
 *
 * ## WHY IT IS NOT PART OF [AdvertisePayloadSource], WHICH IS THE OBVIOUS PLACE
 *
 * `AdvertisePayloadSource` is already called with `(day, epoch)` at every boundary, so pruning
 * from inside `frameForEpoch` looks free. It is a hole. **`frameForEpoch` is only called on a
 * device that is ADVERTISING**, and under decision 35 exactly one device per account advertises
 * while every other device is `SCAN_ONLY`. A scan-only device still holds a full key ring — it
 * needs one for `isOwnEphemeralId`, and it holds one against the day the advertising role is
 * transferred to it. Tying destruction to advertising would therefore leave the keys undestroyed
 * on the majority of devices, which is precisely the population decision 35 creates. The
 * boundary is a DEVICE-scoped event and the listener is device-scoped to match.
 *
 * ## Contract on the implementer
 *
 *  - MUST be fast and non-blocking. It runs on the boundary, in front of the advertising restart,
 *    and `KEY_SCHEDULE.md` §8.5.1 requires that a rotation not be observable as a change in radio
 *    behaviour — which includes not stalling the restart.
 *  - MUST NOT throw. It is invoked from a timer with no caller to report to.
 *  - MUST be safe to call on EVERY boundary, not only on seams. `pruneSupersededAt` is idempotent
 *    and cheap when there is nothing to do, and "only on seams" would need seam detection here,
 *    which is a second copy of the ring logic in the wrong module.
 *
 * ## SIBLING OBLIGATION, NOT YET WIREABLE
 *
 * `KeyRing.destroyAllKeyMaterial()` (decision 55, ADR-008 M5) must be called on LOGOUT and on
 * ACCOUNT DELETION — a device that keeps its ring after account deletion is the same retroactive
 * -derivation hole reached by a different door. There is no auth, logout or delete path in the
 * codebase yet, so there is nothing to call it from. Recorded here, next to its twin, because
 * this is where someone implementing that path will be looking.
 */
public fun interface EpochBoundaryListener {
    public fun onEpochBoundary(dayIndex: Long, epochIndex: Int)
}

// ---------------------------------------------------------------------------------------------
// `AdvertiseRole` IS NOT DEFINED HERE. It is `com.radius.shared.protocol.AdvertiseRole`, and it is
// ble-protocol's — `SPEC.md` §8.1, `KEY_SCHEDULE.md` §9.3, decision 35.
//
// 40-contracts CONSISTENCY GAP 3 says the radio port has no representation of the advertising
// role. This closes it by CONSUMING the owner's type rather than by inventing a parallel one. An
// earlier draft of this file did define its own `AdvertiseAuthority`, on the reasoning that a
// "radio permission" and a "server-issued role" are different concepts. They are not different
// enough to be worth two enums: a second type would need a mapping function, and a mapping
// function between two two-valued fail-closed enums is a place where SCAN_ONLY can become
// ADVERTISE by a typo, in the one control that keeps two devices from broadcasting the same
// ephemeral id from two locations.
//
// The property that matters survives the change intact, because ble-protocol built it in for the
// same reason: SCAN_ONLY is ordinal 0, so the accidental value — a zeroed field, a defaulted
// parameter, a fresh decode — is the SAFE one. Pinned by AdvertiseGuardTest across both targets.
// ---------------------------------------------------------------------------------------------

/**
 * Where an [AdvertiseRole] grant came from. Recorded for audit, never validated.
 *
 * The point of the enum is the grep: every path that has ever switched a transmitter on is
 * findable by name, and a non-production one is impossible to mistake for a production one in a
 * diff.
 */
public enum class AdvertiseRoleSource {
    /** The real path: role delivered alongside key issuance, `KEY_SCHEDULE.md` §2.2. */
    SERVER_GRANT,

    /**
     * Phase 0 spike harness only. Present because the spike must transmit before issuance exists.
     * MUST NOT appear outside a debug source set. If you find this in `src/main`, that is a bug.
     */
    DEBUG_SPIKE_HARNESS,
}

/** What the transmitter is doing. See [BleRadio.advertiseState]. */
public enum class AdvertiseStatus {
    /** Not advertising, and not asked to. */
    STOPPED,

    /** Command accepted by the platform; `onStartFailure` has not (yet) fired. */
    STARTING,

    /** Platform confirmed the advertising set is live. */
    ADVERTISING,

    /** Asked to advertise, and not transmitting. [AdvertiseState.reason] says why. */
    FAILED,
}

/**
 * Transmitter state, with the failure reason attached.
 *
 * @property epochIndex the 15-minute epoch index the live frame was derived for, or -1 when not
 *   advertising. Present so a consumer can detect a stalled epoch loop — an [epochIndex] that
 *   stops advancing while [status] is [AdvertiseStatus.ADVERTISING] means the device is
 *   broadcasting a stale identity, which is invariant 5 failing from our side rather than the
 *   controller's.
 */
public class AdvertiseState(
    public val status: AdvertiseStatus,
    public val reason: BleOutcome.Reason?,
    public val detail: String?,
    public val epochIndex: Int,
) {
    override fun toString(): String = "AdvertiseState($status, $reason, $detail, epoch=$epochIndex)"
}

/**
 * What to listen for.
 *
 * @property serviceUuid16 filter. Never scan unfiltered — it is a battery and privacy disaster and
 *   on iOS it does not survive backgrounding at all.
 * @property duty scan cadence.
 */
public class ScanRequest(
    public val serviceUuid16: String,
    public val duty: DutyProfile,
)

/**
 * Cadence contract, from root CLAUDE.md HARD NUMBERS: adv 250ms foreground / 1000ms background,
 * scan duty 30%. The adaptive controller in shared core picks the profile; the radio maps it onto
 * whatever coarse knobs the platform actually offers (Android has three advertise modes, iOS has
 * none). Battery budget: <4%/hr scanning, <1%/day idle. CI gates this.
 */
public enum class DutyProfile {
    /** App visible, user is looking at Radar. ~250ms advertising, aggressive scan window. */
    FOREGROUND,

    /** Backgrounded or screen off. ~1000ms advertising, reduced scan window. */
    BACKGROUND,

    /**
     * Stationary, low battery, or no peer seen for 10 minutes. Deepest duty cut short of stopping.
     * The adaptive-duty requirement in mobile/CLAUDE.md.
     */
    CONSERVE,
}

/** Coarse radio state. Anything finer is platform detail and stays inside the `actual`. */
public enum class RadioAvailability {
    /** Ready to advertise and scan. */
    READY,

    /** Hardware present, adapter off. Recoverable by the user. */
    ADAPTER_OFF,

    /** Runtime permission missing. Recoverable by the user. */
    PERMISSION_DENIED,

    /** No BLE hardware, or peripheral role unsupported. Not recoverable. */
    UNSUPPORTED,

    /** Not yet determined — iOS reports this until the central manager settles. */
    UNKNOWN,
}

/** Result of a radio command. No exceptions across the interop boundary. */
public sealed class BleOutcome {
    public data object Ok : BleOutcome()

    public class Rejected(public val reason: Reason, public val detail: String?) : BleOutcome() {
        override fun toString(): String = "Rejected($reason${detail?.let { ": $it" } ?: ""})"
    }

    public enum class Reason {
        ADAPTER_OFF,
        PERMISSION_DENIED,
        UNSUPPORTED,
        /** Payload exceeded the 26-byte advertisement budget, or the platform rejected its shape. */
        PAYLOAD_TOO_LARGE,
        /** Platform said no for a reason we could not classify. Detail carries the raw code. */
        PLATFORM_ERROR,
        /** Radio was shut down. */
        NOT_RUNNING,

        /**
         * [AdvertiseRole.SCAN_ONLY]. This device is not the account's advertiser
         * (decision 35, `KEY_SCHEDULE.md` §9.3). It is the DEFAULT rejection, not an error state.
         */
        ROLE_SCAN_ONLY,

        /**
         * [AdvertisePayloadSource.frameForEpoch] returned `null` — no active key covers this epoch
         * (`E_NO_ACTIVE_KEY`). The radio goes silent. It does NOT reuse the previous frame.
         */
        NO_PAYLOAD_FOR_EPOCH,

        /**
         * The supplied frame was not exactly 19 bytes (`SPEC.md` §3: "not 18, not 20").
         * 40-contracts CONSISTENCY GAP 1 — the radio refuses to put a wrong-length frame on air
         * even though decoding is not its job.
         */
        FRAME_LENGTH,

        /**
         * Deferred by the platform's own rate limit rather than refused. Android silently stops
         * delivering scan results after 5 `startScan` calls in 30 seconds; the radio counts them
         * itself and returns this instead of walking into the trap. Detail carries the wait in ms.
         */
        THROTTLED,
    }
}
