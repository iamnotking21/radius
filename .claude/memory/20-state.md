# 20 · LIVE STATE
<!-- EVERY agent updates this at end of session. append to LOG, rewrite NOW block. -->
<!-- keep under 150 lines. prune LOG older than 30 entries into 30-decisions if durable. -->

## NOW
phase: 0 — BLE feasibility spike
week: 1
blocking-everything: spike go/no-go memo not written. ZERO hardware measurement exists.
mobile arch: KOTLIN MULTIPLATFORM shared core + native UI (ADR-007)
repo: pushed to github.com/iamnotking21/radius (main @ 932c72f, 160 files).
  Android side BUILDS AND TESTS GREEN. iOS scaffolded only, never compiled (B4).
infra: not provisioned
contracts: proto v0 NOT locked · shared API v0 GATED · BLE spec v0 WRITTEN, 0/16 cells measured
hires: 0/2 BLE specialists

## PLATFORM PRIORITY (founder, 2026-08-04)
ANDROID FIRST. iOS DEFERRED — scaffold stays, no further iOS work until Android is real.
consequence: B4 (no Mac) drops from hard blocker to SCHEDULED. B8 is now the TOP technical risk —
Android is the whole product surface, so an OEM that cannot rotate its MAC is not a gap in
coverage, it is a hole in the product. measure B8 before anything else.

## BUILD STATUS — FIRST VERIFIED BUILD 2026-08-04
toolchain: Temurin JDK21 · Gradle wrapper 8.13 (committed) · AGP 8.9 · Kotlin 2.1.20 · SDK 35
  (gradle self-installed platform 35; no Android Studio step needed. no system gradle needed —
   wrapper was generated from the 8.13 dist already cached in ~/.gradle and copied in.)
VERIFIED GREEN, by a compiler, not by assertion:
  :shared:compileDebugKotlinAndroid · :android:assembleDebug (APK 49.5MB) ·
  :shared:testDebugUnitTest 9/9 pass · :android:lintDebug 0 errors / 50 warnings
UPDATED 2026-08-04 (android-kotlin, real radio + spike harness), all with --rerun-tasks:
  :shared:testDebugUnitTest 53/53 pass (26 android-kotlin + 23 ble-protocol vectors + 4 other)
  :android:assembleDebug 52.1MB · :android:assembleRelease 23.5MB (R8, lintVitalRelease green)
  :android:lintDebug 0 errors / 50 warnings (all pre-existing: GradleDependency, UnusedResources)
  SPIKE HARNESS PROVEN ABSENT FROM RELEASE: 69 Spike* classes in debug intermediates, 0 in
  release; 0 occurrences of "spike" in the release merged manifest.
  P1 PROVEN STRUCTURALLY: 0 uses-permission INTERNET in the MERGED manifest of BOTH variants.
  The spike build cannot open a socket, so `adb pull` is the only egress that exists.
  KSP+Hilt+SQLCipher+Compose all wire up. SQLDelight with zero .sq files = NO-SOURCE, not an error.
  host guard works: iOS targets correctly SKIPPED with a loud warning on Windows.
STILL UNVERIFIED, do not confuse with the above: everything iOS · every BLE behaviour ·
  battery · anything requiring a radio or a device. an APK that builds is not a feature that works.

## HARD BLOCKERS (detail in 60-blockers)
B5 CLOSED 2026-08-04 — JDK21 in, wrapper committed, builds green.
B8 RPA co-rotation uncontrollable + chipset-dependent ⇒ invariant 5 may be unsatisfiable per
   model. TOP RISK under android-first. needs a SNIFFER — unverifiable from inside the app.
B9 service UUID 0xFDA9 PROVISIONAL. 16-bit SIG allocation is MANDATORY for Carrier A (adv budget
   leaves no room for 128-bit). sequence: legal entity → SIG Adopter → UUID. CI gate blocks
   release builds meanwhile (decision 34).
B10 RESOLVED by ADR-008 — account_key is SERVER-ISSUED. mitigations M1-M7 are now requirements.
B6 NO Go toolchain — backend unbuildable. matters at P1, not now.
B4 NO MAC — deferred with iOS. not on the critical path any more.
B7 iOS cannot emit service/mfr data in ANY state. resolved in spec via Carrier B. deferred.

