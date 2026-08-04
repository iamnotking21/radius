# Phase 0 Spike Test Matrix — Android scope

**Owner:** qa-test (`**/tests/` carve-out, root `CLAUDE.md` REPO MAP) · **Status:** matrix defined,
**zero cells executed** — no hardware exists yet. This document is the plan the founder buys
equipment against, not a report of results.

**Binds to:** root `CLAUDE.md` PHASE 0 SPIKE MATRIX + HARDWARE RIG, decision 33 (Android-first),
decision 37 (RPA bijection pass criterion), `mobile/protocol/KEY_SCHEDULE.md` §4.3 + §5,
`mobile/protocol/SPEC.md` §5.0 + §11, `.claude/memory/60-blockers.md` B8 (top technical risk).

---

## READ THIS FIRST — the rule that outranks every row below

> **BLE TESTED ON REAL HARDWARE ONLY. A simulator or emulator BLE result is NOT a pass, and MUST
> NEVER be recorded as one — anywhere in this document, in a PR, in a standup, or in the founder
> update.** `mobile/CLAUDE.md`: "simulator BLE result = invalid, never claim pass." This is repeated
> here, at the top, in the one document someone under ship-date pressure is most likely to skim,
> because that is exactly the condition under which "it passed the emulator, ship it" gets said out
> loud and believed. It has never once been true for this protocol and it will not become true later.
> A JVM/Robolectric unit-test pass on `mobile/shared/src/commonMain` codec logic is a DIFFERENT,
> LEGITIMATE thing (arithmetic conformance — see `devops/ci/gates/conformance_gate.sh`) and is not
> what this sentence is about. This sentence is about the radio: discovery, RSSI, RPA rotation,
> battery, timing. None of that exists in a simulator because a simulator has no radio.

Second rule, same weight: **if a row below cannot currently be executed (no device, no sniffer, no
harness), it is UNMEASURED, not PASS.** Nothing in this document may be marked green without a
capture or a measurement behind it. See `devops/ci/gates/battery_gate.sh` for the same principle
applied mechanically in CI.

---

## 0. Scope

**Android only** (decision 33). Every iOS cell is marked `DEFERRED`, per `SPEC.md` §5.0's own
convention: `DEFERRED ≠ UNMEASURED`. Deferred is a deliberate scheduling choice, not an unknown risk,
and must not be reported as one. It reverts to `UNMEASURED` the day iOS returns to scope — that
reversion is not automatic and someone must re-open it explicitly then.

The single number that decides whether this whole approach is viable is **not** in this document's
main tables — it is `KEY_SCHEDULE.md` §4.3's RPA co-rotation bijection test, Priority 0 below. Every
other row is compatibility detail by comparison. `SPEC.md` §11 says it in one sentence: *"RPA
co-rotation... outranks battery, latency, and the iOS emit problem, because those three cost us
features and this one costs us the reason the product is allowed to exist."*

---

## PRIORITY 0 — RPA co-rotation (B8, the top technical risk in the project)

**This runs first. Nothing else in this document is worth running until this either passes on
enough hardware or the project has a documented excluded-device list.**

### 0.1 What is being tested, precisely

Not "does the MAC address change" — that formulation passes on hardware that is actually broken (see
0.3). The real question, verbatim from `KEY_SCHEDULE.md` §4.3.1:

> Over a capture window spanning ≥6 consecutive epoch boundaries, is the mapping between observed
> advertiser addresses and observed `ephemeral_id`s a **bijection**? No `AdvA` is ever seen with two
> different eids, and no eid is ever seen with two different `AdvA`s.

This is decision 37: **the pass criterion is a bijection over a capture, not "the address changes."**
A single violation in either direction is the §4.1 bridging attack (an observer who sees one MAC
alongside two eids, or one eid alongside two MACs, has just re-identified the device across a
rotation the whole architecture depends on being unlinkable) — one packet is enough, there is no
"mostly passes."

### 0.2 The phase-offset trap — the specific reason a casual test lies

BLE's **default RPA timeout is 900 seconds — exactly our 15-minute epoch.** If a controller rotates
its own address on that same 900 s period but **out of phase** with our epoch boundary, a test that
only asks "does the address change, and how often" reports success, because the address does change
every ~15 minutes. It is a **permanent** bridge anyway: at every one of our boundaries, the address
has not yet rotated (or has already rotated early), so the old address is observed alongside the new
eid (or vice versa) at every single boundary, forever, for as long as the device is watched. This is
`KEY_SCHEDULE.md` §4.3.4's `PHASE-OFFSET` verdict, and it is called out as **the outcome people are
least prepared for** — a same-period-different-phase result is the most likely way a well-intentioned
"it rotates fine!" report gets filed while the bug is still live. The bijection test (0.1) catches
it. A period-only test does not. **Do not let anyone substitute a period-only test for this reason.**

