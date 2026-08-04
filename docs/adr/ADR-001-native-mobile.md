# ADR-001 · Native Swift + Kotlin, not cross-platform

**Status:** SUPERSEDED IN PART by [ADR-007](ADR-007-kotlin-multiplatform-shared-core.md) (2026-08-04)
**Date:** 2026-08-04
**Deciders:** CTO, founder

> **Superseded in part.** ADR-007 overturns the "share no application code" decision and the
> GRDB / Room persistence choices, replacing them with a Kotlin Multiplatform shared core and
> SQLDelight. Still in force from this ADR: native UI per platform, direct platform BLE APIs
> with no third-party wrapper, and the analysis below of why BLE cannot be meaningfully
> abstracted — that analysis is load-bearing in ADR-007. Read both.

## Context

Radius's defensible feature is Bluetooth LE proximity discovery and offline peer-to-peer messaging. That requires deep, sustained use of `CoreBluetooth` on iOS and `android.bluetooth.le` on Android, including background execution modes, state restoration, foreground services, resolvable private addresses, adaptive scan duty cycling, and battery profiling with platform-native instruments.

Every cross-platform framework treats Bluetooth as a plugin boundary. React Native and Flutter both require writing the BLE layer natively per platform anyway, then marshalling across a bridge that adds latency and obscures exactly the platform-specific behaviour we most need to control. Kotlin Multiplatform is more promising, but the BLE layer would still be `expect`/`actual` per platform, and the talent pool is the smallest of the four options.

The remaining ~80% of the app (onboarding, discovery, chat UI) is genuinely well-served by cross-platform tooling. So this decision is a trade: we pay roughly 1.8× on the easy 80% to get full control over the hard 20% that is the entire business.

## Decision

Build two fully native applications: **Swift 6 / SwiftUI / CoreBluetooth** for iOS (minimum iOS 16), and **Kotlin 2 / Jetpack Compose / platform BLE APIs** for Android (minimum API 29).

Share no application code. Instead share three generated or specified artefacts: the BLE wire protocol specification with cross-platform conformance test vectors (`mobile/protocol/`), generated API clients from protobuf (`backend/proto/`), and design tokens (`mobile/design-tokens/`).

On Android, use the platform Bluetooth APIs directly rather than a third-party wrapper such as Nordic's library, so that our single most critical code path has no dependency we do not control.

## Alternatives considered

**React Native + native BLE modules.** Shared UI, JS talent is abundant. Rejected because the BLE layer is written twice regardless, the bridge complicates the concurrency model in exactly the code where races are most dangerous, and background execution behaviour becomes harder to reason about.

**Flutter + platform channels.** Excellent UI fidelity, which matters for a premium product. Rejected for the same BLE reason, plus a scarcer talent pool in our hiring market.

**Kotlin Multiplatform.** Genuinely attractive — shared business logic and protocol layer in Kotlin, native UI per platform. Rejected for v1 on hiring risk and setup risk, not on technical merit. This is the alternative most likely to be revisited.

## Consequences

**Good.** Full control over the radio layer, background modes, and battery. Native performance for the continuously-animating Radar canvas. Platform-native instruments (Instruments Energy Log, Battery Historian) work without indirection. No framework upgrade treadmill on the critical path. Best-in-class accessibility support on both platforms.

**Bad / accepted costs.** Roughly 1.8× the mobile engineering effort. Two codebases that will drift without discipline. Two sets of bugs. Requires hiring specialists on both platforms — the BLE-deep iOS and Android engineers are the longest hiring pole in the plan.

**Drift mitigation.** The conformance vectors in `mobile/protocol/vectors/` are the primary control: both platforms parse identical hex payloads and must produce identical results, enforced in CI. Plus a weekly cross-platform parity review.

**Reversibility:** Expensive. Roughly a one-way door for the life of v1.

## Revisit when

Kotlin Multiplatform's BLE story matures materially, or we find ourselves maintaining a third client (a wearable, say) and the duplication cost triples.
