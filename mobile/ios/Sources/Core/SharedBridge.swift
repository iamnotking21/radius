// SharedBridge.swift
// Radius iOS — the Kotlin ↔ Swift boundary.
//
// !! UNVERIFIED !!  Never compiled. No Mac, no Xcode (blocker B4).
// !! SPECULATIVE !! The shared API this file calls DOES NOT EXIST YET. mobile/shared/ has not
//                   been written. Every Kotlin symbol referenced below is an ASSUMPTION and is
//                   listed explicitly in the "ASSUMED SHARED API" block. android-kotlin owns
//                   that surface; it is a CONTRACT (ORCHESTRATION §3) and we do not get to
//                   invent it. This file is a proposal to be reconciled, not a fait accompli.
//
// ─────────────────────────────────────────────────────────────────────────────────────────
//  THE RULE
// ─────────────────────────────────────────────────────────────────────────────────────────
//  Kotlin types do not leak past this file.
//
//  No SwiftUI view, no view model, no other file in Sources/ may `import RadiusShared`.
//  Grep for it: exactly one match, here. If a second appears, that PR is wrong.
//
//  Why this is not architectural fussiness:
//
//  1. Kotlin/Native objects are reference types with their own lifetime and thread rules.
//     Under Swift 6 strict concurrency they are not Sendable, and SwiftUI will happily hop
//     them across actors. Converting to Swift value types at one chokepoint makes the
//     boundary auditable instead of diffuse.
//  2. Standing risk R12 (the KMP interop tax) is mitigated by keeping the boundary SMALL
//     and VALUE-TYPED. That mitigation only works if there is one boundary.
//  3. The shared API is owned by another agent and gated by the orchestrator. When it
//     changes, exactly one Swift file needs review. Not forty.
//  4. Kotlin `Flow` must never surface raw in Swift (R12, explicitly). Subscriptions cross
//     as callbacks and are re-published here as Combine `@Published` state.
//
//  Direction of travel: Kotlin ──(convert)──▶ Swift value type ──▶ SwiftUI.
//                       SwiftUI ──▶ intent method on this bridge ──▶ Kotlin.
// ─────────────────────────────────────────────────────────────────────────────────────────

import Foundation
import Combine

#if canImport(RadiusShared)
import RadiusShared
#endif

// ─────────────────────────────────────────────────────────────────────────────────────────
// MARK: - ASSUMED SHARED API (unpublished — reconcile before writing any more Swift)
// ─────────────────────────────────────────────────────────────────────────────────────────
//
// Assumed Kotlin, in mobile/shared/src/commonMain, exported to Obj-C/Swift:
//
//   class RadiusCoreFactory {
//       fun create(config: RadiusConfig): RadiusCore
//   }
//   data class RadiusConfig(val apiBaseUrl: String, val databaseKeyProvider: KeyProvider)
//
//   interface RadiusCore {
//       val radar: RadarController
//       fun close()
//   }
//
//   interface RadarController {
//       fun start()
//       fun stop()
//       fun setGhostMode(enabled: Boolean)
//       // Flow must NOT cross the boundary raw (R12). Assumed callback-based subscription
//       // returning a cancellation handle. Named RadiusCancellable, NOT Cancellable, to
//       // avoid colliding with Combine.Cancellable once exported to Swift.
//       fun observe(onChange: (SharedRadarState) -> Unit): RadiusCancellable
//   }
//   interface RadiusCancellable { fun cancel() }
//
//   data class SharedRadarState(
//       val isScanning: Boolean,
//       val isGhostMode: Boolean,
//       val peers: List<SharedRadarPeer>
//   )
//
//   data class SharedRadarPeer(
//       val handle: String,        // opaque, session-scoped. NOT ephemeral_id, NOT account id.
//       val band: SharedBand,      // HERE | CLOSE | AROUND | EDGE
//       val displayMeters: Int,    // band midpoint + jitter, computed IN SHARED (invariant 2)
//       val isVerified: Boolean,   // invariant 6 — Radar is verified accounts only
//       val waveState: SharedWaveState,
//       val lastSeenEpochMs: Long
//   )
//
// Things this bridge asserts the shared API must NOT have, and which we will reject on sight:
//   - any bearing, heading, angle, azimuth or direction field
//   - any latitude, longitude, coordinate, or geohash finer than 5 characters
//   - any raw RSSI or raw distance exposed to UI (banding + jitter belong in shared)
//   - any stable cross-session identifier for a peer
// Safety invariants 1, 2, 3, 4, 5. A field like this appearing in shared/ is a PR blocker,
// and the correct response is a HANDOFF to the orchestrator, not a local workaround.
//
// ─────────────────────────────────────────────────────────────────────────────────────────

