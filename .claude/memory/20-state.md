# 20 · LIVE STATE
<!-- EVERY agent updates this at end of session. append to LOG, rewrite NOW block. -->
<!-- keep under 150 lines. prune LOG older than 30 entries into 30-decisions if durable. -->

## NOW
phase: 0 — BLE feasibility spike. CODE COMPLETE to the limit of this machine.
blocking-everything: ZERO hardware measurement exists. go/no-go memo has an empty results table.
mobile arch: KOTLIN MULTIPLATFORM shared core + native UI (ADR-007). ANDROID FIRST, iOS deferred.
repo: github.com/iamnotking21/radius · main · PUBLIC · CI green on ubuntu-latest
infra: not provisioned. backend/ and website/ contain one memory file each. ~8% of v1.
  (~15k lines Kotlin · 1.2k Swift · 0 Go · 0 TS · 0 proto · 0 migrations)
  design tokens: REAL — primitives from Figma, semantic layer designed, generated at build time,
  contrast-gated. fonts still absent (§8 founder call) so type is metrics-only.
contracts: proto v0 NOT locked · shared API v0.1 GATED · BLE wire v0.1 written, 0/16 cells measured
hires: 0/2 BLE specialists

## VERIFIED — by a compiler or the GitHub API, not by assertion
build: Temurin JDK21 · wrapper 8.13 · AGP 8.9 · Kotlin 2.1.20 · SDK 35
  :shared + :android compile · assembleDebug + assembleRelease (R8) · lint 0 errors
  126 unit tests · 116 conformance vectors · 95 gate self-test assertions ·
  51 WCAG contrast pairings gating :android:assembleDebug
  code-reviewer ran TWICE: 14 must-fix from the full audit, then 6 more on the delta. all landed.
CI: run 4 fully green, all 3 jobs, ubuntu-latest. Android toolchain proven on Linux.
  CI has already caught one real bug local testing structurally could not (a Windows-only
  assertion inside the fix for a Windows-only hardcode).
gates (9, 1 not yet wired — B13): conformance · invariant-1 map/bearing (now incl backend+website) ·
  internal-escape · THE LINE · RSSI egress · merged-manifest permissions · 0xFDA9 release · battery ·
  comment-nesting (NEW 2026-08-05, HANDOFF to devops-tencent to wire into fast-gates, B13)
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
  [F AUDITED 2026-08-05 growth-conversion] done — decisions 86-93. NO banned pattern found;
  6 unverifiable/false CLAIMS found instead. 2 must be fixed in Figma BEFORE F is built:
  "Reveal hidden profiles nearby" (row 87) and "EXCLUSIVE PRESENT" (row 91). design-tokens
  follow-up stands, unaffected.
