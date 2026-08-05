# Radius iOS

SwiftUI app shell. Consumes the Kotlin Multiplatform shared core (ADR-007) as the
`RadiusShared` XCFramework, produced by Gradle from `mobile/shared/`.

Owner: `ios-swift`. This directory is the only thing this agent writes.

---

## ⛔ THIS CANNOT BE BUILT ON WINDOWS — BLOCKER B4

**iOS builds require a Mac and Xcode. There is no Windows path and there is no Linux path.**

Kotlin/Native's `iosArm64` and `iosSimulatorArm64` targets compile on macOS only, and even if
they did not, `xcodebuild` does. ADR-007 states this as an accepted, permanent cost:

> A Mac becomes mandatory infrastructure, not a preference. […] There is no Windows or Linux
> path. This is the single largest new cost introduced by this ADR.

**Every file in this directory is UNVERIFIED.** It was authored on Windows. Nothing here has
been through `tuist generate`, `xcodebuild`, `swift build`, a simulator, or a device. No line
of it is known to compile. Treat all of it as a reviewable proposal, not working code.

Related open blockers: **B4** (no Mac), **B5** (no JDK — Gradle cannot run, so the shared
module cannot be produced either).

Do not "verify" any of this by reasoning about it. Verification means a green build on a Mac.

---

## Layout

```
mobile/ios/
  Project.swift                       Tuist manifest. App target RadiusApp, com.radius.ios.
  Tuist/Config.swift                  Tuist + Xcode version pin.
  Scripts/build-shared-framework.sh   Gradle → XCFramework → Frameworks/. Runs pre-build.
  Frameworks/                         Build output. Git-ignored. Never committed.
  Sources/
    RadiusApp.swift                   Entry point. Holds the single RadiusCore (via bridge).
    RootTabView.swift                 Three tabs: Discover / Radar / Threads. No fourth.
    Core/SharedBridge.swift           THE Kotlin ↔ Swift boundary. Read the rule in it.
    BLE/CoreBluetoothRadio.swift      Swift-side radio stub + the notes that matter.
    Radar/RadarView.swift             Radar screen. Canvas + accessible list + ghost mode.
    Radar/RadarCanvas.swift           CAShapeLayer + CADisplayLink placeholder.
    Discover/DiscoverView.swift       Placeholder. No swipe deck, ever.
    Threads/ThreadsView.swift         Placeholder. One inbox, both origins.
```

---

## Build (on a Mac, once one exists)

Prerequisites, none of which are present on the authoring machine:

