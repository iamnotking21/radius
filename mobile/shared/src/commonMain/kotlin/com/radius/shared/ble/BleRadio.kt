package com.radius.shared.ble

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
 *    belongs to `mobile/shared/protocol/`, owned by ble-protocol, which is not written yet.
 *    Defining the parse here would be a contract-first violation (ORCHESTRATION §3).
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
     * Every advertisement matching the Radius service UUID, as received. Hot, conflated per
     * device by the platform stack, never replayed to late subscribers.
     *
     * Emission rate under a crowd is high. Consumers must debounce; this layer does not.
     */
    public val sightings: Flow<RawSighting>

    /** Begin advertising [request]. Idempotent: a second call replaces the active advertisement. */
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

    /** Register the sink for sightings and availability changes. Called once, at construction. */
    public fun attach(listener: BleRadioListener)

    public fun startAdvertising(request: AdvertiseRequest): BleOutcome

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
 *   never reaches the UI (safety invariant 2).
 * @property observedAtEpochMs monotonic-ish wall clock of observation, milliseconds since epoch.
 */
public class RawSighting(
    public val payload: ByteArray,
    public val rssiDbm: Int,
    public val observedAtEpochMs: Long,
) {
    /**
     * Redacted on purpose. The payload carries a rotating ephemeral id; dumping it into a log,
     * a crash report or a bug attachment turns a 15-minute pseudonym into a durable record.
     */
    override fun toString(): String =
        "RawSighting(payload=<${payload.size}B redacted>, rssiDbm=$rssiDbm, at=$observedAtEpochMs)"

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
 * @property payload the fully-encoded advertisement produced by `mobile/shared/protocol/`. This
 *   layer does not build it and must not mutate it.
 * @property serviceUuid16 the assigned 16-bit service UUID, hex without the base UUID, e.g.
 *   "FD6F"-shaped. REQUIRED — iOS background scanning does not function without a service filter.
 *   ble-protocol owns the real value; it is not assigned yet.
 * @property duty advertising cadence.
 */
public class AdvertiseRequest(
    public val payload: ByteArray,
    public val serviceUuid16: String,
    public val duty: DutyProfile,
)

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
    }
}