- WHO OWNS THE FREE-TIER NUMBERS? no repo file states the free comment cap, the tier feature
  matrix, or a single price. three F screens already disagree with each other about what Plus
  includes. needs a founder answer before F1/F2 are built, not after.
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
2026-08-05 growth-conversion · ADR-006 audit of Figma F/monetization, all 7 screens, every string
  read at 12x rather than at strip scale. Decisions 86-93. HEADLINE: the founder's spot-check was
  right about the thing he doubted and wrong about where the risk was. ZERO banned patterns —
  the 22:47 countdown is on a real purchased 30-min product, all three dismissals are neutral
  ("Not Now" / "Maybe later" / "Close Status"), cancellation is one unguarded tap on a screen that
  is itself one tap from Settings, and E7 reached the mockup as a gift instead of quietly becoming
  a card-required trial. What the page actually fails is CLAIM-TRUTH: it makes seven factual
  assertions and not one has a source of truth in this repo.
  Three worth keeping:
  (a) the store maths is wrong in both packs, in the SAME DIRECTION. "$2.49 each" x12 = $29.88 but
  the pack is $29.99; "$4.99 each" x3 = $14.97 but the pack is $14.99. Both are the true unit price
  TRUNCATED ($2.4992, $4.9967) rather than rounded, so both understate what the buyer pays per item.
  Eleven cents — and it is the tell: a per-unit figure was typed by a human instead of divided by a
  machine, which is the same hand that will type "SAVE 50%" into a storefront where Apple's price
  tiers do not preserve the ratio.
  (b) "1 Hour Beacon · Reveal hidden profiles nearby" is the one item I would block. 50-glossary
  defines beacon as boosted Radar VISIBILITY — a transmit-side product sold with receive-side copy.
  The read-it-literally version sells a ghost-mode defeat (invariant 10) that cannot even function.
  The charitable version is worse than it first looks: boosting BLE visibility means TX power or
  advertising rate, and MORE TX POWER RAISES THE RSSI A PEER COMPUTES AND THEREFORE SHIFTS THE BAND
  IT SHOWS. That is a paid distance lie. CLAIMS_REGISTER A1 does not defend it — A1 stops us
  inferring distance from RSSI, not us being paid to corrupt the RSSI upstream of the honest maths.
  (c) F1 has the 6-month "SAVE 33%" chip selected while the CTA reads "Continue with Gold
  ($29.99/mo)" — the 1-month price. The price of the selected thing is on no screen, and the total
  charged for a 6/12-month term appears nowhere on the purchase surface at all. The renewal footnote
  itself is present, unlinked and honest, but sits BELOW the CTA in the smallest, lowest-contrast
  type on the page; it survives "above the fold" only because this Figma frame is a tall device.
  At 375x667 (iPhone SE, our min-iOS16 floor) that content scrolls and the disclosure leaves the
  viewport. Pin it above the button and layout-test at 667pt.
  NOT FOUND, and looked for specifically: fatigue rules, the 24h-after-report suppression, and the
  founder honesty check (paying vs free conversation + reply rate). Correctly absent — they are
  logic and an admin surface, not phone screens — but they have no home in the repo either, so
  nothing yet distinguishes "not drawn" from "not built".
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
2026-08-05 android-kotlin · design tokens wired. RadiusTheme.kt placeholders gone; the whole
  mobile/android/ tree now has zero raw hex and zero magic dp/sp. Four things worth carrying:
  (a) I did NOT vendor design-system's generated RadiusDesignTokens.kt. :android runs
  generate.mjs at build time (task generateDesignTokens, hangs off preBuild) and emits into
  build/generated/, which is gitignored. Vendoring would have put 30 hex literals in my source
  tree that drift the first time nobody re-copies. Consequence, stated so nobody is surprised:
  :android now needs `node` on PATH. run-stage.sh already requires node for battery/conformance
  but NOT for android_lint / android_assemble_* — HANDOFF to devops-tencent. Free upside: the
  48-pairing WCAG contrast gate now fails :android:assembleDebug, so a contrast regression can no
  longer be merged by someone who did not run the generator.
  (b) THE GENERATED FILE DOES NOT COMPILE AS EMITTED — three separate bugs, all in generate.mjs,
  all one-liners: `import Color` is an unresolvable root-package import; the nested `object Color`
  shadows Compose's Color so all 30 `val x: Color = Color(0x..)` are type mismatches; and
  Accent.Radar's KDoc contains the prose "signal/*", which opens a NESTED block comment (Kotlin
  nests, Java does not) and swallows the last 100 lines of the file. That third one reports as
  "Missing '}'" plus "Unclosed comment at EOF" and points nowhere near the cause. Three asserting
  rewrites in build.gradle.kts work around them; each fails loudly if its match count drops, so
  the day design-system fixes the generator the build tells us to delete the shim.
  (c) The outline split was a real a11y defect, not a rename. One `outline` token fed both the
  divider and OutlinedButton's stroke. M3 draws every focus ring, OutlinedTextField edge and
  outlined-button border from colorScheme.outline, so a hairline-weight value there put the ONLY
  visible affordance of every such control at ~1.1-1.5:1, under SC 1.4.11's 3:1 floor. Fixed at
  the SCHEME level (outline = border.interactive, outlineVariant = border.hairline) rather than at
  the call site, so the next OutlinedTextField anyone adds is compliant by default instead of by
  memory. Deliberately did not hand-roll a BorderStroke: no stroke-WIDTH token exists to build one
  from, and an explicit border would defeat M3's enabled/disabled stroke handling on a button that
  currently ships disabled.
  (d) Spacing scale REPLACED, not aliased. Old xs/sm/md/lg/xl (4/8/16/24/32) was invented locally
  and missing 2/6/12/20/40/64/80. Mapping md->space16 would have preserved a second unreviewed
  scale behind a friendly name, which is the exact failure the token drop existed to end.
  Type scale wired with the real metrics; FONTS ARE NOT — Fraunces/Inter files are not bundled and
  bundling vs Downloadable Fonts is an ORCHESTRATION §8 call, so display maps to the platform serif
  and ui to the platform sans as an explicitly unbranded fallback. Do not read a screenshot as
  "type is done". Also took two review one-liners in RadarForegroundService: isGhost was declared
  and never assigned (so a ghost-mode user read "Discovering people nearby" in the shade — a
  safety-surface lie, invariant 10) and ACTION_START was declared but never matched. isGhost now
  arrives on the intent and start() requires it. 52 unit tests green, lintDebug clean,
  assembleDebug green. STILL NO DEVICE: none of this has been seen on hardware, and the a11y claim
  here is arithmetic on token values, not a TalkBack or 200%-font-scale pass on a real handset.
