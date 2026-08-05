# Claims Register

**Purpose:** every factual claim our public privacy documents make about our system, paired with the thing that makes it true and the event that would silently make it false.

**Why this exists.** A privacy policy does not rot because someone lies. It rots because a claim was true when written, an engineer shipped a feature two months later, and nobody re-read the policy. The claim is now false and no one has noticed. That is the normal case, not the exceptional one.

The fix is to make the **build go red when a claim expires** — the same pattern as the CI known-red gates, which fail on good news so a stale tolerance cannot outlive its cause.

**Rule:** adding a claim to a public document means adding a row here. A row with `Enforced by: nothing` is a claim held up by memory alone.

---

## Legend

| Class | Meaning |
|---|---|
| **VERIFIED** | True, and a test, gate, or inspected artifact makes it true |
| **DESIGN** | True only because the thing does not exist yet. Marked `[NOT YET BUILT]` in the document. |
| **UNMEASURED** | Depends on a measurement not yet taken |

---

## A. Claims about the mobile apps

| # | Claim | Class | Enforced by | Falsified when |
|---|---|---|---|---|
| A1 | Displayed distance is never derived from signal strength | **VERIFIED** | `DisplayJitter.metresFor(salt, peerId, band)`, seeded by HMAC with no RSSI input; pinned by `display_jitter.json` incl. an 18 dB-spread independence case | Someone adds an RSSI argument to the jitter path |
| A2 | Raw RSSI never leaves the device in a distributed build | **VERIFIED** (shipped builds) | `BleRadio.kt` `toString` redaction + inverted `RawSightingTest` | A crash reporter, analytics call, log line, or serializer field carries `rssiDbm`. **No CI gate exists — see gap G1** |
| A3 | The broadcast payload carries no stable identifier of ours | **VERIFIED** | 19-byte frame, `Frame.kt`; 116 conformance vectors; reserved bits rejected not ignored | A field is added to the frame |
| A4 | The app requests no network permission | **VERIFIED** | Merged manifest, both variants, zero `INTERNET` | **The API client ships.** This is scheduled, not hypothetical — see gap G2 |
| A5 | No map, bearing, or coordinate code in the mobile apps | **VERIFIED** | `no_map_no_bearing_gate.sh`, blocking in CI | Gate scope is `mobile/` only — see gap G3 |
| A6 | Exactly one device per account can broadcast | **VERIFIED** | `AdvertiseGuard`, one shared pure function; role defaults `SCAN_ONLY`, fail-closed; `AdvertiseGuardTest` | The default flips, or a second grant path appears |
| A7 | Superseded keys are destroyed on the device | **PARTIAL** | `pruneSupersededAt` implemented + vector-pinned. **Only production caller is the debug spike harness** | Already limited: no product path drives it, and it requires the radio to be running |
| A8 | Ghost mode is one tap and takes effect | **DESIGN** | UI control exists, whole-row target, no confirm dialog. **Not connected to the radio** | Already true-only-vacuously: nothing broadcasts yet |
| A9 | Rotating IDs prevent a nearby scanner tracking you | **UNMEASURED** | Depends on **B8**. Android hides the TxAdd bit, so a fixed address is indistinguishable from a rotating one on-device | If B8 returns below 100%, false for the failing models unless the scan-only exclusion list is built and enforced |

## B. Claims about the backend

Every row is **DESIGN**. `backend/` contains one file — a memory document. There is no server, no schema, no migration, no Go.

| # | Claim | Falsified when |
|---|---|---|
| B1 | No coordinates stored finer than a geohash-5 cell | First `.sql` or `.proto` lands. **The invariant-1 gate does not scan `backend/`** — gap G3 |
| B2 | No call-content column exists | First migration lands. Becomes checkable — then either verified or falsified |
| B3 | Messages are E2EE; we hold only ciphertext | Messaging ships. No crypto exists today beyond HKDF/HMAC |
| B4 | Break-glass access is logged, alerted, attributable | **Publication-gated.** No key store, no audit table, no alerting, no procedure |
| B5 | Deleted accounts are removed from backups | **No deletion pipeline designed.** Continuous WAL plus off-site copies makes this hard, not automatic |
| B6 | Block is enforced server-side at key resolution | `RadarController.block()` is an unimplemented interface method |

## B2. Claims made by the PAYWALL — added 2026-08-05 after the ADR-006 audit

A paywall is a claims surface, and this one asserts seven facts with **no source of truth anywhere in the repo.** `backend/` holds a single memory file: no price list, no tier feature matrix, no free-tier cap. Getting one of these wrong is a refund, a chargeback, and a store review — not a copy edit.

