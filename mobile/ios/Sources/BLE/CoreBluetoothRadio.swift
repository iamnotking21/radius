// CoreBluetoothRadio.swift
// Radius iOS — the Swift side of the radio. STUB + design notes.
//
// !! UNVERIFIED !!  Never compiled, never run on hardware. No Mac (blocker B4).
// !! NO BLE RESULT IN THIS FILE IS A RESULT. !! Simulator BLE is worthless and is not
//    claimed anywhere. Every number below is a question for the Phase 0 spike, not an answer.
//
// ADR-007: all radio is native on both platforms, behind expect/actual. KMP did not shrink
// the BLE work — mobile/CLAUDE.md says so explicitly. This file is the iOS half of the hard
// 20% that is the entire business.
//
// ═════════════════════════════════════════════════════════════════════════════════════════
//  OPEN CONTRACT QUESTION #1 — where does the `actual BleRadio` actually live?
// ═════════════════════════════════════════════════════════════════════════════════════════
//  ADR-007 says radio sits behind `expect`/`actual`. It does not say who writes the iOS
//  `actual`, and there are two materially different readings:
//
//  (A) Kotlin/Native calls CoreBluetooth directly from `iosMain`.
//      + Literal reading of the ADR. One place to look for radio logic.
//      − CBCentralManager state restoration, `UIApplicationDelegate` wakeup handling, the
//        permission prompt lifecycle and background-mode plumbing are all UIKit-shaped, and
//        Kotlin/Native's Obj-C interop makes delegate-heavy, queue-sensitive code hard to
//        read and harder to debug. This is precisely risk R12.
//      − The file you would debug at 2am during a radio day is Kotlin, on a platform where
//        the debugger is the weakest link.
//
//  (B) `iosMain` declares a small port interface; SWIFT implements it and injects at startup.
//      + State restoration, background modes and permissions stay in Swift where the platform
//        documentation, the sample code and the debugger all live.
//      + The shared core still owns the codec, the state machine, banding and the key
//        schedule — i.e. everything ADR-007 actually wanted shared. Only the "turn the radio
//        on and hand me bytes + RSSI" seam is native, which is the seam that is native anyway.
//      − One more interface across the interop boundary.
//
//  RECOMMENDATION: (B). It keeps the Kotlin surface to bytes and integers, which is also what
//  R12's mitigation asks for ("keep the shared API small and value-type-only").
//
//  THIS IS NOT MY DECISION TO MAKE. The shape of `BleRadio` is part of the mobile/shared
//  public API, which is a CONTRACT under ORCHESTRATION §3, owned by android-kotlin and gated
//  by the orchestrator. HANDOFF raised — see README "Handoffs owed". The stub below assumes
//  (B) purely so there is something concrete to argue about.
//
// ═════════════════════════════════════════════════════════════════════════════════════════
//  OPEN SPIKE QUESTION #2 — iOS BACKGROUND ADVERTISING MAY NOT BE ABLE TO CARRY THE PAYLOAD
// ═════════════════════════════════════════════════════════════════════════════════════════
//  This is the most important thing in this file and it may be a go/no-go input for B1.
//
//  The BLE wire draft (40-contracts) is a ≤26 byte advertisement:
//      [ver:1][ephemeral_id:16][txpower:1][flags:1]
//  carried, presumably, as service data or manufacturer data.
//
//  On iOS, `CBPeripheralManager.startAdvertising` honours only two keys — local name and
//  service UUIDs — and in the BACKGROUND it honours only the service UUID list, which is
//  moved into Apple's private "overflow" area, discoverable solely by other iOS devices that
//  are explicitly scanning for that exact UUID.
//
//  Consequences that need measuring on real hardware before anyone writes protocol code:
//    - A backgrounded iPhone appears to have NO WAY to broadcast a 16-byte rotating
//      ephemeral ID as service/manufacturer data. Not "unreliably" — at all.
//    - ADR-004 already documents Android-scanning-backgrounded-iPhone as frequently failing.
//      This is a stronger claim than that, and if it holds, foreground framing (R1) is not a
//      mitigation, it is the only mode that carries identity.
//    - The obvious workaround — encode the ephemeral ID as a rotating 128-bit service UUID,
//      which is exactly 16 bytes — collides with the fact that iOS background scanning
//      requires naming the service UUIDs you want, and a peer cannot name a UUID it has not
//      yet learned. Some scheme with a fixed discovery UUID plus a GATT read for identity may
//      be the answer, at a latency and battery cost that has to be measured.
//    - Apple built Exposure Notification into the OS rather than shipping it as an app for
//      substantially this reason. That is evidence, not an anecdote.
//
//  ACTION: this belongs in the Phase 0 spike matrix and in the go/no-go memo. Flagged to
//  ble-protocol and the orchestrator via HANDOFF. Do NOT let a wire spec be finalised on the
//  assumption that iOS can advertise arbitrary bytes in the background.
//
// ═════════════════════════════════════════════════════════════════════════════════════════
//  REQUIRED PLATFORM PLUMBING (checklist, none of it written)
// ═════════════════════════════════════════════════════════════════════════════════════════
//  1. State restoration. `CBCentralManagerOptionRestoreIdentifierKey` at construction, plus
//     `centralManager(_:willRestoreState:)`. Without the restore identifier, iOS will not
//     relaunch us for BLE events at all, and the background modes in Info.plist buy nothing.
//     Also needed for the peripheral side: `CBPeripheralManagerOptionRestoreIdentifierKey`.
//  2. Relaunch handling. Restoration relaunches hand the app a
//     `UIApplicationLaunchOptionsBluetoothCentralsKey` payload, and the manager MUST be
//     recreated with the same restore identifier synchronously, early. SwiftUI's `App`
//     lifecycle has no hook early enough — this needs `@UIApplicationDelegateAdaptor`.
//     Not wired yet.
//  3. Permission timing. Constructing `CBCentralManager` triggers the system prompt. Do NOT
//     construct it at launch. Construct it when the user opens Radar, so the prompt arrives
//     with visible context. Better grant rate, and honest.
//  4. Delegate concurrency. CoreBluetooth delegate callbacks arrive on the queue given at
//     construction. Under Swift 6 strict concurrency they are nonisolated and land
//     concurrently with UI reads. Use a dedicated serial queue and funnel to an actor; do NOT
//     paper over it with `@unchecked Sendable` on a mutable box. Races in radio callbacks are
//     the exact class of bug that eats a week on a device you cannot attach a debugger to.
//  5. TX power. iOS does not expose or let you set radio TX power. The `txpower` byte in the
//     payload therefore has to come from a per-model calibration table, and an uncalibrated
//     device biases banding. Feed the spike's RSSI-vs-distance data into that table.
//  6. Duty cycling for the <4%/hr budget. `CBCentralManagerScanOptionAllowDuplicatesKey` is
//     required to get repeated RSSI samples, is ignored in the background, and is brutal on
//     battery in the foreground. Duty cycle manually. Back off when CoreMotion reports
//     stationary, when battery < 20%, or when no peer has been seen for 10 minutes.
//  7. Instruments Energy Log on a real device, attached to the PR. No trace, no battery claim.
//
// ═════════════════════════════════════════════════════════════════════════════════════════
//  SAFETY
// ═════════════════════════════════════════════════════════════════════════════════════════
//  - Payload is [ver][ephemeral_id:16][txpower][flags] and NOTHING else (invariant 4). No
//    device name, no account id, no coordinates, no stable identifier. iOS's habit of
//    appending a local name to advertisements is a leak: never set
//    `CBAdvertisementDataLocalNameKey`.
//  - ephemeral_id rotates every 15 minutes and the MAC (RPA) rotates WITH it (invariant 5).
//    Both or neither — rotating the payload while the MAC persists defeats the entire scheme
//    and is worse than not rotating, because it looks like privacy.
//  - iOS gives no direct control over RPA rotation. Whether the OS rotation can be aligned
//    with our 15-minute epoch, or whether we must accept drift, is a SPIKE MEASUREMENT.
//    Do not assume it works.
//  - No RSSI value and no raw byte from this layer may reach the UI. Everything goes to the
//    shared core, which does the filtering, banding, hysteresis and jitter (invariant 2).
//  - This file must never import CoreLocation. No bearing, no direction, no coordinates,
//    anywhere in Radius (invariant 1, 3).

