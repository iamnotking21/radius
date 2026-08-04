# Radius BLE Wire Protocol — v0

**Status:** DRAFT, contract-first. Spec lands before implementation.
**Owner:** ble-protocol
**Implemented by:** exactly one Kotlin implementation in `mobile/shared/protocol/` (commonMain), per ADR-007.
**Consumed by:** ios-swift (radio `actual`), android-kotlin (radio `actual`), backend-go (proximity service, encounter resolution only).
**Binds to:** ADR-004 (proximity protocol), ADR-007 (KMP shared core), **ADR-008 (`account_key` is server-issued)**, safety invariants 1-10 — invariants **4 and 5 are owned by this document**.

> **v0.1, 2026-08-04.** Two founder decisions landed. **ADR-008** (decision 32)
> settled `account_key` provenance as server-issued — the consequences live in
> `KEY_SCHEDULE.md` §2, §8, §9, §10, and **not one byte of the frame changed**.
> **Android-first** (decision 33) re-scoped §5.0: twelve cells are now DEFERRED
> rather than UNMEASURED, and B8 (RPA co-rotation) is the top technical risk in
> the project rather than one of three. Nothing in this document has been
> validated on hardware.

> This document is the law. Both platforms obey it. Where this document and any
> implementation disagree, this document and `vectors/` are correct and the
> implementation is broken.

---

## 0. Reading order

| Document | Covers |
|---|---|
| `SPEC.md` (this) | byte layout, flags, radio parameters, service UUID, GATT table |
| `KEY_SCHEDULE.md` | `account_key → daily_key → ephemeral_id`, provenance + issuance + storage (§2), MAC/RPA co-rotation (§4, §5), **root-key rotation and the seam (§8)**, **multi-device (§9)**, **threat model incl. the operator (§10)** |
| `STATE_MACHINE.md` | IDLE → … → SESSION, timeouts, failure edges, backgrounding, BT-off |
| `BANDING.md` | RSSI → Kalman → normalise → 4 bands + hysteresis + display jitter |
| `vectors/` | machine-readable conformance vectors. CI regression net. |
| `calibration/` | TX power class table. **placeholder until the hardware rig runs.** |

---

## 1. Scope and non-goals

**In scope.** What goes on the air, how it is encoded, how it is validated, what
GATT surface exists, and what a conformant implementation must reject.

**Explicit non-goals.** This protocol does not carry, derive, or enable:
location, coordinates, bearing, heading, altitude, velocity, a metric distance,
a stable identifier, an account identifier, a display name, or any user-visible
attribute. If a future change would add any of these, it is not a change to this
protocol — it is a different protocol, and it requires a new ADR that overturns
safety invariants 1, 2 and 4.

---

## 2. Terminology

Normative keywords MUST / MUST NOT / SHOULD / MAY per RFC 2119.

- **Frame** — the 19-byte Radius protocol data unit defined in §3.
- **Carrier** — the BLE mechanism that transports a frame (§5). Two carriers exist.
- **Peripheral** — the advertising role. **Central** — the scanning role.
  Every device performs both, alternating.
- **eid** — `ephemeral_id`, 16 bytes, rotates every 15 minutes. See `KEY_SCHEDULE.md`.
- **epoch** — a 15-minute UTC-aligned interval, index 0-95 within a UTC day.

---

## 3. Frame format

The frame is **exactly 19 bytes**. Not 18, not 20.

```
 offset  size  field           type      notes
 ------  ----  --------------  --------  -----------------------------------------
   0       1   version         uint8     v0 protocol => value 0x01
   1      16   ephemeral_id    bytes     see KEY_SCHEDULE.md
  17       1   tx_power_cal    int8      calibrated RSSI at 1 m, dBm, quantised (§3.2)
  18       1   flags           uint8     bit0 CONNECTABLE; bits1-7 reserved, MUST be 0
 ------  ----
  total   19
```

Multi-byte integers are big-endian. `ephemeral_id` is an opaque byte string and
is transmitted in derivation order (byte 0 first); it MUST NOT be byte-swapped.

### 3.1 There are no reserved *bytes*

Root `CLAUDE.md` invariant 4 lists four fields. This spec implements exactly
those four and adds nothing. There is deliberately **no reserved/padding byte**.

Reserved bytes are a covert channel. Nineteen bytes carrying five spare bytes is
40 bits per frame of somewhere for a well-meaning future engineer — or a
compromised build — to put a stable identifier. Over two frames that is a
globally unique ID, and invariant 4 is dead without a single line of the spec
changing. The extension mechanism is the **version byte**, and only the version
byte.

### 3.2 `tx_power_cal` — quantised on purpose

`tx_power_cal` is the **RSSI a reference receiver measures at 1 metre** from
this transmitter (the "measured power" convention, as used by iBeacon and the
Exposure Notification framework). It is *not* the radio's TX power setting.

**Legal values are exactly these seven, and no others:**

```
 dBm   -75   -70   -65   -60   -55   -50   -45
 byte  0xB5  0xBA  0xBF  0xC4  0xC9  0xCE  0xD3
```

A frame carrying any other value MUST be rejected as `E_TX_POWER_NOT_ALLOWED`.

**Why quantised.** Per-device calibration is a device-model fingerprint. If we
broadcast a fine-grained value, "the only device in this room advertising −61"
is a stable attribute that survives every ephemeral ID rotation and links a
person across the entire day. Rotation would be decorative. Quantising to a
7-value grid caps that leak at ~2.8 bits of *model class*, which is not
attributable to an individual in any realistic crowd.

**Why an allow-list rather than a clamp on receive.** Rejecting out-of-set
values closes the byte as a covert channel: an implementation cannot smuggle
bits through fine-grained TX power. Transmitters MUST clamp their measured
calibration to the nearest legal value before encoding.

**Accepted cost.** Up to ±2.5 dB of calibration error, which is well inside the
noise floor of BLE RSSI and irrelevant at 13-15 dB band widths. We do not need
precision. We are forbidden from having precision.

### 3.3 `flags` — one bit, and a hard budget

```
 bit 0   CONNECTABLE     1 = peripheral is currently accepting GATT connections
                             on the Radius Proximity Service
 bit 1-7 RESERVED        MUST be transmitted as 0.
                         MUST be rejected as E_RESERVED_FLAG_SET if non-zero.
```

**No bit of this byte may ever encode identity, name, account id, position,
bearing, or any user attribute. Not now, not in v1, not "temporarily for
debugging".** A flags bit is the cheapest possible place to leak a stable
identifier, and it is the specific thing invariant 4 exists to prevent. Any
proposed new bit must arrive with a written answer to: *how many bits of
cross-rotation linkability does this add, and to how small a population?*

Bits 1-7 are rejected rather than ignored for the same reason `tx_power_cal` is
allow-listed. "Ignore unknown bits" is normally good protocol hygiene; here it
is a 7-bit-per-frame covert channel that every conformant receiver would
silently tolerate. Forward compatibility is provided by the version byte.