If `stop → start` advertising forces an immediate fresh address, the phase resets and the verdict
upgrades to `CO-ROTATES` — this must be tested explicitly (full restart cycle inside the capture
window), not assumed.

### 0.3 The pre-test that costs one packet — run before anything else, per model

Read the advertiser address type from a single captured PDU (two MSBs of the first address byte):

| Bits | Meaning | Verdict |
|---|---|---|
| `0b01xxxxxx` | Resolvable Private Address | only acceptable value — proceed to full capture |
| `0b11xxxxxx` | Static random | **FAIL, stop.** `NOT-RPA` — model excluded from advertising, no exception |
| `TxAdd=0` | Public device address | **CATASTROPHIC FAIL, stop.** Globally unique, permanent |
| `0b00xxxxxx` | Non-resolvable private | rotates, acceptable — proceed, record the type |

One packet, ~5 minutes including setup. **Run this on every model before spending 90 minutes on it.**

### 0.4 Hardware — what the sniffer actually needs to be, and the trap in the cheap version

| Option | Cost (approx) | Verdict |
|---|---|---|
| **3× nRF52840 USB dongle**, one pinned per primary advertising channel (37/38/39), nRF Sniffer for Bluetooth LE → Wireshark, captures merged by timestamp | **~£30 / ~US$40 total (3 units)** | **RECOMMENDED — this is the shopping-list item.** |
| 1× nRF52840 dongle | ~£10 | **NOT SUFFICIENT. Do not buy only one for the full protocol.** Usable only for the §0.3 one-packet screen. |
| TI CC1352 (CatSniffer / Sonoff) + Sniffle | ~£25 | Acceptable alternative — confirm all-3-channel coverage before trusting a negative result. |
| Ellisys / Teledyne Sodera | £thousands | Overkill for this question. Do not let procurement lead time on this become the blocker. |

**Why three dongles, not one, decision 37's hardware clause:** a single-channel sniffer sees roughly
a third of advertising events. This test is an assertion of **absence** (no packet ever bridges an
old address to a new eid) and a missed packet is indistinguishable from an absent one on one channel.
**A single-channel "no overlap observed" result is not evidence of no overlap** — it is the cheapest
possible way to record a false pass on the highest-severity question in the protocol. Three dongles
(£30) remove the ambiguity. This is not a nice-to-have tier; it is the minimum credible rig.

No device-side instrumentation, no debug build, no rooted phone: the eid travels in the Service Data
of the same PDU as the advertiser address, so `(t, AdvA, AdvA_type, eid)` comes straight out of the
capture. Filter Wireshark on the 16-bit service UUID.

**Optional throughput accelerant, not required:** a second 3-dongle kit (+£30, 6 dongles total) lets
two device-models be captured in parallel instead of sequentially, roughly halving elapsed calendar
time across the device matrix (§2). Cheap enough that it is worth buying up front rather than
discovering the need for it after the first week of sequential captures.

### 0.5 Capture protocol (per model, per condition) — reference, not reinvented

Full protocol is `KEY_SCHEDULE.md` §5.2–§5.3. Summary, with the parts most likely to be shortcut
under time pressure called out:

1. §0.3 address-type screen. Stop if `NOT-RPA`.
2. Advertise Carrier A on the **real, uncompressed 15-minute UTC epoch schedule.** Do not use a
   compressed test epoch — the phase-offset interaction with the controller's own ~900 s timer (§0.2)
   is the thing being measured, and shortening the period changes the measurement.
3. Capture **≥ 90 minutes**, spanning **≥ 6 consecutive epoch boundaries.**
4. **Five conditions, captured separately, each a full 90-minute run:**
   - foreground, screen on
   - foreground, screen off
   - background, under a foreground service
   - after Doze entry
   - after a Bluetooth stack restart (BT off → on)

   The last two are where OEM power management is most likely to silently change controller
   behaviour, and are the ones most likely to be skipped when time is short. Do not skip them.
5. Pass criterion (`KEY_SCHEDULE.md` §5.3, C1–C5): address-type legal, eid↔AdvA bijection holds, ≥6
   distinct eids observed, packet coverage within 5s of every boundary, ≥60% expected packet yield.
   **A capture failing the coverage/yield checks (C4/C5) is VOID — re-run, do not report it either
   way.**
