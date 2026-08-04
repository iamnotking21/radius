// Radius iOS — Tuist config
//
// !! UNVERIFIED !!  Never run. No Tuist on this machine (Windows, blocker B4).
//
// API NOTE: Tuist moved this file to `Tuist.swift` at the project root (`let tuist = Tuist(...)`)
// in 4.18. `Tuist/Config.swift` with `let config = Config(...)` is still honoured but is the
// older shape. First human on a Mac: if `tuist generate` complains, migrate this file — the
// contents map one-to-one.

import ProjectDescription

let config = Config(
    // Pin the toolchain. An unpinned generator across two machines produces two different
    // .xcodeproj files, and the diff noise hides real changes.
    // ASSUMPTION: Xcode 16.x. Swift 6 language mode requires Xcode 16 or newer; nobody has
    // verified which Xcode the (not yet purchased) Mac will run.
    compatibleXcodeVersions: .upToNextMajor("16.0"),
    swiftVersion: "6.0"
)