**Known residual: `CONNECTABLE = 0` is a platform tell.** `CBPeripheralManager`
always advertises connectably; an iOS peripheral therefore always sets
`CONNECTABLE = 1`. Only Android peripherals can emit `CONNECTABLE = 0`
(`ADV_NONCONN_IND`, used when the connection budget is exhausted or battery is
low). So `CONNECTABLE = 0` weakly implies Android. This is documented, accepted,
and is the entire distinguishing budget of the flags byte. The inverse
(`= 1`) implies nothing.

### 3.4 Presence is not authenticated

The frame carries no MAC, signature, or freshness proof. There is no room for
one, and adding a carrier for one would enlarge the on-air surface. Therefore:

- An observer who captures an `eid` **can replay it** for as long as the
  receiving epoch window accepts it (see `KEY_SCHEDULE.md` §6).
- Replay produces **false presence only.** It cannot produce a wave, a
  handshake, or a session, because all three require a signature verified
  against an identity key the attacker does not hold (§7, `STATE_MACHINE.md`).

**Product consequence, binding on all consumers:** no feature may treat mere
presence as proof of anything. No "check-in", no "you were both there" badge, no
attendance, no reward for proximity. Presence is a hint that starts a consent
flow; it is never evidence.

### 3.5 Reserved ephemeral_id values

`00…00` and `FF…FF` are reserved sentinels and MUST be rejected as
`E_EPHEMERAL_ID_RESERVED`, on both transmit and receive. They are the two values
a broken or uninitialised implementation emits, and a device broadcasting either
would be trivially recognisable on air. The chance of a legitimate derivation
colliding is 2/2^128.

### 3.6 Decode validation order

When a frame has multiple faults the reported error MUST be deterministic, so
that vectors are stable across platforms. Checks run in this order and return on
first failure:

1. length ≠ 19 → `E_SHORT_FRAME` (<19) or `E_LONG_FRAME` (>19)
2. `version` ≠ 0x01 → `E_UNSUPPORTED_VERSION`
3. `tx_power_cal` not in the legal set → `E_TX_POWER_NOT_ALLOWED`
4. `flags & 0xFE` ≠ 0 → `E_RESERVED_FLAG_SET`
5. `ephemeral_id` reserved → `E_EPHEMERAL_ID_RESERVED`

Rejected frames MUST be dropped silently. They MUST NOT surface to the UI, MUST
NOT create a peer instance, and MUST NOT be logged with their payload. A
per-epoch counter per error code MAY be kept for local diagnostics; it MUST NOT
be transmitted with frame contents.

---

## 4. Service UUID allocation

### 4.1 Advertised UUID (SIG-dependent — see blocker)

```
16-bit :  0xFDA9                                     PROVISIONAL, DEV ONLY
128-bit:  0000FDA9-0000-1000-8000-00805F9B34FB       (Bluetooth Base UUID expansion)
```

One UUID exists, in two encodings. The 16-bit form is what goes on air; the
128-bit form is the same UUID and is what iOS `CBUUID` and Android `ParcelUuid`
will report. Implementations MUST treat them as identical.

**0xFDA9 is provisional and MUST NOT ship.** 16-bit UUIDs in the `0xFDxx` member
range are allocated by the Bluetooth SIG to member companies. Using one we were
not allocated is squatting, may collide in the field, and is a certification
problem. Before any external build:

1. Join the Bluetooth SIG as an Adopter (no fee).
2. Apply for a 16-bit member UUID allocation.
3. Replace the constant here and in `vectors/`, bump nothing else.

Lead time is weeks, not days. **This is on the critical path for shipping, not
for the spike** — the spike may use the provisional value. Raised as a blocker;
see the report to orchestrator.

Before using 0xFDA9 in the lab, check it against the current SIG 16-bit UUID
assignment list. If it is assigned, pick another unassigned-looking value — for
lab purposes the specific number is irrelevant, only collision-avoidance is.

**Why a 16-bit UUID is not optional.** With a 128-bit UUID, the Service Data AD
structure costs `1 + 1 + 16 = 18` bytes of overhead, leaving 13 bytes for the
frame in a 31-byte legacy advertisement — the frame does not fit. See §5.1.
The alternatives are all worse: extended advertising (not supported on our
minimum devices), scan response (forces active scanning, costs battery, and the
scanner must transmit — which makes the *scanner* trackable), or Manufacturer
Specific Data (needs a SIG Company ID, i.e. the same dependency).

### 4.2 GATT service and characteristics (self-allocated, no SIG dependency)

Custom 128-bit UUIDs, generated once, fixed forever. Base:
`7C09xxxx-B4DC-3C06-A71D-16738585F3C2`.

```
7C090000-B4DC-3C06-A71D-16738585F3C2   Radius Proximity Service (primary)
7C090001-B4DC-3C06-A71D-16738585F3C2   beacon_payload
7C090002-B4DC-3C06-A71D-16738585F3C2   wave_inbox
7C090003-B4DC-3C06-A71D-16738585F3C2   wave_receipt
7C090004-B4DC-3C06-A71D-16738585F3C2   handshake
7C090005-B4DC-3C06-A71D-16738585F3C2   session_data
```

The GATT service UUID is deliberately **not** the advertised UUID. This
decouples the GATT layer from the SIG allocation entirely: when 0xFDA9 is
replaced, no GATT constant changes.

---

## 5. Carriers — and the iOS problem, stated at protocol level

> **This section is the go/no-go section.** Raised by ios-swift via orchestrator,
> 2026-08-04, as a blocking finding against this spec. It is a *stronger* claim
> than ADR-004 priced in. ADR-004 says Android may fail to **see** a backgrounded
> iPhone. The finding says a backgrounded iPhone may be unable to **emit**
> identity at all. Both are addressed below.

`CoreBluetooth` accepts exactly two keys in `startAdvertising`:
`CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey`.
**iOS cannot transmit Service Data or Manufacturer Data. At all. In any app
state.** This is an API limit, not a background restriction, and no entitlement
removes it. In the background iOS honours only the service-UUID list, and moves
it into Apple's private overflow area. (iOS *centrals* read Service Data from
others perfectly well — the limitation is transmit-only.)

Naively encoding the frame into the advertised UUID is not a free way out:
`scanForPeripherals(withServices:)` has no UUID mask and background scanning
with a nil filter returns nothing, so **a scanner must name in advance every
UUID it is willing to see** — and it cannot name a rotating value it has never
observed. Rotating identifiers and named-UUID scanning are in direct tension.
That Apple shipped Exposure Notification inside the OS rather than as an app is
evidence about the shape of this constraint, not a footnote.

Confirmed independently by ios-swift and android-kotlin, from opposite sides,
without contact. Treat the convergence as evidence. The restriction is **not** a
background restriction: service data and manufacturer data are unavailable to
iOS apps in every state, foreground included, and they are dropped silently with
no error.

### 5.0 Emission / acquisition matrix — 4 cells UNMEASURED, 12 DEFERRED

**Re-scoped 2026-08-04 for ANDROID-FIRST (decision 33).** iOS is deferred, not
cancelled. The cells have not been deleted and the predictions have not been
revised — they are simply off the Phase 0 critical path.

