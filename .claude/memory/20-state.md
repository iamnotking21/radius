# 20 · LIVE STATE
<!-- EVERY agent updates this at end of session. append to LOG, rewrite NOW block. -->
<!-- keep under 150 lines. prune LOG older than 30 entries into 30-decisions if durable. -->

## NOW
phase: 0 — BLE feasibility spike
week: 1
blocking-everything: spike go/no-go memo not written. ZERO hardware measurement exists.
mobile arch: KOTLIN MULTIPLATFORM shared core + native UI (ADR-007)
repo: git init'd, nothing committed. KMP tree + iOS tree + BLE spec all scaffolded, ALL UNVERIFIED.
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