// MARK: - Swift value types (the ONLY shapes SwiftUI is allowed to see)

/// Distance is BANDED, never measured. Four bands, no fifth (safety invariant 2).
///
/// The dBm thresholds (HERE ≥ −55, CLOSE ≥ −70, AROUND ≥ −82, EDGE ≥ −95) and the hysteresis
/// live in the shared Kotlin core. iOS never sees RSSI and never classifies. If you find
/// yourself writing a threshold comparison in Swift, stop — that is the shared core's job and
/// duplicating it recreates exactly the divergence ADR-007 eliminated.
enum ProximityBand: String, Sendable, CaseIterable {
    case here
    case close
    case around
    case edge
}

/// Chat unlocks ONLY on mutual wave (safety invariant 6).
///
/// `idle` rather than `none` deliberately: `WaveState.none` is ambiguous with `Optional.none`
/// at every call site that touches a `WaveState?`, and that ambiguity produces confident,
/// wrong code around a consent gate.
enum WaveState: String, Sendable {
    case idle
    case sent
    case received
    case mutual
}

/// A person currently visible on Radar.
///
/// Note what is absent: no name, no photo, no account id, no bearing, no coordinates, no RSSI.
/// Before a mutual wave there is nothing to show but presence and a rough band, and that is
/// the design, not a gap to be filled in later.
struct RadarPeer: Identifiable, Hashable, Sendable {
    /// Opaque handle minted by the shared core for this session only.
    /// NOT the ephemeral_id (invariant 4 — that never leaves the radio layer), NOT an account
    /// id, and NOT stable across app launches (invariant 5 — IDs rotate every 15 minutes in
    /// lockstep with the MAC/RPA). Safe to use as a SwiftUI `id` and as the seed for the
    /// per-session random canvas angle. Safe for nothing else.
    let id: String

    let band: ProximityBand

    /// Already hedged and jittered BY THE SHARED CORE: band midpoint plus deliberate noise.
    /// iOS renders it with hedging language ("about 20 m") and never recomputes or refines it.
    let displayMeters: Int

    /// Radar is verified accounts only (invariant 6). An unverified peer should never reach
    /// this type; if one does, the filter belongs in shared, not in a SwiftUI `if`.
    let isVerified: Bool

    let waveState: WaveState

    let lastSeen: Date
}

/// Everything the Radar screen renders, as one immutable snapshot.
struct RadarSnapshot: Sendable {
    var isScanning: Bool = false

    /// Ghost mode: stop advertising, stay invisible. Must be reachable in ≤1 tap from the
    /// Radar screen (invariant 10). See RadarView.
    var isGhostMode: Bool = false

    var peers: [RadarPeer] = []

    static let empty = RadarSnapshot()
}

// MARK: - The bridge

/// Owns the single `RadiusCore` instance for the process and converts everything crossing
/// the boundary into Swift value types.
///
/// `@MainActor` on purpose. The shared core is not documented as thread-confined, but under
/// Swift 6 the compiler needs a definite isolation story for a non-Sendable reference held in
/// observable state, and "the main actor owns it" is the simplest correct one. If profiling
/// later shows main-thread cost from conversion, the fix is to move the CONVERSION off-actor,
/// not to share the Kotlin object across actors.
@MainActor
final class SharedBridge: ObservableObject {

    /// The one instance. Created once in RadiusApp, injected downward.
    /// Not a global singleton by accident — the shared core owns a SQLDelight/SQLCipher
    /// connection and (eventually) ratchet session state. A second instance would be two
    /// writers on one encrypted database.
    static let live = SharedBridge()

    // MARK: Published state (Swift value types only)

    @Published private(set) var radar: RadarSnapshot = .empty

    /// True once the shared core has actually been constructed.
    ///
    /// False today, always, on every machine — mobile/shared/ does not exist. The whole
    /// `#if canImport(RadiusShared)` scaffold below exists so the SwiftUI skeleton can be
    /// reviewed and (on a Mac) compiled before android-kotlin publishes the contract.
    /// DELETE THE FALLBACK BRANCHES the moment shared API v0 lands. A permanent stub is how
    /// a project ends up shipping a UI that was never wired to anything.
    @Published private(set) var isSharedCoreAvailable: Bool = false

    // MARK: Kotlin-side handles — private, never exposed, never returned