> **`DEFERRED` ≠ `UNMEASURED`.** An `UNMEASURED` cell is an open risk that the
> spike must close. A `DEFERRED` cell is a cell we have deliberately chosen not
> to schedule. **A `DEFERRED` cell MUST NOT be counted as an unknown risk in the
> go/no-go**, and a `DEFERRED` cell MUST NOT be cited as validated either. It
> reverts to `UNMEASURED` the moment iOS returns to scope, with its prediction
> intact and its measurement still owed.

The question that matters is not "can X advertise" but "can scanner S obtain
emitter E's `ephemeral_id`". That is a 4 × 4 matrix and its Android quadrant may
itself be the Phase 0 verdict.

`A` = Carrier A (passive, frame in Service Data).
`B` = Carrier B (connect + GATT read).

| Emitter ↓ / Scanner → | Android fg | Android bg | iOS fg | iOS bg |
|---|---|---|---|---|
| **Android fg** | **A · UNMEASURED** · predicted YES | **A · UNMEASURED** · predicted YES (needs FGS) | DEFERRED · A · predicted YES | DEFERRED · A · predicted YES, throttled |
| **Android bg** | **A · UNMEASURED** · predicted YES | **A · UNMEASURED** · predicted YES (needs FGS) | DEFERRED · A · predicted YES | DEFERRED · A · predicted YES, throttled |
| **iOS fg** | DEFERRED · B · predicted YES | DEFERRED · B · predicted YES (needs FGS) | DEFERRED · B · predicted YES | DEFERRED · B · predicted YES, slow |
| **iOS bg** | DEFERRED · **predicted NO** | DEFERRED · **predicted NO** | DEFERRED · B via overflow · predicted YES, slow | DEFERRED · B via overflow · predicted YES, heavily throttled |

**The four bold cells are the entire Phase 0 measurement surface for this
matrix.** They are first-class, they are unmeasured, and no cell of them has been
observed on hardware. The `predicted` values are inference from Android API
documentation only. Simulator results are invalid and MUST NOT populate this
table.

Readings from this matrix:

1. **Under android-first, only the Android × Android quadrant is on the critical
   path.** It is also the quadrant with the *best* predicted outcome, which is
   exactly why the Android measurement that actually decides Phase 0 is not in
   this table at all — it is RPA co-rotation, `KEY_SCHEDULE.md` §4.3 (B8).
   Do not read a green Android quadrant here as a passing spike.
2. **Two cells are predicted hard NO**: backgrounded iOS emitter → any Android
   scanner. The overflow area is an Apple-private hashed structure, not a
   standard AD structure; Android cannot parse or match it. This is ADR-004's
   known cost, confirmed at protocol level, and it is not fixable by us. It is
   deferred with the rest of iOS, but note that **deferral does not make this
   cell any more likely to work** — it remains a permanent platform hole
   (§5.4 Tier 3).
3. **Every iOS-emitter cell requires Carrier B** — a GATT connection per peer
   per epoch. This is the part ADR-004 did not price. It is not a degraded
   advertisement; it is a categorically different and much more expensive
   mechanism. All of it is deferred, and **Carrier B is therefore not exercised
   at all in an Android-only spike.** Its cost stays unknown, and it returns as
   an unpriced risk on the day iOS comes back. That is a scheduling consequence
   worth writing down rather than rediscovering.
4. Android emitters are unaffected throughout. The problem was always entirely on
   the iOS emit side, which is part of why deferring iOS is cheap for this table
   and expensive for nothing here.

**Required measurements per Android cell** (hand-off to qa-test, first-class
spike axis, not a footnote): acquisition success rate over 10 min; discovery
latency p50 and p95; battery %/hr on both ends; behaviour at 1, 5, 15 and 30
concurrent peers; and whether the emitter's MAC and `ephemeral_id` rotate
together, observed on an external sniffer (`KEY_SCHEDULE.md` §4.3, §5). **Run the
co-rotation measurement first** — it is the only one that cannot be designed
around after the fact.

**Deferred measurements, owed on iOS return:** every DEFERRED cell above, plus
Carrier B connect-and-read latency and battery at realistic peer density (§5.5),
plus overflow-area matching reliability for a 16-bit UUID (§10, item 8).

### 5.0-a The decision, stated as a decision

**The frame does not degrade. The frame changes carrier.**

This is the substantive choice in v0 and it is worth stating plainly, because
the obvious reading of the iOS constraint — "the 19-byte frame is untransmittable
from an iPhone, therefore the wire format must shrink to 16 bytes" — assumes the
advertisement is the only carrier. It is not.

Under Carrier B (§5.2) the *entire* 19-byte frame, unchanged and byte-identical,
lives in a GATT characteristic and is fetched after connect. `version`,
`tx_power_cal` and `flags` all keep their home. Invariant 4 holds exactly as
written. There is one wire format, not two.

**What it costs:** a GATT connection per peer per epoch on every iOS-emitter
path, with the battery, latency and scanner-anonymity consequences in §5.5. That
is a real and possibly disqualifying cost, and it is measured, not assumed
(§5.0).

**What we did not do, and why:** the "carry the ephemeral ID as a 128-bit service
UUID" workaround fits 16 bytes exactly and leaves `version`, `tx_power_cal` and
`flags` with nowhere to live. That is not a tweak to the frame — it is a second
wire format with a worse interop story. §5.3 F2/F3 enumerate it and §5.0-b
quantifies what it would actually cost, because it remains the most attractive
route to *passive* iOS-foreground discovery and someone will propose it again.

### 5.0-b If `version` / `tx_power_cal` / `flags` lose their home — quantified

Applies only to the F3a variant (§5.3). Not adopted, costed so the trade is
visible rather than discovered later.

**Losing `tx_power_cal` is the expensive one, and it is worse than it sounds.**
Calibrated 1 m RSSI spans roughly −45 to −75 dBm across handset models — a
**30 dB spread**. Substituting the `TX_REF_DBM = -60` default for an
uncalibrated peer therefore admits up to **±15 dB of normalisation error**
against band widths of 13-15 dB (`BANDING.md` §1).

> **A peer with no `tx_power_cal` can land a full band wrong, and at the
> extremes two bands wrong.** A loud handset at 25 m could report `CLOSE`
> ("very close", ~2-10 m) instead of `AROUND`. That is invariant 2 delivering a
> materially false answer, and in a product where "HERE" means "in this room" it
> is the failure direction that matters.

Mitigation if F3a were ever taken: degrade confidence one level for
uncalibrated peers (`BANDING.md` §3 already specifies this), and suppress `HERE`
entirely for them. Not free, and not good.

**Losing `version` breaks wire-format versioning**, which is a forward-compatibility
bill paid at every future protocol change — no negotiated upgrade, no graceful
rejection of unknown frames, effectively a flag day. There is a clean fix if
F3 is ever adopted: **make the fixed companion service UUID the version.** One
SIG-allocated 16-bit UUID per major protocol version; a v0 scanner filters for
the v0 UUID and never sees v1 frames it cannot parse. Costs one UUID allocation
per major version and nothing on air.

**Losing `flags` costs almost nothing.** The only defined bit is `CONNECTABLE`,
and under F3 the iOS peripheral is always connectable, so the value is implied.

