# ADR-007 · Kotlin Multiplatform shared core, native UI per platform

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** founder
**Supersedes:** ADR-001 (Native Swift + Kotlin, not cross-platform) — partially. See "Relationship to ADR-001".

## Context

ADR-001 chose two fully independent native applications and explicitly shared no application code. That decision named Kotlin Multiplatform as "the alternative most likely to be revisited", and rejected it on hiring risk and setup risk rather than on technical merit. Its own "Revisit when" clause anticipated exactly this reopening.

Two things changed. First, the founder has directed that mobile be Kotlin Multiplatform. Second, we are still at Phase 0 with zero lines of application code committed — the cost of this reversal is currently near zero, and it rises steeply the moment either client ships a feature. If this decision is going to be made at all, now is the only cheap moment to make it.

The honest analysis has not changed on the part that matters. Radius's moat is BLE proximity. KMP does **not** share that layer. `CoreBluetooth` and `android.bluetooth.le` differ in threading model, permission model, background execution, advertising API shape, and scan-callback semantics. Under KMP they sit behind `expect`/`actual` and are written twice, exactly as ADR-001 predicted. Background modes, iOS state restoration, the Android foreground service, resolvable private address rotation, and adaptive duty cycling all remain per-platform native work.

What KMP does share is the other ~70% where duplication actually hurts us: the BLE **wire codec** and state machine, distance banding and hysteresis, the ephemeral-ID key schedule, Double Ratchet session handling, the Connect-RPC client, local persistence, and the domain models behind Discover / Radar / Threads. ADR-001's stated drift risk — two implementations of one protocol diverging — is not mitigated by conformance vectors under this ADR. It is eliminated, because there is exactly one implementation.

## Decision

**Mobile is Kotlin Multiplatform with a shared core and fully native UI.**

Shared, in `mobile/shared/` (Kotlin, `commonMain`):
- BLE wire codec, frame parsing, and the discovery state machine
- distance banding, hysteresis, and display jitter
- key schedule: `account_key → daily_key → ephemeral_id`
- E2EE session handling (Double Ratchet)
- Connect-RPC client and generated protobuf models
- persistence via **SQLDelight**
- domain models and use cases for all three modes

Not shared — native per platform:
- **All UI.** SwiftUI on iOS (minimum iOS 16), Jetpack Compose on Android (minimum API 29). No Compose Multiplatform on iOS.
- **All radio.** `CoreBluetooth` and `android.bluetooth.le` behind `expect`/`actual`.
- Background execution: iOS background modes and state restoration; Android foreground service and WorkManager.
- WebRTC media layer: native WebRTC framework on iOS, libwebrtc on Android.
- Platform integration: push, IAP / Play Billing, permissions, camera, keychain / keystore.

The **public API surface of `mobile/shared/` is a contract** and falls under the contract-first law in `.claude/ORCHESTRATION.md` §3, identically to `backend/proto/`. It is consumed by two clients; it does not change without the gate.

### UI: why not Compose Multiplatform

Rejected. Radius is a premium product whose Radar screen is a continuously-animating custom canvas, and whose value proposition is that it feels more considered than the category. Compose Multiplatform on iOS still carries rough edges in scrolling physics, text input, and accessibility — precisely the surfaces where "cheap app" is perceived. ADR-001's argument for native UI performance and best-in-class accessibility survives this ADR intact.

### Persistence: SQLDelight, not GRDB + Room

A shared module cannot own persistence through two platform-specific database libraries. GRDB (iOS) and Room (Android) are both dropped.

SQLDelight is chosen over Room-KMP because it is compile-time-checked raw SQL with no ORM layer — the exact mobile analogue of `sqlc` on the backend, and consistent with the project-wide "no ORMs" rule. Room-KMP is an ORM with annotation-processor codegen and sits closer to that ban.

SQLCipher encryption at rest is retained on both platforms, unchanged.

## Relationship to ADR-001

ADR-001 is **superseded in part**, not withdrawn. What survives:

- Native UI per platform — reaffirmed, same reasoning.
- Direct platform BLE APIs, no Nordic wrapper — reaffirmed.
- Two native app shells, two app-store artefacts — unchanged.
- Its analysis that BLE cannot be meaningfully abstracted — reaffirmed and load-bearing here.

What is overturned: the "share no application code" clause, and the GRDB / Room persistence choices.

## Consequences

**Good.** One implementation of the BLE codec, the key schedule, and the ratchet — the three places where a cross-platform divergence would be a security bug rather than a cosmetic one. Roughly 60-70% less duplicated non-UI work. Business logic testable once on JVM, fast, with no device in the loop. Native UI and native radio control fully preserved.

**Bad / accepted costs.**
- **A Mac becomes mandatory infrastructure, not a preference.** Kotlin/Native iOS targets compile only on macOS. iOS development and CI both require Apple hardware. There is no Windows or Linux path. This is the single largest new cost introduced by this ADR.
- Build complexity rises materially: Gradle drives the iOS framework, so Xcode builds depend on a Gradle step.
- iOS engineers must read and debug Kotlin, including Kotlin/Native memory and concurrency behaviour crossing the interop boundary.
- Kotlin/Native compile times and the resulting binary size are both worse than pure Swift.
- The talent pool remains the smallest of the cross-platform options — ADR-001's original objection is accepted, not solved.
- Debugging across the Swift ↔ Kotlin/Native boundary is worse than either side alone.

**Explicitly not gained.** BLE effort is not reduced. Anyone claiming this ADR shortens the Phase 0 spike is wrong; the spike still requires two native radio implementations and real hardware on both.

**Safety invariants.** Unchanged, all ten, plus C1-C8. The shared module makes several of them *easier* to enforce, because invariants 4 (BLE payload shape), 5 (ID rotation), and 2 (banded distance) now have exactly one implementation to audit rather than two.

**Reversibility:** Expensive but not one-way. Reverting means reimplementing the shared core twice natively. Cheapest today, at Phase 0, with no application code written.

## Revisit when

Kotlin/Native interop or tooling regresses badly enough to cost more than the duplication it removes, or Compose Multiplatform on iOS reaches a fidelity bar where the UI split is worth reopening.