| # | Claim (Figma page F) | Class | Falsified when |
|---|---|---|---|
| P1 | "MOST CHOSEN" on Gold | **UNBACKED** | The moment the tier mix shifts. Must be a live query — plurality by ≥5pp over ≥100 subscriptions, trailing 30d, recomputed daily, **fail-closed**. At launch, with zero subscribers, it is false by construction. |
| P2 | "SAVE 33% / SAVE 50%" | **UNCHECKABLE** | No total price appears on the screen, so the arithmetic cannot be verified at all. Hardcoding it is worse than getting it wrong: it goes false in every storefront the day a price changes in App Store Connect, with no code change for anyone to notice. |
| P3 | "$2.49 each / $29.99" and "$4.99 each / $14.99" | **WRONG NOW** | Already false: 12 × 2.49 = $29.88, not $29.99. Both packs truncate rather than round, so both **understate** the real per-item price. Trivial in cents; the tell is that a human typed a figure a machine should have divided. |
| P4 | "12 comments a day" | **UNSOURCED, and self-contradictory** | Three F screens already disagree about what Plus includes. No repo file states the free allowance. |
| P5 | "You are being shown to more people right now" (Boost) | **FALSE FOR RADAR** | Radar has no server in the loop — that is the moat. There is no queue to move to the front of. Boost must be Discover-only. |
| P6 | "Reveal hidden profiles nearby" (Beacon) | **FALSE OR UNSAFE** | See the ruling below. Both readings fail. |
| P7 | VIEWS / LIKES counters on Boost | **UNDEFINED** | "View" is not in the glossary and does not exist as a concept. It is also the exact shape of the banned invented "someone viewed you" signal. Defining it creates new data collection with a privacy-policy consequence — and if a Radar sighting ever counts as a view, it leaks proximity presence. |

### P6 — the finding worth reading twice

**"1 Hour Beacon — Reveal hidden profiles nearby — $8.99."** The glossary defines a beacon as *transmit-side* boosted visibility. This is *receive-side* copy. Both readings fail:

- **Literally:** "hidden profiles" means people who have hidden themselves. That sells a ghost-mode defeat — invariant 10 — and it cannot even work, since a phone that is not advertising cannot be revealed by anything we sell.
- **Charitably, and this is worse:** there are exactly two ways to boost BLE visibility. More advertising, which lands on the `<4%/hr` battery contract and the contracted intervals. Or **more TX power — which raises the RSSI a peer computes, and therefore shifts the band it displays.** That is a *paid distance lie*.

Register row A1 does not defend against this. **A1 stops *us* inferring distance from RSSI. It says nothing about us being paid to corrupt the RSSI upstream of the honest maths.** Decision 87 blocks the copy until `ble-protocol` rules on what a Beacon may physically be.

## C. Structural gaps — claims with nothing holding them up