**F3b avoids all three losses** by packing `ephemeral_id[14] || tx_power_cal[1]
|| flags[1]` into the 16 bytes, with `version` carried by the companion UUID. A
112-bit ephemeral ID is far beyond brute force. It changes invariant 4's literal
field layout, so it needs an ADR amendment — flagged, not taken here.

Therefore v0 defines two carriers. **The frame bytes are byte-identical in
both.** Only the transport differs, so invariant 4 holds in both.

### 5.1 Carrier A — `SERVICE_DATA` (connectionless). Android peripherals.

Advertisement, legacy `ADV_IND` / `ADV_NONCONN_IND`, 31-byte budget:

```
 02 01 06                    Flags AD: LE General Discoverable | BR/EDR Not Supported
 03 03 A9 FD                 Complete List of 16-bit Service UUIDs (little-endian on air)
 16 16 A9 FD <19 bytes>      Service Data - 16-bit UUID (len 0x16 = 22)
 -----------------------
 30 bytes of 31 used. 1 byte spare, deliberately left spare.
```

The scanner parses the frame directly from the advertisement. No connection, no
transmission by the scanner, lowest battery cost. This is the preferred path.

**Budget note.** Root `CLAUDE.md` sets the payload ceiling at ≤26 B. The frame is
19 B. The 7 bytes of headroom are **not available for use** — they are the margin
that keeps us inside a legacy advertisement alongside the AD headers. Any
proposal to spend them must first re-derive the arithmetic above.

### 5.2 Carrier B — `GATT_PULL` (connection-oriented). iOS peripherals.

The peripheral advertises only the service UUID (no frame on air). A scanner
that sees the UUID with no Service Data MUST connect and read
`beacon_payload`, which returns the same 19 bytes.

```
 02 01 06
 03 03 A9 FD
 -----------------------
 7 bytes. Frame is fetched over GATT.
```

Properties, stated honestly:

- **Strictly more private on air** (nothing but "a Radius device is nearby"),
  and strictly more expensive: a connection per peer per epoch.
- Backgrounded iOS moves the service UUID into Apple's private **overflow
  area**, a hashed representation readable only by another iOS device that is
  explicitly scanning for that exact UUID. It is not parseable by Android. So
  **Android → backgrounded-iOS discovery does not work.** This is a product
  constraint, not a defect, and is not fixable by us (ADR-004).
- Because the peripheral must be connectable to be discoverable at all, Carrier
  B is exposed to connect-spam battery attacks. §7.4 mandates the limits.
- The pulled frame MUST be cached against the peer's current address and MUST
  NOT be re-pulled more than once per 60 s while the address is unchanged.

**Selection.** A scanner selects the carrier per advertisement: Service Data for
our UUID present → Carrier A; absent → Carrier B. Peripherals use whichever
their platform supports. Implementations MUST NOT assume carrier from platform,
and MUST NOT record which carrier a peer used beyond the current epoch.

### 5.3 Candidate fallbacks for the iOS emit problem

Enumerated with costs. **Only F1 is adopted in v0.** The rest are recorded so
that nobody re-derives them under deadline pressure and picks a bad one.

The binding constraint on every row: **no fallback may weaken invariant 4 (no
stable identifier on air) or invariant 5 (eid and MAC rotate together, both or
neither). If the only way to make iOS background work requires a stable
identifier, the answer is that iOS background does not work.** We report that;
we do not design around it.

---

**F1 · Fixed service UUID + `ephemeral_id` in a GATT characteristic read after
connect.** — **ADOPTED as Carrier B (§5.2).**

- Invariant 4: **satisfied, and strictly better than the baseline.** Nothing but
  a fixed service UUID on air. The `ephemeral_id` never appears in an
  advertisement at all.
- Invariant 5: **satisfied**, subject to the same unresolved RPA-rotation
  question that affects every option (`KEY_SCHEDULE.md` §5).
- Latency: adds connect + MTU exchange + read per peer per epoch. Foreground
  cost is likely acceptable; background cost is UNMEASURED and may be the
  deciding number.
- Battery: the worst of all options. Connections dominate BLE energy use, and
  the cost scales with peer *density*, not peer *interest* (§5.5).
- Product cut: none. Full Open-mode discovery preserved.
- Works in: all iOS-emitter cells, including iOS-bg → iOS-only scanners.

---

**F2 · Rotating 128-bit service UUID carrying the `ephemeral_id`.** — **REJECTED.**

Dimensionally perfect: 128 bits is exactly 16 bytes. It fails on discovery, not
on encoding.

- A scanner must name every UUID it wants. It cannot name a rotating value it
  has never seen, so it can only discover peers whose `daily_key` it already
  holds — and must enumerate `N peers × 3 epochs` UUIDs in the scan filter.
- Android offloads scan filters to controller slots (commonly 8-16, chipset
  dependent). Beyond that it falls back to software filtering, which does not
  survive a screen-off background scan. iOS's practical filter cardinality is
  undocumented and degrades with array size.
- **Kills Open mode.** Discovery of someone you have not already matched with is
  the core Radar promise. F2 makes first-time discovery impossible by
  construction.
- Invariant 4: satisfied in letter (the UUID is the eid, no stable identifier).
  Invariant 5: unchanged.
- Rejected on product capability, not on safety.

---

**F3 · Fixed UUID *plus* a second 128-bit UUID carrying the payload
(iOS foreground only).** — **NOT adopted in v0. Measure in the spike.**

The variant that escapes F2's trap. The peripheral advertises **two** service
UUIDs: the fixed `0xFDA9`, and a rotating 128-bit UUID whose 16 bytes carry the
payload. The scanner filters on the **fixed** UUID — which it can name — and
then simply *reads the second UUID out of the advertisement record*. It never
has to predict the rotating value.

Size check, iOS foreground: `flags 3 + 16-bit list 4 + 128-bit list 18 = 25 ≤ 31`. Fits.

- **Upgrades the entire `iOS fg` row from Carrier B to passive acquisition.**
  No connection. Given that ADR-004 already frames Radar as a foreground
  destination, this covers the dominant real-world case.
- Does **not** help `iOS bg`: in the background both UUIDs go to the overflow
  area, where the rotating one is a hash and is unrecoverable. Background still
  needs F1.
- Two sub-variants, and the difference matters:
  - **F3a** — the 16 bytes are the `ephemeral_id`. `version`, `tx_power_cal` and
    `flags` are lost for iOS-foreground peers, so banding for those peers must
    assume a default calibration. Costs band accuracy (`BANDING.md` §3).
    Invariant 4: satisfied.
  - **F3b** — pack `ephemeral_id[14] || tx_power_cal[1] || flags[1]` into the 16
    bytes. Preserves full frame semantics; `version` is implied by the fixed
    companion UUID. A 112-bit ephemeral ID is far beyond brute force.
    **But it changes invariant 4's literal field layout, so it requires an ADR
    amendment and orchestrator sign-off. I am not taking that decision here.**
- Risk, UNMEASURED: iOS chooses AD packing itself. If it places the second UUID
  in the scan response rather than the advertisement, Android scanners need
  active scanning to see it — which makes the *scanner* transmit (§5.5) and
  costs battery. Must be observed on a sniffer before F3 is believed.
- Invariant 5: unchanged — the rotating UUID must co-rotate with the MAC exactly
  as the eid does.

