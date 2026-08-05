# 20 · LIVE STATE
<!-- EVERY agent updates this at end of session. append to LOG, rewrite NOW block. -->
<!-- keep under 150 lines. prune LOG older than 30 entries into 30-decisions if durable. -->

## NOW
phase: 0 — BLE feasibility spike. CODE COMPLETE to the limit of this machine.
blocking-everything: ZERO hardware measurement exists. go/no-go memo has an empty results table.
mobile arch: KOTLIN MULTIPLATFORM shared core + native UI (ADR-007). ANDROID FIRST, iOS deferred.
repo: github.com/iamnotking21/radius · main · PUBLIC · CI green on ubuntu-latest
infra: not provisioned. backend/ and website/ contain one memory file each. ~7% of v1.
  (12.5k lines Kotlin · 1.2k Swift · 0 Go · 0 TS · 0 design tokens · 0 proto · 0 migrations)
contracts: proto v0 NOT locked · shared API v0.1 GATED · BLE wire v0.1 written, 0/16 cells measured
hires: 0/2 BLE specialists

## VERIFIED — by a compiler or the GitHub API, not by assertion
build: Temurin JDK21 · wrapper 8.13 · AGP 8.9 · Kotlin 2.1.20 · SDK 35
  :shared + :android compile · assembleDebug + assembleRelease (R8) · lint 0 errors
  114 unit tests (69 shared + 45 android) · 116 conformance vectors executing ·
  80 gate self-test assertions. code-reviewer post-merge audit applied: 14 must-fix landed.
CI: run 4 fully green, all 3 jobs, ubuntu-latest. Android toolchain proven on Linux.
  CI has already caught one real bug local testing structurally could not (a Windows-only
  assertion inside the fix for a Windows-only hardcode).
gates (8): conformance · invariant-1 map/bearing (now incl backend+website) · internal-escape ·
  THE LINE · RSSI egress · merged-manifest permissions · 0xFDA9 release · battery
  0xFDA9 + battery are KNOWN-RED BY DESIGN and use xfail: they go red on GOOD news.
structural proofs: 0 INTERNET permission in merged manifest, both variants ⇒ the app cannot
  open a socket. Spike harness absent from release (105 classes debug, 0 release — re-checked
  across all 17 debug dex files after the P1/P2/§5.0 extension).

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
- [x] Spike harness EXTENDED to the three remaining Phase 0 measurements — P1 battery (+radio-state
      attribution), P2 discovery latency (UTC-aligned probe + 4 stacked clock-skew controls),
      SPEC §5.0 acquisition rate + peer density. src/debug only, no INTERNET, adb-pull only.
      qa-test's PHASE0_SPIKE_MATRIX §1 instrumentation blocker is CLEARED.
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
0. NOTE FOR THE PHONE PURCHASE, NEW 2026-08-05: buy at least TWO handsets on **Android 13+**.
   The independent clock-skew error bar for discovery latency (P2) uses
   `SystemClock.currentNetworkTimeClock()`, which is **API 33**, not our API 29 floor. On Android
   10/11/12 — i.e. much of the budget MediaTek tier the matrix tests FIRST — it does not exist, and
   the paired-sum estimator over two mutually-advertising phones becomes the ONLY skew control.
   That still works, but it means a latency run on a single advertising handset there has NO bound
   on its error. Does not change the shopping list; does change how the runs are paired.
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
- [RESOLVED 2026-08-05] Figma fully enumerated — docs/SCREEN_INVENTORY.md. ~75 screens, 8
  sections + foundations. Raises two follow-ups: design-tokens is EMPTY while the Foundations
  page is not (placeholder hexes are hardening in the Android theme), and page F monetization
  needs a real ADR-006 audit rather than the visual spot-check I did.