6. Re-run after any OS update touching the Bluetooth stack.

**Human time per model, full 5-condition protocol:** 5 × 90 min capture ≈ 7.5 hrs of capture time,
plus setup/teardown/Wireshark merge/analysis per condition (~30–45 min each) ⇒ **realistically a full
working day (~8 hrs) per device-model for the first unit of a pair.**

**qa-test's proposed sequencing for the second unit of a pair** (not specified by `KEY_SCHEDULE.md` —
flagged as a proposal, not a pre-decided rule, open to override): run the full 5-condition protocol on
unit 1. If it passes cleanly, run only the two highest-failure-likelihood conditions (foreground
screen-off, background-under-FGS) on unit 2 to check for per-unit firmware inconsistency
(`KEY_SCHEDULE.md` §4.3.4 `INCONSISTENT`); escalate to the full 5 conditions on unit 2 only if either
of those two diverges from unit 1. This roughly halves unit-2 cost (~3 hrs instead of ~8) without
giving up the inconsistency check the spec requires. **If this trade is unacceptable, run the full
protocol on both units — that is the literal reading of §4.3.3 and is always the safe default.**

### 0.6 Decision rules — reference `KEY_SCHEDULE.md` §4.3.4, do not reinvent

Every verdict (`CO-ROTATES`, `CO-ROTATES-FG-ONLY`, `SLOWER`, `PHASE-OFFSET`, `NO-ROTATION`,
`NOT-RPA`, `INCONSISTENT`) and its pre-decided consequence is defined there. Three worth restating
because they are easy to get wrong under pressure:

- **`SLOWER` on any model we intend to ship advertising on forces a GLOBAL epoch change**, capped at
  60 minutes — never a per-model epoch (§4.3.5, and this follows directly from §3's no-jitter rule:
  a device on its own schedule is a population of one at every boundary, which is the exact thing
  synchronised rotation exists to prevent).
- **`INCONSISTENT` (units of the same model disagree) takes the WORST result, never an average and
  never the best.** Add OS build as a table dimension when this happens.
- **Aggregate go/no-go threshold, proposed in `KEY_SCHEDULE.md` §4.3.6 and not yet founder-set:** if
  models verdicted `CO-ROTATES`/`CO-ROTATES-FG-ONLY` cover **< 80% of the intended install base**,
  this escalates to a go/no-go conversation, not a compatibility footnote. **This number must be
  confirmed by the founder before the first capture, not after** — set it now, per that document's
  own reasoning, so it is not chosen to fit whatever the data turns out to say.

---

## PRIORITY 1 — the four Android×Android cells of `SPEC.md` §5.0

Once Priority 0 has run on at least the first device pair, these run using the **same physical
fleet** (§2) — no separate hardware purchase.

| Emitter | Scanner | Status | Predicted |
|---|---|---|---|
| Android fg | Android fg | **UNMEASURED — first-class, on the critical path** | YES |
| Android fg | Android bg (needs FGS) | **UNMEASURED — first-class, on the critical path** | YES |
| Android bg | Android fg | **UNMEASURED — first-class, on the critical path** | YES |
| Android bg | Android bg (needs FGS) | **UNMEASURED — first-class, on the critical path** | YES |