---

**F4 · Accept that iOS cannot emit identity in the background. Foreground-only
Radar on iOS.** — **The honest fallback. Already half-adopted by ADR-004.**

- Invariants 4 and 5: trivially satisfied. Nothing is emitted.
- Cost: no opportunistic background discovery on iOS at all. `CBCentralManager`
  state restoration and region wakeups still give short bursts of
  foreground-quality operation, which is a real but modest mitigation.
- Product: Radar is a destination you open. ADR-004 already requires the UI to
  promise nothing more. This fallback makes that framing load-bearing rather
  than merely honest.
- This is where the evidence currently points for iOS background. See §11.

---

**F5 · Declare discovery capability tiers rather than pretend uniformity.**
— **ADOPTED as framing (§5.4).** Not a transport; a way of being honest in the
product and in the code.

---

**F6 · `CBAdvertisementDataLocalNameKey` as a payload carrier.** — **REJECTED.**

The one remaining iOS-writable field. It is stripped in the background, it is
surfaced in OS-level Bluetooth UI, and using a user-visible device-name field to
carry identity is precisely the kind of thing that reads as hostile when
someone eventually looks. Rejected on both function and taste.

---

**F7 · A stable per-device identifier so iOS background discovery works.**
— **REJECTED. NO-GO, not a trade-off.**

This is the option that will be proposed when the spike numbers come back
disappointing, so it is written down now with its answer already attached. A
stable identifier on air is a permanent tracking beacon. It voids invariant 4,
makes invariant 5 pointless, and re-enables every attack in the abuse model
simultaneously: tracking over time, triangulation, home/work inference, targeted
stalking. **If iOS background discovery requires it, then iOS background
discovery does not ship.** Report the constraint; do not design around the
invariant.

### 5.4 Discovery capability tiers (F5)

The protocol does not pretend the matrix is uniform. Three declared tiers.
Consumers MUST surface the distinction honestly and MUST NOT promise Tier 1
behaviour while operating in Tier 3.

| Tier | Condition | Expectation |
|---|---|---|
| **1 — Full** | both devices foreground | passive or single-connect acquisition, target < 5 s |
| **2 — Opportunistic** | Android↔Android bg (FGS), or iOS↔iOS bg | works, throttled, target < 60 s, higher battery |
| **3 — None** | backgrounded iOS emitter ↔ Android scanner | **does not work.** Not slow — absent. |

Tier 3 is a permanent, platform-imposed hole. Product copy MUST NOT claim
continuous background discovery. Any consumer surfacing a "nearby" count MUST
NOT imply it is complete.

### 5.5 Carrier B makes the scanner transmit — a privacy cost, not only battery

Worth stating separately because it is easy to miss.

Under Carrier A the scanner is **passive**: it emits nothing and is
unobservable. Under Carrier B the scanner must **connect** — and to do that it
transmits, using its own resolvable private address, to a peer it has no
relationship with. Two consequences:

1. **Scanner anonymity drops.** A passive observer can now see that a device is
   running Radius and is actively probing. The scanner's own RPA becomes
   observable and linkable for the duration of the connection.
2. **You connect to strangers by necessity.** A scanner cannot resolve an
   identity it has not yet fetched, so under Carrier B it must connect to
   *every* Radius device in range to discover whether it cares about any of
   them. Cost scales with peer density, not with peer interest. In a venue with
   30 Radius users, an iOS-heavy environment implies up to 30 connections per
   epoch per device.

Mitigations, mandatory: cache per peer address for the epoch (§5.2, 60 s
minimum); cap connections per epoch; prioritise by RSSI so the closest peers are
fetched first; abandon the sweep when the connection budget is spent rather than
queueing. The scaling wall is real and is a spike measurement (§5.0).

---

## 6. Radio parameters

| Parameter | Foreground | Background | Source |
|---|---|---|---|
| Advertising interval | 250 ms | 1000 ms | `CLAUDE.md` HARD NUMBERS |
| Scan duty cycle | 30 % | platform-governed | `CLAUDE.md` |
| Scan window / interval | 300 ms / 1000 ms | platform-governed | derived: 30 % |
| Advertisement payload | ≤ 26 B ceiling; 19 B actual | same | invariant 4 |
| ATT MTU | negotiate ≥ 185 | same | `CLAUDE.md` |
| Roles | central and peripheral, alternating | same | ADR-004 |
| Extended advertising | MUST NOT be used | — | min API 29 / iOS 16 device floor |
| Bonding / pairing | **MUST NOT** — see §7.3 | — | invariant 5 |

Duty cycling is adaptive and reduces below the table when the device is
stationary, battery is under 20 %, or no peer has been seen for 10 minutes
(`mobile/CLAUDE.md` battery contract). Adaptive reduction is a platform concern
and lives in the radio `actual`, not in `mobile/shared/protocol/`.

**Neither the intervals nor the duty cycle in this table has been validated on
hardware.** They are the ADR's starting point for the spike. Battery draw,
discovery latency (p50/p95) and band accuracy at each of these settings are
spike deliverables. Simulator measurements are worthless and MUST NOT be
reported as validation of any row above.

---

## 7. GATT

### 7.1 Characteristic table

All characteristics are on the Radius Proximity Service
(`7C090000-B4DC-3C06-A71D-16738585F3C2`).

| # | Name | UUID suffix | Properties | Max value | Access gate | Purpose |
|---|---|---|---|---|---|---|
| 1 | `beacon_payload` | `…0001` | Read | 19 B | open | Carrier B frame fetch (§5.2) |
| 2 | `wave_inbox` | `…0002` | Write with response | MTU−3, chunked | open, rate-limited | inbound signed wave record |
| 3 | `wave_receipt` | `…0003` | Read, Indicate | ≤ 96 B | requires a wave written on this connection | signed receipt for the wave just written |
| 4 | `handshake` | `…0004` | Write with response, Indicate | MTU−3, chunked | requires **verified mutual wave** | X3DH prekey / key agreement |
| 5 | `session_data` | `…0005` | Write with response, Indicate | MTU−3, chunked | requires established session | Double Ratchet ciphertext |

No characteristic exposes: a name, an account id, a profile field, a photo
reference, RSSI, a distance, a band, a coordinate, or a timestamp finer than the
current epoch. There is deliberately no `device_info` or `protocol_info`
characteristic — version is already in the frame, and a capabilities
characteristic is a fingerprinting surface for nothing in return.

`wave_receipt` MUST only answer for a wave written on the *same connection*.
It MUST NOT support querying arbitrary wave state, because "do you have a wave
from X" is an oracle an attacker would use to test whether an identity is known
to a device.

### 7.2 Chunking and framing

Application payloads larger than `MTU − 3` are chunked. Chunk size is
`min(MTU − 3, 180)`. Each chunk carries a 3-byte app-layer header
`[msg_id:1][seq:1][flags:1]` where `flags` bit0 = `LAST`. Receivers MUST
reassemble by `msg_id`, MUST drop a partial message after 10 s, and MUST cap
reassembly at 8 KB per message and 2 in-flight messages per connection.

