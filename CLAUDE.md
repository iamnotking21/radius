# RADIUS — ROOT MEMORY
<!-- CAVEMAN MODE. Terse = cheap. Read fully. Obey exactly. -->
<!-- READ ORDER: this → .claude/memory/*.md → .claude/ORCHESTRATION.md → folder CLAUDE.md -->

## WHAT
Premium dating app. Moat = BLE proximity. Find + chat ppl nearby, NO internet.
Online = normal dating worldwide. Native iOS + Android. Backend Go. Self-host.

## 3 MODES (memorize)
- DISCOVER — online dating. finite daily set. NO swipe deck. accent=gold ember/400
- RADAR — BLE nearby. works offline. accent=teal signal/400
- THREADS — one inbox, both origins. label transport. calls live here too.

## CALLING (added v1.1)
1:1 WebRTC PEER-TO-PEER. no media server, no SFU. media never touches our infra.
signalling = Go svc over existing WS gateway. STUN/TURN = coturn. DTLS-SRTP mandatory.
INVITED NEVER COLD-RUNG — explicit request→accept BEFORE any SDP. phone never rings first.
NUMBERS NEVER EXCHANGED. NEVER RECORDED (no content column exists, by design).
in-call safety ≤1 tap. blur + camera-off before AND during. beauty filters default OFF.
BLE calling = physically impossible at quality. P4 = push-to-talk clips only. say so honestly.
watch TURN egress: relayed video ~0.7GB/call-hr each way. voice default, 800kbps video cap.

## STACK (locked, ADR-backed, do not re-litigate)
MOBILE = KOTLIN MULTIPLATFORM. shared core, NATIVE UI. ADR-007 supersedes ADR-001 in part.
shared (mobile/shared, commonMain): BLE codec + state machine · banding/hysteresis/jitter ·
  key schedule · ratchet · Connect-RPC client · SQLDelight · domain models. Kotlin2.
  public API of shared/ = CONTRACT. contract-first law applies, same as proto.
iOS: Swift6 strict-concurrency · SwiftUI · CoreBluetooth · Tuist · min iOS16
Android: Kotlin2 · Compose · platform BLE (no Nordic lib) · Hilt (UI graph only) · min API29
NOT shared, native both sides: ALL UI · ALL radio (expect/actual) · background modes ·
  WebRTC media · push · IAP · permissions · camera · keychain/keystore
NO Compose Multiplatform on iOS. NO GRDB. NO Room. SQLDelight only (raw SQL, no ORM).
DI: constructor injection. shared exposes plain factory. NO Koin. Hilt stays Android-UI-only.
MAC MANDATORY. Kotlin/Native iOS targets compile on macOS ONLY. no Windows/Linux path.
Backend: Go1.23+ · Connect-RPC+buf · sqlc · chi · River jobs · golang-migrate
Calling: WebRTC P2P · coturn (STUN/TURN) · Opus + H.264 · LiveKit SFU ONLY if group calls P4
Data: Postgres16 + PostGIS + pgvector · Valkey · NATS JetStream · SeaweedFS
Web: Next15 marketing · React+Vite admin
Infra: OpenTofu · Ansible · Docker · Compose→K3s→TKE · Caddy · SOPS→OpenBao
CI: Gitea Actions self-host. Obs: Prometheus Grafana Loki Tempo GlitchTip
Crypto: vodozemac (Apache2). NOT libsignal (AGPL = legal risk).
Cloud: Tencent CVM, region SINGAPORE. NEVER mainland (ICP filing trap).

## BANNED
- 3rd-party SaaS: Firebase Supabase Auth0 Twilio Algolia Sendbird Agora Stripe-in-app
- MinIO (AGPL) · Redis post-license-change (use Valkey) · Terraform (use OpenTofu)
- ORMs. use sqlc + raw SQL.
- map view / lat-lng storage / bearing calc — ANYWHERE. see SAFETY.
- swipe deck · streaks · expiry timers · blurred-face paywall · ads
- DARK PATTERNS, all of them: fake countdown/scarcity · fake likes or bot profiles ·
  invented "someone viewed you" · hidden/obstructed cancellation · confirmshaming ·
  throttling matches to induce despair · auto-renew w/o conspicuous disclosure
  why: FTC enforces under ROSCA now; EU Digital Fairness Act draft Q3/Q4 2026;
  and dating-app refund/chargeback rates already draw store scrutiny.
  ALSO: we SELL honesty. a manipulative paywall contradicts the product.

## ALLOWED 3rd-PARTY (only these 4, unavoidable)
APNs+FCM push · Apple IAP + Play Billing · OTP delivery (email first, SMS later) · store review

## SAFETY INVARIANTS (violate = block PR, no exceptions)
1. NO map. NO bearing. NO lat/lng server-side. city = geohash5 max.
2. distance = 4 bands only (HERE/CLOSE/AROUND/EDGE). display hedged + jittered.
3. radar UI node angle = RANDOM per session. never encodes real direction.
4. BLE payload = [ver:1][ephemeral_id:16][txpower:1][flags:1] ONLY.
   NO name, NO account id, NO coords, NO stable identifier. flags carry protocol bits only.
5. ephemeral_id rotates 15min. MAC rotates in step (RPA). both or neither.
6. chat unlocks ONLY on mutual wave. verified accounts only on Radar.
7. block enforced at key-resolution layer. blocked user unresolvable, invisible.
8. EXIF stripped on ingest BEFORE durable write.
9. msg content E2EE. server holds ciphertext only.
10. ghost mode ≤1 tap from Radar screen.

## CALLING INVARIANTS (same weight as the 10 above)
C1. phone numbers NEVER exchanged. no code path reveals one.
C2. calls NEVER recorded. no content column exists. wiretap law + trust.
C3. invited, never cold-rung. request→accept BEFORE any ring or SDP.
C4. in-call safety control ≤1 tap from every active call.
C5. end call instant, never confirmed with a dialog.
C6. blur + camera-off available before AND during. beauty filters default OFF.
C7. "who can call me" enforced SERVER-SIDE, not hidden in UI.
C8. blocked ⇒ call authorisation FAILS at the service.

## HARD NUMBERS
battery: <4%/hr scanning · <1%/day idle. CI FAILS on regression.
discovery latency target: <5s foreground, <60s background.
BLE adv: 250ms fg / 1000ms bg. scan duty 30%. payload ≤26B. MTU≥185.
bands dBm: HERE≥-55 · CLOSE≥-70 · AROUND≥-82 · EDGE≥-95. hysteresis on.
retention: encounters 30d (free tier 24h) then HARD delete. face vectors ≤30d.
account delete: 30d grace → true hard delete incl backups.

## REPO MAP + OWNER AGENT (exactly one writer per path)
backend/   → backend-go        (proto/ = SOURCE OF TRUTH for all interfaces)
  EXCEPT backend/services/discovery/ranking/ → data-ml
  EXCEPT backend/services/calling/ → calling-webrtc
  EXCEPT backend/services/billing/ → growth-conversion
mobile/ios → ios-swift        (SwiftUI + CoreBluetooth actual + Xcode/Tuist)
mobile/android → android-kotlin (Compose + android.bluetooth.le actual + Gradle app)
mobile/shared → android-kotlin (KMP commonMain. API change = CONTRACT, orchestrator gates,
  ios-swift is a consumer and MUST be notified. never change shared API unilaterally.)
  EXCEPT mobile/shared/protocol/ → ble-protocol (BLE codec + state machine, the moat)
mobile/protocol → ble-protocol (BLE wire SPEC + conformance vectors. spec is prose+vectors;
  the single Kotlin impl lives in mobile/shared/protocol/ and must pass the vectors)
mobile/design-tokens → design-system
website/   → web-next
devops/    → devops-tencent   EXCEPT devops/ci/ TEST stages → qa-test
  (devops-tencent owns the runner + infra/deploy stages; qa-test owns the test + gate stages)
  devops/tofu/coturn/ → calling-webrtc (only module carve-out)
analytics/ → data-ml
**/tests/  → qa-test (source dirs stay with their platform agent)
docs/adr/  → orchestrator ONLY (append only). others PROPOSE, never write.
.claude/memory/ → orchestrator owns structure+pruning. ALL agents append to 20/30/60.