- SMS provider after email OTP · moderator staffing

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
2026-08-05 android-kotlin · spike harness extended to P1/P2/SPEC-5.0. Three findings worth keeping:
  (a) the obvious latency formulation `t mod CYCLE` is floorMod and CANNOT return a negative — so
  the free "a packet cannot arrive before it was sent" skew detector would have silently not
  existed, and a mis-clocked run would have looked merely disappointing. Fixed with bounded
  next-cycle attribution.
  (b) the latency probe's own once-a-minute advertising restart rotates OUR address inside a
  protocol epoch, which is the exact shape of a §4.3.1 bijection failure. A latency run is now
  VOID for B8 in the header, on screen, and in the integrity note — the instrument would otherwise
  have manufactured the project's top-severity finding.
  (c) lintDebug caught `currentNetworkTimeClock()` being API 33 while the KDoc claimed API 29.
  Would have crashed on the first budget handset. See NEXT item 0.
2026-08-05 android-kotlin · code-reviewer post-merge audit (first ever on this repo) → REQUEST-CHANGES,
  14 must-fix. Fixed 12 of them plus finding 8 after the orchestrator's ruling; 9/10 (iosMain) and
  17 (contract) stayed out. No invariant touched. The pattern across almost all of them is one thing:
  EVERY DEFECT FAILED IN THE FLATTERING DIRECTION. That is worth more than the individual fixes.
  (a) LatencyTracker.missedCycles was sighting-driven, so a cycle in which nothing was heard never
  ended and no miss was ever attributed to it. A peer dying at minute 10 of 90 froze the counter and
  the p50 over those ten good minutes read clean for the whole run — the exact hiding its own KDoc
  ("Counted, never inferred") promised it prevented. And "expected" was a monotone high-water mark,
  so one peer legitimately leaving manufactured a miss per remaining cycle forever. Now timer-driven
  with an explicit expected set and a DEPARTED threshold. It had NO test; it now has 11.
  (b) battery %/hr divided by wall clock. An NTP re-sync mid-capture silently rewrites the
  denominator of every figure in the run, and it was unrecoverable in analysis because the sample
  carried no monotonic timestamp. Both clocks are now in battery.csv, so a re-sync is visible as a
  step between two columns instead of a wrong answer.
  (c) SpikeWriter appended from four threads with no lock. appendLine is write-then-newLine = two
  monitor acquisitions, so rows TEAR — breaking the one property events.jsonl is documented to have
  (line-oriented, so an OEM kill still leaves a valid file). Worse than the audit said: post-close
  appends hit a nulled handle and were SILENTLY counted as written, so the last rows of every run
  vanished and write_failures said 0.
  (d) scan was stopped BEFORE ScanStartGate was consulted — a refused start left the radio blind for
  up to 30s, which is the exact dead-air the gate exists to prevent. Gate first now.
  (e) two accumulator maps bumped outside the lock while publish() copy-constructs them from three
  threads. CME inside a collect with no handler = process death at minute 47 and an unexplained gap.
  Kept: decision-61 lock discipline, no lock across a suspension point, AdvertiseGuard parity.
  114 tests green incl conformance vectors; lintDebug clean. STILL ZERO RADIO EVIDENCE — every fix
  here is desk work and the numbers this instrument produces are still unmeasured.
2026-08-05 android-kotlin · finding 8 ruling applied (orchestrator corrected decision row 60, not me).
  Epoch ticker predicate is now EpochTickerPolicy.wanted() in commonMain, called by both actuals;
  desiredScan dropped out entirely. Ghost mode — one tap, the highest-signal privacy assertion in
  the product — was switching OFF key destruction, while a low-signal adapter-off event kept it
  running. iOS had the right predicate by accident (it holds no scan state). setEpochBoundaryListener
  now syncs the ticker and refuses registration after shutdown (R-D ring pinning). The predicate was
  a boolean expression written twice in two files neither of which can be instantiated without a
  platform — moving it to a pure function of three booleans is what made it testable at all.
  CONDITION IN THE CODE: the ruling holds only because the ticker is a coroutine delay, which cannot
  wake a Dozing SoC. Promote it to setExactAndAllowWhileIdle and 96 exact alarms/day land on a
  CI-gated battery contract — noted at both call sites. Decision 78: when ring persistence lands the
  guarantee moves to prune-on-load in the ring store, because the radio's lifetime is not the ring's.