`MTU < 185` after negotiation is a hard failure: tear down the connection,
report `E_MTU_TOO_SMALL`, do not fall back to smaller chunks. Silent degradation
to tiny MTUs is a battery and latency trap.

### 7.3 Bonding is forbidden

**Devices MUST NOT bond and MUST NOT pair.** Not "should avoid" — MUST NOT.

BLE bonding exchanges an Identity Resolving Key. An IRK is, by construction, a
long-term secret whose entire purpose is to let the peer resolve this device's
Resolvable Private Address *forever*. Bonding with a peer hands them a permanent
tracking key and voids safety invariant 5 completely, no matter how correctly
the eid and RPA rotate. One accidental `createBond()` call defeats the whole
protocol.

Link-layer encryption is therefore not used. All confidentiality and
authenticity is application-layer: signed wave records, X3DH, and Double Ratchet
via vodozemac. GATT is a dumb pipe.

Any characteristic declared with an encryption or authentication permission
would trigger pairing on access. Every characteristic in §7.1 MUST be declared
with **no** security permissions. **This is a mandatory review checklist item on
every PR touching either radio `actual`.**

### 7.4 Connection limits (anti-abuse, mandatory)

Because the peripheral must be connectable to receive waves, connect-spam is a
real battery-drain and denial-of-discovery attack.

| Limit | Value |
|---|---|
| Max concurrent inbound connections | 2 |
| Idle timeout before any verified wave | 5 s |
| Idle timeout after verified wave | 30 s |
| Per-peer-address connection rate | 1 per 30 s |
| Global inbound connection rate | 10 per minute, then refuse for 5 min |
| Consecutive failed verifications before teardown | 3 |
| Address quarantine after teardown | 10 min |

Per-address limits alone are insufficient — an attacker rotates its own address
freely. The **global** cap is the load-bearing one; the per-address cap only
raises the cost. Quarantine lists MUST be cleared at every eid rotation
boundary, because keeping a peer-address list across a rotation is itself a
linkability structure.

---

## 8. Shared-module API contract (normative shape, not source)

`mobile/shared/protocol/` exposes this and nothing wider. Written as
language-neutral signatures.

> **IMPLEMENTED 2026-08-04.** The Kotlin has landed at
> `mobile/shared/src/commonMain/kotlin/com/radius/shared/protocol/` (package
> `com.radius.shared.protocol` — the `mobile/shared/protocol/` path in
> `40-contracts` is an ownership carve-out, and it is realised as a package
> subtree inside the module's own source set so that no change to
> `mobile/shared/build.gradle.kts` is required). All 116 conformance cases
> execute against it via `:shared:testDebugUnitTest` and pass — 110, plus the 6
> `destruction_at_seam` cases added on the same day when a security review found
> `KEY_SCHEDULE.md` §8.5.2 normative and unimplemented. §8.2 records the three
> places where the landed surface differs from the shape written below; each needs
> an orchestrator gate.

```
BleFrame            = { version: u8, ephemeralId: bytes[16],
                        txPowerCal: i8, connectable: bool }

encodeFrame(BleFrame)            -> bytes[19]
decodeFrame(bytes)               -> Result<BleFrame, DecodeError>
buildAdvertisement(BleFrame)     -> bytes            // Carrier A AD structure
parseAdvertisement(bytes)        -> Result<AdvScanResult, DecodeError>

DecodeError = E_SHORT_FRAME | E_LONG_FRAME | E_UNSUPPORTED_VERSION
            | E_TX_POWER_NOT_ALLOWED | E_RESERVED_FLAG_SET
            | E_EPHEMERAL_ID_RESERVED | E_AD_MALFORMED

Band        = HERE | CLOSE | AROUND | EDGE | OUT_OF_RANGE | UNKNOWN
Confidence  = WARMING | COARSE | STABLE | STALE
PeerReading = { band: Band, confidence: Confidence, displayMetres: int? }
```

**`PeerReading` has no RSSI accessor, and never will.** Not filtered RSSI, not
adjusted RSSI, not a "debug" variant behind a flag. See `BANDING.md` §6 for why
this is a hard boundary rather than an API preference.

### 8.1 ADR-008 additions — GATED 2026-08-04, IMPLEMENTED 2026-08-04

The key ring (`KEY_SCHEDULE.md` §8) and the advertising role (§9.3) have to
reach the shared module somehow, and the shared public API is a gated contract
owned outside this document (`40-contracts` SHARED). **This was approved as-is
in `40-contracts` "mobile/shared API v0.1", and is now normative and
implemented.** The two structural choices the gate called out — `ephemeralId()`
taking `(ring, day, epoch)` rather than a resolved key, and `AdvertiseRole`
defaulting to `SCAN_ONLY` — are kept verbatim in the Kotlin. Do not "simplify"
either; see §8.2 for the three places the implementation does deviate.

```
KeyRingEntry     = { kid: u32, accountKey: bytes[32], effDay: u32, effEpoch: u16 }
KeyRing          = list<KeyRingEntry>                 // validated on load, §8.2
AdvertiseRole    = ADVERTISE | SCAN_ONLY              // §9.3, fail closed to SCAN_ONLY

activeKid(KeyRing, day: u32, epoch: u16) -> Result<u32, KeyError>
ephemeralId(KeyRing, day: u32, epoch: u16) -> Result<bytes[16], KeyError>
isOwnEphemeralId(KeyRing, day: u32, epoch: u16, observed: bytes[16]) -> bool   // §9.6

KeyRing.pruneSupersededAt(day: u32, epoch: u16) -> count           // KEY_SCHEDULE §8.5.2
KeyRingEntry.destroyKeyMaterial()                                  // irreversible
KeyRingEntry.isDestroyed -> bool

KeyError = E_NO_ACTIVE_KEY | E_KEY_RING_NOT_MONOTONIC
         | E_EPOCH_INDEX_OUT_OF_RANGE | E_ACCOUNT_KEY_LENGTH
```

Four shape notes, each of which is a safety property rather than taste:

- **`accountKey` crosses as `ByteArray`, never `String`.** Same reasoning as
  ruling R-C for `ephemeral_id` (`40-contracts`): a hex `String` is log-shaped,
  survives interpolation, and lands in crash reports. This value is the single
  most sensitive number in the system and must be the most awkward to print.
- **`ephemeralId()` takes the ring and the epoch, not a resolved key.** The
  per-epoch ring evaluation of §8.4 is then structurally impossible to skip. An
  API that accepted a pre-resolved `accountKey` would make the once-per-table
  -build bug easy to write and invisible in review.
- **`AdvertiseRole` is an enum defaulting to `SCAN_ONLY`,** not a boolean
  defaulting to permissive. Same reasoning as ruling R-B for
  `setVisibility`/`RadarVisibility`.
- **Key destruction is on the contract, not an implementation detail.**
  `pruneSupersededAt` is the caller's obligation under `KEY_SCHEDULE.md` §8.5.2
  and must be driven from the epoch-boundary advertising restart (§4.2) by
  whichever platform owns the key ring's lifetime. There is no accessor that
  returns `account_key` bytes on the public surface at all — reading key material
  is `internal` and goes through a use-and-zero callback, so the only public verb
  on a key is *destroy it*. **`NEW-CONSUMER-VISIBLE, NEEDS A GATE`**: both
  platforms must call it, and a platform that does not simply retains every key
  it has ever held.