2026-08-05 devops-tencent · NODE ASSERTED IN THE STAGE TABLE. Design tokens are GENERATED
  (generateDesignTokens off preBuild), so node is now a build dependency of :android itself, not
  just a gate helper. Verified WHICH stages by task-graph inspection rather than assuming preBuild
  covers everything: --dry-run puts generateDesignTokens in the graph for testDebugUnitTest,
  lintDebug, assembleDebug, assembleRelease AND processDebug/ReleaseManifestForPackage; :shared
  alone does NOT pull it (so gate-conformance's node need is a DIFFERENT one — phase-1
  vectors_manifest_check.js — and its message says so).
  New require_node <reason> helper; every existing ad-hoc `command -v node` replaced so there is one
  message shape. Proved it by stripping node from PATH: build-lint, build-manifests,
  gate-conformance, gate-battery each fail in ~1s naming their own reason.
  PROPERTY WORTH KNOWING, now pinned in-source: require_node/require_android_toolchain call `exit`
  DIRECTLY, so they terminate before the known-red tolerance is reached. A missing node on
  gate-battery therefore fails the build with a toolchain message instead of being absorbed as
  "expected red". The tolerance cannot hide a broken RUNNER any more than it can hide a broken gate.
  Do not tidy those preflights into something that returns a status.
  README §5b triage table gained 3 rows for the new failure mode. The valuable one: a node failure
  and a WCAG CONTRAST regression now surface from the SAME Gradle task and are completely different
  findings. Discriminator is the generator's own line `N pairings checked, M regression(s)` — if it
  printed, the toolchain worked and you are looking at a real finding. An accessibility regression
  can now fail an Android build; that is a deliberate property of generating tokens, not a bug.
  *** FOUND WHILE VERIFYING, NOT MINE TO FIX, LIVE RIGHT NOW: every :android stage is RED.
  `:android:generateDesignTokens` fails with 'Design-token rewrite "drop the unresolvable
  `import Color` (generator bug 1)" matched 0 times, expected at least 1.' Cause is an UNCOMMITTED,
  in-flight edit to mobile/design-tokens/scripts/generate.mjs (design-system, +307/-206) that fixes
  exactly that bug — it now imports the real type as ComposeColor and emits `ComposeColor(0xFF..)`,
  so the bare `import Color` line android-kotlin's shim compensates for is gone. android-kotlin's
  own DELETE RULE in mobile/android/build.gradle.kts already prescribes the fix: "when generate.mjs
  emits compilable Kotlin, delete [rewrites] entirely". THIS IS THE SHIM WORKING, not a defect —
  it refused to silently rewrite output whose shape it no longer recognises. NEEDS: design-system
  to land the generator change, then android-kotlin to delete [rewrites]. Contrast gate itself is
  GREEN throughout (48 pairings, 0 regressions), which is how I could tell these apart in one line.
  It was green ~20 min earlier in the same session, so it landed mid-sweep. NOT caused by my change:
  my diff adds preflights only, and the fast gates + gate-conformance stay green. ***
  RE-VERIFIED: 4 YAMLs parse, bash -n clean, 5 fast gates + gate-conformance exit 0, known-red pair
  still 0 push / 1 strict. STILL: nothing has ever run in CI.