## DONE
- [x] ADR 001-006 · v1.1 calling + conversion · design system spec v1.1 · repo + agent roster
- [x] ADR-007 KMP. ADR-001 superseded in part. stack/repo-map/roster/contract-law all updated.
- [x] mobile/ios scaffold (Tuist, SwiftUI 3-tab, SharedBridge boundary) — UNVERIFIED
- [x] mobile/shared + mobile/android scaffold (KMP, Compose, FGS, real BLE perms) — UNVERIFIED
- [x] shared API v0 GATED into 40-contracts. rulings R-A..R-E. both client agents unblocked.
- [x] BLE wire spec v0 + 65 conformance vectors (mobile/protocol/) — arithmetic only, no radio
- [x] NEXT-6 item 4 — android-kotlin: REAL android.bluetooth.le radio (advertise Carrier A,
      scan, duty per profile, epoch-boundary advertising restart, adapter-off/permission/
      no-peripheral-role handling) + the Phase 0 SPIKE HARNESS. Compiles, tested, packaged.
      NOT ON A RADIO. Every BLE claim is still owed.
- [x] 40-contracts CONSISTENCY GAP 3 CLOSED. one-advertiser-per-account is now structural:
      radio starts at AdvertiseRole.SCAN_ONLY on both platforms with no ctor/config/flag that
      can start it otherwise; AdvertiseGuard is one shared pure function both actuals call, so
      the rule is testable without a device and cannot drift per platform. The key-ring half is
      closed by AdvertiseRequest carrying an AdvertisePayloadSource instead of a ByteArray —
      the radio holds no payload, so it cannot cache one across a rotation seam.
- [x] Figma file lvh2NvQKYn4byiBREyzaYL. 0:1 Foundations (14 frames) · 11:115 A·Onboarding
      (14 screens). 11:116-11:122 = 7 pages NOT enumerated.

## IN FLIGHT
- [ ] nothing. all three Phase 0 agents returned. next move is HUMAN (toolchain + hardware).

## NEXT 6 — ANDROID FIRST (ordered, do not reorder without orchestrator)
1. HUMAN: 2+ real Android devices across chipset vendors (incl a budget MediaTek) +
   3× nRF52840 sniffer dongles. NOTHING about BLE is answerable without them.
   [JDK step DONE 2026-08-04 — builds are green]
2. ble-protocol: update KEY_SCHEDULE.md for SERVER-ISSUED account_key (ADR-008) + regenerate
   affected vectors. CONTRACT CHANGE — gated. security-privacy reviews M1-M7 before impl.
3. ble-protocol: Kotlin codec in mobile/shared/protocol/ passing the 65 vectors (needs B5)
4. android-kotlin: Android actual radio + throwaway advertise/scan harness (needs B5)
5. qa-test: spike matrix, ANDROID ONLY for now. RPA co-rotation FIRST — the only item that
   cannot be designed around after the fact. then the Android cells of SPEC §5.0.
6. orchestrator: GO/NO-GO memo (Android scope)
DEFERRED, not cancelled: all iOS work · Mac procurement · Carrier B validation · B7

## OPEN QUESTIONS
- [RESOLVED ADR-008] account_key = SERVER-ISSUED. now an OBLIGATION, not a question:
  privacy policy + all privacy copy must be reviewed against "the operator CAN correlate
  broadcasts to an account". a law-enforcement response policy must exist BEFORE launch.
  M1-M7 in ADR-008 are release requirements. security-privacy must sign them off.
- legal entity / jurisdiction — now BLOCKS the SIG Adopter application (decision 34), which
  blocks the real service UUID, which blocks shipping. was a background question, now sequenced.
- [DEFERRED w/ iOS] Mac: buy hardware, or rent macOS CI?
- if RPA co-rotation fails on a chunk of Android OEMs: exclude those models, ship them
  scan-only (see but not seen), or don't ship? product+legal call.
- is "Radius quietly notices people near you all day" load-bearing for the business case?
  ble-protocol's read: it does NOT survive iOS. "Open Radar when you're out" does.
