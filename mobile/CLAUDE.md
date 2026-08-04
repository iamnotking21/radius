# mobile/ — MEMORY
owners: ios-swift(ios/) · android-kotlin(android/ + shared/ minus protocol/) ·
        ble-protocol(protocol/ + shared/protocol/) · design-system(design-tokens/)

## KOTLIN MULTIPLATFORM (ADR-007). shared core, NATIVE UI.
shared/ = commonMain Kotlin. ONE impl of: BLE codec + state machine · banding/hysteresis/
jitter · key schedule · Double Ratchet · Connect-RPC client · SQLDelight · domain models.
shared/ PUBLIC API = CONTRACT. 2 consumers. orchestrator gates every change. never unilateral.

## NOT shared. write twice, natively.
ALL UI (SwiftUI / Compose) · ALL radio (CoreBluetooth / android.bluetooth.le via expect/actual) ·
background modes · WebRTC media · push · IAP · permissions · camera · keychain/keystore
KMP DID NOT SHRINK THE BLE WORK. do not plan as if it did.

## BUILD
Gradle drives everything incl the iOS XCFramework. Xcode build depends on a Gradle step.
Kotlin/Native iOS targets compile on macOS ONLY. no Windows/Linux path. Mac = mandatory.
targets: androidTarget · iosArm64 · iosSimulatorArm64

## both platforms MUST
- consume the SAME shared/ codec. conformance vectors still run in CI as the regression net.
- same BLE state machine: IDLE→SCAN→RESOLVE→WAVE_SENT→WAVE_RECV→HANDSHAKE→SESSION
  (now literally one Kotlin impl — divergence should be impossible, prove it anyway)
- same 4 bands, same hysteresis, same jitter rule
- local DB encrypted: SQLDelight + SQLCipher both sides. NO GRDB. NO Room.
- cert-pin the API domain
- E2EE via vodozemac. keys NEVER leave device. never sync to server.
- DI = constructor injection. shared exposes a plain factory. NO Koin. Hilt = Android UI only.

## battery contract (CI-gated)
<4%/hr scanning · <1%/day idle. adaptive duty: cut scan when stationary, <20% batt,
or no peer seen 10min.

## radar UI invariants (do not "improve")
node angle RANDOM per session · no map · no bearing · banded distance only ·
displayed metres = band midpoint + jitter · ghost mode ≤1 tap

## calling
native WebRTC framework (iOS) / libwebrtc (Android). no wrapper lib.
Opus audio · H.264 preferred (hardware encode = battery) / VP8 fallback. DTLS-SRTP mandatory.
1:1 = P2P. if you find yourself adding a media server for 1:1, stop — that's ADR-005 violation.
in-call safety ≤1 tap. end call instant, no confirm dialog. blur + camera-off before AND during.
beauty filters default OFF. call must be fully usable audio-only.
a11y: quality chip always has a TEXT label. announce state changes as live regions.
  audio-reactive ring is decorative — pair with an announced "X is speaking".
  offer a persistent non-auto-hiding control mode in a11y settings.

## testing
BLE tested on REAL HARDWARE only. simulator BLE result = invalid, never claim pass.
weekly radio day: whole mobile team, real world, real distances.
hardware rig: 6 devices fixed distances → discovery latency + RSSI + battery → Grafana.