## android-kotlin — 2026-08-05 — shims deleted, :android green cold
design-system landed the generator fix; all three token rewrites are DELETED from
mobile/android/build.gradle.kts. The asserting shim did its job end to end: it went red on good
news, named the rewrite to remove, and the removal is now done. Only the package rewrite remains —
that is HANDOFF step 1, delegated to the consumer, not a workaround. It still asserts exactly one
`package com.radius.designtokens.generated` line.
VERIFIED COLD (build/ dirs wiped, JDK21): :android:assembleDebug + :android:testDebugUnitTest +
:android:lintDebug all BUILD SUCCESSFUL. 52 unit tests, 0 failures. Lint 51 issues, ALL Warning,
0 Error (GradleDependency/UnusedResources/MissingApplicationIcon scaffold noise, pre-existing).
Contrast gate prints "48 pairings checked, 0 regression(s)" on every build. APK produced.
Generated file now differs from generate.mjs's raw emission by the package line ONLY (diff = 1 line).
*** THE TRAP THAT BIT ME, AND IT IS THE SAME ONE THREE TIMES NOW: while writing the KDoc that
explains generator bug 3, I typed a literal block-comment opener INSIDE that KDoc. Kotlin block
comments NEST, so it opened a nested comment, my KDoc's terminator closed only the nested one, and
everything from that KDoc to EOF — the task registration, the preBuild hook, and the whole
dependencies{} block — was silently commented out. Gradle reported NO syntax error. It reported
"The Hilt Android Gradle plugin is applied but no com.google.dagger:hilt-android dependency was
found", because from its point of view dependencies{} genuinely was not there. Diagnosed with an
init-script probe: `implementation` had 0 declared deps, 2 across all configurations, vs 23 on the
working file. design-system hit the identical trap writing its own note about the bug; that is now
three independent hits, which is why their structural docSafe() fix was right and why a one-off
would not have been. RULE: never write a raw block-comment opener in a Kotlin doc comment — spell
it out in words. A `dependencies{}` block can vanish with no syntax error at all. ***
Token hygiene re-greped: ZERO raw hex and ZERO raw dp/sp on non-comment lines across
mobile/android/src/. Only res/ numeric is ic_radar_notification.xml's own 24dp intrinsic size —
the platform notification-icon spec, i.e. asset geometry, documented in-file as not layout spacing.
STILL OPEN, NOT MINE: Fraunces/Inter absent, type metrics wired to UNBRANDED FALLBACK platform
fonts pending the founder's bundled-OFL-vs-Downloadable-Fonts call. No stroke-width/elevation/
motion tokens and nothing for M3 errorContainer/scrim/surfaceDim/surfaceBright — design-system
deliberately did not invent those, so M3 defaults stand.
STILL TRUE: no BLE has run on real hardware. Nothing here is a Phase 0 spike result.