- Figma pages 11:116-11:122 — what are they? affects screen inventory + build order
- entity/jurisdiction for data controller? · SMS provider after email OTP? · moderator staffing

## LOG
<!-- pruned 2026-08-04 by orchestrator: durable content moved to 30-decisions rows 15-31 -->
2026-08-04 orchestrator · repo + memory + agents initialized. phase0 armed. v1.1 calling +
  conversion added (ADR-005/006), roster 14.
2026-08-04 orchestrator · ADR-007 KMP (founder direction). shared core Kotlin, UI stays native,
  SQLDelight replaces GRDB+Room, Compose-MP-iOS rejected. Mac now mandatory infra.
  KMP explicitly does NOT shrink BLE work — spike scope unchanged.
2026-08-04 ios-swift · mobile/ios scaffolded, UNVERIFIED (B4). SharedBridge = the ONLY file
  importing Kotlin. 14 shared-API assumptions listed in its README, now reconciled at the gate.
  RAISED B7 (iOS bg advertising).
2026-08-04 android-kotlin · :shared + :android scaffolded, UNVERIFIED (B5). CONFIRMED AND
  SHARPENED B7 independently (foreground too, not just bg). RAISED B8 (RPA). proposed shared
  API v0. Later applied rulings 21+22: iOS radio is now a Swift port, kapt→ksp.
2026-08-04 orchestrator · rulings 21,22 + shared API v0 GATED (R-A..R-E) into 40-contracts.
  held both client agents mid-flight per §6 — parallel-safe only while the API is frozen.
2026-08-04 ble-protocol · BLE wire spec v0 + 65 vectors. B7 resolved by CARRIER CHANGE, no
  invariant weakened. Ref impl caught 2 real bugs pre-platform (outlier-gate livelock; a warm-up
  margin that silently shifted every band threshold 3dB and stuck peers a band too far forever).
  Crypto vectors REAL (OpenSSL 3.5.6, RFC5869-verified) but prove ARITHMETIC ONLY — never quote a
  green vector run as spike validation. PHASE 0 CALL: expect pass, but offline moat is
  foreground-first on iOS + a named list of unsafe Android models. True no-go = RPA failing
  broadly; measure it FIRST.
2026-08-04 ble-protocol · ADR-008 + android-first absorbed. spec now v0.1 (KEY_SCHEDULE §2 issuance/
  storage/recovery, NEW §8 rotation+seam, NEW §9 multi-device, NEW §10 threat model w/ operator).
  NOT ONE BYTE ON AIR CHANGED — invariants 4+5 re-verified field by field (§10.1). ZERO vectors
  regenerated (derivation identical; provenance is not an input to HKDF) — reasoning recorded in
  vectors/index.json `unaffected_by_ADR_008`; all 11 old KATs re-derived on a 2nd independent impl
  and agree. +34 NEW vectors (key_rotation.json) for rules that did not exist before: key ring,
  seam, self-eid. total 99.
  *** MULTI-DEVICE IS THE BITE. two devices sharing account_key broadcast an IDENTICAL eid from two
  places. that permanently bridges their MACs (the §4.1 attack, structural not accidental) and turns
  a stationary 2nd device into a live-eid oracle at a KNOWN ADDRESS — a stalking primitive needing
  no tailing. v0 ANSWER: exactly ONE advertising device per account, others scan-only, fail closed.
  ADR-008 sold multi-device as a benefit: TRUE for account access (chat/discover/threads, N devices),
  FALSE for Radar broadcast. unblocking needs a device discriminator in the derivation = salt v1 =
  flag day = ALL vectors regenerate. AN ORDER OF MAGNITUDE CHEAPER BEFORE LAUNCH. founder call. ***
  M7 vs recovery tension resolved: recovery RE-ISSUES a new key, never re-delivers the old one.
  account continuity ≠ account_key continuity. reinstall = rotation. seam rule: rotation lands ONLY
  on an epoch boundary (mid-epoch = staggered = decision 27's forbidden case, unrepresentable by
  construction); exactly one active kid per (day,epoch) ⇒ no gap, no double identity; ±1 window
  legitimately spans 2 kids because it spans epochs.
  NEW PROHIBITION P1 (§10.3, same status as F7): never operate/ingest a BLE scanning network, never
  accept observed eids on ANY endpoint incl. diagnostics. the operator's derivation capability is
  only location data if we also hold captures. "let clients upload eids to debug discovery" is the
  benign way this becomes mass surveillance — pre-rejected in writing.
  B8 sharpened to executable: pass criterion is a BIJECTION eid<->AdvA over the capture, not "the
  address changes". PHASE-OFFSET is the trap — BLE default RPA timeout is 900s = our epoch, so same
  period + wrong phase is a PERMANENT bridge that a period-only test reports as a pass. hardware =
  3x nRF52840 (~£30), one per adv channel 37/38/39 — single-channel cannot prove ABSENCE of an
  overlapping packet. 1-packet pre-screen: address type. public/static-random = instant exclude.
  a per-model SLOWER result forces a GLOBAL epoch change (decision 27 forbids per-model periods) or
  exclusion — there is no per-model epoch. proposed go/no-go threshold: <80% of intended install
  base co-rotating = escalate. scan-only devices MUST be told so in the Radar UI.
  §5.0 rescoped: 4 Android cells UNMEASURED + first-class, 12 iOS cells DEFERRED (≠ unknown risk,
  ≠ validated). §11 odds PRESERVED UNREVISED with a scoping note — a forecast quietly edited after
  a scope change is worthless. NOTHING HERE IS HARDWARE-VALIDATED. no JDK, no Mac, no device, no
  sniffer was used. still true, still says so on every page.
