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

## C. Structural gaps — claims with nothing holding them up

| Gap | Missing | Consequence |
|---|---|---|
| **G1** | No RSSI egress gate | Decision 28 is load-bearing and enforced by one redaction, one test, and memory. Proposed: `rssi_egress_gate.sh`, blocking on `src/main` + `commonMain`, flagging `rssiDbm` reaching any log or string template, and any `rssi` field in a serializer, RPC message, or `.sq` file |
| **G2** | No merged-manifest permission gate | Decision 50 said audits must read the merged manifest; nobody wrote the check. A transitive dependency can re-add `INTERNET` and nothing goes red. Proposed: `permission_gate.sh` with an explicit allowlist, failing with a message naming **the privacy-policy paragraph it invalidates** |
| **G3** | Invariant-1 gate scans `mobile/` only | Cannot see `backend/`, `website/`, `.go`, `.sql`, `.proto`. It checks the one directory where the claim was never at risk |
| **G4** | No crash-report scrubbing policy | Crash reports are the canonical RSSI/ephemeral-ID egress path. Needs a written rule before any reporter is integrated |
| **G5** | No relay-only calling option | ICE exposes each party's IP to the other. On a dating app between people who met via proximity, that is a known stalking vector. Small to build: a relay policy plus a signalling flag. **Must be free** |

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