## qa-test — 2026-08-05 — comment-nesting gate (B13, HANDOFF to devops-tencent)
NEW GATE: `devops/ci/gates/comment_nesting_gate.sh` + engine `lib/comment_depth_scan.awk` +
self-test `devops/ci/tests/gates/test_comment_nesting_gate.sh` (15 assertions). Total gate
self-test count: 80 -> 95, all pass. Closes the trap that has hit this project FOUR times
independently: Kotlin block comments NEST (Java's don't) — a literal `/*` inside a KDoc opens a
nested comment that the KDoc's own `*/` closes only partially, silently swallowing everything after
it to EOF, with no syntax error at the point of the mistake.
CHOSE the nesting-aware scan over the reviewer's line regex (`grep -rnE
'^[[:space:]]*\*.*(/\*|\*/)'`): a char-by-char state machine tracks block-comment depth across the
WHOLE file (not one line at a time) and reports depth != 0 at EOF — the actual failure condition —
while correctly ignoring `/*`/`*/`/`//` inside STRING/CHAR/raw-string literals with no bolted-on
exception needed. DELIBERATELY does not reuse `qa_strip_comments` (this gate checks comment
STRUCTURE; stripping first would blind it) and DELIBERATELY does not exclude test source the way
every other pattern gate here does — the live violation IS in test source (iosTest), the one source
set nothing on this machine compiles, and that is exactly the point.
LIVE STATUS, stated honestly rather than waiting for a clean run to claim a pass: at HEAD (committed),
`mobile/shared/src/iosTest/kotlin/com/radius/shared/ble/IosRadioContractTest.kt:16` IS the violation
(`vectors/*.json` unbroken inside a KDoc) — verified by running the gate against `git show
HEAD:<path>` directly: fails exactly as expected. MID-SESSION, android-kotlin fixed it in the working
tree (uncommitted at time of writing — backtick-split the glob + added a KDoc explaining why), so the
gate now shows PASS against the current working tree. This is the gate correctly reporting a fix that
landed while it was being tested, not a gate that never caught anything — self-test pins the ORIGINAL
committed content verbatim so the regression case can never quietly disappear regardless of what HEAD
says. Full story in `devops/ci/README.md`'s status table note.
NOT YET WIRED into CI: `run-stage.sh` is devops-tencent's file (RUNNER concern per root CLAUDE.md
carve-out). HANDOFF filed as B13 in 60-blockers.md: add a `gate-comment-nesting` case + STAGES entry
to `run-stage.sh`, and one step in the `fast-gates` job of all four workflow files, same shape as the
existing `gate-rssi-egress` wiring.

## android-kotlin — 2026-08-05 — review round 2 delta (4 must-fix + 4 small), ALL CLOSED
BUILD, this working tree, JDK21 Temurin, `--no-daemon`: `:android:assembleDebug`
`:android:testDebugUnitTest` `:shared:testDebugUnitTest` `:android:lintDebug` = BUILD SUCCESSFUL.
android 52 tests / shared 69 tests, 0 failures 0 skipped. lint 0 errors, 51 warnings — the same
pre-existing set (33 GradleDependency, 8 UnusedResources, 6 AGP-version, 1 DefaultLocale in
SpikeScreen:466, 1 OldTargetApi, 1 UnusedAttribute, 1 MissingApplicationIcon), NONE from this
delta. Forced recompile of both Kotlin tasks: ZERO compiler warnings.
1. COMMENT NESTING, 4th recurrence, `IosRadioContractTest.kt:16`. Confirmed exactly as reported:
   `vectors/*.json` opened a nested comment inside a KDoc and swallowed the class, the @Test and
   EOF. Fixed by splitting the path across a backtick pair + a KDoc paragraph saying WHY the split
   is load-bearing. It lived because iosTest is the one source set nothing on Windows compiles.
2. `SpikeController.sampleBattery` — `drain.add` and the five `duty` reads were outside `lock`
   while every other reader/writer took it. Now ONE critical section producing a `BatteryRowSnapshot`
   (writer, n, scanning, advertising, scan_on_ms, advertise_on_ms, transitions, sightings, peers,
   write_failures); the CSV row is assembled from the snapshot. `batteryReader.read()` stays outside
   the lock deliberately — it is a binder call and must not stall the event collector. This was P1's
   numerator and denominator with no happens-before edge, failing SILENTLY because longs do not throw.
