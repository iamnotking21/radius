// Radius iOS — Tuist manifest
//
// !! UNVERIFIED !!  Authored on Windows. There is no Mac, no Xcode, no Tuist and no JDK on
// this machine (blockers B4 + B5). Nothing in this file has been run through `tuist generate`
// or `xcodebuild`. Every API shape, build setting and Gradle task name below is a PROPOSAL
// until a human runs it on macOS. Do not cite a green build that has not happened.
//
// Architecture: ADR-007. Business logic, BLE codec, key schedule, ratchet, persistence and
// the API client live in Kotlin at mobile/shared/ and arrive here as the RadiusShared
// XCFramework, produced by Gradle. This project owns UI (SwiftUI), the CoreBluetooth radio,
// background modes, keychain, push, IAP and camera — and nothing else.
//
// Owner: ios-swift. mobile/shared/ is owned by android-kotlin; we are a CONSUMER of its
// public API, which is a CONTRACT under .claude/ORCHESTRATION.md §3.

import ProjectDescription

// MARK: - Constants

/// Name of the XCFramework produced by `:shared`. Must match the Kotlin
/// `binaries.framework { baseName = "RadiusShared" }` declaration in mobile/shared.
/// ASSUMPTION — mobile/shared/build.gradle.kts does not exist yet.
let sharedFrameworkName = "RadiusShared"

/// Stable path the Gradle wrapper script copies the freshly-built XCFramework to.
/// Gradle's own output path is variant-dependent (`build/XCFrameworks/{debug,release}/`),
/// which a static manifest cannot express — so Scripts/build-shared-framework.sh normalises it.
/// This directory is build output. It is git-ignored, never committed.
let sharedFrameworkPath: Path = "Frameworks/\(sharedFrameworkName).xcframework"

// MARK: - Build settings

let baseSettings: SettingsDictionary = [
    // Swift 6 language mode. Strict concurrency is the point: CoreBluetooth delegate
    // callbacks land on a background queue, and the Kotlin/Native interop boundary has its
    // own thread-confinement rules. Data races here are real bugs, not pedantry.
    "SWIFT_VERSION": "6.0",
    "SWIFT_STRICT_CONCURRENCY": "complete",

    "IPHONEOS_DEPLOYMENT_TARGET": "16.0",
    "TARGETED_DEVICE_FAMILY": "1", // iPhone only for v1. iPad is not scoped.

    // The pre-build script shells out to Gradle, which reads and writes far outside
    // DerivedData (mobile/shared/build, the Gradle user home, the Kotlin/Native konan cache).
    // User script sandboxing forbids that and fails the build with an opaque error.
    "ENABLE_USER_SCRIPT_SANDBOXING": "NO",

    // The XCFramework from Kotlin/Native is a dynamic framework; it must be embedded and
    // signed into the app bundle. Tuist does this for `.xcframework(status: .required)`,
    // but be explicit about the signing behaviour we expect.
    "CODE_SIGN_STYLE": "Automatic",

    "ENABLE_MODULE_VERIFIER": "NO", // Kotlin/Native generated headers do not pass it.
    "DEAD_CODE_STRIPPING": "YES",
]

// MARK: - Info.plist

let appInfoPlist: [String: Plist.Value] = [
    "CFBundleDisplayName": "Radius",
    "CFBundleShortVersionString": "0.1.0",
    "CFBundleVersion": "1",
    "UILaunchScreen": [:],
    "UIUserInterfaceStyle": "Dark",

    // Background modes.
    //
    // These are declared so that CBCentralManager state restoration and opportunistic
    // background wakeups work where the platform permits them. They are NOT a promise of
    // continuous background discovery — ADR-004 documents that a backgrounded iPhone moves
    // its service UUID into Apple's private overflow area and is frequently invisible to
    // Android scanners. No engineering effort fixes that.
    //
    // Product consequence (ADR-004, standing risk R1): Radar is framed as a FOREGROUND
    // DESTINATION you open. UI copy must never imply the app keeps finding people while
    // it is closed. That is a promise the platform will break.
    "UIBackgroundModes": ["bluetooth-central", "bluetooth-peripheral"],

    // Permission strings. These are user-facing copy and are placeholders pending a real
    // copy pass — but they already follow the honesty rule: say what we do, no more.
    "NSBluetoothAlwaysUsageDescription":
        "Radius uses Bluetooth to find people near you. It never uses or stores your location.",

    // Deliberately NOT set: ITSAppUsesNonExemptEncryption.
    // Radius ships E2EE (vodozemac / Double Ratchet). Declaring `false` would be a false
    // export statement. The correct value depends on the exemption analysis — legal call,
    // escalate before shipping to TestFlight. Leaving it unset means Xcode asks, which is
    // the safe failure mode.

    // Deliberately NOT present: any NSLocation*UsageDescription key.
    // Safety invariant 1. Radius has no map, no bearing and no lat/lng, anywhere, ever.
    // If a location usage key ever appears in this dictionary, that is a PR blocker.
]

// MARK: - Scripts

/// Pre-build phase: build (or no-op if up to date) the Kotlin shared module and copy the
/// resulting XCFramework into Frameworks/ before the compiler links against it.
///
/// Why this exists: without it, Xcode happily links whatever stale RadiusShared.xcframework
/// happens to be sitting on disk. A silently stale shared core means the BLE codec, the key
/// schedule and the ratchet in the running app are not the ones in the repo. That failure is
/// invisible until it is a security bug.
///
/// `basedOnDependencyAnalysis: false` forces the phase to run on every build. Gradle is
/// itself incremental, so the up-to-date case costs a Gradle daemon round-trip, not a rebuild.
let buildSharedFrameworkScript = TargetScript.pre(
    script: #"""
    set -euo pipefail
    "${SRCROOT}/Scripts/build-shared-framework.sh"
    """#,
    name: "Build RadiusShared (Gradle)",
    basedOnDependencyAnalysis: false
)

// MARK: - Project

let project = Project(
    name: "Radius",
    organizationName: "Radius",
    options: .options(
        automaticSchemesOptions: .enabled(),
        developmentRegion: "en"
    ),
    settings: .settings(base: baseSettings),
    targets: [
        .target(
            name: "RadiusApp",
            destinations: [.iPhone],
            product: .app,
            bundleId: "com.radius.ios",
            deploymentTargets: .iOS("16.0"),
            infoPlist: .extendingDefault(with: appInfoPlist),
            sources: ["Sources/**"],
            scripts: [buildSharedFrameworkScript],
            dependencies: [
                // The Kotlin shared core. Produced by `:shared`, normalised into Frameworks/
                // by the pre-build script above.
                //
                // NOTE FOR THE FIRST HUMAN ON A MAC: `tuist generate` requires this path to
                // already exist, because the manifest is evaluated before any build phase
                // runs. Run Scripts/build-shared-framework.sh ONCE before the first generate.
                // See README.md.
                .xcframework(path: sharedFrameworkPath, status: .required),
            ],
            settings: .settings(base: [
                // Kotlin/Native emits a module map + umbrella header inside the XCFramework.
                // Nothing extra needed here today; kept as an explicit anchor point so the
                // next person knows where interop build flags belong.
            ])
        ),
        // NO TEST TARGET DECLARED YET — deliberate.
        //
        // Tests are owned by qa-test (`**/tests/`), but Project.swift is owned by ios-swift.
        // Adding a `.unitTests` target here before qa-test has created the directory would
        // break `tuist generate` (Tuist errors on a sources glob that matches nothing).
        // qa-test must raise a HANDOFF to add the target; see README.md "Handoffs owed".
    ]
)
