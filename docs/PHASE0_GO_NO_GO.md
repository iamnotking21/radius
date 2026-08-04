# Phase 0 — BLE Feasibility Spike · GO/NO-GO Memo

**Status:** ⏳ **PENDING DATA — no measurement has been taken.**
**Thresholds pre-committed:** 2026-08-04, before any hardware existed.
**Decision owner:** founder. **Evidence owner:** qa-test. **Protocol interpretation:** ble-protocol.

---

## Why this document exists before the data

Every threshold below was written down **before a single measurement was taken**. That is the entire point.

The predictable failure mode of a feasibility spike is not bad measurement. It is ambiguous measurement meeting a team that has already spent months and a founder who has already told people what they are building. At that moment, "62% of devices co-rotate" becomes a negotiation instead of a result. Somebody says the failing devices are old, or a niche market, or fixable in firmware, and the number quietly stops being a threshold and becomes an opening bid.

Pre-registering the criteria removes that conversation. When the data arrives, the only question is which pre-written row it lands in.

**Rule: no threshold in this document may be edited after measurement begins.** If a threshold turns out to be wrong, that is a finding — record it, state why, and take the consequence. Changing the bar to fit the data is how a spike stops being a spike.

---

## What Phase 0 is actually asking

Not "does BLE work". BLE works.

The question is narrower and harder:

> **Can we discover nearby people, reliably enough to feel magical, on real phones people already own, without draining their battery, and without ever making them trackable?**

Four clauses. Any one failing kills the moat, and they fail in different ways.

---

## P0 · The blocking question — RPA co-rotation (B8)

**Measure this first. It is the only question that cannot be designed around after the fact.**

Safety invariant 5 requires the ephemeral ID and the MAC address to rotate together — both or neither. Neither Android nor iOS lets an app control RPA rotation; on Android it is controller-firmware behaviour. So invariant 5 is a **per-device-model property**, not a per-platform one (decision 30).

**Pass criterion: a bijection between advertiser address and ephemeral ID across the capture** (decision 37). Not "the address changes". A one-to-many or many-to-one mapping in either direction is a linkability bridge.

Two traps, both pre-identified:

- **PHASE-OFFSET.** The BLE default RPA timeout is 900 s — identical to our epoch. Same period, wrong phase, is a *permanent* bridge that a period-only test scores as a pass. Must be tested explicitly by checking whether `stop→start` resets phase.
- **The on-device screen cannot declare a pass.** Android never exposes the TxAdd bit, so a public address with an OUI in `0x40`-`0x7F` reads as resolvable-private — meaning the catastrophic case passes the harness screen. **Non-zero is real evidence of failure; zero is not evidence of success.** Only a 3× nRF52840 capture, one dongle pinned per advertising channel, can assert absence.

| Result across the intended install base | Verdict |
|---|---|
| ≥95% of tested models show a clean bijection | **GO** on invariant 5 |
| 80-95% co-rotate | **GO with an exclusion list.** Failing models ship **scan-only** — they see, they are not seen — and are *told so in the Radar UI*. Never silently degraded. |
| <80% co-rotate | **ESCALATE TO FOUNDER as a probable NO-GO.** Not an automatic no-go, but the offline moat is not deliverable on Android as designed, and the product needs rescoping before any further build. |
| Any model rotates the eid against a **fixed** MAC | **NO-GO for that model, no exceptions.** Decision 30: this is worse than not rotating — it merely looks like privacy. |

**Pre-committed corollary:** if a model rotates its MAC *slower* than 15 minutes, the fix is to **lengthen the global epoch** (capped at 60 min), never to shorten it per-model. Decision 27 forbids per-model rotation periods — staggering collapses the anonymity set.

---

## P1 · Battery

Contract: **<4%/hr scanning, <1%/day idle.** CI-gated once a rig exists.

Note before reading any number: the scaffold originally used `SCAN_MODE_LOW_LATENCY` — 4096/4096, **100% duty against a contracted 30%** (decision 44). Any battery figure taken before that fix is meaningless. It is now `SCAN_MODE_BALANCED` (25% on paper), and several vendors ship their own windows regardless.

