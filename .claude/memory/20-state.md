# 20 · LIVE STATE
<!-- EVERY agent updates this at end of session. append to LOG, rewrite NOW block. -->
<!-- keep under 150 lines. prune LOG older than 30 entries into 30-decisions if durable. -->

## NOW
phase: 0 — BLE feasibility spike. CODE COMPLETE to the limit of this machine.
blocking-everything: ZERO hardware measurement exists. go/no-go memo has an empty results table.
mobile arch: KOTLIN MULTIPLATFORM shared core + native UI (ADR-007). ANDROID FIRST, iOS deferred.
repo: github.com/iamnotking21/radius · main · PUBLIC · CI green on ubuntu-latest
infra: not provisioned. backend/ and website/ contain one memory file each. ~4% of v1.
contracts: proto v0 NOT locked · shared API v0.1 GATED · BLE wire v0.1 written, 0/16 cells measured
hires: 0/2 BLE specialists

## VERIFIED — by a compiler or the GitHub API, not by assertion
build: Temurin JDK21 · wrapper 8.13 · AGP 8.9 · Kotlin 2.1.20 · SDK 35
  :shared + :android compile · assembleDebug + assembleRelease (R8) · lint 0 errors
  63 unit tests · 116 conformance vectors executing · 80 gate self-test assertions
CI: run 4 fully green, all 3 jobs, ubuntu-latest. Android toolchain proven on Linux.
  CI has already caught one real bug local testing structurally could not (a Windows-only
  assertion inside the fix for a Windows-only hardcode).
gates (8): conformance · invariant-1 map/bearing (now incl backend+website) · internal-escape ·
  THE LINE · RSSI egress · merged-manifest permissions · 0xFDA9 release · battery
  0xFDA9 + battery are KNOWN-RED BY DESIGN and use xfail: they go red on GOOD news.
structural proofs: 0 INTERNET permission in merged manifest, both variants ⇒ the app cannot
  open a socket. Spike harness absent from release (69 classes debug, 0 release).

## STILL UNVERIFIED — do not confuse with the above
every BLE behaviour · battery · discovery latency · band accuracy · all of iOS · anything
needing a radio or a device. An APK that builds is not a feature that works.

## HARD BLOCKERS (detail in 60-blockers)
B8  RPA co-rotation — TOP RISK. invariant 5 may be unsatisfiable per device model. needs phones.
B9  0xFDA9 provisional. legal entity → SIG Adopter → real UUID → ship. nobody assigned.
B3  Tencent not provisioned. B6 no Go toolchain. B4 no Mac (deferred with iOS).
B5 CLOSED · B10 CLOSED (ADR-008) · B11/B12 CLOSED

## DONE
- [x] ADR 001-009. ADR-007 KMP supersedes 001 in part. ADR-008 server-issued key. ADR-009 CI.
- [x] BLE wire spec v0.1 + 116 vectors + single Kotlin codec, security-reviewed
- [x] Real Android radio + Phase 0 spike harness (bijection screen, honest raw logging)
- [x] 8 CI gates + green pipeline on GitHub Actions
- [x] Security review PASS-WITH-FIXES, all 5 landed incl the HIGH (key destruction at seam)
- [x] docs/PHASE0_GO_NO_GO.md — thresholds pre-committed BEFORE any data exists
- [x] docs/legal/ — privacy + LE policy rewritten after security BLOCK, CLAIMS_REGISTER,
      TELEMETRY_SCRUBBING_POLICY. NOT publishable: 5 gates listed.
- [x] Figma lvh2NvQKYn4byiBREyzaYL. 0:1 Foundations · 11:115 Onboarding (14 screens).
      11:116-11:122 = 7 pages NOT enumerated.

## IN FLIGHT
- [ ] nothing. every remaining item needs hardware, money, or a founder decision.

## NEXT — ANDROID FIRST
1. HUMAN: 3-4 Android phones across chipset vendors, budget MediaTek FIRST (~$400-600).
   dongles (~$35) DEFERRED — needed only if the phone result comes back clean (decisions 68/69).
2. qa-test: run the spike matrix. RPA co-rotation FIRST — only item unfixable after the fact.
3. orchestrator: complete the GO/NO-GO memo with real numbers.
4. HUMAN: legal entity → SIG Adopter (blocks shipping) · privacy counsel (deferred, not skipped)
DEFERRED: all iOS · Mac · Carrier B validation · B7 · relay-only calling (G5, with calling at P2)

## OPEN QUESTIONS
- is "Radius quietly notices people near you all day" load-bearing for the business case?
  ble-protocol's read: it does NOT survive iOS. "Open Radar when you're out" does.
- if RPA fails on a chunk of Android OEMs: exclude, ship scan-only, or don't ship?
  NOTE: PHASE0_GO_NO_GO pre-committed the answer (exclusion list, told in the UI). Publishing
  the privacy §3a wording makes that a PUBLIC commitment — founder-level, decide knowingly.
- entity/jurisdiction — blocks SIG UUID AND the privacy policy. Two paths, one dependency.
- Figma pages 11:116-11:122 · SMS provider after email OTP · moderator staffing

## LOG
<!-- pruned 2026-08-05 by orchestrator. durable content lives in 30-decisions rows 15-76. -->
2026-08-04 orchestrator · ADR-007 KMP (founder direction). ADR-008 server-issued account_key.
  shared API v0.1 gated. Mac + JDK logged as blockers; JDK closed same day.
2026-08-04 ios-swift · mobile/ios scaffolded, never compiled (B4). RAISED B7.
2026-08-04 android-kotlin · :shared + :android. CONFIRMED AND SHARPENED B7 independently
  (foreground too). RAISED B8. Later: real radio + spike harness, and found 3 scaffold bugs
  no build would ever report — incl a 100%-duty scan mode against a contracted 30%.
2026-08-04 ble-protocol · wire spec + codec. B7 resolved by CARRIER CHANGE, no invariant
  weakened. Found its own manifest was miscounting (99 declared vs 110 real) BY EXECUTING IT.
  Proved the suite can go red via injected mutations. Phase 0 call: expect pass, but the moat
  is foreground-first on iOS.
2026-08-04 security-privacy · protocol review PASS-WITH-FIXES. HIGH: superseded account_key
  never destroyed ⇒ rotation bounded nothing. Proved `internal` is not JVM access control by
  reading the raw key from a foreign compilation unit with no reflection.
2026-08-04 qa-test · 8 gates. Caught a live decision-34 violation on first run. Disassembled a
  real APK to check its own claim and found R8 decomposes the constant — so the source scan is
  authoritative and the artifact scan is defence-in-depth only.
2026-08-04/05 devops-tencent · ADR-009. GitHub interim, Gitea destination, zero logic in either.
  xfail for known-red gates: they fail on GOOD news. Corrected the orchestrator on the Ubuntu
  flip — "verified on Windows" was one dev box, not a hosted image.
2026-08-05 orchestrator · security BLOCK on the legal drafts: 3 claims false, 5 overclaimed,
  1 contradicting ADR-008 M4. Rewritten with NOT-YET-BUILT markers. CLAIMS_REGISTER added so a
  claim cannot silently rot. G1-G4 closed; G5 deferred with calling.
2026-08-05 qa-test + devops-tencent · RSSI-egress and permission gates built and wired.
  Both found real bugs only by testing against LIVE artifacts rather than fixtures — a
  case-sensitive pattern blind to Go's exported fields, and a `tr '\x01'` this runner treats
  as four literal characters.