### 8.2 Where the landed Kotlin differs from §8 — THREE ITEMS, ALL NEED A GATE

Written down rather than absorbed silently, because the public surface of
`mobile/shared` is a gated contract with two consumers.

**1. One error enum, not two.** §8 named `DecodeError` and `KeyError`;
`vectors/index.json` names a single flat `error_codes` list of thirteen. The
vectors win. A conformance case says `"expect_error": "E_SHORT_FRAME"`, and that
string has to map to exactly one constant or the runner has to guess which enum
it lives in. Landed as `ProtocolError`, thirteen constants, the union of both.
`DecodeError` and `KeyError` are now names for subsets of it, not types.

**2. `ProtocolResult<T>` instead of `Result<V, E>`.** Kotlin has no two-type
result and `kotlin.Result` carries a `Throwable`. Landed as a sealed
`ProtocolResult<T>` = `Success(value)` | `Failure(error: ProtocolError)`.
Deliberately not exceptions: a malformed advertisement is the ORDINARY case — a
scan in a busy place is a continuous stream of other vendors' beacons — and an
exception crossing the Kotlin/Native boundary is a crash, not a `throws`.
Owed at iOS un-deferral: a generic sealed class exports to ObjC as
`ProtocolResult<AnyObject>`. If ios-swift wants concrete non-generic result
types, that is a gated change, not a unilateral one.

**3. `Band` duplicates `domain.radar.ProximityBand`, and one of them must die.**
`ProximityBand` has only the four displayable bands; the pipeline needs
`UNKNOWN` (below warm-up, MUST NOT be displayed) and `OUT_OF_RANGE`. Two band
enums in one module is precisely the divergence ADR-007 exists to prevent. The
right fix is to delete `ProximityBand` and have the radar domain consume
`protocol.Band` — that touches android-kotlin's files, so it is a **HANDOFF**,
not something ble-protocol does. Until it happens, the module contains two
answers to "what band is this peer in", which is a real defect and not a
cosmetic one.

Also landed, beyond §8's list, and each justified where it is declared:
`BleFrameCodec` (`isLegalTxPowerCal`, `legalTxPowerCalDbm`, `clampTxPowerCal` —
transmitters MUST clamp to the seven-value grid, so the grid has to be
reachable), `AdvertisementCodec` (`SERVICE_UUID16`, `buildAdvertisement`,
`parseAdvertisement`), `Carrier`, `KeySchedule.dayIndex`/`epochIndex`/
`validateRing`, `BandingPipeline`, `Confidence`, `PeerReading`.
`DisplayJitter` is **internal**: the only supported way to obtain a metre value
is through `BandingPipeline`, which cannot be handed an RSSI-derived seed.

**What `internal` means here, corrected 2026-08-04.** Kotlin `internal` is a
COMPILE-TIME property of the Kotlin compiler, not JVM access control. On
Kotlin/Native it is enforced in the binary; on the JVM the compiler emits a
`public` method with a mangled name, and a foreign compilation unit can call
`someInternalFn$shared_debug()` with no reflection and no friend path — this was
demonstrated in review against `conformanceState`, `accountKey`, `dailyKey` and
`ephemeralIdFor`. So `internal` in this spec means **"off the contract, and loud
when you cross it"**, never "unreachable on Android". Nothing that must be
genuinely unreachable may rest on the keyword; key material rests on
use-and-zero plus destruction at the seam instead.

---

## 9. Abuse model re-check for v0

| Attack | v0 position |
|---|---|
| Tracking over time | eid + RPA co-rotate every 15 min (`KEY_SCHEDULE.md`). Residual: `tx_power_cal` class ~2.8 bits; `CONNECTABLE=0` ~1 bit; continuous co-located observers defeat rotation via RSSI/timing continuity regardless. Documented, not solved. |
| Multi-receiver triangulation | Bands only, never metres, no bearing. Raw RSSI never crosses the module boundary or reaches the server (`BANDING.md` §6). Attacker with own radios can still multilaterate — that is physics, not our leak; we simply add nothing to it. |
| Spoofed presence | **Possible.** Frame is unauthenticated (§3.4). Bounded to false presence: waves and handshakes are signature-verified. No feature may trust presence (§3.4). |
| Sybil / mass scanning | Daily keys are rate-limited per verified account server-side; verification mandatory for Radar. Not a wire-protocol control — flagged as a backend dependency. |
| Targeted stalking | Blocks enforced at key resolution: a blocked account's daily key is never in the candidate set, so their frames are unresolvable and no peer instance is created (invariant 7). Bloom-filter resolution in Open mode MUST re-check the exact local blocklist after a filter hit — a false positive must never resolve a blocked peer. |
| Home/work inference | Blackout zones suppress **advertising entirely** (peripheral off). Scanning may continue. Enforced in the radio `actual`, gated by shared state. |
| Replay | Bounded by the epoch acceptance window (`KEY_SCHEDULE.md` §6). Widening skew tolerance widens the replay window one-for-one; that trade is stated there. |
| Covert channel inside our own frame | Closed by construction: no reserved bytes (§3.1), reserved flag bits rejected (§3.3), `tx_power_cal` allow-listed (§3.2), strict length (§3.6). |
| Permanent linkability via bonding | Forbidden (§7.3), with a mandated PR checklist item. |
| Scanner de-anonymisation under Carrier B | **New in v0.** Carrier B forces the scanner to transmit (§5.5). Partially mitigated by caching and connection caps; not eliminated. Carrier A scanners remain fully passive. |
| **The operator itself** | **New in v0.1 (ADR-008).** `account_key` is server-issued and server-retained, so the operator can derive every eid any account will ever broadcast, past and future, and attribute any capture to an account. Conceded deliberately; conditions are ADR-008 M1-M7. Bounded in practice by holding no captures — `KEY_SCHEDULE.md` §10.3 **P1**, which forbids operating or ingesting from any BLE scanning network and forbids any endpoint that accepts observed eids. **Nothing on air changed** (`KEY_SCHEDULE.md` §10.1). |
| **Multi-device eid collision** | **New in v0.1.** Two devices sharing an `account_key` broadcast an identical eid from two locations, which bridges their MACs permanently and turns a stationary second device into a fixed-location oracle for the account's live eid. Blocked in v0: **exactly one advertising device per account**, others scan-only (`KEY_SCHEDULE.md` §9.3). Unblocking requires a device discriminator in the derivation, i.e. a protocol version change (§9.5). |
| Self / reflected / replayed own eid | **New in v0.1.** A receiver MUST drop any frame carrying one of its own eids in its own accepted window (`E_SELF_EID`, `KEY_SCHEDULE.md` §9.6). Also the cheapest local detector for both duplicate broadcast and replay of one's own identity. |

---

## 10. Open items this spec cannot settle

Carried to the report and to `.claude/memory/20-state.md`. Not resolvable at a desk.

**ON THE CRITICAL PATH (android-first):**

