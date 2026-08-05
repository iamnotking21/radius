# RADIUS — Technical Strategy, Stack & Delivery Plan

**Prepared by:** CTO / Tech Lead
**Version:** 1.1 · August 2026 *(v1.1 adds §4.7b–c calling and §15 the conversion system)*
**Status:** Approved for build
**Audience:** Engineering leadership, investors, incoming senior hires

---

## 1. EXECUTIVE SUMMARY

Radius is a premium dating app whose defensible feature is **proximity connection over Bluetooth Low Energy** — discovering and messaging people who are physically near you, with no internet connection required. Everything else in the product is table stakes that competitors already have. The BLE mesh layer is the moat, and it is also by far the hardest thing to build.

That single fact drives every decision in this document.

**The strategy in one paragraph.** Build native on both mobile platforms because the BLE layer cannot be built well any other way. Build the backend in Go as a small set of services that speak a contract-first protobuf API. Self-host the entire stack on open-source software running on Tencent Cloud compute, so the running cost during pre-launch is effectively zero and there is no vendor holding our data. Ship in four phases over roughly nine months, front-loading the BLE spike into week one so we learn whether the moat is real before we spend a year building around it.

The product also ships **in-app voice and video calling** — peer-to-peer WebRTC that costs almost nothing to run, never exposes a phone number, and is invited rather than cold-rung. §4.7b covers it. And §15 sets out the conversion system, including where I drew the line on monetization psychology and why that line is a commercial decision as much as an ethical one.

**The three things that will decide whether this works:**

1. **iOS background BLE is severely restricted by Apple, and no amount of engineering fully removes that restriction.** Section 6.7 explains exactly what does and does not work. The product must be designed to tolerate it. This is the single largest technical risk in the company.
2. **Proximity dating has a cold-start density problem.** Radar is worthless with no one nearby. The launch plan must be geographically concentrated, not national.
3. **Trust and safety is not a feature, it is a licence to operate.** A proximity dating app that leaks location or enables stalking is an existential legal and reputational event, not a bug.

---

## 2. CONSTRAINTS & ENGINEERING PRINCIPLES

### 2.1 The constraints you gave me

| Constraint | What it means technically |
|---|---|
| **Free for now** | Zero recurring software licence cost. Self-hosted open-source only. Run on existing Tencent Cloud credits/free tier. |
| **No third-party dependencies** | No Firebase, no Supabase, no Auth0, no Twilio, no Algolia, no Sendbird, no Agora. We own auth, realtime, search, storage and messaging. |
| **Tencent Cloud available** | Tencent is our compute and network substrate. We use it as raw infrastructure (VMs, object storage, load balancing), not as a managed-service lock-in. |
| **Team of 8–15** | We can afford to build rather than buy. We can staff two native mobile tracks. We cannot afford to build things that are genuinely commodity. |
| **Native Swift + Kotlin** | Two mobile codebases. Shared logic lives in the *protocol spec*, not in shared code. |

### 2.2 Principles

1. **Own the moat, rent the commodity.** We build the BLE mesh, the matching engine, and the safety system ourselves. We do not build our own database, message broker, or observability stack — we self-host proven open-source ones.
2. **Contract first.** Every interface — HTTP, gRPC, WebSocket, and the BLE wire format — is defined in protobuf before any implementation exists. This is what lets twelve people and multiple agents work in parallel without constant collision.
3. **Privacy is architectural, not procedural.** We cannot leak a precise location if we never compute one. Design so that the dangerous data does not exist.
4. **Portable by default.** Everything runs in containers, defined in OpenTofu and Ansible. If we ever need to leave Tencent Cloud, it is a weekend, not a quarter.
5. **Boring where it doesn't matter.** Postgres, Go, Kotlin, Swift. Save the innovation budget for the radio layer.
6. **Ship a vertical slice early.** A working end-to-end BLE handshake between two phones beats a beautiful architecture diagram.

### 2.3 The four dependencies we cannot avoid

I want to be straight with you rather than pretend "no third party" is fully achievable. Four things are outside our control:

| Dependency | Why it's unavoidable | Cost |
|---|---|---|
| **Apple APNs + Google FCM** | The only way to deliver push notifications. Platform-owned, no alternative exists. | Free |
| **Apple IAP + Google Play Billing** | Store policy *requires* digital subscriptions go through their billing. Using Stripe in-app gets the app removed. | 15% (small business / after year one) to 30% |
| **Phone/email OTP delivery** | Someone must physically deliver the code. | Use **email OTP at MVP (free, self-hosted SMTP)**; add SMS only when we can afford it. Tencent Cloud SMS is the natural choice since we're already there. |
| **App Store + Play Store review** | Distribution. Dating apps get extra scrutiny and a 17+/18+ rating. | $99/yr Apple, $25 once Google |

Everything else in this document is open-source and self-hosted.

---

## 3. REPOSITORY STRUCTURE

Matching the folders you already have, with two additions:

```
radius/
├── backend/                 Go services, protobuf contracts, migrations
│   ├── proto/               ← THE SOURCE OF TRUTH for every interface
│   ├── services/
│   │   ├── identity/        auth, sessions, verification
│   │   ├── profile/         profiles, photos, prompts
│   │   ├── discovery/       matching, daily set, filters
│   │   ├── proximity/       BLE ID issuance, encounter ledger, handshakes
│   │   ├── messaging/       threads, delivery, sync
│   │   ├── calling/         WebRTC signalling, TURN auth, call ledger
│   │   ├── gateway/         public API + WebSocket edge
│   │   ├── media/           upload, transcode, moderation pipeline
│   │   ├── safety/          reports, blocks, moderation queue, appeals
│   │   └── billing/         receipt validation, entitlements
│   ├── pkg/                 shared Go libraries
│   └── migrations/          SQL, versioned, forward-only
│
├── mobile/
│   ├── ios/                 Swift 6 / SwiftUI / CoreBluetooth
│   ├── android/             Kotlin 2 / Compose / BLE
│   ├── protocol/            BLE wire spec + conformance test vectors
│   └── design-tokens/       generated from the Figma design system
│
├── website/
│   ├── marketing/           Next.js public site
│   ├── admin/               React internal console (moderation, support, ops)
│   └── shared/              UI primitives, design tokens
│
├── devops/
│   ├── tofu/                OpenTofu — infrastructure as code
│   ├── ansible/             host provisioning
│   ├── compose/             Phase-0 single-host stack
│   ├── k8s/                 Phase-2+ manifests / Helm charts
│   ├── ci/                  pipeline definitions
│   └── runbooks/            on-call procedures
│
├── docs/                    ← NEW: architecture, ADRs, specs
└── .claude/                 ← NEW: agent definitions + permanent memory
```

**One monorepo.** With 8–15 people and a contract-first protobuf layer, a monorepo means an API change and its four consumers land in one atomic commit. Polyrepo at this size buys you nothing but version skew.