- macOS with Xcode 16+ (Swift 6 language mode)
- JDK 17+ with `JAVA_HOME` set
- [Tuist](https://tuist.dev) 4.x
- A Gradle wrapper at `mobile/gradlew` — **it does not exist yet** (blocker B5)
- `mobile/shared/` — **it does not exist yet**; `android-kotlin` owns it

```bash
# 1. Build the shared core FIRST. Tuist resolves the .xcframework path while evaluating the
#    manifest, before any build phase runs, so the artefact must already be on disk for the
#    very first generate.
cd mobile/ios
chmod +x Scripts/build-shared-framework.sh
./Scripts/build-shared-framework.sh

# 2. Generate and open the Xcode project.
tuist generate
```

After the first generate, every subsequent Xcode build re-runs the Gradle step automatically
via the `Build RadiusShared (Gradle)` pre-build phase.

### Why the pre-build phase exists

Without it, Xcode links whichever `RadiusShared.xcframework` happens to be on disk. A silently
stale shared core means the BLE codec, the key schedule and the Double Ratchet running on the
device are not the ones in the repo. That failure is invisible until it is a security bug, so
the script fails loudly instead — including when Gradle succeeds but produces no framework at
the expected path.

The phase is marked `basedOnDependencyAnalysis: false` so it runs on every build. Gradle is
itself incremental; the up-to-date case costs a daemon round-trip, not a rebuild.

`ENABLE_USER_SCRIPT_SANDBOXING` is `NO` because Gradle reads and writes well outside
DerivedData (the Gradle user home, the Kotlin/Native konan cache, `mobile/shared/build`).

---

## What a human on a Mac needs to verify

In order. Nothing below this line has been done.

1. **`tuist generate` succeeds.** Most likely failure: `Tuist/Config.swift` uses the older
   `Config` shape; Tuist ≥ 4.18 prefers `Tuist.swift` with `let tuist = Tuist(...)`.
2. **The XCFramework actually appears.** The Gradle task name
   (`:shared:assembleRadiusSharedDebugXCFramework`) and the output path
   (`mobile/shared/build/XCFrameworks/debug/`) are both **assumptions** — see below.
3. **`xcodebuild` compiles under Swift 6 strict concurrency.** Known suspect:
   `@StateObject private var bridge = SharedBridge.live` in `RadiusApp.swift`, where a
   main-actor-isolated static is touched from a non-isolated property initialiser. Fix by
   annotating the `App` struct `@MainActor` — **not** by relaxing `SharedBridge`'s isolation.
4. **`#if canImport(RadiusShared)` resolves true.** If the fallback branch compiles on a Mac
   that *has* the framework, linking is misconfigured and the app is silently running against
   nothing.
5. **Accessibility.** VoiceOver over the Radar screen: the canvas must be silent and the list
   must carry every peer. Dynamic Type at 200%: no clipping in `RadarPeerRow`.
6. **Reduced motion.** Enable it; the radar sweep must not rotate.
7. **BLE on real hardware only.** Simulator BLE results are invalid and must never be reported
   as a pass. Two physical devices minimum.
8. **Instruments Energy Log**, on device, attached to the PR. The budget is < 4%/hr scanning
   and < 1%/day idle, and it is CI-gated. No trace, no battery claim.

---

## Assumptions about the shared API (NOT YET PUBLISHED)

`mobile/shared/` does not exist. Every Kotlin symbol referenced from Swift is invented by this
agent to have something concrete to review, and **must be reconciled with `android-kotlin`
before any more Swift is written.** The shared public API is a contract
(`.claude/ORCHESTRATION.md` §3); consumers do not get to define it.

The authoritative list, with signatures, is in the `ASSUMED SHARED API` block at the top of
`Sources/Core/SharedBridge.swift`. Summary:

> **RECONCILED 2026-08-05 against the gated shared API v0.1.** These are no longer assumptions —
> the API exists and is frozen in `.claude/memory/40-contracts.md`. **Two were overturned.** Do
> not write Swift against the original column; the bridge in `SharedBridge.swift` still encodes
> the pre-gate shape and must be rewritten before it compiles against anything real.

| # | Assumed | Outcome |
|---|---|---|
| 1 | Framework `baseName` is `RadiusShared` | ✅ holds |
| 2 | Gradle task `:shared:assemble<Name><Variant>XCFramework` | ✅ holds |
| 3 | Output at `shared/build/XCFrameworks/{debug,release}/` | ✅ holds |
| 4 | `RadiusCoreFactory().create(config:)` | ❌ **OVERTURNED — ruling R-A.** It is `RadiusCore.Companion.create(config, radio, scope)`. The assumed form omits `radio`, which became mandatory the moment decision 21 made Swift inject the port. |
| 5 | `setGhostMode(Bool)` | ❌ **OVERTURNED — ruling R-B.** It is `setVisibility(RadarVisibility)`. An enum extends without a signature break, and `GHOST` stays greppable so invariant 10 is auditable by search. `setGhostMode(true)` at a call site is unreadable exactly where safety matters. |
| 6 | Callbacks, not raw `Flow` | ✅ **and now binding.** Every Swift-consumed stream needs a sibling `<name>Adapter(): FlowAdapter<T>`. Only `radarNodesAdapter()` exists — Discover and Threads cannot be bound until android-kotlin adds theirs. |
| 7 | `RadiusCancellable`, not `Cancellable` | ✅ holds — the Combine collision was real |
| 8 | Opaque session-scoped peer handle | ✅ holds |
| 9 | `displayMeters` computed in shared | ✅ holds — and hardened: it is `f(session_salt, peer_id, band)` and takes no RSSI argument at all (decision 29) |
| 10 | Four bands as a Kotlin enum | ⚠️ **six, not four.** `protocol.Band` adds `UNKNOWN` and `OUT_OF_RANGE`. `domain.radar.ProximityBand` was **deleted** (ruling D3). Do not collapse the two extra states onto `EDGE` to make a `switch` exhaustive — that shows a peer as present when we do not know that it is. |
| 11 | Unverified accounts filtered in shared | ✅ holds |
| 12 | iOS supplies the SQLCipher key from Keychain | ⏳ still open — no persistence exists yet |
| 13 | Cert pinning in shared's Ktor Darwin client | ⏳ still open — no RPC client exists yet |
| 14 | `BleRadio` seam is a Swift port injected into Kotlin | ✅ **RULED — decision 21.** The radio is the moat; write it where the tooling is. Swift gets Instruments Energy Log, a real debugger, and native state-restoration semantics. iOS state restoration is ios-swift's. |

### What changed that iOS does not know about yet

Landed after this bridge was written. All of it is contract, not preference:

- **`AdvertiseRequest` carries an `AdvertisePayloadSource`, not a `ByteArray`** (decision 49). The radio holds no payload, so it cannot cache one across a rotation seam.
- **`BleRadioPort` / `BleRadioListener`** — commands pull, events push, value types only, **no `suspend` in the implemented direction** (an exception crossing Kotlin/Native is a crash, not a catchable throw).
- **`EpochBoundaryListener` + `setEpochBoundaryListener`.** This one is a security obligation, not an API detail: it drives `KeyRing.pruneSupersededAt`, and **the ADR-008 M4 key-destruction fix is inert on any platform that does not call it.** The predicate lives in `EpochTickerPolicy` in commonMain so the platforms cannot drift.
- **`ByteArray` crosses as `KotlinByteArray`; a hex `String` was rejected** (ruling R-C). A String ephemeral ID is log-shaped — it survives interpolation into crash reports and analytics. The awkwardness is the feature.
- **Retain cycle, contract-mandated (R-D):** `shutdown()` drops the listener **and** the Swift port holds it weak. Both, not either — the cycle straddles Kotlin/Native and Swift ARC.
- **Domain facades reach Swift only through `RadiusCore` + `FlowAdapter`** (R-E), keeping the boundary one file wide.
- `iosMain` gained `NSRecursiveLock` — recursive because the Android actual leans on reentrancy three frames deep.

### Still genuinely open

**Carrier B has no representation in the seam.** The port assumes broadcast-only, and iOS cannot emit service or manufacturer data in any state — so the iOS path is a GATT read (decision 23). This is the single largest piece of unpriced iOS work, and an Android-only spike exercises it **not at all**.

---

## Handoffs owed (cross-boundary, cannot be done from here)

1. **→ `ble-protocol` / `orchestrator`, blocking, contract-touched.**
   iOS background advertising honours only the service-UUID list, moved into Apple's private
   overflow area. There appears to be **no way for a backgrounded iPhone to broadcast a
   16-byte ephemeral ID as service or manufacturer data.** If that holds on hardware, the
   `[ver][ephemeral_id:16][txpower][flags]` advertisement is a foreground-only mechanism on
   iOS, and that is a go/no-go input for blocker B1. Full detail in `CoreBluetoothRadio.swift`.
   Must be measured in the Phase 0 spike **before** the wire spec is locked.

2. ~~**→ `android-kotlin` / `orchestrator`** — publish shared API v0~~
   **CLOSED 2026-08-05.** API v0.1 is gated in `.claude/memory/40-contracts.md`. Assumptions 4
   and 5 were overturned; see the table above. The bridge is no longer speculative — it is
   **known wrong** in two places, which is a better problem to have.

3. ~~**→ `design-system`** — design-tokens is empty~~
   **CLOSED 2026-08-05.** Tokens are real: primitives transcribed from Figma's bound variables,
   a semantic layer designed, generated at build time, and a WCAG contrast regression across 51
   pairings fails the Android build. Fonts are still unchosen (§8 founder decision), so type is
   metrics-only on both platforms. The generator emits Kotlin today; `build/tokens.resolved.json`
   is the intended input for a Swift generator when iOS un-defers.

4. **→ `qa-test`, non-blocking.**
   No test target is declared in `Project.swift`, because Tuist fails on a sources glob that
   matches nothing and no test directory exists. `qa-test` owns `**/tests/` but `Project.swift`
   is owned by `ios-swift`, so adding the target needs a handoff.

5. **→ `orchestrator` / founder, blocking everything iOS.**
   Procure a Mac or a macOS CI runner (B4) and install JDK 17+ (B5).

---

## Invariants this code is built around

Not aspirations. Violating any of these blocks a PR.

- **No map, no bearing, no lat/lng, anywhere.** `CoreLocation` is not imported and must not be.
  There is no direction quantity in Radius to leak.
- **Radar node angle is random per session** (`RadarNodeAngle` in `RadarCanvas.swift`),
  re-seeded every launch and never persisted.
- **Distance is four bands**, displayed hedged and jittered, computed in the shared core.
- **Ghost mode is one tap** from the Radar screen, no confirmation dialog, and it stops the
  transmitter rather than hiding rows.
- **Radar is a foreground destination.** UI copy says Radar is on while the screen is open.
  It never implies continuous background discovery — ADR-004, risk R1.
- **The canvas is decorative.** The list is the accessible interface.
- **No swipe deck**, no streaks, no expiry timers, no fake activity, no invented social proof.
- **Kotlin types stop at `SharedBridge.swift`.** Exactly one file imports `RadiusShared`.