1. **Whether the Android advertiser address co-rotates with the eid, per device
   model — B8, `KEY_SCHEDULE.md` §4.3.** Requires an external sniffer. Under
   android-first this is the top technical risk in the project, not one of
   several. §4.3.4 gives the decision rule for every outcome; §5 gives the
   hardware and the pass criterion.
2. SIG 16-bit UUID allocation (§4.1) — external dependency, weeks of lead time,
   now sequenced behind legal-entity formation (decision 34).
3. The four Android × Android cells of §5.0.
4. Real `tx_power_cal` values per Android device model (`calibration/`).
5. **Whether multi-device broadcast is wanted before launch**
   (`KEY_SCHEDULE.md` §9.5). Not a measurement — a product decision, and one
   that is an order of magnitude cheaper before launch than after, because
   enabling it changes the derivation and therefore every crypto vector.

**DEFERRED WITH iOS (decision 33) — not cancelled, not risks on the current
plan, owed again the day iOS returns:**

6. Whether `CBPeripheralManager` actually rotates the RPA when advertising is
   restarted at an epoch boundary (`KEY_SCHEDULE.md` §5).
7. Carrier B cost: real connect-and-read latency and battery for iOS↔iOS. Note
   that an Android-only spike exercises Carrier B **not at all**, so this
   returns entirely unpriced.
8. Whether iOS overflow-area matching works for a 16-bit UUID as reliably as a
   128-bit one. Undocumented by Apple; must be measured.
9. The twelve DEFERRED cells of §5.0.
10. Whether F3 (§5.3) works — and if F3b is taken, an ADR amendment to invariant
    4's field layout.

**RESOLVED SINCE v0:**

11. ~~Whether `account_key` is device-generated or server-issued.~~
    **RESOLVED by ADR-008: server-issued** (decision 32, blocker B10).
    See `KEY_SCHEDULE.md` §2, §8, §9, §10. This spec's earlier recommendation
    (device-generated) was overruled with the cost stated in advance. Invariants
    4 and 5 are unchanged and re-verified field by field in
    `KEY_SCHEDULE.md` §10.1. It introduced two new obligations rather than
    closing cleanly: rotation (§8) and the multi-device hazard (§9).

---

## 11. Does Phase 0 pass? — honest position from the protocol side

Asked directly by ios-swift. Answered directly, with the caveat that **no cell
of §5.0 has been measured** and this is inference, not evidence.

> **Scoping note added 2026-08-04 (decision 33, android-first).** The odds table
> in §11.1 and the prose below were written when Phase 0 covered both platforms.
> **They are preserved unrevised on purpose** — a forecast that gets quietly
> edited after the scope changes is worthless as a forecast. Read them with two
> corrections applied:
>
> 1. Every iOS row is now **DEFERRED**, not open. It is neither passing nor
>    failing; it is not being asked this quarter.
> 2. **The Android-scoped go/no-go now rests almost entirely on one row: "RPA
>    co-rotates on enough hardware, with a documented excluded set" (~70 %).**
>    When iOS carried half the surface, a bad Android RPA result cost coverage.
>    Under android-first it costs the product. B8 is not one of three risks any
>    more — it is the risk, and everything else in this section is secondary to
>    it. `KEY_SCHEDULE.md` §4.3 is written to be executed first.

**The moat is not dead. But on iOS it is probably foreground-first, and the
offline promise needs re-wording before launch, not after.**

What I believe survives:

- **Tier 1 (both foreground) works on all four platform pairings.** Android
  emitters advertise the full frame passively. iOS emitters are reachable via
  Carrier B, and possibly passively via F3 if it measures well. Radar as a
  foreground destination — which ADR-004 already mandates — is intact. The core
  demo (open Radar → discover a peer → wave → handshake → offline message) is
  achievable.
- **Android↔Android background works** with a foreground service.
- **iOS↔iOS background works** via overflow + Carrier B, slowly and expensively.
  Our audience skews iOS, which helps more than it sounds.

What I do not believe survives:

- **Android scanning a backgrounded iPhone. Tier 3. This will not work, and no
  protocol change fixes it.** It is not a number the spike can improve; the
  spike can only confirm it. Plan for it now.
- **"Always-on ambient discovery" as a product claim.** On iOS the honest
  ceiling is opportunistic. If any marketing or onboarding copy implies
  continuous background presence, it needs rewriting.

The three findings that could still turn this into a genuine no-go, none of
which I can produce from a desk:

1. **RPA co-rotation, per device model** (`KEY_SCHEDULE.md` §4.3, §5). Raised
   independently by both platform agents. Neither OS gives an app any control
   over the resolvable private address; on Android whether a stop→start forces a
   fresh RPA is **controller-firmware dependent, so it varies by OEM and by SoC,
   not by platform**. If the MAC does not rotate with the payload, invariant 5
   is unsatisfiable on that hardware and the device is linkable across
   rotations — the exact attack the invariant exists to stop.
   **This is the single highest-severity open question in the protocol.** It
   outranks battery, latency, and the iOS emit problem, because those three cost
   us features and this one costs us the reason the product is allowed to exist.
   It should be the first thing the rig measures, and it needs a sniffer.
2. **Carrier B battery cost at realistic peer density.** If connect-and-read
   scales as badly as I fear (§5.5), iOS may not hold <4 %/hr with more than a
   handful of peers in range. That would push iOS to F4 — foreground only, no
   background discovery at all. Survivable, but it must be a decision, not a
   discovery in month nine.
3. **Whether any of §5.0 behaves as predicted at all.** Sixteen cells, zero
   measured.

### 11.1 Odds, since I was asked directly

Numbers below are judgement, not data. Recorded so they can be checked against
the spike rather than quietly revised afterwards.

| Outcome | My estimate |
|---|---|
| Tier 1 (both foreground) works well enough to ship the core product | **high, ~85 %** |
| Android↔Android background works acceptably | **high, ~80 %** |
| iOS↔iOS background works within the battery budget | **coin-flip, ~50 %** |
| Android ↔ backgrounded iOS ever works | **very low, <5 %.** Treat as a no. |
| RPA co-rotates on *all* target hardware | **low, ~30 %** |
| RPA co-rotates on *enough* hardware, with a documented excluded set | **~70 %** |

**Net: I expect Phase 0 to pass, with the offline moat foreground-first on iOS
and a named list of Android models that cannot advertise safely.** That is a
narrower product than the one in the plan document, and it is still a real one —
"open Radar when you're out, find people near you, no internet needed" survives
all of the above intact. What does not survive is "Radius quietly notices people
near you all day". If that sentence is load-bearing for the business case,
somebody needs to know now.

The outcome I would call a true no-go: RPA co-rotation failing broadly across
both platforms. In that case rotation is theatre, the privacy architecture is
unenforceable at the layer that matters, and the honest options are to ship
scan-only on affected hardware or not to ship proximity at all. I do not think
that is the likely outcome, but it is the one worth measuring first, because it
is the only one that cannot be designed around afterwards.

What I am not willing to do, stated in advance so the conversation is short
later: if the spike comes back and the only path to iOS background discovery is
a stable on-air identifier (F7), the recommendation is **no iOS background
discovery**, not a weakened invariant 4. That trade is not mine to make and I do
not believe it is the founder's to make either — it is the difference between
this product and the ones it is defined against.
