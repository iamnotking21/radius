# 40 · SHARED CONTRACTS (cross-agent interfaces)
<!-- ANY change here = orchestrator approval + ADR + notify every consumer agent -->
<!-- this file is the ONLY legitimate coupling point between agents -->

## rule
contract first. proto/spec merged BEFORE any implementation. no exceptions.
breaking change = new version field, never silent edit.

## surfaces
| id | file | owner | consumers |
|---|---|---|---|
| API | backend/proto/**.proto | backend-go | ios-swift, android-kotlin, web-next |
| CALL | backend/proto/calling/**.proto | calling-webrtc | ios-swift, android-kotlin |
| ENTITLEMENT | backend/proto/billing/**.proto | growth-conversion | ios-swift, android-kotlin, web-next |
| BLE | mobile/protocol/{SPEC,KEY_SCHEDULE,STATE_MACHINE,BANDING}.md + vectors/ | ble-protocol | ios-swift, android-kotlin, backend-go(proximity), qa-test, design-system(band copy) |
| BLE-IMPL | mobile/shared/protocol/ (single Kotlin impl of the above) | ble-protocol | ios-swift, android-kotlin |
| SHARED | mobile/shared public API (explicitApi strict) | android-kotlin | ios-swift, and every future mobile agent |
| TOKENS-BUILD | mobile/design-tokens/tokens.json + scripts/generate.mjs | design-system | android-kotlin (live), ios-swift (at un-deferral), web-next (later) |

## design-token BUILD CONTRACT — added 2026-08-05 (was prose in a LOG line; it is a contract)
`:android:preBuild` runs `mobile/design-tokens/scripts/generate.mjs`. Consequences, all real:
  1. **:android has a hard build-time dependency on a file owned by another agent.** A tokens.json
     edit changes the Android build output. That is the same coupling shape as proto, and it gets
     the same treatment.
  2. **:android requires NODE on PATH.** devops-tencent asserts it in 5 build stages via
     `require_node`; the 2 gate stages assert it for a DIFFERENT reason (their own JS), and the
     messages say which. `:shared` alone does NOT pull it.
  3. **A WCAG CONTRAST REGRESSION FAILS THE ANDROID BUILD.** 48 pairings, non-zero exit. This is
     a deliberate property of generating rather than vendoring: tokens.json becomes the only place
     a colour value exists, and accessibility stops being something anyone has to remember.
  4. Discriminator for triage — the generator prints `N pairings checked, M regression(s)` on
     success. If that line printed, node worked and contrast passed; you are looking at something
     else. A node failure and an accessibility regression surface from the SAME Gradle task and
     are completely different findings.
  5. `build/tokens.resolved.json` is the intended input for a future Swift/TS generator. Anything
     the Kotlin emitter hardcodes instead of reading from tokens.json will silently DIVERGE across
     platforms the day a second generator exists. (One such hardcode — the spacing ramp — was found
     by review and is being fixed; treat it as the class, not the instance.)
| TOKENS | mobile/design-tokens/tokens.json | design-system | ios-swift, android-kotlin, web-next |
| DB | backend/migrations/*.sql | backend-go | data-ml (READ ONLY — handoff for changes) |
| EVENTS | backend/proto/events/**.proto | backend-go | data-ml, backend-go(safety svc) |

## proto status
v0 — NOT LOCKED. no impl may depend on it yet.

## BLE wire v0.1 — SPEC WRITTEN 2026-08-04 (ble-protocol owns). NOT hardware-validated.
EXACTLY ONE ADVERTISING DEVICE PER ACCOUNT. every other device is SCAN_ONLY, FAIL CLOSED.
  why: two devices sharing account_key emit a BYTE-IDENTICAL eid from two places. that (a) turns a
  stationary 2nd device into a live-eid oracle at a KNOWN address — a stalking primitive requiring
  no tailing — and (b) permanently bridges both MACs, which is the §4.1 attack except structural
  rather than accidental. multi-device is REAL for account access (chat/discover/threads, N devices)
  and FALSE for Radar broadcast. unblocking broadcast needs a device discriminator in the derivation
  ⇒ salt bump to radius/ble/v1 + flag day + regenerate every crypto vector. cheap now, not later.
P1 (same status as F7 — PRE-REJECTED IN WRITING, not a trade):
  raw/filtered/hashed/TRUNCATED ephemeral_id must NEVER reach the server on ANY endpoint, incl
  diagnostics. Radius must never operate, fund, contract, or ingest from a BLE scanning network.
  why: ADR-008 gives the operator derivation power. derivation is only location data if we ALSO
  hold captures. "let clients upload the eids they saw so we can debug discovery" is the benign
  -sounding way this becomes mass surveillance. it is now forbidden by name.
frame = EXACTLY 19B: [ver:1=0x01][ephemeral_id:16][tx_power_cal:1 int8][flags:1]
  NO reserved bytes — reserved bytes are a covert channel. version byte = the only extension path.
  tx_power_cal: ALLOW-LIST of 7 values only {-75,-70,-65,-60,-55,-50,-45}. off-grid = reject.
  flags: bit0 CONNECTABLE only. bits1-7 reserved, MUST be 0, nonzero = REJECT (not ignore).
service uuid 0xFDA9 = PROVISIONAL, NOT SIG-ALLOCATED, MUST NOT SHIP. SIG Adopter + allocation
  needed, weeks lead time. GATT service/chars use self-allocated 128-bit, no SIG dependency.
TWO CARRIERS, same 19B frame in both (invariant 4 holds in both):
  A = SERVICE_DATA, passive, Android peripherals. 30 of 31 adv bytes used.
  B = GATT_PULL, connect + read beacon_payload. iOS peripherals — iOS CANNOT emit service or
      manufacturer data in ANY state (API limit, not a bg restriction). Confirmed independently
      by ios-swift AND android-kotlin. Frame does not degrade; it changes carrier.
  Carrier B makes the SCANNER TRANSMIT ⇒ scanner anonymity cost + O(peers) connections.
NO BONDING, NO PAIRING, EVER. a bond exchanges an IRK = permanent RPA-resolution key = voids
  invariant 5 completely. all GATT chars declared with NO security permissions. PR checklist item.
GATT: wave/handshake/session chars gated on verified mutual wave. chunk min(MTU-3,180). ACKed.
  MTU<185 = hard fail, no fallback to smaller chunks.
state machine: IDLE→SCAN→RESOLVE→WAVE_SENT→WAVE_RECV→HANDSHAKE→SESSION
  IDLE/SCAN are DEVICE-scoped; RESOLVE..SESSION are PER-PEER. model as two machines or
  the platforms will disagree. SESSION is durable and survives peer loss / BT off / reboot.
key schedule: HKDF-SHA256, salt="radius/ble/v0", info="daily-key"||u32be(day) and
  "ephemeral-id"||u32be(day)||u16be(epoch). epoch = 15min, UTC-aligned, 0..95.
  ROTATION IS SYNCHRONISED TO THE GLOBAL UTC BOUNDARY. do NOT add jitter — staggering
  shrinks the anonymity set from the whole population to one and makes every rotation linkable.
  account_key is SERVER-ISSUED (ADR-008) and ROTATABLE. device holds a KEY RING, not one key.
  active(ring, day, epoch) = entry with greatest effective_from ≤ (day,epoch).
  effective_from is an EPOCH INDEX ⇒ mid-epoch rotation is UNREPRESENTABLE by construction
  (a mid-epoch rotation IS a staggered rotation, forbidden by decision 27). exactly one active
  kid per epoch ⇒ NO GAP and NO DOUBLE IDENTITY, neither constructible.
  RING MUST BE EVALUATED PER EPOCH, NEVER CACHED ACROSS A BOUNDARY. resolving the active key
  once per table build yields exactly ONE wrong epoch per rotation per user and passes every
  test that does not straddle a seam. pinned by vectors.
  scheduled rotation lands on a UTC DAY boundary; emergency may land on any epoch boundary.
  RECOVERY RE-ISSUES A NEW KEY, never re-delivers the old one — M7 stays literal. account
  continuity does not need account_key continuity (matches/threads/entitlements key off account id).
  reinstall = rotation.
error codes: E_NO_ACTIVE_KEY · E_KEY_RING_NOT_MONOTONIC · E_SELF_EID
banding: Kalman Q=1.0 R=16.0, warmup 3, stable 10, outlier gate 20dB, 3 consecutive
  outliers = filter RE-INIT (not accept-one — that livelocks). hysteresis H_IN=3 K_IN=3,
  H_OUT=2 K_OUT=2. promote is deliberately harder than demote.
  displayed metres = f(session_salt, peer_id, band) ONLY — NEVER a function of RSSI.
  jitter added to a real value is defeated by averaging; ours contains no real value.
RAW/FILTERED/ADJUSTED RSSI MUST NOT cross the shared-module boundary, reach the server,
  or appear in logs/analytics/debug builds. RSSI IS LOCATION DATA (3 receivers ⇒ multilateration).
  server gets (account, band, epoch). nothing finer. no bearing, ever, anywhere.
conformance vectors: mobile/protocol/vectors/*.json + index.json manifest. 116 EXECUTABLE cases.
GATED 2026-08-04 — `EpochBoundaryListener` + `BleRadio.setEpochBoundaryListener`. both actuals
  implement it (iOS keys the ticker off listener-registration, holding no scan state). the radio
  ANNOUNCES the boundary; whoever holds the ring acts. the radio still never learns what a key is —
  deliberately not re-coupled to the ring in order to fix this.
CALLER OBLIGATION, GATED — `KeyRing.pruneSupersededAt(day, epoch)` MUST be driven by every platform
  from an epoch ticker whose predicate is EXACTLY (decision 77, corrects decision 60):
      !isShutdown && (advertising wanted || epoch listener registered)
  `desiredScan` IS NOT A TERM. scanning is a reason for neither of the ticker's two jobs, and
  leaving it in the predicate is what caused the defect — it invites the reader to believe
  scanning is WHY the ticker runs. one shared pure function `EpochTickerPolicy.wanted()` in
  commonMain, called by both actuals, so the platforms cannot drift on when an IRREVERSIBLE
  security obligation runs. `setEpochBoundaryListener` MUST re-evaluate it on both.
  NOT the advertising restart — that was the orchestrator's original instruction and it was WRONG.
  NOT scan state either — that was the orchestrator's SECOND wrong instruction (row 60), which
  Android implemented faithfully, so turning Radar off ended key destruction.
  frameForEpoch only runs on an advertiser; decision 35 makes everything else SCAN_ONLY; a
  scan-only device still holds a full ring. advertising-scoped pruning fixes the HIGH on one device
  per account and leaves it live on the entire rest of the fleet.
  THE ZEROIZATION FIX IS INERT WITHOUT THIS CALL.
  a platform that omits it retains every key it has ever held ⇒ one in-process compromise yields
  retroactive derivation across the device's entire rotation history, which is exactly the blast
  radius ADR-008 M4 was bought to bound. android-kotlin: required now. ios-swift: at un-deferral.
  ACCEPTED COST, pinned by vector and reconciled in KEY_SCHEDULE §8.5.2 vs §9.6: the accepted window
  is {e-1,e,e+1}, so for ONE epoch after a rotation the device cannot recognise its own previous-
  epoch eid and a reflected/relayed copy goes to resolution instead of being dropped. taken
  deliberately — §9.6 is defence-in-depth against a rare event; holding the old key 15 min longer
  means it is still present during every rotation, the one moment it is most worth not having.
  was declared 99 — the manifest was WRONG, not the vectors. no vector added/removed/edited.
  per-file counts had used different rules per file (some silently excluded property_assertions
  and invalid blocks; key_rotation's 34 matched no reading of its own file). "all 99 pass" was
  unverifiable BY CONSTRUCTION. found by executing them, which is the point.
  VectorManifestTest now RECOMPUTES counts from the files every CI run ⇒ cannot rot silently again.
  suite is PROVEN ABLE TO GO RED: two mutations injected into the real impl, both caught by the
  exact cases claiming to pin them (outlier-gate livelock; cached-kid, which reproduced the literal
  trap value key_rotation.json names by hand). a suite that cannot fail is decoration.
  +11 published crypto KATs (FIPS 180-4 incl padding boundaries + 1MB msg, RFC 4231, RFC 5869).
  ADR-008 regenerated ZERO of the original 65 — provenance is not an HKDF input, so the function
  is unchanged. regenerating them would have destroyed the only property that makes a vector file
  useful: that a diff means a BEHAVIOUR change. all 11 original KATs re-derived on a second
  independent impl (Node crypto.hkdfSync) and agree with OpenSSL.
  +34 NEW in key_rotation.json: key ring, rotation seam, self-eid detection.
  crypto values are REAL (OpenSSL 3.5.6, RFC5869-verified), not hand-written.
  BOTH platforms run same vectors in CI. divergence = build fail.
  VECTORS PROVE ARITHMETIC ONLY. they say nothing about on-air behaviour.

## mobile/shared API v0 — GATED 2026-08-04 (android-kotlin owns, orchestrator gated)
STATUS: frozen for Phase 0. ios-swift may now bind against it. changes = handoff, not edit.
`explicitApi()` strict ⇒ the public surface is exhaustive by construction, not by discipline.

ORCHESTRATOR RULINGS settling the ios-swift ↔ android-kotlin divergences:
R-A entry point = `RadiusCore.Companion.create(config, radio, scope)`. android-kotlin's shape.
  ios-swift's assumed `RadiusCoreFactory().create(config)` is REJECTED — it omits `radio`, which
  became mandatory the moment decision 21 made Swift inject the port. Update SharedBridge.
R-B ghost mode = `setVisibility(RadarVisibility)` typed enum, NOT `setGhostMode(Bool)`.
  enum extends without a signature break, and the token GHOST stays greppable so a reviewer can
  audit invariant 10 by search. boolean params at a call site read as `setGhostMode(true)` —
  unreadable at the exact place safety matters.
R-C bytes cross as `ByteArray` → Swift `KotlinByteArray`. ios-swift writes converters.
  hex/base64 String fallback is REJECTED even though it is easier. a String ephemeral_id is
  log-shaped: it survives interpolation, lands in crash reports, analytics, and debug console.
  ByteArray is hostile to accidental logging. that hostility is the feature. same reasoning as
  the RSSI rule above — make the privacy-critical value awkward to print.
R-D retain cycle `adapter→port→listener→adapter` straddles Kotlin/Native and Swift ARC and
  leaks the radio for process lifetime. CONTRACT: `shutdown()` MUST drop the listener, AND the
  Swift port MUST hold it weak. Both, not either. PR checklist item.
R-E Swift reaches domain facades ONLY through `RadiusCore` + `FlowAdapter`. facades are not
  exported to ObjC directly. keeps the boundary one file wide (SharedBridge.swift).

NO-RAW-FLOW RULE (R12 mitigation, binding):
  `Flow<T>` is fine on anything Kotlin/Android consumes. NOTHING Swift touches may expose a raw
  `Flow` — Kotlin `Flow` does not bridge to ObjC at all. every Swift-consumed stream needs a
  sibling `<name>Adapter(): FlowAdapter<T>` on RadiusCore.
  EXISTS: radarNodesAdapter(). MISSING: discover + threads adapters — ios-swift CANNOT bind
  those two until android-kotlin adds them. tracked, not forgotten.
  `suspend` is fine in the CONSUMED direction (exports as ObjC completion handler ⇒ Swift async).
  `suspend` is BANNED in the IMPLEMENTED direction — hence zero suspend on BleRadioPort.
  no default arguments anywhere on the exported surface: they do not export to ObjC.

SEAM (decision 21): commands PULL, events PUSH. value types only.
  `BleRadioPort`   — Swift conforms. attach/startAdvertising/stopAdvertising/startScan/stopScan/shutdown
  `BleRadioListener` — Kotlin implements, Swift calls. onSighting / onAvailabilityChanged
  `expect class BleRadio` actuals are DELIBERATELY ASYMMETRIC: Android ctor `(Context)` talks to
  android.bluetooth.le directly; iOS ctor `(BleRadioPort)` owns zero CoreBluetooth. Do not "fix".

SURFACE: core{RadiusConfig, RadiusCore(internal ctor), FlowAdapter, RadiusCancellable} ·
  ble{BleRadio, BleRadioPort, BleRadioListener, RawSighting, AdvertiseRequest, ScanRequest,
      DutyProfile, RadioAvailability, BleOutcome,
      AdvertisePayloadSource, AdvertiseRoleSource, AdvertiseStatus, AdvertiseState} ·
  DRIFT CORRECTED 2026-08-05 — those last four were public in commonMain and absent from this
  list. explicitApi() is on, so this list is meant to be exhaustive BY CONSTRUCTION; it was not.
  Gated retroactively rather than made internal: all four are load-bearing for decision 49
  (payload source called per epoch, so the radio cannot cache a frame across a seam) and for
  one-advertiser-per-account, and iOS will need them at un-deferral.
ANDROIDMAIN DIAGNOSTICS SURFACE — GATED 2026-08-05, with a deletion obligation attached:
  BleRadio.setDiagnosticsEnabled · androidDiagnostics · androidEvents · diagnosticsDropped ·
  setScanModeOverride · peripheralRoleSupported · multipleAdvertisementSupported ·
  AndroidSightingDiagnostic · AndroidRadioEvent
  These are public in :shared androidMain, therefore PRESENT IN THE RELEASE APK and callable
  from src/main — not contained by living next to a debug-only consumer, as their own KDoc
  claimed. Payload is peer MAC + unfiltered RSSI. code-reviewer flagged the overstatement.
  Containment is BY REVIEW, not structural. Compare the absent INTERNET permission, which is
  structural. OBLIGATION: these go `internal` + debug-only source set when the Phase 0 harness
  is deleted, and that is a release-gate item, not a checklist nicety.
  domain{AppMode, UlidString=String} · discover{DiscoverCandidate, DailyDiscoverSet,
  DiscoverDecision, DiscoverFeed} · radar{ProximityBand, RadarNode, WaveState, RadarVisibility,
  RadarRunState, Encounter, RadarController} · threads{Transport, Direction, ThreadSummary,
  ThreadItem, DeliveryState, CallOutcome, ThreadsInbox}
  `RadiusCancellable` is prefixed deliberately — bare `Cancellable` collides with Combine's.
  `UlidString` is a plain typealias: Kotlin value classes export badly to ObjC.

SPEC §8.2 DEVIATIONS — ALL THREE GATED AND APPROVED 2026-08-04:
  D1 ONE flat `ProtocolError` enum (13 codes), not DecodeError + KeyError. vectors assert
     "expect_error":"E_SHORT_FRAME" — a flat namespace, so a split enum forces the runner to guess
     which enum a code lives in. the test data is the contract; match it.
  D2 `ProtocolResult<T>` (Success/Failure), NOT exceptions. a malformed advertisement is the
     ORDINARY case on a hostile air interface, not an exceptional one — and an exception crossing
     Kotlin/Native is a CRASH, not a catchable throws. owed at iOS un-deferral: exports as
     ProtocolResult<AnyObject>.
  D3 `domain.radar.ProximityBand` is DELETED. `protocol.Band` is the single band type.
     ProximityBand lacked UNKNOWN and OUT_OF_RANGE which the pipeline needs. two band enums in one
     module is precisely the divergence ADR-007 exists to prevent — we do not get to reintroduce it
     inside the shared module itself. android-kotlin executes the deletion.

v0.1 ADDITION — GATED 2026-08-04. from SPEC.md §8.1, proposed by ble-protocol, APPROVED as-is:
  KeyRing · KeyRingEntry · AdvertiseRole · activeKid() · ephemeralId() · isOwnEphemeralId() · KeyError
  approved unchanged because two of its choices are structural, not stylistic, and I want them kept:
  - ephemeralId() takes (ring, day, epoch), NOT a pre-resolved key. that makes the per-epoch
    re-evaluation rule STRUCTURALLY UNSKIPPABLE rather than a comment someone can ignore. it is
    the API-shaped version of the one-wrong-epoch-per-rotation bug. do not "simplify" this.
  - AdvertiseRole is an enum defaulting to SCAN_ONLY. same reasoning as R-B, plus fail-closed:
    the dangerous state must be the one you have to ask for.
NOT IN v0.1, will ADD types when they land: persistence · crypto/ratchet · RPC client ·
  everything in mobile/shared/protocol/ (ble-protocol's, arrives with the codec).
CONSISTENCY CHECK owed at v1, THREE gaps, all real:
  1. RawSighting.payload must carry exactly the 19B frame the BLE section specifies.
  2. Carrier B (GATT_PULL) has no representation in the seam — the port assumes broadcast-only.
     DEFERRED with iOS, but it does not disappear; it returns unpriced the day iOS returns.
  3. the port has no representation for AdvertiseRole or the key ring either. under android-first
     this one is NOT deferred — one-advertiser-per-account is an Android-now rule.

## calling signalling v0 (draft — calling-webrtc owns)
phases: REQUEST → ACCEPT/DECLINE → SDP OFFER → SDP ANSWER → ICE → CONNECTED → ENDED
NO SDP exchanged before ACCEPT. recipient's phone does not ring before ACCEPT.
TURN creds: short-lived (≤5 min), issued per call, never reusable.
ledger row written on ENDED: participants, start, end, transport(p2p|relay), outcome.
NO CONTENT FIELD EXISTS in any calling message. do not add one.

## api conventions
ids = ULID (sortable, client-generatable)
time = RFC3339 UTC string on wire, timestamptz in db
money = minor units int + currency code. never float.
pagination = cursor, never offset
errors = Connect codes + machine `reason` + human `message`