    #if canImport(RadiusShared)
    private var core: RadiusCore?
    private var radarSubscription: RadiusCancellable?
    #endif

    private init() {}

    // MARK: Lifecycle

    func start() {
        #if canImport(RadiusShared)
        guard core == nil else { return }

        // ASSUMPTION: factory shape, config shape, and that the database key is supplied by
        // iOS from the Keychain. E2EE and database keys are generated on-device, live in the
        // Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, and are excluded from
        // iCloud/iTunes backup. They never leave the device and never sync to the server.
        // Keychain wiring is NOT written yet — see BLE/CoreBluetoothRadio.swift notes and
        // README "Not built yet".
        let created = RadiusCoreFactory().create(config: makeConfig())
        core = created
        isSharedCoreAvailable = true

        radarSubscription = created.radar.observe { [weak self] sharedState in
            // ASSUMPTION: this callback may arrive on a non-main thread. Hop deliberately;
            // do not assume the shared core is kind to us.
            Task { @MainActor [weak self] in
                self?.radar = Self.convert(sharedState)
            }
        }
        #else
        // No shared framework on this machine. Present an honest empty state rather than
        // fake peers — a placeholder that invents nearby people is a fake-profiles dark
        // pattern rehearsal, and we do not rehearse those.
        isSharedCoreAvailable = false
        radar = .empty
        #endif
    }

    func stop() {
        #if canImport(RadiusShared)
        radarSubscription?.cancel()
        radarSubscription = nil
        core?.close()
        core = nil
        #endif
        isSharedCoreAvailable = false
        radar = .empty
    }

    // MARK: Intents (SwiftUI → Kotlin). Void-returning, fire-and-forget, state comes back
    //       through the subscription. No request/response pairs across the boundary.

    func startRadar() {
        #if canImport(RadiusShared)
        core?.radar.start()
        #endif
    }

    func stopRadar() {
        #if canImport(RadiusShared)
        core?.radar.stop()
        #endif
    }

    /// Ghost mode. One tap, no confirmation dialog, takes effect immediately (invariant 10).
    /// Optimistically reflected in local state so the UI cannot appear to ignore a safety
    /// control while waiting for a round trip.
    func setGhostMode(_ enabled: Bool) {
        radar.isGhostMode = enabled
        #if canImport(RadiusShared)
        core?.radar.setGhostMode(enabled: enabled)
        #endif
    }

    // MARK: Conversion — Kotlin ▶ Swift. The only place this may happen.

    #if canImport(RadiusShared)
    private func makeConfig() -> RadiusConfig {
        // ASSUMPTION: config shape. API base URL must be certificate-pinned; pinning is a
        // native concern on both platforms, so where it is enforced (Ktor Darwin engine in
        // shared, vs URLSession delegate in Swift) is an OPEN CONTRACT QUESTION. Flagged in
        // README "Assumptions". Do not ship an unpinned client.
        fatalError("shared API v0 not published — RadiusConfig shape unknown")
    }

    private static func convert(_ state: SharedRadarState) -> RadarSnapshot {
        RadarSnapshot(
            isScanning: state.isScanning,
            isGhostMode: state.isGhostMode,
            peers: state.peers.map(convert)
        )
    }

    private static func convert(_ peer: SharedRadarPeer) -> RadarPeer {
        RadarPeer(
            id: peer.handle,
            band: convert(peer.band),
            displayMeters: Int(peer.displayMeters),
            isVerified: peer.isVerified,
            waveState: convert(peer.waveState),
            lastSeen: Date(timeIntervalSince1970: Double(peer.lastSeenEpochMs) / 1000)
        )
    }

    private static func convert(_ band: SharedBand) -> ProximityBand {
        // Exhaustive by hand: Kotlin enums bridge to Obj-C as classes, so Swift gets no
        // exhaustiveness checking. A new band added in shared must break loudly here.
        // (It should never happen — four bands is a safety invariant, not a parameter.)
        switch band {
        case .here:   return .here
        case .close:  return .close
        case .around: return .around
        case .edge:   return .edge
        default:
            preconditionFailure("unknown band from shared core: \(band). Safety invariant 2 says there are exactly four.")
        }
    }

    private static func convert(_ wave: SharedWaveState) -> WaveState {
        switch wave {
        case .idle:     return .idle
        case .sent:     return .sent
        case .received: return .received
        case .mutual:   return .mutual
        default:
            preconditionFailure("unknown wave state from shared core: \(wave)")
        }
    }
    #endif
}