3. `BleRadio.timestampsClamped` was written and never read. Now reaches meta.json
   (`timestamps_clamped` + a `_note`), `SpikeStats.timestampsClamped`, the DEGRADED branch of
   `integrityNote`, a new VOID branch of `latencyNote` ahead of the skew branches (skew is
   correctable off-device, a fabricated reception time is not), and a row on SpikeScreen. Before
   this a run on a handset that fabricates `ScanResult.timestampNanos` still read "no bridging
   observed so far".
4. `BleRadio.ios.kt` had ZERO synchronisation against a fully-locked Android actual. Added an
   `NSRecursiveLock` + private inline `withLock` (no new dependency: Foundation interop is already
   linked; atomicfu would be an ORCHESTRATION §8 escalation). RECURSIVE because `synchronized` is
   reentrant and the Android actual leans on that 3 frames deep — a plain NSLock would deadlock
   rather than race. Closed: the double-ticker launch, the post-`shutdown()` listener invocation
   (now `withLock { if (isShutdown) return else epochBoundaryListener }`, byte-identical to
   Android:657), and the missing NOT_RUNNING checks in `setAdvertiseRole`/`startAdvertising`
   (+`startScan`, same fail-closed answer Android gives). Boundary body extracted to
   `onEpochBoundary()` so the two actuals can be read side by side, which is the only review that
   ever catches radio drift.
ALSO: killed the "starting a scan is enough to make the device running" comment in
`BleRadio.android.kt` (both `syncEpochTickerLocked()` calls in start/stopScan are no-ops under the
corrected predicate and now say so); `SpikeController.stop()` claims the run under `lock`
symmetrically with `start()`; `RadiusTheme` maps `inversePrimary` = `accent.radar.wash` (signal/600,
5.20:1 on inverseSurface — the ONLY teal stop that clears AA there; signal/400 measures 1.84:1) and
dropped a `@Suppress("UNUSED_PARAMETER")` that was simply false.
INVERSEPRIMARY CAVEAT, owed to design-system: that pairing is NOT one of generate.mjs's 48 verified
combinations, so it is hand-verified, not build-enforced. Proper fix is an `accent/*/onInverse` role
in tokens.json. Also: we are using a token whose designed ROLE is a wash BACKGROUND as a FOREGROUND.
VERIFICATION LIMIT, stated plainly: `iosMain`/`iosTest` are still compiled by NOBODY (B4). Fix 1 and
Fix 4 are both in that half. I ran a nesting-aware comment/brace/paren scanner over every .kt/.kts
under mobile/ — ALL BALANCED — but a balanced-delimiter scan is not a type-check. `NSRecursiveLock`
binding, inline non-local returns and expect/actual shape are OWED to the first macOS CI job.
STILL TRUE: no BLE has run on real hardware. No battery number exists. Nothing here is a spike result.
2026-08-05 devops-tencent · B13 gate-comment-nesting WIRED. fast-gates, always blocking, all 4
  workflow files + run-stage.sh case + STAGES. 3.3s against the full repo, awk + sh, no toolchain.
  Added `awk` as an explicit runner requirement — it is the one gate dependency that is not
  coreutils, and it is present on every Linux image and in Git Bash, so this is documentation not a
  provisioning change.
  TRIAGE NOTE ADDED, and it runs OPPOSITE to the rest of README §5b: every other row there answers
  "runner or code?". This one is CODE UNTIL PROVEN OTHERWISE. The scanner's documented gaps all fail
  toward FALSE POSITIVE, never toward hiding an unclosed comment, so a red result is a claim worth
  believing; and the underlying defect makes the compiler report A DIFFERENT PROBLEM IN A DIFFERENT
  PLACE (build.gradle.kts: whole `dependencies {}` block swallowed, Gradle reported a missing Hilt
  dependency, zero syntax errors). Also pinned: the gate deliberately does NOT exclude test sources
  — the live violation was in iosTest, the one source set nobody has ever compiled — so a later
  "tidy the exclusions to match the other pattern gates" would silently undo the catch. Written into
  run-stage.sh next to the case, not only the README, because that is where someone tidying looks.
  FULL SWEEP, 16 stages, all green on Windows: 6 fast gates + build-unit-test/lint/assembleDebug +
  build-manifests + gate-permission + conformance (116 vectors) exit 0; known-red pair 0 push /
  1 strict. Self-tests 95/95, 9 gates. The three :android stages that were RED last round are green
  again — f91bdd0 removed the token shims, so that cross-agent handoff closed itself.
  4 YAMLs parse; every stage name in every workflow resolves against the table, checked mechanically.
  UNCHANGED AND STILL THE HEADLINE: NOTHING HAS EVER RUN IN CI. Not once, on any provider.