---

## 4. THE STACK

> ### ⚠ SECTIONS 4.1 AND 4.2 ARE SUPERSEDED IN PART — read this first
>
> This document was written before Phase 0 started. Three architecture decisions have landed
> since, and **§4.1/§4.2 below still describe the pre-ADR-007 world.** Corrected here rather
> than rewritten in place, so the original reasoning stays legible — several of its arguments
> survived the change and are still the best statement of why.
>
> **[ADR-007](adr/ADR-007-kotlin-multiplatform-shared-core.md) — mobile is Kotlin Multiplatform**,
> shared core with native UI. Supersedes ADR-001 in part.
> - **GRDB and Room are both dropped.** A shared module cannot own two platform databases.
>   **SQLDelight** replaces them — compile-checked raw SQL, the mobile twin of `sqlc`, consistent
>   with the project-wide no-ORM rule. SQLCipher is retained on both sides.
> - **Connect-Swift and Connect-Kotlin are both dropped** for the shared client. `connect-kotlin`
>   is JVM/Android-only (OkHttp-based) and does not build for Kotlin/Native. Provisional
>   replacement is **Wire + Ktor**.
> - What survives unchanged: native UI on both platforms, Compose Multiplatform explicitly
>   rejected for iOS, `android.bluetooth.le` direct with no Nordic wrapper, and §4.1's argument
>   that BLE cannot be meaningfully abstracted — that argument is load-bearing *in* ADR-007.
> - **KMP does not reduce the BLE work.** The radio is still written twice behind
>   `expect`/`actual`. Anyone planning from §4.1/§4.2 should not assume otherwise.
> - **A Mac becomes mandatory infrastructure.** Kotlin/Native iOS targets compile only on macOS.
>
> **[ADR-008](adr/ADR-008-account-key-server-issued.md) — `account_key` is server-issued**,
> confirming §6.2. The consequence §6.2 does not state: **the operator can derive every
> ephemeral ID a user will ever broadcast, retroactively.** Third-party observers learn nothing
> — invariants 4 and 5 are untouched — but *we* entered the threat model, and seven mitigations
> (M1-M7) are release conditions rather than hardening. The privacy policy is constrained by it.
>
> **[ADR-009](adr/ADR-009-interim-github-actions-ci.md) — GitHub Actions is interim CI**, Gitea
> remains the destination. Deviation from §10's self-hosting stance, deliberately reversible,
> with a hard line: the day CI needs a signing key, a registry credential, or prod access, it
> moves to Gitea first.
>
> **Current state, so the phasing below reads correctly:** Phase 0 is code-complete and
> unmeasured. No radio has run. `docs/PHASE0_GO_NO_GO.md` holds thresholds committed before any
> data existed; its verdict section reads NOT YET REACHED.

### 4.1 Mobile — iOS

| Layer | Choice | Why |
|---|---|---|
| Language | **Swift 6**, strict concurrency | Data-race safety matters enormously in a BLE app with concurrent radio callbacks |
| UI | **SwiftUI**, UIKit where needed | The design system is heavily componentised; SwiftUI maps to it cleanly. Fall back to UIKit for the Radar canvas. |
| Radar canvas | **Metal** or Core Animation | The sweep animates continuously; SwiftUI's renderer will drain battery. Use CAShapeLayer + CADisplayLink, escalate to Metal only if profiling demands it. |
| Bluetooth | **CoreBluetooth** | The only option. Both central and peripheral roles. |
| Local DB | **GRDB** (SQLite) | Full SQL control, encryption via SQLCipher, mature. SwiftData is too young for an offline-first message store. |
| Networking | **URLSession** + Connect-Swift | Generated clients from protobuf. No Alamofire. |
| Crypto | **CryptoKit** + **vodozemac** | See §4.11 and ADR-004 — vodozemac is Apache-2.0, unlike libsignal's AGPL. |
| DI | Manual composition root | No framework. 15 people can hold this in their heads. |
| Build | **Tuist** | Keeps the project file out of merge-conflict hell. |
| Testing | XCTest, Swift Testing, **XCUITest** | Plus a hardware test rig — see §12.3. |
| Min version | **iOS 16** | ~97% coverage; gives us modern CoreBluetooth behaviour and SwiftUI maturity. |

### 4.2 Mobile — Android

| Layer | Choice | Why |
|---|---|---|
| Language | **Kotlin 2.x**, coroutines + Flow | Standard |
| UI | **Jetpack Compose** + Material3 (heavily themed) | Our design system overrides Material almost entirely; treat M3 as a layout engine, not a look. |
| Bluetooth | **android.bluetooth.le** direct | Nordic's BLE library is tempting but adds a dependency to our most critical path. Write it ourselves against the platform API. |
| Local DB | **Room** + SQLCipher | Standard, well-understood |
| Networking | **OkHttp** + Connect-Kotlin | Generated from the same protobuf |
| Crypto | **Tink** + **vodozemac** (JNI) | Same E2EE core as iOS |
| DI | **Hilt** | Standard for a team this size |
| Background | **WorkManager** + a **foreground service** for Radar | Android requires a visible notification for sustained BLE scanning. Design that notification well — it's a UI surface. |
| Min version | **Android 10 (API 29)** | BLE behaviour below this is a maintenance tax we shouldn't pay |
| Testing | JUnit5, Turbine, **Compose UI tests**, Robolectric | |

### 4.3 Shared mobile layer

There is no shared *code* — that was the trade-off of going native. Instead we share three artefacts, and this is what keeps the two platforms honest:

1. **`mobile/protocol/`** — the BLE wire format in protobuf, plus a written state-machine spec, plus **conformance test vectors** (hex payloads with expected parse results). Both platforms must pass the same vectors in CI.
2. **`backend/proto/`** — generated API clients for both platforms.
3. **`mobile/design-tokens/`** — colours, type, spacing exported from Figma via Style Dictionary into Swift and Kotlin sources. Designers change tokens, both apps update.

### 4.4 Backend

| Layer | Choice | Licence | Why |
|---|---|---|---|
| Language | **Go 1.23+** | BSD | Best concurrency-per-engineer ratio for realtime services. Single static binary — trivially deployable. Fast to hire for. |
| RPC | **Connect-RPC** + **buf** | Apache 2.0 | One protobuf definition yields gRPC, gRPC-Web, *and* plain JSON/HTTP. Mobile gets REST-ish simplicity; internal services get gRPC performance. |
| HTTP router | **chi** | MIT | For the few non-RPC endpoints (webhooks, health, media upload) |
| DB access | **sqlc** | MIT | Generates type-safe Go from raw SQL. No ORM. You can read the query that runs. |
| Migrations | **golang-migrate** | MIT | Forward-only, versioned |
| Validation | **protovalidate** | Apache 2.0 | Validation rules live in the proto, enforced identically everywhere |
| Auth | **In-house** JWT (EdDSA) + rotating refresh tokens | — | Auth is 400 lines and full of product-specific rules. Ory Kratos is excellent but is another service to operate. |
| Background jobs | **River** (Postgres-backed) | MPL 2.0 | Uses the database we already run. No extra infrastructure until we genuinely need it. |
| Config | Env vars + **koanf** | MIT | |