import Foundation
import CoreBluetooth

/// Coarse radio state, for UI that needs to explain why Radar is quiet.
///
/// This is the ONLY radio detail the UI is allowed to see. Not RSSI, not peripheral
/// identifiers, not advertisement dictionaries.
enum RadioState: Sendable {
    case unknown
    case unsupported
    case unauthorized
    case poweredOff
    case idle
    case scanning
}

/// The seam the shared core is assumed to talk to (reading (B) above).
///
/// SPECULATIVE — mobile/shared/ does not exist, so this protocol matches nothing. It is
/// written value-typed on purpose: bytes and integers cross, never CoreBluetooth objects.
/// If a `CBPeripheral` ever appears in this protocol, the boundary has failed.
protocol BleRadioPort: AnyObject, Sendable {

    func startScanning()
    func stopScanning()

    /// Ghost mode is implemented HERE, by actually stopping the transmitter — not by hiding
    /// rows in the UI (invariant 10). If ghost mode is on and this device is still
    /// advertising, that is a safety bug of the most serious kind.
    func startAdvertising(payload: Data)
    func stopAdvertising()
}

/// Delivered upward on every advertisement observation. Deliberately anaemic.
struct BleObservation: Sendable {
    /// Raw advertisement payload. Parsed by the SHARED codec, never here. There must be
    /// exactly one implementation of the wire format (ADR-007) and it is Kotlin.
    let payload: Data

    /// Raw RSSI. Goes straight to the shared core for Kalman filtering and banding. iOS does
    /// not interpret it, does not threshold it, and never shows it.
    let rssi: Int

    let observedAt: Date
}

/// STUB. No behaviour. Exists so the shape of the seam is reviewable and so the notes above
/// have a home in code rather than in a document nobody opens.
///
/// Nothing here is implemented because implementing it before OPEN SPIKE QUESTION #2 is
/// answered risks building a radio around an advertising model iOS will not support.
final class CoreBluetoothRadio: NSObject {

    private(set) var state: RadioState = .unknown

    // TODO: CBCentralManager with CBCentralManagerOptionRestoreIdentifierKey.
    // TODO: CBPeripheralManager with CBPeripheralManagerOptionRestoreIdentifierKey.
    // TODO: dedicated serial dispatch queue; funnel callbacks to an actor.
    // TODO: adaptive duty cycle driven by CoreMotion + battery level + last-peer-seen.

    override init() {
        super.init()
    }
}