## GOLDEN RULES
- CONTRACT FIRST. proto change → ADR + architect review → THEN code. never reverse.
- agent writes ONLY own dir. cross-dir need = raise to orchestrator.
- no secret in repo. SOPS only.
- forward-only migrations. never edit shipped migration.
- real hardware for BLE. simulator BLE test = worthless, do not claim pass.
- every irreversible decision → ADR in docs/adr/.
- update .claude/memory/20-state.md at end of EVERY session. non-negotiable.

## CONVERSION (v1.1)
strategy: generous free ⇒ real value felt ⇒ contextual offer at MOMENT of felt need ⇒ 2 taps.
allowed psych: anchoring · per-day framing · centre-stage · loss aversion ON TRUE STATEMENTS ·
  reciprocity (E7 no-card gift) · endowed progress · goal gradient · peak-end · real social proof ·
  FRICTION REMOVAL (biggest lever)
fatigue: max 2/session, ≥60s apart. NEVER during onboarding/verification/ACTIVE CALL/
  handshake/match/first-msg/report/block/safety. 3 declines ⇒ 30d suppression.
  ALL upsells suppressed 24h after report, block, or bad call rating.
cancel: ≤2 taps from Settings, 1 real retention offer max, renewal disclosure above the fold.
honesty check on founder dashboard: conversation+reply rate PAYING vs FREE.
  payers not doing better ⇒ price is wrong, psychology won't fix it.

## PHASE NOW
Phase 0 — BLE spike. go/no-go memo. NOTHING else starts until spike passes.

## FULL DETAIL
docs/TECH_STACK_AND_PLAN.md — read when caveman file insufficient. do not duplicate it here.