| Gap | Missing | Consequence |
|---|---|---|
| **G1** | ~~No RSSI egress gate~~ **CLOSED 2026-08-05** | `rssi_egress_gate.sh`, 13 assertions, clean against the live repo. Keys on `rssi`, deliberately **not** `dbm` — `txPowerCalDbm` is a real deliberately-transmitted protocol field (invariant 4's txpower byte) and would false-positive forever. Catches: log/print calls, string interpolation *outside* a log call, `.sq` files, and any file carrying `@Serializable`/`@ProtoNumber`. `src/debug` excluded by design (the spike harness writes RSSI locally, with no network permission). **Wiring owed** — see below. |
| **G2** | ~~No merged-manifest permission gate~~ **CLOSED 2026-08-05** | `permission_gate.sh`, 10 assertions, reads real AGP output for both variants against a 9-item allowlist. `INTERNET`'s failure message names **Privacy §3b bound 1 and register row A4** by name, so whoever adds it learns what they invalidated. Found a real extra entry by reading the live manifest instead of trusting the requested list: AndroidX injects `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — signature-protected, self-scoped, zero capability — now allowlisted by pattern with the reasoning inline. **Wiring owed** — see below. |
| **G3** | ~~Invariant-1 gate scans `mobile/` only~~ **CLOSED 2026-08-05** | Widened to `backend/` (`.go`/`.sql`/`.proto`) and `website/` (`.ts`/`.tsx`/`.js`/`.jsx`), same pattern list, `geohash5` still allowed and coordinates still banned. 17 assertions; the 6 pre-existing mobile ones pass unmodified. Needed **no** runner change — the stage already calls the script with default args. Caught in testing: the bare-identifier patterns were case-sensitive, which is invisible in Kotlin/Swift lowerCamelCase but misses Go's idiomatic exported `Latitude float64` entirely. |

### Wiring — DONE, and observed green 2026-08-05

Both gates are wired into all four workflow files and **ran in CI run 6, both passing.** G1 sits in `fast-gates`; G2 runs in the android job behind a `build-manifests` stage that calls AGP's manifest-merge-only tasks (18 actionable tasks vs 86 for a full release build).

`build-manifests` is deliberately a **separate stage** from `gate-permission`: an AGP failure must report as a build failure, never as a permission violation. A permission-gate failure is the one message in this repo that should make someone stop and read a privacy paragraph, and diluting it would be expensive.

**These claims are now enforced, not merely written down.** The distinction below is kept because it applies to every future row.

### Original wiring note (retained — this is the failure mode the register exists to catch)

`gate-rssi-egress` slots into `fast-gates` with no complications.

`gate-permission` has a structural problem worth stating: it needs **both** merged manifests in the **same** job, and today `ci.yml` only runs `assembleDebug`, while `assembleRelease` happens in a separate job on a separate runner where those manifests are not visible. The cheap fix is AGP's manifest-merge-only tasks (`processDebugManifestForPackage` / `processReleaseManifestForPackage`) — no compile, no R8, no signing, just the step that produced the files — rather than a full release build on every push.

**Until wired, G1 and G2 exist but are not enforced.** That distinction is the reason this register exists.
| **G4** | ~~No crash-report scrubbing policy~~ **CLOSED 2026-08-05** | Written as `docs/TELEMETRY_SCRUBBING_POLICY.md`. Binding before any reporter, analytics SDK, or remote log sink is integrated. R6 is the non-obvious one: self-hosting GlitchTip does **not** make collection safe — a reporter capturing ephemeral IDs would build, by accident, exactly the corpus decision 36 forbids, and our own server holding it is worse than a third party's, not better. Enforcement still owed: R8 requires the G1 gate to be extended to the reporter's API surface at integration time. |
| **G5** | No relay-only calling option | ICE exposes each party's IP to the other. On a dating app between people who met via proximity, that is a known stalking vector. Small to build: a relay policy plus a signalling flag. **Must be free.** **DEFERRED to P2 with calling** — it cannot be built before the calling service exists, but it must not be forgotten when it does, so it is listed as a calling-launch requirement rather than an enhancement. |

---

## D. Trigger table — what to re-read, and when

| Trigger | Re-read | Currently detected by |
|---|---|---|
| App gains `INTERNET` | Privacy §3b bound 1 | **Nothing** (G2) |
| First backend source file | Privacy §2, LE §2.1; extend the invariant gate | **Nothing** (G3) |
| First migration | LE §2.1 call-content row | **Nothing** |
| SMS sign-in ships | LE §2.2 subscriber row | Row already flags it |
| Photo upload ships | EXIF stripping (invariant 8) — currently unclaimed and unimplemented | Nothing |
| Crash reporter integrated | Privacy §2 RSSI claim | Nothing (G1, G4) |
| Calling ships | LE call-content row; add the ICE IP-exposure disclosure | Row flagged |
| iOS un-deferred | Carrier B makes the **scanner transmit**, a new exposure not covered by current wording; `pruneSupersededAt` gains a second platform that must drive it | 40-contracts records the obligation |
| **B8 measured** | Privacy §3a. If below 100%, the exclusion list must be built before the paragraph is true | `PHASE0_GO_NO_GO.md` gates the decision, not the sentence |
| Break-glass built | Un-gate Privacy §3b bound 2 and LE §2.3 bound 2 | Publication gate in both documents |

---

## E. Publication gates

Neither document may be published while any of these is unresolved:

1. **Entity and jurisdiction undecided** — we cannot state whose law binds us, so we cannot state what protection a user has.
2. **Break-glass control does not exist** — ADR-008 M1 and M3. The two bound-2 paragraphs stay `[NOT YET BUILT]` until implemented and independently verified.
3. **Counsel review** — particularly LE §4 (emergency disclosure) and §7 (jurisdiction).
4. **IP-address retention undecided** — LE §2.2 has an explicitly undecided row. An omitted category reads as a denial.
5. **B8 unmeasured** — Privacy §3a says testing is in progress. It may only ship while that is literally true.

**Note on gate 5.** Publishing the §3a wording converts the GO/NO-GO exclusion list from an engineering option into a public commitment. That is deliberate — it is what stops someone later arguing to ship the failing models as broadcasters anyway — but it is a founder-level commitment and should be made knowingly, not absorbed through a copy edit.

---

**Owner:** orchestrator. **Review:** on every change to a public claim, and at each phase boundary.
