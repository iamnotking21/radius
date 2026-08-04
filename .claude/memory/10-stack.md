# 10 · STACK (locked. re-open only via new ADR)

## mobile — KOTLIN MULTIPLATFORM (ADR-007, supersedes ADR-001 in part)
shared (mobile/shared, Kotlin2, commonMain):
     BLE wire codec + discovery state machine · banding + hysteresis + display jitter ·
     key schedule (account_key→daily_key→ephemeral_id) · Double Ratchet session mgmt ·
     Connect-RPC client + protobuf models · SQLDelight+SQLCipher · domain models/use-cases
     targets: androidTarget · iosArm64 · iosSimulatorArm64 (+ iosX64 only if Intel Mac)
     public API = CONTRACT. contract-first law, same gate as proto.
     DI = constructor injection + plain factory. NO Koin.
ios: Swift6 strict-conc · SwiftUI (+UIKit for radar canvas) · CoreBluetooth (actual) ·
     CryptoKit · Tuist · XCTest+XCUITest · min iOS16
     consumes shared as XCFramework produced by Gradle. Xcode build depends on Gradle step.
     radar canvas: CAShapeLayer+CADisplayLink. Metal ONLY if profiler demands.
android: Kotlin2 · Compose+M3(heavily themed) · android.bluetooth.le direct (actual) ·
     Tink · Hilt (UI graph ONLY) · WorkManager + FOREGROUND SERVICE for radar · min API29
NOT shared (native both sides): ALL UI · ALL radio · background modes · WebRTC media ·
     push · IAP/Play Billing · permissions · camera · keychain/keystore
DROPPED by ADR-007: GRDB · Room · "no shared code" rule
BANNED: Compose Multiplatform on iOS (premium UI + animated radar canvas) · Room-KMP (ORM)
BUILD CONSTRAINT: Kotlin/Native iOS targets compile on macOS ONLY. Mac = mandatory infra.
still-shared artifacts: mobile/protocol (BLE spec+vectors) · backend/proto · design-tokens

## backend
Go1.23+ · Connect-RPC + buf (1 proto → gRPC + JSON/HTTP) · sqlc (NO ORM) ·
chi · protovalidate · River (postgres jobs) · golang-migrate · koanf ·
auth in-house JWT EdDSA + rotating refresh

services (10, modular-not-micro, 1 repo):
identity · profile · discovery · proximity · messaging · calling · gateway · media · safety · billing

## calling (v1.1)
1:1 = WebRTC PEER-TO-PEER. NO media server, NO SFU. media never touches our infra.
signalling = Go svc over EXISTING WS gateway (SDP + ICE as protobuf frames)
STUN/TURN = coturn (BSD-3). ~10-20% of calls relay (symmetric NAT / carrier).
codecs: Opus 24-32kbps · H.264 preferred (hw encode = battery) / VP8 fallback
encryption: DTLS-SRTP mandatory. P2P ⇒ genuinely E2E.
LiveKit (Apache2, Go) SFU ONLY if group calls / events mode ship P4.
clients: native WebRTC framework (iOS) / libwebrtc (Android). no wrapper.
BLE calling: NOT POSSIBLE at quality. P4 = push-to-talk, Opus 8-12kbps, 15s ≈ 15KB over GATT.

## data
Postgres16 (+PostGIS geohash5 only, +pgvector 256d) · Valkey (NOT Redis) ·
NATS JetStream (NOT Kafka until 1M DAU) · SeaweedFS (NOT MinIO/AGPL) ·
search: Postgres FTS → OpenSearch only when >100k profiles ·
ClickHouse phase2 analytics

## realtime
WebSocket, own Go gateway. protobuf frames. NATS subject-per-user fanout.
presence = Valkey TTL keys. at-least-once + client dedupe by msg ULID.
outbox pattern postgres→NATS.
why own not Centrifugo: must interleave BLE + net msgs under one sequencing model.

## crypto
E2EE = Double Ratchet via vodozemac (Apache2). X3DH agreement.
NEVER libsignal (AGPL, closed-source app = violation).
BLE identity: account_key -HKDF-> daily_key -HKDF(day,epoch)-> ephemeral_id(16B/15min)

## infra
OpenTofu · Ansible · Docker · Compose(P0) → K3s(P1-2) → TKE(P3+) ·
Caddy (TLS auto) · SOPS+age → OpenBao(P2) · Gitea+Actions self-host ·
Prometheus Grafana Loki Tempo GlitchTip UptimeKuma · pgBackRest
cloud: Tencent CVM. REGION = SINGAPORE (mainland ⇒ ICP filing, GFW. never).
P0 topology: cvm-edge 2c4g pub · cvm-core 4c8g priv · cvm-data 4c16g priv

## web
marketing: Next15 static-export-where-possible + Tailwind
admin: React+Vite+TanStack Router/Query. VPN-gated. SSO + hardware-key 2FA
  for anyone w/ moderation power. admin console = product constraint not side project.

## ml (phase order matters)
P1 matching = DETERMINISTIC RULES. explainable. ship this first.
P3 matching = two-tower embeddings, ONNX Runtime in Go, pgvector.
moderation: self-host NSFW classifier + PDQ hash + human queue
verification: InsightFace embedding compare. store RESULT only. vectors ≤30d.