**Service decomposition.** Ten services (listed in §3). They are *modular*, not micro — deployed as separate binaries but from one repo, sharing `pkg/`. At our scale this gives independent scaling of the hot paths (gateway, proximity, messaging) without distributed-systems tax on the cold ones.

### 4.5 Data

| Concern | Choice | Licence | Notes |
|---|---|---|---|
| Primary DB | **PostgreSQL 16** | PostgreSQL | Everything relational. |
| Geo | **PostGIS** | GPL-2.0 | City-level geo only. **We never store precise coordinates** — see §8. |
| Vector similarity | **pgvector** | PostgreSQL | Profile and preference embeddings for matching. Avoids running a separate vector DB. |
| Cache / presence | **Valkey** | BSD-3 | The Linux Foundation's Redis fork. Genuinely open licence — Redis's own licence changed and is now a legal risk for us. |
| Event streaming | **NATS JetStream** | Apache 2.0 | An order of magnitude simpler to operate than Kafka, and we do not have Kafka-scale problems. Revisit at 1M DAU. |
| Search | **Postgres FTS** → **OpenSearch** | / Apache 2.0 | Start with Postgres full-text. It is genuinely adequate to ~100k profiles. Only add OpenSearch when it isn't. |
| Object storage | **SeaweedFS** | Apache 2.0 | S3-compatible, self-hosted. Preferred over MinIO, whose AGPL licence is a liability for a commercial product. |
| Analytics | **ClickHouse** (Phase 2) | Apache 2.0 | Event analytics. Not needed at MVP — Postgres will do. |

**Why not TencentDB / CKafka / Tencent Redis?** Because they cost money and lock us in. We run the same software ourselves on CVM instances. When scale justifies the operational relief, migrating to the managed equivalent is straightforward *because* we chose the compatible open-source original.

### 4.6 Realtime

| Concern | Choice | Notes |
|---|---|---|
| Transport | **WebSocket**, in-house Go gateway | Protobuf frames over a persistent socket. Falls back to long-poll on hostile networks. |
| Fan-out | **NATS** subject-per-user | The gateway subscribes on connect, publishes on send |
| Presence | **Valkey** with TTL keys | Presence is ephemeral by definition |
| Delivery guarantee | At-least-once with client-side dedupe by message ULID | Exactly-once is a myth; design the client to tolerate duplicates |
| Offline queue | Postgres outbox → NATS | Survives restarts |

We considered Centrifugo (MIT, excellent). We're writing our own because the messaging layer must interleave BLE-transported messages with internet-transported ones under one sequencing model, and that logic doesn't fit a generic pub/sub server.

### 4.7 Media pipeline

Upload → SeaweedFS (quarantine bucket) → **ffmpeg** / **libvips** normalise, strip **all EXIF including GPS** → moderation (§10) → promote to serving bucket → served via **Caddy** with signed, expiring URLs.

Self-hosted image CDN behaviour comes from Caddy + `cache-handler` in front of SeaweedFS. Add Tencent CDN only when bandwidth costs justify it.

### 4.7b Realtime calling (WebRTC)

Voice and video calling, self-hosted, with no third-party CPaaS.

| Concern | Choice | Licence | Why |
|---|---|---|---|
| Media transport | **WebRTC, peer-to-peer** | — | 1:1 calls need **no media server at all** — media flows directly phone to phone. This is the most important fact in the section: it makes calling essentially free to run. |
| Signalling | **Our own Go service** over the existing WebSocket gateway | — | SDP offer/answer and ICE candidates are small protobuf messages on a socket we already hold open. No new infrastructure. |
| STUN | **coturn** | BSD-3 | NAT discovery. Negligible load. |
| TURN relay | **coturn** | BSD-3 | Fallback when symmetric NAT blocks P2P — roughly 10–20% of calls in the wild, higher on mobile carrier networks. The only part of calling that costs real bandwidth. |
| Client | Native **WebRTC** framework (iOS) / **libwebrtc** (Android) | BSD | Mature first-party bindings on both platforms. No wrapper library. |
| Codecs | **Opus** audio, **H.264/VP8** video | — | Opus at 24–32 kbps is transparent for speech. H.264 gets hardware encoding on both platforms, which is a battery decision as much as a quality one. |
| Encryption | **DTLS-SRTP**, mandatory | — | Built into WebRTC. On a P2P call this is genuinely end-to-end — the media never touches our servers. |
| SFU (later) | **LiveKit**, self-hosted | Apache 2.0 | Only if we add group calls or events mode in Phase 4. Written in Go, fits the stack. **Do not deploy it for 1:1** — it adds cost and latency for nothing. |

**TURN bandwidth is the one cost line to watch.** A relayed video call at 800 kbps is roughly 0.7 GB of egress per call-hour in each direction, and relay means traffic passes through twice. Mitigate by defaulting to voice, capping video at 800 kbps, aggressively preferring P2P, and putting coturn on the edge node with a monitored egress budget and an alert. Model this before launch — a viral week of video calling on an unbudgeted TURN server is an expensive surprise.

**What the `calling` service actually does.** It never touches media. It authorises the call (are these two matched, has the recipient allowed calls, is either blocked), issues short-lived TURN credentials, relays signalling, and writes a ledger row: participants, start, end, transport, outcome. **No content, ever.** Calls are not recorded and the schema has no column that could hold a recording.

**Invited, not cold-rung.** A call request arrives as a notification and an in-thread card. The recipient's phone does not ring until they accept. This is a product decision with a technical consequence — the signalling flow has an explicit request/accept phase before any SDP exchange — and it is the main reason dating-app calling features usually fail: strangers cold-ringing strangers is frightening.

### 4.7c Radar Voice — push-to-talk over Bluetooth (Phase 4)

Users will ask why they can message over Bluetooth but not call. The honest answer belongs in the product.

BLE's practical application throughput is on the order of 100–300 kbps in real conditions, and duplex calling needs sustained low-latency bandwidth in both directions plus jitter buffering. **Full-duplex calling over BLE is not achievable at acceptable quality.** We will not fake it.

What *is* achievable is **short push-to-talk voice clips**. Opus at 8–12 kbps — or Codec2 at 3.2 kbps in extreme conditions — gives intelligible speech in a payload small enough to move over GATT in seconds. A 15-second clip at 8 kbps is about 15 KB.

Ship it as a walkie-talkie, label it honestly in the UI ("Sent over Bluetooth — lower quality by design"), and include the one-line explanation: *"Bluetooth carries about a thousandth of the data a phone call needs."* A limitation explained well becomes evidence that we understand the radio, which is on-brand.

