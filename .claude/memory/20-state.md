# 20 · LIVE STATE
<!-- EVERY agent updates this at end of session. append to LOG, rewrite NOW block. -->
<!-- LOG = ONE LINE, or two at most. NOT a section with its own heading. Three agents appended -->
<!-- full ## sections on 2026-08-05 and this file hit 444 lines against a 150 cap. Detail belongs -->
<!-- in your report and in 30-decisions; this file is what the NEXT session reads first. -->
<!-- orchestrator prunes. keep under 150 lines. -->

## NOW
phase: 0 — BLE feasibility spike. CODE COMPLETE to the limit of this machine.
blocking-everything: ZERO hardware measurement. go/no-go memo has an empty results table.
mobile arch: KOTLIN MULTIPLATFORM shared core + native UI (ADR-007). ANDROID FIRST, iOS deferred.
repo: github.com/iamnotking21/radius · main · PUBLIC · CI run 14 green
infra: not provisioned. backend/ and website/ hold one memory file each. **~8% of v1.**
  ~15k lines Kotlin · 1.2k Swift · 0 Go · 0 TS · 0 proto · 0 migrations
contracts: proto v0 NOT locked · shared API v0.1 GATED · BLE wire v0.1, 0/16 cells measured ·
  design-token build contract GATED
hires: 0/2 BLE specialists

## VERIFIED — by a compiler, a test, or the GitHub API. never by assertion.
build: Temurin JDK21 · wrapper 8.13 · AGP 8.9 · Kotlin 2.1.20 · SDK 35 · node (tokens)
  126 unit tests · 116 conformance vectors · 95 gate self-test assertions ·
  51 WCAG pairings gating :android:assembleDebug · lint 0 errors · debug+release APKs under R8
CI: 9 gates, ALL WIRED, green on ubuntu-latest across 3 jobs.
  0xFDA9 + battery are KNOWN-RED BY DESIGN via xfail — they go red on GOOD news.
  CI has caught 1 bug local testing structurally could not (a Windows-only assertion inside the
  fix for a Windows-only hardcode — one OS cannot catch that).
reviews: code-reviewer TWICE (14 must-fix on the full audit, 6 on the delta — all landed) ·
  security-privacy on the protocol (PASS-WITH-FIXES incl the key-destruction HIGH) ·
  security-privacy on the legal drafts (BLOCK — 3 false claims, 5 overclaimed) ·
  growth-conversion on monetization (no banned pattern; 7 unbacked CLAIMS instead)
structural proofs: 0 INTERNET permission in the merged manifest, BOTH variants ⇒ the app cannot
  open a socket. Spike harness absent from release (0 of 105 classes).
FIRST RUN ON AN ANDROID RUNTIME 2026-08-05 — emulator (API 36), UI ONLY, NOT hardware:
  app installs, launches, no crash. Discover + Radar render. Mode accents CORRECT on screen
  (Discover amber, Radar teal). Ghost mode = one tap, NO confirm dialog (invariant 10 visually
  holds). "Start Radar" correctly DISABLED with honest copy "the radio is not wired up yet".
  NO map, NO bearing, NO direction anywhere (invariant 1 visually holds).
  design tokens confirmed applied on a real Android renderer, not just in a unit test.
  **BLE UNTESTED AND UNTESTABLE HERE** — an emulator has no radio. Nothing about discovery,
  battery, or RPA rotation was learned. mobile/CLAUDE.md: simulator BLE result = invalid.
FIRST RUN ON REAL HARDWARE 2026-08-07 — Xiaomi Redmi 15 5G (25057RN09G, Android 16/API 36),
  SPIKE HARNESS UI ONLY, STILL NO RADIO RESULT. Harness installs, launches, records, exports.
  3 layout defects found and fixed on-device; 1 reported defect DISPROVEN (see LOG).
  200% font scale checked ON THIS HANDSET, no clipping. Insets, pinned controls, steppers verified
  by uiautomator BOUNDS, not by eye.

