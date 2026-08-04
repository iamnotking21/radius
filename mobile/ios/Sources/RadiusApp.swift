// RadiusApp.swift
// Radius iOS — application entry point.
//
// !! UNVERIFIED !!  Never compiled. No Mac, no Xcode on the authoring machine (blocker B4).

import SwiftUI

// SWIFT 6 NOTE, unverified: `SharedBridge` is `@MainActor` and `SharedBridge.live` is a
// main-actor-isolated static. Property initialisers on a struct are not actor-isolated, so
// under strict concurrency the `bridge` initialiser below may be rejected depending on SDK
// version (recent SwiftUI SDKs annotate `App` as `@MainActor`, which makes it legal; older
// ones do not). If the compiler objects on the Mac, the fix is `@MainActor` on this struct —
// NOT making `SharedBridge` nonisolated. The whole point of the isolation is that the Kotlin
// core has one owner.
@main
struct RadiusApp: App {

    /// The single `RadiusCore` instance for the process, held behind the bridge.
    ///
    /// One instance, for the whole app lifetime, created here and injected downward. The
    /// shared core owns the SQLCipher-encrypted database connection and (later) Double
    /// Ratchet session state; a second instance would be two writers on one encrypted store
    /// and two ratchets on one session. That is data loss at best.
    ///
    /// `@StateObject` rather than `@State` because the deployment target is iOS 16 and
    /// `@Observable` requires iOS 17.
    @StateObject private var bridge = SharedBridge.live

    /// Used to stop the radio when the app leaves the foreground.
    ///
    /// Radar is a FOREGROUND DESTINATION (ADR-004, standing risk R1). Backgrounding does not
    /// mean "keep scanning quietly" — background modes are declared for state restoration and
    /// opportunistic wakeups only. Anything that behaves like a persistent background scanner
    /// both drains the battery past the 4%/hr contract and implies a capability iOS will not
    /// honour.
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .environmentObject(bridge)
                .task {
                    bridge.start()
                }
        }
        .onChange(of: scenePhase) { phase in
            // iOS 16 signature: `onChange(of:perform:)`. The two-parameter closure form is
            // iOS 17+. Do not "modernise" this without raising the deployment target.
            switch phase {
            case .active:
                break // Radar is started by the Radar screen appearing, not by app launch.
            case .inactive, .background:
                bridge.stopRadar()
            @unknown default:
                break
            }
        }
    }
}