### 4.8 ML & matching

| Concern | Approach |
|---|---|
| Matching (Phase 1) | Deterministic, explainable rules: intentions match, hard filters, activity recency, reciprocity likelihood, geographic feasibility. **Ship this first.** |
| Matching (Phase 3) | Two-tower embedding model, served with **ONNX Runtime** in Go, vectors in pgvector. Trained offline on interaction data. |
| Photo moderation | Self-hosted NSFW classifier (open weights) + **PDQ hashing** for known-bad content, human review queue for anything uncertain |
| Face verification | Self-hosted **InsightFace** embedding comparison between the selfie and profile photos. Never stored as a raw biometric — store only the comparison result and delete vectors after 30 days. Check jurisdictional biometric law before shipping. |
| Text moderation | Rules + a small self-hosted classifier for harassment and contact-info extraction |

**Do not build the ML matcher first.** A well-tuned rules engine beats a badly-trained model, and you have no training data on day one.

### 4.9 Web

| Property | Choice |
|---|---|
| Marketing site | **Next.js 15** (static export where possible) + Tailwind, self-hosted behind Caddy |
| Admin console | **React + Vite + TanStack Router/Query**, internal only, VPN-gated |
| Design tokens | Shared from `mobile/design-tokens` via Style Dictionary |
| Auth for admin | SSO against our own identity service + mandatory hardware-key 2FA for anyone with moderation powers |

The admin console is not a side project. Moderation throughput is a product constraint — build it properly in Phase 2.

### 4.10 Infrastructure & DevOps