| Result | Verdict |
|---|---|
| ≤4%/hr on median hardware | **GO** |
| 4-6%/hr | **CONDITIONAL** — adaptive duty cycling must close the gap before P1 exit. Re-measure, do not assume. |
| >6%/hr | **NO-GO as designed.** Uninstall risk (R4) dominates. Radar becomes an explicitly short-session foreground mode, or the duty model is rebuilt. |

---

## P2 · Discovery latency

Target: **<5 s foreground.** Background target is deliberately not a gate on Android-first — see scope note below.

| Result (p50 / p95, foreground, 2 devices, indoor, ~5 m) | Verdict |
|---|---|
| p50 ≤5 s and p95 ≤15 s | **GO** |
| p50 ≤10 s | **CONDITIONAL** — acceptable only if the Radar UI honestly communicates scanning-in-progress rather than implying instant presence |
| p50 >10 s | **NO-GO as designed.** "Someone is here right now" is not deliverable; the feature becomes a log, not a radar. |

---

## P3 · Band accuracy

Four bands: HERE ≥-55, CLOSE ≥-70, AROUND ≥-82, EDGE ≥-95 dBm, with hysteresis.

| Result | Verdict |
|---|---|
| Correct band ≥80% of samples at known distances, ≤1 band error otherwise | **GO** |
| Frequent 2-band errors | **CONDITIONAL** — reduce to three bands or two. Fewer honest bands beat four dishonest ones. |
| No stable mapping between RSSI and distance indoors | **Bands become qualitative only** ("nearby" / "in range"). Not a NO-GO for the product, but a NO-GO for any distance-suggesting copy. |

---

## Scope note — what this memo does *not* cover

**iOS is deferred** (founder decision, 2026-08-04). Twelve of the sixteen emission/acquisition cells in `SPEC.md` §5.0 are DEFERRED, not unmeasured — they are not unknown risk, they are unexamined scope.

Two consequences, recorded now so they are not rediscovered as surprises:

1. **Carrier B (GATT-pull) is exercised not at all** by an Android-only spike. Its cost — a connection per peer per epoch, and the fact that it makes the *scanner* transmit — returns unpriced the day iOS comes back.
2. **A GO here is a GO for Android only.** It says nothing about whether the product works on iPhone, and iPhone is roughly half the market in most launch geographies. Do not read an Android GO as a v1 GO.

---

## The product question that survives either verdict

`ble-protocol`'s standing assessment, recorded before measurement:

> "Open Radar when you're out" survives. **"Radius quietly notices people near you all day" does not.**

iOS cannot emit service or manufacturer data in any state, and Android background advertising is OEM-dependent. Even a clean GO on every threshold above leaves Radar a **foreground-first** feature.

**This is a positioning question, not an engineering one, and it needs answering regardless of the verdict.** If the business case depends on ambient all-day discovery, that case needs revising now — not after launch, and not in the marketing copy. `CLAUDE.md` commits us to selling honesty; that commitment binds the pitch as much as the paywall.

---

## Evidence required before this memo can be completed

None of the below exists yet. All require hardware.

- [ ] RPA bijection capture, 3× nRF52840, one per advertising channel, ≥90 min per model
- [ ] ≥10 Android models, 2 units each, budget MediaTek in the **first** batch
- [ ] Battery: full-charge-to-20% scanning runs, post-scan-mode-fix only
- [ ] Discovery latency p50/p95: distance × foreground/background × indoor/outdoor
- [ ] `tx_power_cal` per model — currently a placeholder marked UNCALIBRATED
- [ ] Peer density behaviour at 1 / 5 / 15 / 30 concurrent advertisers
- [ ] OEM background survival (aggressive killers: Xiaomi, Huawei, Oppo, Vivo, Samsung)

Estimated: **~7-8 working days of hands-on capture for P0 alone.**

---

## Verdict

**NOT YET REACHED.** Anyone citing this document as evidence of feasibility is citing an empty results table.

A green conformance-vector run means the codec is arithmetically correct. It says nothing about the air, and it must never be reported as spike validation.
