# Radius

Premium dating app. The moat is **BLE proximity** — finding and chatting with people nearby, with no internet. Online mode is normal worldwide dating. Native iOS + Android, Go backend, self-hosted.

> **Status: Phase 0 — BLE feasibility spike. NOT YET PROVEN.**
> The Android code builds and its tests pass. **No radio has been touched, no device has run it, and no measurement exists.** A green test run means the codec is arithmetically correct; it says nothing about whether the product is possible. See [`docs/PHASE0_GO_NO_GO.md`](docs/PHASE0_GO_NO_GO.md), whose verdict section currently reads *NOT YET REACHED*.

---

## Three modes

- **DISCOVER** — online dating. A finite daily set. No swipe deck.
- **RADAR** — BLE nearby. Works offline.
- **THREADS** — one inbox, both origins, transport labelled.

## What actually exists today

| Area | State |
|---|---|
| BLE wire spec + 116 conformance vectors | Written, executing, security-reviewed |
| Protocol codec (frame, key schedule, banding) | One Kotlin implementation, 126 tests green |
| Android radio (`android.bluetooth.le`) | Real advertise + scan, role-gated, never on a device |
| Phase 0 spike harness | Built. Logs raw sightings + radio lifecycle, counts its own losses |
| CI gates | 9 gates, 95 self-tests, green on GitHub Actions |
| Design system | Tokens from Figma, generated at build time. A WCAG contrast regression across 51 pairings fails the Android build. Fonts not yet chosen. |
| Code review | Two full passes. 20 must-fixes found and landed. |
| iOS | Scaffold only. Has never been compiled — needs a Mac |
| Backend, web, infra | **Nothing.** Not started, correctly — Phase 0 gates them |

Roughly **8%** of v1. The advanced part is the moat; almost everything else is zero.

That number moves slowly on purpose. The work since Phase 0 began has gone into making the instrument trustworthy rather than into features — because a spike that returns confident garbage is worse than one that crashes, and you would act on it.

## The constraints that shape everything

**Ten safety invariants** and **eight calling invariants** in [`CLAUDE.md`](CLAUDE.md) block a merge if violated. The load-bearing ones:

- No map, no bearing, no lat/lng anywhere — city resolution is geohash5 at most
- Distance is four bands only, displayed hedged and jittered
- The BLE payload carries no name, no account id, no stable identifier
- Ephemeral IDs rotate every 15 min, and the MAC rotates in step — **both or neither**
- Messages are E2EE; the server holds ciphertext only
- Phone numbers are never exchanged; calls are never recorded — no content column exists, by design
- No dark patterns, enumerated and banned

Several are mechanically enforced in CI, not left to review.

## What we learned in Phase 0 that changed the design

These were found before a line of product code was written, which is the entire point of a spike.

- **iOS cannot advertise service or manufacturer data in any state** — not a background restriction, an API limit. Found independently by two agents from opposite platforms. The 19-byte frame therefore travels over a **second carrier** (a GATT read) on iOS rather than degrading.
- **RPA co-rotation is uncontrollable and chipset-dependent.** Safety invariant 5 is a *per-device-model* property, not a per-platform one. This is the open question most likely to kill the thesis, and it needs a hardware sniffer — an app cannot observe its own MAC address.
- **Two devices sharing an account broadcast an identical ephemeral ID from two locations.** A stationary second device becomes a live-ID oracle at a known address — a stalking primitive requiring no tailing. Hence: exactly one advertising device per account, everything else scan-only, fail-closed.
- **The scan mode we started with was 100% duty against a contracted 30%.** No build would ever have reported it.

## Repository map

Exactly one writer per path. Cross-boundary changes go through the orchestrator as a HANDOFF ([`ORCHESTRATION.md`](.claude/ORCHESTRATION.md) §4).

```
CLAUDE.md              Root memory. Read every session.
docs/
  TECH_STACK_AND_PLAN.md   Full architecture, phasing, cost, risk
  PHASE0_GO_NO_GO.md       Decision thresholds, pre-committed before any data
  adr/                     9 Architecture Decision Records + template
mobile/
  protocol/            BLE wire spec + conformance vectors (prose + data)
  shared/              KMP core — codec, key schedule, banding, domain
  android/             Compose app + Phase 0 spike harness
  ios/                 SwiftUI shell. Uncompiled.
devops/ci/             Gates, runner, workflows
.claude/memory/        Live state, decision log, contracts, blockers
```

**Mobile is Kotlin Multiplatform with native UI** — shared logic, SwiftUI on iOS, Compose on Android. See [ADR-007](docs/adr/ADR-007-kotlin-multiplatform-shared-core.md). KMP does **not** share the radio layer; that is written twice, deliberately.

## Building

Android needs JDK 17+ and the Android SDK. iOS needs macOS — Kotlin/Native iOS targets do not compile anywhere else.

```bash
cd mobile && ./gradlew :android:assembleDebug :shared:testDebugUnitTest
```

The build prints a loud warning on non-macOS hosts that iOS targets were skipped. That warning is deliberate: it exists so nobody reports cross-platform parity from a build that never compiled half of it.

## The rules that make parallel work possible

**Contract first.** A protobuf, BLE spec, or shared-API change is reviewed and merged *alone*, with consumers notified, before any implementation depends on it. Never the reverse.

**Memory updated every session.** Every agent rewrites [`20-state.md`](.claude/memory/20-state.md) before finishing. Without the ritual it decays into fiction within a week.

**Never report unverified work as verified.** Files carry `UNVERIFIED` markers until a compiler or a device says otherwise. A gate that cannot run yet fails loudly rather than passing on nothing.

## What is blocking

Phase 0 cannot complete from a keyboard. It needs:

- Android handsets across chipset vendors, budget MediaTek first
- 3× nRF52840 dongles, one per advertising channel — one dongle cannot distinguish a missed packet from an absent one, and the question is about absence
- A legal entity → Bluetooth SIG Adopter → a real service UUID. The provisional one is blocked from release builds by CI, deliberately and indefinitely.