| Concern | Choice | Licence |
|---|---|---|
| IaC | **OpenTofu** | MPL 2.0 (Terraform's licence is no longer open) |
| Config management | **Ansible** | GPL-3.0 |
| Containers | **Docker** / OCI | Apache 2.0 |
| Orchestration | **Docker Compose** (Phase 0) → **K3s** (Phase 1–2) → **TKE** (Phase 3+) | Apache 2.0 |
| Ingress / TLS | **Caddy** | Apache 2.0 — automatic HTTPS, zero config |
| Secrets | **SOPS** + age (Phase 0–1) → **OpenBao** (Phase 2+) | MPL 2.0 / MPL 2.0 |
| CI/CD | **Gitea + Gitea Actions**, self-hosted | MIT |
| Registry | Gitea's built-in OCI registry | MIT |
| Metrics | **Prometheus** + **Grafana** | Apache 2.0 / AGPL (self-host is fine) |
| Logs | **Loki** | AGPL |
| Traces | **Tempo** + OpenTelemetry SDKs | AGPL / Apache 2.0 |
| Error tracking | **GlitchTip** (Sentry-compatible, self-hosted) | MIT |
| Uptime | **Uptime Kuma** | MIT |
| Backups | `pgBackRest` → SeaweedFS → weekly off-provider copy | MIT |

### 4.11 Security

| Concern | Choice |
|---|---|
| Transport | TLS 1.3 everywhere, HSTS, cert pinning on mobile for the API domain |
| At rest | LUKS on data volumes, `pgcrypto` for the few PII columns that need it, SQLCipher on both mobile DBs |
| E2EE messaging | Double Ratchet via **vodozemac** (Apache 2.0). Keys never leave the device. |
| BLE identity | Rotating ephemeral IDs derived from a daily key — see §6.2 |
| Secrets in CI | SOPS-encrypted, decrypted only in the runner |
| Dependency scanning | `govulncheck`, `osv-scanner`, Dependabot equivalent in Gitea |
| SAST | `gosec`, `semgrep` (LGPL, self-hosted) |
| Pen test | External, before public launch. Budget for it. |

---

## 5. THE ARCHITECTURE, IN ONE PICTURE

```
   ┌────────────┐   ┌────────────┐            BLE mesh (no internet)
   │  iOS app   │◄──┤ Android app│◄══════════════════════════════════►
   │ Swift/CB   │   │ Kotlin/BLE │      rotating IDs · E2EE payloads
   └─────┬──────┘   └─────┬──────┘
         │◄─ WebRTC P2P media (DTLS-SRTP) · coturn relay only if NAT forces it ─►
         │  TLS 1.3 · protobuf over HTTP/WS
         ▼                ▼
   ┌──────────────────────────────────┐
   │  Caddy  (TLS, ingress, cache)    │
   └────────────┬─────────────────────┘
                ▼
   ┌──────────────────────────────────┐
   │  gateway  (auth, rate limit, WS) │
   └───┬────┬────┬────┬────┬────┬─────┘
       ▼    ▼    ▼    ▼    ▼    ▼
 identity profile discovery proximity messaging calling media safety billing
       └────┴────┴────┴────┬────┴────┴────┴────┴────┘
                           ▼
     ┌─────────────┬──────────────┬─────────────┬──────────────┐
     │ PostgreSQL  │   Valkey     │    NATS     │  SeaweedFS   │
     │ +PostGIS    │  presence    │  JetStream  │    media     │
     │ +pgvector   │  cache       │  events     │              │
     └─────────────┴──────────────┴─────────────┴──────────────┘

   observability: Prometheus · Grafana · Loki · Tempo · GlitchTip
```

---

## 6. THE PROXIMITY SYSTEM (the moat)

This is the section to read twice. Everything else is a solved problem.

### 6.1 Goals

- Two Radius users within roughly 100 m discover each other **without any server involvement**.
- Neither learns the other's location — only a coarse distance band.
- After a mutual wave, they can exchange **end-to-end encrypted messages with no internet at all**.
- An adversary passively sniffing Bluetooth learns nothing durable about anyone.

### 6.2 Identity on the radio

Modelled on the Apple/Google Exposure Notification design, which is the most-audited proximity privacy system ever deployed.

```
account_key            (server-issued, long-lived, never broadcast)
   │  HKDF
   ▼
daily_key              (rotates every 24h, shared with the server so it can
   │  HKDF(day, epoch)  resolve encounters for users who are online)
   ▼
ephemeral_id           (16 bytes, rotates every 15 minutes, THIS is broadcast)
```

- The broadcast payload contains **only** the ephemeral ID, a protocol version, and a calibrated TX power byte. No name, no photo, no user ID, no coordinates.
- BLE MAC addresses use **Resolvable Private Addresses**, rotating in step with the ephemeral ID. Rotating the payload but not the MAC would defeat the entire scheme.
- Resolution happens on-device against a cached set of daily keys for people you might plausibly meet (your matches, your likes, and — for Open mode — a bloom filter of active nearby-region users synced when last online).

### 6.3 Radio design

| Parameter | Value | Reason |
|---|---|---|
| Role | Both central and peripheral, alternating | Every device must both advertise and scan |
| Service UUID | One fixed 16-bit UUID | Required for iOS background scanning to work at all |
| Advertising interval | 250 ms foreground / 1000 ms background | Battery vs discovery latency |
| Scan mode | Balanced, 30% duty cycle | Aggressive scanning is a battery catastrophe |
| Payload | ≤ 26 bytes | Fits a legacy advertisement; no extended advertising, which older devices don't support |
| Connection | GATT, only after mutual wave | Discovery is connectionless. Connections cost battery and are observable. |
| MTU | Negotiate to 185+ | Message chunking below that |

### 6.4 Distance estimation — deliberately imprecise

RSSI to distance is noisy, non-linear, and defeated by a pocket or a body. **We lean into that.**

```
raw RSSI → Kalman filter (10-sample window)
        → normalise by advertised TX power
        → map to one of four bands, with hysteresis to stop flapping

  HERE     ≥ -55 dBm    "in this room"        (~0–2 m)
  CLOSE    ≥ -70 dBm    "very close"          (~2–10 m)
  AROUND   ≥ -82 dBm    "nearby"              (~10–30 m)
  EDGE     ≥ -95 dBm    "somewhere around"    (~30–100 m)
```

We never compute or display a metre figure derived from a single reading. The UI language ("about 8 m") is generated from the band's midpoint with jitter, deliberately, so that repeated readings cannot be triangulated. **Bearing is never computed and the node angle in the radar UI is randomised per session.** These are security controls, not UI choices — see the seven Radar invariants in the design spec.

### 6.5 The handshake

```
A discovers B's ephemeral ID
A resolves it locally → it's B, and B is someone A may see (mode check)
A taps Wave
   ├── online:  wave goes to the server, push to B, normal flow
   └── offline: A opens a GATT connection to B and writes an encrypted
                wave record signed with A's identity key
B's device verifies the signature, stores the wave
B waves back → both devices independently detect mutuality → HANDSHAKE
   → X3DH key agreement over GATT
   → Double Ratchet session established
   → messaging unlocked, entirely peer-to-peer
```

The mutuality check is symmetric and local. Neither device trusts the other's claim about the wave — each verifies a signature it can check with a key it already holds.

### 6.6 Offline messaging

| Property | Design |
|---|---|
| Encryption | Double Ratchet (vodozemac). Forward secrecy and post-compromise security. |
| Transport | GATT write with chunking, ~180-byte frames, ACKed |
| Ordering | Vector clock per session; the client merges BLE-delivered and server-delivered messages deterministically |
| Store & forward | Undeliverable messages queue on-device with a TTL |
| **Mesh relay** | **Phase 4, not MVP.** Multi-hop relay through intermediate devices is a substantial protocol and abuse-surface expansion. Ship single-hop first. |
| Sync | On reconnect, devices reconcile with the server; the server stores only ciphertext it cannot read |

### 6.7 The iOS problem — read this carefully

Apple restricts background BLE in ways that materially constrain the product. Being honest about this now saves a year.

**What works:**
- iOS ↔ iOS in the background: yes, *if* the scanning device explicitly filters for our service UUID. Backgrounded iOS moves the service UUID into a special "overflow" advertising area that only other iOS devices scanning for that exact UUID can see.
- iOS foreground ↔ anything: fully works.
- Android ↔ Android background: works, with a foreground service and its mandatory persistent notification.
- State restoration: iOS will relaunch our app into the background when a matching peripheral is seen, via `CBCentralManager` restoration.

**What does not work reliably:**
- **Android scanning for a backgrounded iOS device.** The overflow advertising area is an Apple-private format. This is the single biggest gap.
- Sustained aggressive scanning on iOS in the background — the OS throttles it hard.
- Any expectation of sub-10-second discovery latency in the background.

**Mitigations, in order of value:**
1. **Design the product around foreground discovery.** Radar is a thing you *open* when you're out. Frame it that way in the UI. The design spec already does this — the Radar tab is a destination, not a passive background process.
2. Use significant-location-change and region-monitoring wakeups to opportunistically run a short foreground-quality scan.
3. iOS↔iOS is the strong path; if the early market skews iOS (it will, given the audience), this is less damaging than it sounds.
4. Push notification wakeups when a *matched* user is known to be in the same city.
5. Set expectations in the UI honestly. Never claim continuous background discovery.

**Week-one spike must validate this before anything else is built.** See §11 Phase 0.

### 6.8 Battery

Target: **under 4% of battery per hour** with Radar actively scanning; **under 1% per day** when idle. Enforce with an automated battery test rig (§12.3) that fails CI on regression. Adaptive duty cycling: reduce scan frequency when the device is stationary (accelerometer), when battery is below 20%, and when no peers have been seen for ten minutes.

### 6.9 Abuse resistance

| Attack | Mitigation |
|---|---|
| Tracking someone over time | Ephemeral IDs + rotating MACs; no persistent identifier on air |
| Triangulation with multiple receivers | Banded distance, no bearing, jittered display values, randomised UI angles |
| Fake presence / spoofing | Waves are signed with an identity key bound to a verified account |
| Sybil / mass scanning | Server-issued daily keys are rate-limited per verified account; verification is mandatory to appear on Radar |
| Stalking a specific person | Blocks are enforced at key-resolution level — a blocked user's ephemeral IDs are never resolvable by the blocker's device |
| Home/work inference | User-declared blackout zones (design spec C7), enforced client-side by suppressing advertising entirely |
| Replay | Ephemeral IDs carry an epoch; stale IDs are rejected |

---

## 7. DATA MODEL (core tables)

Abbreviated — the full schema lives in `backend/migrations/`.

```
accounts          id, phone_hash, email_hash, created_at, status, deleted_at
profiles          account_id, display_name, dob, gender, pronouns, intention,
                  bio_fields jsonb, city_geohash(5), search_vector, embedding vector(256)
photos            id, account_id, position, storage_key, moderation_state, phash
prompts           id, account_id, prompt_id, answer, position
verifications     account_id, type, state, reviewed_at, reviewer_id
preferences       account_id, age_min, age_max, distance_km, intentions[], advanced jsonb
likes             id, from_id, to_id, target_kind, target_id, comment, created_at
matches           id, a_id, b_id, origin(discover|radar), created_at, ended_at, ended_by
threads           id, match_id, last_message_at, transport_hint
messages          id(ulid), thread_id, sender_id, ciphertext, transport(net|ble),
                  vector_clock, sent_at, delivered_at, read_at
proximity_keys    account_id, day, daily_key_enc, issued_at
encounters        id, account_id, peer_account_id, band, first_seen, last_seen, venue_hint
waves             id, from_id, to_id, transport, signature, created_at
blocks            blocker_id, blocked_id, created_at
reports           id, reporter_id, subject_id, reason, detail, state, resolution
calls             id, thread_id, caller_id, callee_id, kind(voice|video),
                  state, requested_at, started_at, ended_at, end_reason,
                  transport(p2p|relay), quality_score
                  -- no content column exists. calls are never recorded.
call_prefs        account_id, allow_from(matches|after_20_msgs|nobody)
subscriptions     account_id, tier, store, receipt, expires_at, state
entitlements      account_id, feature, value, source, expires_at
```

**Deliberate absences.** There is no `locations` table. There is no `last_seen_lat_lng`. There is no call-recording column, and there never will be — a schema that *cannot* store a recording is a far stronger guarantee than a policy saying we don't. City is stored as a **geohash truncated to 5 characters** (~5 km precision) and nothing finer ever touches the database. `encounters` records *that* two accounts were near each other and in which band — never where.

---

## 8. PRIVACY ARCHITECTURE

The rule: **we cannot leak what we never collect.**

| Data | Policy |
|---|---|
| Precise GPS | Never stored server-side. Used on-device only, for city resolution, then discarded. |
| BLE encounters | Band + timestamp + peer account. No coordinates. Retained 30 days (24 h on free tier), then hard-deleted. |
| Message content | E2EE. The server stores ciphertext it structurally cannot decrypt. |
| Photos | EXIF stripped on ingest, including GPS, before the file is ever written to durable storage. |
| Biometrics | Face-match vectors held in memory for the comparison, persisted at most 30 days for appeal purposes, then destroyed. Only the boolean result is durable. |
| Phone / email | Stored as a peppered hash for lookup; the plaintext is encrypted with `pgcrypto` and readable only by the identity service. |
| Call media | Never touches our servers on a P2P call. On a TURN-relayed call it passes through encrypted and is never written to disk. No call is ever recorded. |
| Call metadata | Participants, timestamps, duration, transport. Retained 90 days for abuse investigation, then deleted. |
| Deleted accounts | 30-day soft-delete grace, then a genuine hard delete across all stores and backups within the following backup cycle. Documented and auditable. |

Build a **data-subject-request pipeline** (export and delete) in Phase 2, not Phase 4. Retrofitting it is miserable, and GDPR/PH Data Privacy Act obligations attach the moment you have users.

---

## 9. TRUST & SAFETY SYSTEMS

Non-negotiable for a proximity dating product.

1. **Mandatory verification** before appearing in Discover or on Radar. Selfie pose match against profile photos.
2. **Automated photo moderation** on every upload, before the photo is ever visible.
3. **Human review queue** in the admin console with SLA tracking. Budget for real moderators — this is a headcount line, not a tooling line.
4. **Block enforcement at the protocol layer**, including key resolution, so a blocked person cannot even detect you on Radar.
5. **Report flow** with categorised reasons, evidence capture, and an appeal path.
6. **Behavioural signals**: mass-liking, rapid unmatch-after-match, report clustering, device fingerprint reuse after a ban.
7. **Calling safety.** Numbers are never exchanged — all calls route through Radius signalling, and there is no code path that reveals a phone number to another user. Calls are invited, never cold-rung. Calls are never recorded, which is both a trust position and a wiretap-law necessity given two-party-consent jurisdictions. An in-call safety control is one tap from every active call, offering report, block-and-end, and background blur. Because media is end-to-end encrypted we cannot inspect call content, so abuse handling is **report-driven**: a report captures call metadata, the reporter's account of what happened, and — with the reporter's explicit consent — any evidence their own device holds. Repeat call reports are a strong ban signal and should be weighted heavily in the behavioural model.
8. **Incident runbook** for the worst case — a real-world safety event traced to the app. Legal, comms, and law-enforcement-liaison process written *before* it happens.

---

## 10. INFRASTRUCTURE ON TENCENT CLOUD

### 10.1 Region choice — decide this first

**Use Singapore. Not a mainland China region.** Hosting in a mainland region triggers **ICP filing** obligations, which for a foreign-operated consumer app is a months-long process requiring a Chinese entity, and mainland regions sit behind the Great Firewall with all the latency and reachability consequences that implies for a global product. Your users are in Manila; **Singapore** is the right latency choice at roughly 30–50 ms. Hong Kong is the only acceptable fallback if Singapore capacity is unavailable; it is also outside the mainland filing regime.

Verify current requirements with Tencent before committing — regulations shift.

### 10.2 Phase 0–1 topology (target cost: near zero)

Everything on **two or three CVM instances** in one Singapore VPC:

```
cvm-edge     2 vCPU / 4 GB    Caddy, gateway, WS termination     public IP
cvm-core     4 vCPU / 8 GB    all Go services (Docker Compose)   private
cvm-data     4 vCPU / 16 GB   Postgres, Valkey, NATS, SeaweedFS  private
```

- Private subnet for everything but the edge. Security groups deny by default.
- Bastion via SSH certificate authority, no long-lived keys, no public DB ports ever.
- TLS terminated at Caddy with automatic Let's Encrypt.
- Nightly `pgBackRest` full + continuous WAL archiving to SeaweedFS, with a **weekly encrypted copy off Tencent entirely** — never keep your only backup at your only provider.
- Tencent's free tier and new-user credits cover meaningful early usage; **check the current offer, as terms change**. Plan to be paying a modest monthly amount by the time you have real users, and treat "free" as a runway extension, not a permanent state.

### 10.3 Scale path

| Stage | Trigger | Change |
|---|---|---|
| Phase 1 | Internal alpha | Add a second edge node, K3s across the core nodes |
| Phase 2 | Closed beta, ~200–1k users | Postgres streaming replica; Valkey Sentinel; separate NATS node; CDN in front of media |
| Phase 3 | Launch, ~10k users | Migrate to TKE; consider TencentDB for Postgres to shed operational load |
| Phase 4 | ~100k users, multi-region | Second region, geo-DNS, region-local media, single-writer Postgres with regional read replicas |

### 10.4 Environments

`local` (Compose) · `dev` (shared, auto-deployed from main) · `staging` (production-shaped, seeded with synthetic data) · `production`.

Every environment is defined in OpenTofu. No environment is ever configured by hand.

---

## 11. DELIVERY PLAN

### Phase 0 — Validate the moat (Weeks 1–3)

**Nothing else starts until this concludes.** If BLE discovery doesn't work acceptably on real hardware, the entire product thesis changes and you need to know in three weeks, not nine months.

- Two throwaway apps — one Swift, one Kotlin — that do nothing but advertise and scan.
- Test matrix: iOS↔iOS, Android↔Android, iOS↔Android, each in foreground/background/screen-locked, at 1 m / 5 m / 20 m / 50 m / 100 m, indoors and outdoors, in-pocket and in-hand.
- Measure: discovery latency, RSSI stability and band accuracy, battery drain per hour.
- **Deliverable: a written go/no-go memo with the real numbers**, plus the calibration constants for §6.4.

### Phase 1 — Vertical slice (Weeks 4–14)

One thin path all the way through: sign up → verify → see a profile → open Radar → discover a peer → wave → handshake → send an offline message. Ugly is fine. It must be real.

- Backend: identity, profile, proximity, messaging (minimum viable), gateway
- Postgres schema, protobuf contracts locked
- Both apps: onboarding, Radar, one chat screen
- BLE protocol v1 implemented on both platforms and passing the shared conformance vectors
- Compose-based infrastructure, CI green, staging deploying automatically

**Exit criterion:** two engineers on opposite platforms, in a café, with no wifi, complete a handshake and exchange messages.

### Phase 2 — Product completeness (Weeks 15–28)

- Full design system implemented on both platforms
- Discover feed, prompts, likes-with-comment, filters, matches
- Full messaging: media, voice notes, date planning
- **Voice and video calling** end-to-end: signalling service, coturn deployed, request/accept flow, in-call safety, call ledger. Voice ships before video.
- Verification pipeline end-to-end, photo moderation, admin console
- Safety: report, block, appeal, moderation queue with SLAs
- Billing: IAP/Play Billing, receipt validation, entitlement service
- Data-subject request pipeline
- Observability complete; on-call rotation and runbooks exist

**Exit criterion:** a closed beta of 200 real users in one Manila district, running for four weeks without a P1 incident.

### Phase 3 — Harden and launch (Weeks 29–38)

- External penetration test and remediation
- Load test to 10× projected launch traffic
- Battery and performance regression gates in CI
- Store submission, age rating, privacy nutrition labels
- Geographically concentrated launch — **one city, then one more**. Proximity products die of thin density; do not launch nationally.
- Growth instrumentation and a real analytics pipeline

### Phase 4 — Extend (Post-launch)

Multi-hop mesh relay · **Radar Voice push-to-talk over BLE** · **group calls / events mode (LiveKit SFU)** · venue partnerships · events mode · travel · web app · second region.
(ML matching lands in Phase 3 alongside launch hardening — see §4.8 — not here.)

---

## 12. TEAM & WAYS OF WORKING

### 12.1 Shape of a 12-person team

| Role | Count | Notes |
|---|---|---|
| Engineering lead / architect | 1 | Owns the protobuf contracts and the BLE spec. This is the highest-leverage seat. |
| iOS engineers | 2 | One must be a genuine CoreBluetooth specialist. Hire this person first — they are rare. |
| Android engineers | 2 | Same; one BLE-deep. |
| Backend engineers | 3 | Go. One owns messaging/realtime **and calling signalling**, one owns discovery/matching, one owns identity/billing. |
| Infra / SRE | 1 | Owns `devops/`, on-call, cost. |
| Data / ML | 1 | Matching, analytics, moderation models. Joins in Phase 2. |
| QA / release | 1 | Including the physical BLE test rig. |
| Product designer | 1 | Owns the Radius design system. |

Plus, outside engineering: a product manager, a trust-and-safety lead, and moderators (part-time initially, scaling with users).

**Hiring order:** BLE-specialist iOS → BLE-specialist Android → backend lead → SRE → the rest.

**A note on WebRTC skills.** Do not hire a dedicated WebRTC engineer. For 1:1 calling the platform frameworks do the heavy lifting, and the work is a few weeks of signalling plus coturn operations. If you find yourself needing a WebRTC specialist for 1:1 calls, something has been over-engineered.

### 12.2 Process

- Two-week iterations. Demo on real hardware, never on a simulator — a BLE demo in a simulator is meaningless.
- Trunk-based development with short-lived branches. Feature flags over long-lived branches.
- Every PR: CI green, one review, and for anything touching `proto/` or `mobile/protocol/`, a second review from the architect.
- ADRs for every irreversible decision, in `docs/adr/`.
- Weekly "radio day": the whole mobile team tests BLE in the real world. Bugs found on a desk are not the bugs users hit.

### 12.3 The hardware test rig

Build this in Phase 1. It pays for itself within a month.

A shelf with six devices — two iPhones (one old, one current), two Androids (one Samsung, one Pixel), and two spares — at fixed measured distances, wired to a controller that flashes builds, runs a scripted discovery scenario, and records discovery latency, RSSI, and battery drain. Results published to Grafana. **CI fails on battery or latency regression.** Without this, BLE quality silently rots.

---

## 13. COST MODEL

| Phase | Infrastructure | Other | Monthly total |
|---|---|---|---|
| Phase 0–1 | Tencent free tier + credits | Apple $99/yr, Google $25 once | ~$0–50 |
| Phase 2 (beta, 200 users) | 3 CVMs, modest storage/egress | Domain, email | ~$80–200 |
| Phase 3 (launch, 10k users) | 5–6 CVMs, CDN, backups, **coturn egress** | SMS OTP begins to matter, pen test one-off ~$8–15k | ~$400–900 + TURN |
| Phase 4 (100k users) | TKE, replicas, real egress | Moderation headcount dominates | ~$3–6k |

**TURN egress, modelled separately.** Assume 20% of calls relay, an average call of 6 minutes, video at 800 kbps, and doubled traffic through the relay. That is roughly 0.7 GB per relayed video call-hour each way. At 10k users making 2 calls a week with a 30% video mix, expect on the order of 1–3 TB/month of relay egress — a few hundred dollars at Tencent rates. Budget it, alert on it, and default to voice.

The dominant cost past launch is still **not infrastructure — it is moderation headcount and the store's 15–30% cut**. Model the business on that, not on server spend.

---

## 14. RISK REGISTER

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | **iOS background BLE limits make Radar feel broken** | Critical | Phase 0 spike; design Radar as a foreground destination; honest UI copy |
| 2 | **Cold-start density — Radar is empty** | Critical | Launch one city, one neighbourhood at a time; venue partnerships; Discover carries the product until density arrives |
| 3 | **A real-world safety incident** | Critical | Verification, protocol-level blocking, blackout zones, incident runbook, insurance, legal counsel retained before launch |
| 4 | Battery drain drives uninstalls | High | Adaptive duty cycling; automated battery gate in CI; a visible battery setting users control |
| 5 | Two native codebases diverge | High | Contract-first protobuf; shared conformance vectors; weekly cross-platform parity review |
| 6 | Store rejection (dating category scrutiny) | High | Early TestFlight/internal-track submissions; 18+ rating; complete privacy labels; moderation evidence ready |
| 7 | Self-hosting burden exceeds a 1-person SRE | Medium | Everything containerised and OpenTofu-defined; a documented escape hatch to Tencent managed equivalents |
| 8 | Tencent regional/regulatory change | Medium | Never use provider-proprietary APIs; portable by construction |
| 9 | BLE-specialist hiring is slow | Medium | Start recruiting now; consider a specialist contractor for the Phase 0 spike |
| 10 | E2EE complicates moderation | Medium | Client-side reporting attaches decrypted evidence *with the reporter's consent*; design this in Phase 2, not later |
| 11 | **Calling abuse — exposure, harassment on video** | High | Invited-not-rung flow; one-tap in-call safety; instant end with no confirmation; camera-off and blur available before and during; report-driven enforcement with heavy weighting for repeat call reports |
| 12 | **TURN egress cost spike** | Medium | Monitored egress budget with alerting; voice default; 800 kbps video cap; aggressive P2P preference |
| 13 | **Regulatory action on subscription practices** | High | No dark patterns by construction (§15); two-tap cancellation; conspicuous renewal disclosure; refund rate tracked as a first-class metric. FTC enforces under ROSCA today and the EU Digital Fairness Act draft is expected late 2026 |

---

## 15. THE CONVERSION SYSTEM — A CTO'S POSITION

You asked for a psychology strategy that forces people to subscribe. Here is my position as the person who would have to build, operate, and defend it.

### 15.1 What I built instead, and why it converts better

I built an aggressive conversion system using well-established behavioural psychology — anchoring, reciprocity, loss aversion, the centre-stage effect, endowed progress, the peak-end rule, and ruthless friction removal. The full design specification is in §12 of the UI/UX document. What I did not build is deception: fake scarcity, fabricated likes, blurred faces implying attention that may not exist, obstructed cancellation, or deliberately degrading the free experience to manufacture desperation.

That is not squeamishness. It is three concrete arguments.

**The legal argument is live, not theoretical.** The FTC's Click-to-Cancel Rule was vacated by the Eighth Circuit in July 2025, but the FTC has continued enforcing subscription practices under ROSCA and Section 5 throughout, with recent settlements in the tens of millions, and it reopened negative-option rulemaking in March 2026. In the EU, the Digital Fairness Act — aimed explicitly at dark patterns, addictive design, and "hidden renewal clauses, difficult cancellations, automatic subscriptions" — is expected as a draft proposal in Q3/Q4 2026, with entry into force potentially in 2027. A product architected around those patterns today is a product that needs re-architecting inside eighteen months.

**The commercial argument is stronger than the legal one.** Dating apps already carry unusually high refund and chargeback rates. Manipulated subscriptions produce a specific, recognisable curve: elevated day-1 conversion, elevated day-30 refunds, chargebacks that endanger your payment standing, one-star reviews that name the tactic, and store review scrutiny that Apple and Google apply disproportionately to the dating category. You convert once and churn permanently. The metric that matters is not conversion rate — it is **retained subscriber months net of refunds**, and dark patterns are negative on that number.

**The brand argument is fatal for this particular product.** Radius sells restraint, honesty, and the absence of manipulation. That is the entire premium positioning — it is why someone pays for this rather than using Tinder free. A manipulative paywall doesn't merely risk a fine; it *contradicts the thing the customer is buying*. You cannot sell "we're not like the others" through a fake countdown timer.

### 15.2 Where the real conversion leverage actually is

Having watched this play out, the leverage is not where founders expect. Ranked by impact:

**First, timing.** The contextual upsell fired at the moment of demonstrated need — the user just ran out of comments, just hit the Radar range limit, just finished a call that went well — outperforms a generic paywall by a wide margin. This is the single biggest lever and it is entirely honest. The full trigger table is in §12.6.

**Second, friction.** From tapping an upsell to a completed purchase must be two taps. Every interstitial, confirmation, and form field between intent and payment is measurable lost revenue. Most teams underinvest here because it isn't clever.

**Third, the free tier being genuinely good.** The counter-intuitive one. A free user who has never had a real conversation, a real handshake, or a real call has no idea what they'd be buying more of. Generosity early is what makes the wall meaningful later.

**Fourth, the gift instead of the trial.** A no-card, nothing-to-cancel week of Gold (screen E7) has near-total uptake, creates genuine reciprocity, and converts on demonstrated value rather than on forgetfulness — which is precisely the mechanism regulators are targeting in card-required trials.

**Fifth, price architecture.** Annual-first anchoring, per-day framing, three visible tiers with a true "most chosen" middle. Legitimate, and worth several points of conversion.

### 15.3 What to instrument, and the honesty check

Track conversion by trigger and surface, gift-to-paid, **refund rate by tier and by acquisition trigger**, chargeback rate, day-30 and day-90 subscriber retention, cancellation reasons, involuntary churn from failed payments (often 20–40% of total churn, and fixed with a better billing alert rather than with psychology), and dismissal rate per trigger — a trigger above 90% dismissal is noise and should be removed.

Then the honesty check, which I would put on the founder dashboard: **conversation-started rate and reply rate for paying users versus free users.** If people who pay do not measurably do better, the product is not worth its price, and no amount of conversion psychology fixes that. That single ratio is the difference between a subscription business and a churn treadmill.

### 15.4 If you disagree

This is your company and your call. If you want any of the excluded patterns, tell me which one and I will give you the specific regulatory exposure, the likely refund impact, and the honest alternative that gets most of the conversion without the tail risk. What I would not do quietly is build them and let you discover the consequences at scale.

---

## 16. WHAT I'D DO IN THE FIRST TWO WEEKS

1. Set up the monorepo, the Gitea instance, and the three Tencent CVMs in Singapore.
2. Start recruiting the two BLE specialists today — they are the long pole.
3. Run the **Phase 0 BLE spike** with whoever you have. Nothing matters more.
4. Lock `backend/proto/` v0 and `mobile/protocol/` v0 so parallel work can start the moment the spike passes.
5. Write ADR-001 through ADR-004 (already seeded in `docs/adr/`).
6. Stand up CI with a hello-world build for all four folders, so the pipeline exists before there's anything to break.

---

*Sources on subscription regulation: [Gibson Dunn — FTC restarts negative-option rulemaking after Eighth Circuit vacatur](https://www.gibsondunn.com/ftc-restarts-negative-option-rulemaking-after-eighth-circuit-vacatur-enforcement-under-rosca-continues/), [Goodwin — From dark patterns to fair play: the EU Digital Fairness Act](https://www.goodwinlaw.com/en/insights/publications/2025/11/alerts-practices-antc-from-dark-patterns-to-fair-play), [European Parliament — Digital Fairness Act legislative train](https://www.europarl.europa.eu/legislative-train/theme-protecting-our-democracy-upholding-our-values/file-digital-fairness-act).*

*Sources consulted for cloud specifics: [Tencent Cloud Free Tier](https://www.tencentcloud.com/act/pro/FreeTier), [Tencent Cloud COS free tier documentation](https://www.tencentcloud.com/document/product/436/6240), [Tencent Cloud ICP registration support](https://www.tencentcloud.com/solutions/icp-registration-support), [AppInChina — Guide to Tencent Cloud](https://appinchina.co/a-guide-to-tencent-cloud-in-china/). Verify current pricing and regulatory terms directly with Tencent before committing.*