2026-08-04 android-kotlin · REAL RADIO + SPIKE HARNESS. NEXT-6 item 4 done, item 5 unblocked.
  radio: Carrier A service data, UUID-filtered scan, DutyProfile duty, 15-min UTC epoch restart
  (KEY_SCHEDULE §4.2), adapter-off recovery that keeps user intent, scan-start throttle counted
  on our side (Android's 5-per-30s punishes with SILENCE, not an error).
  THREE CORRECTIONS to the scaffold, each a real bug: (a) FOREGROUND scan mode was
  SCAN_MODE_LOW_LATENCY = 100% duty = four times the contracted 30% and no path to <4%/hr; now
  BALANCED (25%). (b) advertising was gated on isMultipleAdvertisementSupported, which answers a
  different question and would have refused perfectly capable hardware; now the advertiser
  null-check. (c) scan filtered on service DATA, which OEMs are least likely to offload and which
  silently hides Carrier B peers; now the service UUID.
  CONTRACT AMENDMENTS NEEDING A GATE (ios-swift must be notified, iOS is DEFERRED so no code is
  broken today): AdvertiseRequest.payload:ByteArray -> payloadSource:AdvertisePayloadSource ·
  BleRadioPort.startAdvertising now takes resolved (frame, uuid, duty) not an AdvertiseRequest,
  so Swift never sees an epoch or a payload source · BleRadio gains advertiseRole/advertiseState/
  setAdvertiseRole · new BleOutcome reasons ROLE_SCAN_ONLY/NO_PAYLOAD_FOR_EPOCH/FRAME_LENGTH/
  THROTTLED. ProximityBand deleted per ruling D3; RadarNode.band is protocol.Band and
  displayedMetres is now nullable.
  HARNESS (mobile/android/src/debug/, absent from release): advertises a controllable payload,
  scans continuously, writes events.jsonl + sightings.csv + meta.json (with Build.FINGERPRINT,
  the §4.3.3 row key) to app-external storage for adb pull. Live unique-advertiser-address count
  and a LIVE §4.3.1 BIJECTION SCREEN. Counts its own dropped records and write failures, because
  the B8 question is an assertion about ABSENCE and an uncounted drop voids the conclusion.
  Separate FGS so screen-off and Doze conditions are capturable; deliberately NOT START_STICKY —
  an OEM killing it IS the measurement.
  STILL UNMEASURED AND UNMEASURABLE FROM A DESK: whether stop->start rotates the RPA on ANY
  chipset (B8), real duty/battery, OEM survival, discovery latency, tx_power_cal per model,
  ADAPTER_SETTLE_MS (1500ms is a guess). The on-device bijection screen finds failures cheaply;
  it CANNOT declare a pass — a phone misses packets and Android never exposes the TxAdd bit, so
  the catastrophic public-address case passes it. §5.3 still needs 3 sniffer dongles.
  FLAGGED: androidx.work injects WAKE_LOCK + ACCESS_NETWORK_STATE + RECEIVE_BOOT_COMPLETED into
  the merged manifest. Unused today. ACCESS_NETWORK_STATE on an app with no network permission
  reads oddly in store review; documented in the manifest, not silently accepted.
2026-08-04 android-kotlin · post-review fixes. all re-verified with --rerun-tasks:
  :shared:testDebugUnitTest 54/54 · :android:assembleDebug 52.1MB · :android:assembleRelease
  23.5MB · :android:lintDebug 0 errors / 50 warnings.
  FIX B (decision 28) LANDED: RawSighting.toString no longer prints rssiDbm. The test that
  asserted the OPPOSITE — "rssi is not sensitive and is useful in logs" — was INVERTED rather
  than deleted, so the history stays visible in the diff; a test holding a leak in place is worse
  than no test. Timestamp deliberately survives: with the payload redacted it degrades to "the
  radio saw something at time T", carrying no distance, direction or pseudonym, and it is what
  makes a log usable for the gap analysis these logs exist for. Verified by grep that no RSSI
  reaches any string interpolation or any Log.* call anywhere in shared/ or android/.
  WORKMANAGER REMOVED (not annotated). Declared, never called, and merging WAKE_LOCK +
  ACCESS_NETWORK_STATE + RECEIVE_BOOT_COMPLETED into the manifest for nothing. All three are now
  GONE from the merged manifest of BOTH variants, verified. Catalog pin kept with the reasoning
  attached at the place someone would look when re-adding; re-adding must come with
  tools:node="remove" for whatever we still do not want. Radar must never use WorkManager anyway
  (15-min floor + Doze cannot hold a scan).
  GENERAL LESSON, now written into the main manifest: A PERMISSION AUDIT MUST READ THE MERGED
  MANIFEST, NOT THE SOURCE ONE. A source-only review would have called our permission set clean
  while the shipped APK asked for a background-persistence signal and a network permission on an
  app with no network permission. Any CI permission gate must target
  build/intermediates/packaged_manifests/*/*/AndroidManifest.xml.
  TIMING CONSTANTS: ADVERTISE_RESTART_GAP_MS (250), ADAPTER_SETTLE_MS (1500),
  REGISTRATION_BACKOFF_MS (35000), CONSERVE_ON/OFF_MS are now under one banner block headed
  "UNMEASURED GUESSES ... a number somebody made up at a desk", with the rule that a constant
  LEAVES the block when it is measured — so the still-guessed set is visible at a glance and no
  one can read a value as tuned. BOUNDARY_SETTLE_MS is called out as NOT a guess in the same
  sense: fixed and identical on every device, so the population still rotates as one, and it is
  numerically equal on both platforms on purpose.
2026-08-04 android-kotlin · §8.5.2 KEY DESTRUCTION WIRED (ble-protocol handoff, security-privacy
  HIGH). :shared:testDebugUnitTest 63/63 · assembleDebug 51.1MB · assembleRelease 23.3MB ·
  lintDebug 0 errors. The library fix was inert without a caller; it now has one.
  SCOPE CORRECTION I MADE AND WANT REVIEWED: the handoff said drive prune from the epoch-boundary
  ADVERTISING restart. Tying it to advertising leaves the HIGH unfixed on most of the fleet —
  under decision 35 exactly ONE device per account advertises and every other is SCAN_ONLY, yet a
  scan-only device still holds a full ring (needs it for isOwnEphemeralId, and holds it against a
  role transfer). frameForEpoch is only called while advertising, so pruning from there would
  destroy nothing on the majority population. The epoch ticker is therefore DEVICE-scoped: it runs
  whenever the radio is scanning OR advertising, and it keeps running across adapter-off, because
  key destruction is not radio work and a phone with BT disabled has no business retaining keys.
  SHAPE: new `EpochBoundaryListener` (commonMain, fun interface) + `setEpochBoundaryListener` on
  BleRadio. The radio announces the boundary; whoever holds the ring acts. The radio still never
  learns what a key is. Prune runs FIRST, UNCONDITIONALLY, and OUTSIDE the lock — the irreversible
  security obligation must not sit behind an advertising restart that may be skipped (scan-only),
  fail (adapter off) or return early; and calling out to foreign code under the radio monitor is
  how a radio deadlocks. runCatching guards only against a listener violating MUST-NOT-THROW.
  ALSO FIXED while in there: revoking to SCAN_ONLY now clears desiredAdvertise. Previously the
  ticker would re-attempt an advertisement at every boundary and be refused by our own fail-closed
  role gate — a retry loop against our own control, filling the log with ROLE_SCAN_ONLY rejections
  that read as a bug.
  SEAM CONSEQUENCE ACCEPTED AND PINNED (EpochBoundaryPruneTest, 7 tests): after a prune the device
  can no longer recognise its own pre-seam eid for one epoch, so a reflected copy falls through to
  ordinary resolution. NO error, NO anomaly log, NO retry, NO widened window — handled as an
  ordinary non-resolving frame. Nothing depends on "a device does not carry its own account as a
  candidate", which is the point of §9.6. Also pinned: active key never destroyed, advertising
  still works after a prune, idempotent, clock-rewind and out-of-range no-op, single-entry ring
  never pruned (the spike's case and every never-rotated device — destroying that key would make
  them silently undiscoverable). selfEidDrops is now KNOWN-INCOMPLETE for one epoch per seam by
  construction; noted in-source so a zero is never read as proof nothing was reflected.
  OWED WHEN A LOGOUT/DELETE PATH EXISTS: KeyRing.destroyAllKeyMaterial() (decision 55, ADR-008
  M5). No auth path exists to call it from. Recorded in the EpochBoundaryListener KDoc, next to
  its twin, because that is where someone building that path will be looking.
2026-08-04 devops-tencent · CI GATES NOW ACTUALLY RUN. Six gates existed and passed their own
  self-tests; NONE executed on push, because devops/ci/workflows/android.yml sits in a directory no
  CI provider discovers. Decorative gates are worse than none — they invite "CI is green" from a
  founder update. FIXED, dual-target: .github/workflows/{ci,release-gates}.yml (interim, GitHub is
  where the code is) + .gitea/workflows/ same two (destination, dormant until B3). NEITHER FILE
  CONTAINS A COMMAND — both call devops/ci/runner/run-stage.sh <stage>, one stage table, so the
  providers cannot drift; check logic stays in qa-test's devops/ci/gates/, untouched, never
  reimplemented.
  ADR-003 TENSION, resolved not ignored: the ADR objects to lock-in + a vendor holding user data.
  A runner executing our own bash on our own source holds neither. No proprietary GitHub surface,
  no secret, `permissions: contents:read`, release APK is unsigned. THE LINE: the day CI needs a
  signing key / registry credential / prod access it moves to Gitea FIRST. Orchestrator: ADR
  proposed in devops/ci/runner/README.md §2, plus root CLAUDE.md REPO MAP has no owner for
  .github/ or .gitea/ (claimed as runner territory, needs a line to be real).
  BLOCKING SPLIT (the design, not a detail): 0xFDA9 gate + battery gate are RED BY DESIGN and are
  NON-BLOCKING on push/PR, BLOCKING on tag/release/**. A gate red on every build for months is a
  gate people learn to ignore, and it stops meaning anything the day it matters. They are NOT
  silenced: run-stage.sh ASSERTS the expected state (xfail) — still-failing prints the full reason
  into the run summary every build and passes; STARTED PASSING fails the stage and demands the
  tolerance be deleted, because a tolerance outliving its cause silently absorbs the next real
  violation. Cannot mask a broken gate script: gate-selftests is blocking on every path.
  release-gates.yml IS EXPECTED TO FAIL TODAY (B9/B11/battery). That is it working.
  VERIFIED LOCALLY, all 12 stages, real commands not assertions: 4 fast gates exit 0 · known-red
  exit 0 on push and exit 1 under RADIUS_CI_STRICT=1 · XPASS branch exit 1 · unit-test/lint/
  assembleDebug/assembleRelease green · conformance gate PASS, 116 vectors live · artifact scan
  PASS on android-release-unsigned.apk · Android-SDK preflight fails in 1s with the fix in the
  message · 4 YAML files parse. UNPROVEN UNTIL A REAL RUN: runner images, actions/* resolution,
  cache, path filters, job-summary rendering. No CI run has ever happened.
  RAISED B12 (HANDOFF-1) — conformance_gate.sh hardcodes ./gradlew.bat, so the GitHub android job
  is forced onto windows-latest (2x minutes) and the Linux/Gitea conformance stage cannot run.
  qa-test one-liner; run-stage.sh detects it and prints the reason instead of "no such file".
  COST: $0/mo. infra still not provisioned (B3).
2026-08-04 qa-test · B12 FIX SIDE CLOSED. `qa_gradle_wrapper()` added to devops/ci/gates/lib/
  common.sh — same 3-line uname-branch rule as run-stage.sh's gradle_wrapper() (pattern reused,
  their file untouched, per the repo's own "two ways of choosing a wrapper = two ways of counting
  vectors" lesson). conformance_gate.sh phase 2 now calls it instead of hardcoding ./gradlew.bat.
  release_uuid_gate.sh's one diagnostic error-message string (advice text, not an invocation) fixed
  the same way while in the file. VERIFIED ON WINDOWS (the only machine that can run it today):
  conformance_gate.sh end-to-end PASS, phase1 116/116 + phase2 :shared:testDebugUnitTest green,
  live count "116 executable cases across 7 files". Self-tests 45/45 (was 39; +6 new for
  qa_gradle_wrapper, all branches incl. the real-uname-on-this-machine case). LINUX PATH REASONED,
  NOT EXECUTED — cannot run Linux here. What's actually checked: the case pattern is byte-identical
  to run-stage.sh's own (already relied on as portable); mobile/gradlew is committed 100755, LF-only
  shebang `#!/bin/sh`; core.autocrlf=true means the git blob for every file touched this session is
  LF-only regardless of this machine's local checkout, confirmed by grep on `git show HEAD:<path>`.
  UNPROVEN: an actual Gradle build (AGP/SDK/toolchain resolution, first-time dependency download)
  succeeding end-to-end on a real Linux box — no way to execute that from here.
  devops/ci/workflows/android.yml (qa-test's, HANDOFF-2 from devops-tencent) REDUCED TO A POINTER —
  it never ran (wrong directory for either provider's discovery), real definitions now live in
  .github+.gitea/workflows + run-stage.sh + devops/ci/gates, and a stale-but-authoritative-looking
  workflow file is how someone edits the wrong thing later. History preserved in git, not deleted.
  HANDOFF TO devops-tencent: flip `runs-on: windows-latest` -> `ubuntu-latest` for the `android` job
  in .github/workflows/ci.yml — the qa-test half of B12 is done, this is the other half.
2026-08-04 devops-tencent · B12 FULLY CLOSED. Flipped every GitHub job to ubuntu-latest (ci.yml
  android; release-gates.yml release-uuid-gate + android). 1x billed minutes instead of 2x, before
  a single minute was ever spent, and .gitea/'s conformance stage is no longer knowingly broken.
  JUDGEMENT CALL, since the cautious-looking move was to stay on Windows until a run was observed:
  THAT FRAMING IS BACKWARDS. "Everything green was verified on Windows" is true of ONE DEV BOX with
  a local SDK, a local.properties and warm Gradle caches. GitHub's windows-latest is not that box —
  it has no local.properties either, and it is a far less-trodden path for Android CI than
  ubuntu-latest. The real choice was between TWO unproven hosted images, and ubuntu is the
  better-trodden one, at half the cost, on the same OS family as the Gitea runner everything
  migrates to. Staying on Windows buys a feeling of control, not control, and needs a second
  unobserved experiment later anyway. So: flipped, and NOT declared working.
  RESIDUAL RISK NAMED RATHER THAN HEDGED: no Android build has ever executed on Linux, by anyone,
  on any machine. First ubuntu run is a genuine experiment whose specific failure mode is a
  RUNNER-IMAGE problem getting written down as a CODE REGRESSION — after which the next person
  spends a day in Kotlin instead of 30s in runner config. Mitigations: run-stage.sh's preflight
  already fails in ~1s with an unmistakably runner-shaped message; NEW §5b "FIRST UBUNTU RUN —
  ATTRIBUTION" in devops/ci/runner/README.md maps every likely failure signature to runner-vs-code
  (SDK env, licence/platform-35 download, mode-bit, CRLF shebang, vs compile/lint/test/vector
  divergence which are OS-independent); and a genuine one-line bisect back to windows-latest —
  `shell: bash` was KEPT on every ubuntu step for exactly that reason, so the bisect stays one line.
  Deliberately did NOT pre-add an sdkmanager step: an untested provisioning step on an image that
  probably already has platform 35 just adds a new way for the first run to fail. The remedy is
  pre-written in the triage table instead of pre-applied.
  Tripwire in run-stage.sh's gate-conformance case SELF-RETIRED exactly as designed — it greps
  conformance_gate.sh for the literal, which is now gone. KEPT dormant so a reintroduction re-arms
  it, and deliberately NOT widened to lib/common.sh where the literal lives legitimately inside
  qa_gradle_wrapper(); widening it would make it fire forever on the correct implementation.
  RE-VERIFIED after the flip, all 12 stages on Windows: 4 fast gates + conformance (116 vectors) +
  unit-test/lint/assembleDebug exit 0 · known-red exit 0 on push, exit 1 under RADIUS_CI_STRICT=1 ·
  4 YAML files parse · gate self-tests now 45/45. HANDOFF-2 closed by qa-test (android.yml is a
  pointer). ADR-009 Accepted. STILL OPEN: HANDOFF-3 — root CLAUDE.md REPO MAP has no owner line for
  .github/ or .gitea/ (orchestrator). NOTHING HAS EVER RUN IN CI. Not once. "Done" for this item
  means one observed green run, and that has not happened.
2026-08-04 qa-test · B12 SELF-TEST BUG, FOUND BY THE FIRST REAL CI RUN, NOT BY ME. Run 2
  (ubuntu-latest fast-gates) failed gate-selftests. android job (conformance gate itself, incl. the
  live 116-vector count) was GREEN — the production fix in qa_gradle_wrapper() was correct from the
  start. The FAILURE WAS INSIDE THE FIX'S OWN TEST: test_gradle_wrapper.sh asserted that the REAL,
  un-overridden `uname` on "this machine" resolves to `./gradlew.bat` — true only on the Windows box
  that wrote it. On ubuntu-latest, real uname is Linux, qa_gradle_wrapper() correctly returned
  `./gradlew`, and the assertion demanding `.bat` failed BECAUSE THE CODE WAS WORKING. Textbook
  instance of the exact defect class B12 exists to remove (a host-OS hardcode), reintroduced one
  layer up, inside the regression test for removing a host-OS hardcode — and STRUCTURALLY
  UNCATCHABLE on a single-OS dev box: this file's own header said so in writing before CI ran
  ("this test cannot itself observe [Linux/Darwin uname] on a Windows machine"), and 45/45 green
  locally was never claimed as more than that. It took a second OS to see it. That is what CI is for.
  FIX: the real-uname assertion no longer restates which OS wrote the test. It now asserts a
  property true on ANY host — the real result is a recognised wrapper name (`./gradlew` or
  `./gradlew.bat`), that file actually exists under mobile/, and (only for `./gradlew`, since `.bat`
  is invoked via Windows' file association, not the unix x-bit) that it is executable. Caught and
  fixed a real bug in the process: `assert_true "..." [[ -x "$path" ]]` does not work — `[[` is a
  shell reserved word, not a command, and cannot be invoked through `"$@"`; switched to `[` (a real
  command). AUDITED the rest of devops/ci/tests/gates/ for the same blind spot (grepped for
  windows-latest/MINGW/MSYS_NT/.bat/.exe/cmd.exe/powershell and GNU-vs-BSD sed/stat divergences):
  none found. Self-tests now 46/46 locally (was 45; net +1 assertion from the fix, one check split
  into "recognised name" + "exists" + conditionally "executable"). Awaiting CI run 3 (queued on
  devops-tencent's runs-on flip) to confirm on the actual second OS rather than trusting local green
  again — that is the standing lesson of this whole exchange, not a one-off.