All twelve iOS-involving cells of the same matrix: **DEFERRED** (decision 33), not unknown risk.
**Android → backgrounded-iOS is the cell root `CLAUDE.md` names as the number that kills the project
if bad — it is DEFERRED under the current plan, `SPEC.md` §5.0 already predicts it hard-`NO`
(Apple's overflow area is unparseable by Android, a permanent platform hole, not fixable by us), and
it returns as priority-0-again the day iOS re-enters scope.** Do not let its deferral read as
resolved-favourably; it is scheduled-away, and the prediction sitting behind that deferral is bad.

### Required measurements, per cell (`SPEC.md` §5.0, verbatim requirement)

- acquisition success rate over a 10-minute window
- discovery latency, p50 and p95
- battery %/hr on **both** ends (emitter and scanner) — feeds `devops/ci/gates/battery_gate.sh`
  once real numbers exist; this document is where those numbers get produced
- behaviour at 1, 5, 15, and 30 **concurrent peers**
- whether the emitter's MAC and `ephemeral_id` rotate together, **observed on the external sniffer**
  (this is Priority 0's measurement, reused here, not a separate exercise)

### The concurrent-peer density problem, and a cheap way to actually test it

1/5/15/30 concurrent peers cannot be measured with a ~10-device Phase 0 fleet (§2) alone without
either borrowing real handsets or a cheaper substitute. **qa-test's proposal:** flash 15–30 spare
nRF52840 dongles (the same part as the sniffer, ~£10/unit off a bulk order) as **dumb advertisers**
broadcasting valid-shaped-but-inert 19-byte frames, to simulate crowd density cheaply at the RADIO
layer. This validates the scanner/radio's raw capacity to keep up with N concurrent advertisers — it
does **not** validate full app-level behaviour (duty cycling, OS backgrounding, banding under load),
because a dongle is not running our app. State that distinction explicitly in any report using this
method: it is a radio-capacity proxy, not an end-to-end density test. **Shopping list impact: +£150–
300 for 15–30 dongles, separate from the 3 sniffer dongles in §0.4** — optional, but the only
affordable way to hit the 15/30-peer points in `SPEC.md`'s own required measurement list.

### App-level instrumentation this needs, and does not yet exist

None of the above (discovery latency, acquisition rate, battery) can be measured without an
app-level telemetry harness recording discovery-event timestamps and battery drain over a timed run.
**This does not exist yet** — it is a dependency on android-kotlin's / ble-protocol's implementation
work, not something qa-test can build without their radio/codec landing first. Flagged as a HANDOFF:
the moment the radio produces real `onSighting` events, qa-test needs a hook to log
`(timestamp, band, epoch)` locally during a rig run (never raw eid/RSSI off-device — decision 28,
P1 in `KEY_SCHEDULE.md` §10.3 — this stays a local, on-device-only diagnostic artifact for the human
running the rig, never uploaded).

---

## 2. Device matrix — chipset vendor coverage, ordering, and the shopping list

Order and reasoning straight from `KEY_SCHEDULE.md` §4.3.3: **budget MediaTek devices go in the
FIRST batch, not the second.** The instinct to test flagships first is backwards — flagships are the
devices most likely to pass, and MediaTek-class budget controllers are the least documented and the
most likely to fail, while also being a large share of the target-market install base.

**Minimum sample per `KEY_SCHEDULE.md` §4.3.3: two physical units per (OEM, SoC) pair.** A single
unit cannot distinguish "this model always does X" from "this specific handset does X" (possible
per-unit firmware/radio variance) — this is not a nice-to-have, it is the documented minimum.

| Priority | Category | Why first / why included | Qty (2 per pair) | Notes for procurement |
|---|---|---|---|---|
| 1 | MediaTek budget (Transsion/Infinix/Tecno, Realme, low-tier Xiaomi) | Highest risk, least-documented BT controllers, large install-base share in target SEA/launch markets | **2–4** (1–2 distinct models × 2 units) | Pick current, regionally-available SKUs at time of purchase — exact model numbers age out fast in this tier; category match matters more than a specific pinned SKU |
| 2 | Samsung, Exynos variant | Same marketing name, different controller than the Snapdragon variant of the same phone — must be tested separately | **2** | Confirm Exynos vs Snapdragon by exact model/region before buying — Samsung ships both under one name in different markets |
| 2 | Samsung, Snapdragon variant | ” | **2** | ” |
| 3 | Xiaomi / Oppo / Vivo mid-tier (Snapdragon) | Broad mid-tier coverage | **2** minimum (start with one brand, expand if time allows) | |
| 4 | Google Pixel (Tensor) | **Control / reference**, least representative of the real install base — do not over-weight a pass here | **2** | |
| — | **Sniffer kit** | Priority 0, §0.4 | **3× nRF52840 (~£30)**, optionally +3 more for parallel capture (+£30) | |
| — | **Density-simulator dongles (qa-test proposal, §1)** | Concurrent-peer measurement | **15–30× nRF52840 (~£150–300)** | Optional; skip if budget-constrained and accept 1/5-peer-only coverage for Phase 0 |

**Minimum §4.3.3-compliant fleet total: 10 handsets.** (4 MediaTek + 4 Samsung split Exynos/Snapdragon
+ 2 mid-tier — the table above shows a slightly flexible 8–10 depending on how many MediaTek/mid-tier
models are split; treat 10 as the floor, not the ceiling.)

**Total Phase 0 hardware shopping list (going to the founder):**

| Item | Qty | Approx cost | Purpose |
|---|---|---|---|
| Android handsets (per table above) | 10 | market-rate, budget-tier where possible except the 2 controls | device-model coverage, §2 |
| nRF52840 USB dongle (sniffer, ch 37/38/39) | 3 | ~£30 total | Priority 0 bijection capture |
| nRF52840 USB dongle (sniffer, 2nd kit — optional, parallel capture) | 3 | ~£30 total | halves elapsed capture time |
| nRF52840 USB dongle (density simulator — optional) | 15–30 | ~£150–300 | concurrent-peer radio-capacity proxy, §1 |
| Wireshark + nRF Sniffer for BLE (software) | — | free | capture analysis |
| A laptop/PC to run Wireshark, USB hub for 3+ dongles | 1 (assume already available) | — | flag if not already available |

**Human time budget, rough order of magnitude, not a schedule commitment:** Priority 0 full protocol
at ~1 day/device-model (first unit) + ~0.5 day (second unit, qa-test's shortcut) across 5 categories ≈
**7–8 working days of hands-on capture+analysis**, before Priority 1's app-level measurements (which
depend on a telemetry harness that does not exist yet — see above) even start. This is a floor, not a
ceiling; expect re-runs from VOID captures (§0.5 point 5) and OS-update re-tests.

---

## 3. Distance × state × environment × carry — reference, not reinvented

`KEY_SCHEDULE.md` §4.3/§5 do not parameterise the RPA bijection test by distance, indoor/outdoor, or
carry position — the bijection question is about address/eid pairing, not signal quality, so those
axes are **not required** for Priority 0. They matter for Priority 1's discovery-latency/RSSI/battery
measurements and for the standing hardware rig (below). Root `CLAUDE.md`'s full spike axes:

```
platform-pair: Android↔Android only (this phase) — iOS pairs DEFERRED
state:         fg · bg · screen-locked
distance:      1, 5, 20, 50, 100 m
env:           indoor · outdoor
carry:         hand · pocket · bag
```

Full cross product = 3 × 5 × 2 × 3 = 90 cells per device-model-pair for Priority 1's measurements.
**This is not run exhaustively per model.** qa-test's proposal (not pre-decided elsewhere, flagged as
a recommendation): run the full 90-cell grid once, on the Pixel control pair (§2), to characterise
expected-good-case behaviour and calibrate `tx_power_cal` (`SPEC.md` §3.2,
`mobile/protocol/calibration/tx_power_classes.json` — currently a placeholder). For every other
device pair, run a **reduced grid**: {fg, bg} × {1m, 20m, 100m} × {indoor} × {hand} — the 3 distance
points that separate HERE/AROUND/EDGE bands (`CLAUDE.md` HARD NUMBERS: HERE≥-55dBm, CLOSE≥-70,
AROUND≥-82, EDGE≥-95) — and escalate to the full grid only for a model that fails the RPA bijection
test's condition set or shows an unexpected result on the reduced grid. This keeps Priority 1 from
becoming a second multi-week project on top of Priority 0 while still exercising every model at the
points that actually separate the four bands.

**Verdict rules for these measurements are pre-decided in `KEY_SCHEDULE.md` §4.3.4 and `BANDING.md`**
(band thresholds, Kalman warmup/outlier-gate/hysteresis constants) — not reinvented here.

---

## 4. Standing hardware rig (P1) — pointer, not a separate purchase

`mobile/CLAUDE.md`: *"hardware rig: 6 devices fixed distances → discovery latency + RSSI + battery
→ Grafana. CI FAILS on regression."* This is a distinct, ongoing deliverable from the one-time Phase
0 spike above, but **reuses the same device fleet** (§2) rather than requiring a second purchase — 6
of the 10 Phase 0 handsets, kept at fixed measured distances with a controller that flashes each
build and runs a scripted scenario, become the permanent regression harness once Phase 0 clears. Full
rig design (controller software, Grafana dashboards, CI wiring) is a separate piece of work, not
specified further in this document; flagged here only so the founder's hardware purchase is sized
once, for both purposes, rather than twice.

---

## 5. Go/No-Go reporting format

Once Priority 0 has run, report exactly the table shape in `KEY_SCHEDULE.md` §4.3.3, one row per
`(model, OS build)`, with every column populated from a real capture:

`| Model | SoC/BT controller | OS build | addr type | fg bijection | bg bijection | observed RPA period | phase vs our boundary | verdict |`

Aggregate against the founder-set install-base threshold (§0.6). **No cell in that table may be
filled from anything other than a capture meeting `KEY_SCHEDULE.md` §5.3's C1–C5 pass criteria.** A
cell with no capture behind it stays `UNMEASURED` in the report, visibly, rather than being left
blank or inferred from a similar model.