FIRST REAL TELEMETRY 2026-08-07 — Redmi 15 5G, ~2.4 min CAPTURE run, PLUGGED IN so battery
  figures are correctly VOID. Pipeline proven end to end: 7 files written, adb pull works.
  Design decisions now observable OUTSIDE a test, in a real event log:
    "scan-only: no advertise role requested"  ⇒ AdvertiseGuard fail-closed default (dec 35)
    "SCAN_STARTED ... startsInWindow=1"       ⇒ ScanStartGate budget counting (dec 47)
    "DUTY: role=SCAN_ONLY source=DEBUG_SPIKE_HARNESS" ⇒ the greppable non-production grant
    "epoch boundary day=20672 epoch=23 pruneSupersededAt destroyed=0 ring=K"
       ⇒ ADR-008 M4 KEY DESTRUCTION FIRED ON REAL HARDWARE at a real epoch boundary, and
         correctly destroyed NOTHING because a single-entry ring is never pruned (dec 62).
         rows 54/60/77 (my own corrected predicate) all confirmed outside a test.
    3 density buckets with 0 packets each ⇒ empty buckets emitted from a TIMER (dec: a success
         rate computed only over buckets where something succeeded is 100% by construction)
  DEVICE CAPABILITY, measured not assumed: peripheral role SUPPORTED (dec 45's corrected check),
    multiple-advertisement SUPPORTED, availability READY, 0 scan failures.
  DEVICE LIMITS, measured: BATTERY_PROPERTY_CURRENT_NOW and ENERGY_COUNTER both UNSUPPORTED
    (Int.MIN_VALUE / Long.MIN_VALUE). drain must come from level_pct + charge_counter_uah,
    both of which work. the column is named ..._RAW_SIGN_UNVERIFIED, which is why we noticed.
  0 sightings is CORRECT, not a failure: the scan filters on our service UUID (dec 46), so one
    handset alone can only ever see zero.
  scan_on_ms ≈ elapsed_ms (141767/141805) = HOST-REQUESTED continuous. The contracted 25% duty
    happens INSIDE the controller. This number can never show a duty violation — only battery can.

## STILL UNVERIFIED — do not confuse with the above
every BLE behaviour · battery · discovery latency · band accuracy · all of iOS · every
accessibility claim (arithmetic over tokens, no TalkBack, no device) · anything needing a radio.
**An APK that builds is not a feature that works.**

## HARD BLOCKERS (detail in 60-blockers)
B8  RPA co-rotation — TOP RISK. invariant 5 may be unsatisfiable per device model. needs phones.
B9  0xFDA9 provisional. legal entity → SIG Adopter → real UUID → ship. nobody assigned.
B3  Tencent not provisioned · B6 no Go toolchain · B4 no Mac (deferred with iOS)
CLOSED: B5 (JDK) · B10 (ADR-008) · B11 · B12 · B13

## DONE
- [x] ADR 001-009 · BLE wire spec v0.1 + 116 vectors + one Kotlin codec, security-reviewed
- [x] Real Android radio + Phase 0 spike harness — bijection screen, P1 battery (with radio-state
      attribution), P2 latency (UTC-aligned probe, 4 stacked skew controls), §5.0 density.
      src/debug only, no INTERNET, adb-pull only. NOT ON A RADIO.
- [x] 9 CI gates, wired and green. docs/PHASE0_GO_NO_GO.md — thresholds pre-committed BEFORE data.
- [x] docs/legal/ — privacy + LE policy (rewritten after BLOCK), CLAIMS_REGISTER,
      TELEMETRY_SCRUBBING_POLICY. NOT publishable: 5 gates listed.
- [x] docs/SPIKE_DAY_ONE_RUNBOOK.md — procedure for a non-engineer with a bag of phones
- [x] Figma FULLY enumerated → docs/SCREEN_INVENTORY.md. ~75 screens, 8 sections + foundations.
- [x] Design tokens REAL — primitives from Figma variables, semantic layer designed, generated at
      build time, contrast-gated. Placeholders gone from the Android theme.

## IN FLIGHT
- [ ] nothing. every remaining item needs hardware, money, or a founder decision.

## NEXT
1. **HUMAN — 3-4 Android phones, budget MediaTek FIRST, ≥2 on Android 13+ (~$400-600).**
   The 13+ note is not about the shopping list, it is about PAIRING: the independent clock-skew
   error bar for P2 uses an API-33 call, so below 13 the paired-sum estimator over two mutually
   advertising phones is the ONLY skew control. Dongles (~$35) DEFERRED — needed only if the
   phone result comes back CLEAN, which is the untrustworthy answer (decisions 68/69).
2. qa-test: run the spike matrix. RPA co-rotation FIRST — the only item unfixable after the fact.
3. orchestrator: complete the GO/NO-GO memo with real numbers.
4. HUMAN: legal entity → SIG Adopter · fonts (§8: bundled OFL vs Downloadable) · free-tier numbers
DEFERRED: all iOS · Mac · Carrier B · B7 · relay-only calling (G5, with calling at P2) ·
  Beacon physics ruling (decision 87, with the feature)

## OPEN QUESTIONS
- is "Radius quietly notices people near you all day" load-bearing for the business case?
  ble-protocol's read: it does NOT survive iOS. "Open Radar when you're out" does.
- if RPA fails on a chunk of Android OEMs: exclude, ship scan-only, or don't ship?
  PHASE0_GO_NO_GO pre-committed the answer (exclusion list, told in the UI). Publishing the
  privacy §3a wording makes that a PUBLIC commitment — founder-level, decide knowingly.
- entity/jurisdiction — blocks the SIG UUID AND the privacy policy. Two paths, one dependency.
- WHO OWNS THE FREE-TIER NUMBERS? no repo file states a price, the tier matrix, or the free
  comment cap. three F screens already disagree. needed BEFORE F is built, not after.
- fonts: bundled OFL (+400KB, offline-safe) vs Downloadable (smaller, needs GMS + network,
  silently falls back on non-GMS). Recommendation: bundled, for an offline-moat product.
- SMS provider after email OTP · moderator staffing

## LOG
<!-- pruned 2026-08-05. durable content lives in 30-decisions (99 rows) and 40-contracts. -->
2026-08-04 orchestrator · ADR-007 KMP + ADR-008 server-issued account_key (founder calls).
  shared API v0.1 gated. JDK blocker raised and closed same day.
2026-08-04 ios-swift · mobile/ios scaffolded, never compiled (B4). RAISED B7.
2026-08-04 android-kotlin · :shared + :android. CONFIRMED B7 INDEPENDENTLY. RAISED B8. Later:
  real radio + spike harness, and 3 scaffold bugs no build would report — incl a 100%-duty scan
  mode against a contracted 30%.
2026-08-04 ble-protocol · wire spec + codec. B7 resolved by CARRIER CHANGE, no invariant weakened.
  Found its own manifest miscounting (99 declared vs 110 real) BY EXECUTING IT. Proved the suite
  can go red via injected mutations. Phase 0 call: expect pass, moat foreground-first on iOS.
2026-08-04 security-privacy · protocol PASS-WITH-FIXES. HIGH: superseded account_key never
  destroyed ⇒ rotation bounded nothing. Proved `internal` is not JVM access control.
2026-08-04/05 qa-test · 9 gates, 95 assertions. Caught a live decision-34 violation on run 1.
  Disassembled a real APK to check its own claim — R8 decomposes the constant, so source scan is
  authoritative and artifact scan is defence-in-depth only.
2026-08-04/05 devops-tencent · ADR-009. GitHub interim, Gitea destination, zero logic in either.
  xfail for known-red gates. Corrected the orchestrator twice: on the Ubuntu flip, and on which
  stages actually need node.
2026-08-05 orchestrator · legal BLOCK applied. CLAIMS_REGISTER so a claim cannot silently rot.
  Figma enumerated. Design tokens commissioned. 40-contracts gained the token build contract.
2026-08-05 design-system · tokens from Figma + contrast gate (51 pairings, fails the build).
  Refused to invent THREE times: status ramps, light theme, threads.onInverse.
2026-08-05 android-kotlin · tokens wired, placeholders gone. outline split fixed AT THE M3 SCHEME
  so the next OutlinedTextField is compliant by default. Review round 2 applied.
2026-08-05 orchestrator · nesting-comment trap hit 4x independently; now a gate. Root cause of
  one hit was decision row 60 stating a wrong predicate that Android implemented faithfully —
  the defect was in MY recorded decision, corrected by rows 77/78.
2026-08-07 android-kotlin · spike harness on a REAL Redmi 15 5G. "Screen does not scroll" was
  NOT a bug: MIUI refuses `adb shell input` (INJECT_EVENTS) but prints to stderr and EXITS 0, so
  an undelivered swipe looks like a dead UI. Drove it with /system/bin/uinput instead. Real fixes:
  missing safeDrawing insets (API35+ edge-to-edge is not opt-out-able); Start/Stop moved OUT of a
  2.5-screen scroll into a pinned bar; and a Row-weight bug that rendered "Mode" as M/o/d/e —
  found LIVE a second time in Stat(), where one row measured 1099px tall. oem.md now carries the
  Xiaomi host-toolchain table (pm grant + input + sendevent REFUSED, svc bluetooth WORKS).
2026-08-07 android-kotlin · NAMING DEFECT found by reading REAL telemetry, not by a test.
  meta.json said `battery_figures_valid: "true"` while every row of the SAME run's battery.csv
  said `valid_for_drain=false` (phone on a charger). Both true in their own terms — the flag meant
  "this MODE permits a battery figure", the column meant "THIS sample counts" — but the one at the
  top of the file is the one a human reads first, so a reader would have quoted battery numbers
  from a plugged-in run. Fix is a SPLIT, not a reword: `..._permitted_by_mode` (capability,
  written at START, before data exists) vs a computed verdict appended at STOP from the rows
  (`battery_samples_valid_for_drain: "0 of 4"`, `bijection_screen_evidence`,
  `latency_figures_observed`). Same defect shape in all three flags; `latency_figures_present`
  asserted PRESENCE on the strength of the mode alone. Bonus property: a run killed by an OEM
  battery manager never reaches closeMeta, so a missing verdict key now MEANS "no clean stop".
  Also: `advertiseState STOPPED   epoch=-1` (a normal scan-only event that reads as an error)
  → `epoch=none` via a named sentinel, double spaces gone.
  VERIFIED ON THE REDMI, not just compiled: new 173s run emits "0 of 4" against 4 rows of
  valid_for_drain=false. oem.md gained the per-device battery-telemetry row the matrix wanted —
  CURRENT_NOW and ENERGY_COUNTER are UNSUPPORTED on this handset (Int/Long.MIN_VALUE), drain must
  come from level_pct + charge_counter_uah — and a second silent-failure trap: `uinput` needs
  UI_SET_PROPBIT INPUT_PROP_DIRECT or InputReader treats the device as a TOUCHPAD and discards
  every tap, printing nothing and exiting 0. Same false conclusion as the `input` trap, different
  route. Also `uiautomator dump` omitted the pinned Start/Stop bar entirely; take coords off a
  screenshot.