2026-08-05 android-kotlin · inversePrimary CAVEAT CLOSED, and the caveat's whole CATEGORY got a
  name. design-system shipped `accent.<mode>.onInverse` (gate 48 -> 51 pairings, all PASS, 0
  regressions). `RadiusAccent` grew a fourth field, `inversePrimary = colors.accent.radar.onInverse`.
  ZERO VISUAL DIFF — signal/600 either way — the change is that the build now watches it. The line
  in the entry above ("hand-verified, not build-enforced") is now stale; that is the point.
  ALSO WIRED: `surfaceDim`/`surfaceBright` -> `surface.canvas`/`surface.modal`. They were the LAST
  instance of the trap: M3 pairs both with `onSurface`, which IS mapped, so a verified foreground
  would have landed on an unverified M3 baseline background and NO GATE WOULD FIRE, because the
  foreground half passes on its own. design-system refused to compute a ratio for them rather than
  guess an M3 baseline hex — mapping to our own surfaces makes the question disappear instead of
  answering it wrong. Not `surface.sunken`, though it is darker: content.primary-on-sunken is not
  one of the 51, so that trades an unwatched background for an unwatched pairing.
  KNOWN GAPS list now CLASSIFIES rather than lists: unmapped-and-safe (errorContainer/
  onErrorContainer — matched M3 pair, safe ONLY while no call site mixes one half with a mapped
  token; scrim — no `onScrim` role exists) vs unmapped-and-unwatched (the dangerous one, now empty).
  NEW, RAISED NOT FIXED: `surfaceContainerLowest = surface.sunken` is MAPPED but its pairing with
  content.primary is not among the 51. Not a risk — sunken is ink/1000, strictly darker than
  canvas's gated 18.36:1, and contrast is monotone in background luminance — but the argument lives
  in a comment instead of the gate. Ask design-system for the check.
  REFUSAL RECORDED, do not go looking: there is NO `accent.threads.onInverse`. Threads is one
  borrowed ink/200 stop, not a ramp, so any ink step clearing AA would be generic dark-neutral text
  wearing an accent role's name. `$onInverseRefused` in tokens.json.
  TESTABILITY CHANGE WORTH KNOWING: the M3 slot map moved out of the `RadiusTheme` composable into
  `internal fun radiusMaterialColorScheme(colors)`. Every defect this file has had (`outline` at
  1.14:1, `inversePrimary` baseline purple, surfaceDim) was a SLOT-ASSIGNMENT bug — invisible to
  generate.mjs and to a screenshot, and previously unreachable from a unit test. 5 new tests assert
  slots directly. HONEST LIMIT: `onInverse` and `wash` are the same primitive today, so a revert to
  `wash` still passes the role assertion; it catches every OTHER wrong answer, and gains teeth the
  moment the roles diverge. A canary test documents the coincidence and says to DELETE it when it
  fails rather than re-alias.
  GREEN: assembleDebug + testDebugUnitTest (57 tests, 0 fail) + lintDebug (0 errors, 51 warnings,
  all pre-existing: Gradle version nags, unused resources, one DefaultLocale in the debug-only
  SpikeScreen). STILL TRUE, UNCHANGED BY ANY OF THIS: no BLE on real hardware, no battery number,
  no device has run this theme.
