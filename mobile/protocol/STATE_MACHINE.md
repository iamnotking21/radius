# Radius Discovery State Machine — v0.1

**Owner:** ble-protocol · **Binds:** ADR-004, **ADR-008**, safety invariants 6, 7, 10

> **v0.1, 2026-08-04 (ADR-008).** Two new guards — `keyed` and `may_advertise` —
> two new device transitions (D7, D8), one new `IDLE` reason (`not_keyed`), and
> one new attack row (`E_SELF_EID`). No state was added, removed, or renamed.
> Not hardware-validated; nothing here has run.
**One implementation:** `mobile/shared/protocol/` (commonMain), per ADR-007.
The radio `expect`/`actual` layer drives this machine with events; it does not
contain a second copy of it.

```
IDLE → SCAN → RESOLVE → WAVE_SENT ┐
                    │             ├→ HANDSHAKE → SESSION
                    └→ WAVE_RECV ─┘
```

---

## 1. The machine is two machines. Say so or the platforms will disagree.

The seven canonical states are not one flat sequence. Two of them are
**device-scoped** and five are **per-peer**, and an implementation that models
them as one machine will produce nonsense the moment a second peer appears.

| State | Scope | Meaning |
|---|---|---|
| `IDLE` | device | not discovering. Includes "cannot" (radio off, no permission) and "will not" (Radar closed). Carries a `reason`. |
| `SCAN` | device | scanning, and advertising unless suppressed. Multiple peer machines run underneath. |
| `RESOLVE` | per-peer | transient *and* resting. Frame resolved to a known account; peer is tracked and visible on Radar; no wave either way yet. |
| `WAVE_SENT` | per-peer | we have waved. Their wave not yet verified. |
| `WAVE_RECV` | per-peer | their signed wave verified. We have not waved back. |
| `HANDSHAKE` | per-peer | mutual wave verified independently by both sides. X3DH in progress. |
| `SESSION` | per-peer | Double Ratchet established. **Durable** — survives the peer leaving. |

The device must be in `SCAN` for a peer machine to be created or advanced. When
the device leaves `SCAN`, every peer machine is suspended, not destroyed (§7).

`RESOLVE` doing double duty as transient and resting state is deliberate — it
matches the canonical list, and there is no useful distinction between
"resolving" and "resolved, waiting for the user", since resolution is an O(1)
table lookup (`KEY_SCHEDULE.md` §6.1).

---

## 2. Guards

Six orthogonal conditions gate the machine. They are not states; they are
inputs evaluated on every transition and on every change to themselves.

| Guard | True when | Effect when false |
|---|---|---|
| `radio_available` | Bluetooth on, permissions granted, adapter usable | device → `IDLE(reason)`; see §8 |
| `app_phase` | `FOREGROUND` \| `BACKGROUND` \| `TERMINATED` | changes parameters, not states; see §7 |
| `visible` | ghost mode off **and** not in a blackout zone | advertising stops; scanning continues (§9) |
| `verified` | account is verified (invariant 6) | device stays `IDLE(not_verified)`. Unverified accounts do not appear on Radar and do not scan. |
| `keyed` | a key ring is held **and** it has an active `kid` at the current epoch (`KEY_SCHEDULE.md` §8.2) | device stays `IDLE(not_keyed)`. **New in v0.1.** A device MUST NOT scan or advertise before `account_key` issuance has completed and been ACKed (`KEY_SCHEDULE.md` §2.2). There is no placeholder-key path. |
| `may_advertise` | the server-assigned device role is `ADVERTISE` (`KEY_SCHEDULE.md` §9.3) | advertising never starts; scanning continues. **New in v0.1. Fails CLOSED** — a device that has never been granted the role does not advertise, including offline, including on first run, including in debug builds. |

`visible` and `may_advertise` are the two guards that produce an asymmetric
state: **see but not seen.** Both suppress the peripheral role entirely and leave
the central role running. They differ in origin and in what the user is told:
`visible` is the user's own choice and is reversible in one tap (invariant 10);
`may_advertise` is a server-assigned account-level role and is **not** a
user-facing toggle. A device sitting at `may_advertise = false` MUST tell the
user plainly that it can see people nearby but cannot be seen — silently shipping
a permanently undiscoverable device is not acceptable
(`KEY_SCHEDULE.md` §4.3.6, §9.3).

---

## 3. Device transitions

| # | From | To | Trigger | Notes |
|---|---|---|---|---|
| D1 | `IDLE` | `SCAN` | Radar opened, or background wake, with all guards true | start scan; start advertising iff `visible` **and** `may_advertise` |
| D2 | `SCAN` | `IDLE(user)` | Radar closed / ghost mode with scanning disabled | peer machines suspended |
| D3 | `SCAN` | `IDLE(radio)` | `radio_available` → false | §8 |
| D4 | `SCAN` | `IDLE(unverified)` | `verified` → false | e.g. verification revoked mid-session |
| D5 | `SCAN` | `SCAN` | epoch boundary | rotate: stop adv → **re-evaluate the key ring for the NEW epoch** → new eid → restart adv (`KEY_SCHEDULE.md` §4.2, §8.4); clear address-keyed caches |
| D6 | `IDLE` | `IDLE` | any guard change that does not satisfy all guards | update `reason` for honest UI |
| D7 | `SCAN` | `SCAN` | advertising role revoked (`may_advertise` → false) | stop advertising **immediately**; scanning continues; tell the user. Role hand-off ordering is normative — `KEY_SCHEDULE.md` §9.3 |
| D8 | `IDLE(not_keyed)` | `SCAN` | key ring received and ACKed | first entry into discovery for a new or recovered device |

`IDLE` reasons are enumerated and MUST be surfaced honestly rather than
collapsed into a generic "unavailable": `user`, `radio_off`, `permission_denied`,
`unverified`, `not_keyed`, `blackout`, `battery_critical`, `terminated`.

**D5 is where the rotation seam lives, and it is the transition most likely to be
implemented wrongly.** The key ring MUST be re-evaluated for the new epoch on
every boundary, not resolved once and cached. An implementation that caches the
active `kid` across a rotation produces exactly one wrong epoch per rotation per
user, and it will pass every test that does not straddle a seam
(`vectors/key_rotation.json` straddles seams on purpose). D5 is otherwise
byte-for-byte identical whether or not a root-key rotation occurred — a rotation
MUST NOT be observable as a change in radio behaviour (`KEY_SCHEDULE.md` §8.5).

---

## 4. Per-peer transitions

| # | From | To | Trigger | Timeout / failure |
|---|---|---|---|---|
| P1 | — | `RESOLVE` | frame decoded **and** eid resolved to a permitted account | unresolvable → no peer instance created at all (§6.1) |
| P2 | `RESOLVE` | `RESOLVE` | further frames from same account | updates banding; refreshes `last_seen` |
| P3 | `RESOLVE` | *destroyed* | no frame for `DROP_MS` (90 s) | peer removed from Radar |
| P4 | `RESOLVE` | `WAVE_SENT` | local user taps Wave | delivery per §5.2; on permanent failure → back to `RESOLVE`, wave queued |
| P5 | `RESOLVE` | `WAVE_RECV` | inbound wave written to `wave_inbox` **and signature verified** | verification failure → drop, §6.2 |
| P6 | `WAVE_SENT` | `HANDSHAKE` | inbound wave verified | both sides now hold two verified waves |
| P7 | `WAVE_RECV` | `HANDSHAKE` | local user waves back, delivery confirmed | |
| P8 | `HANDSHAKE` | `SESSION` | X3DH complete **and** first ratchet message authenticated both ways | timeout 30 s → return to prior wave state, retry with backoff |
| P9 | `HANDSHAKE` | `WAVE_SENT` / `WAVE_RECV` | handshake timeout or crypto failure | 3 failures → suspend attempts for 10 min |
| P10 | `SESSION` | `SESSION` | link up / link down | session is durable; see §4.1 |
| P11 | any of `WAVE_*`, `HANDSHAKE` | *suspended* | device leaves `SCAN` | state persisted, not discarded (§7) |
| P12 | any | *destroyed* | peer blocked, or account deleted, or user unmatches | immediate; §6.1 |

**Waves are never inferred.** A device enters `HANDSHAKE` only after it has
*itself* verified a signature over the peer's wave, using an identity key it
already holds. It never accepts the peer's assertion that a mutual wave exists.
Both sides reach mutuality independently and symmetrically; there is no
"initiator" whose claim is trusted. This is what makes invariant 6 (chat unlocks
only on mutual wave) enforceable on-device and offline, with no server in the
loop to arbitrate.

### 4.1 `SESSION` is durable; the link is not

`SESSION` means a Double Ratchet session exists and is persisted. It does not
mean a GATT connection exists. Two sub-conditions:

```
SESSION / LINK_UP     GATT connected, messages flow over BLE
SESSION / LINK_DOWN   no connection; messages queue locally, or route over the
                      network path if online
```

Losing the peer, Bluetooth going off, the app terminating, or a reboot all move
the session to `LINK_DOWN`. **None of them ends the session.** Ratchet state is
persisted in the encrypted local DB and resumes when the peer is seen again.
Destroying ratchet state because a peer walked away would silently break message
continuity and force a re-handshake, which the user experiences as their
conversation partner vanishing.

---

## 5. Timeouts, retries, budgets

### 5.1 Discovery

| Item | Value |
|---|---|
| Warm-up before a band is displayable | 3 accepted samples (`BANDING.md`) |
| Peer marked STALE | 30 s without a frame |
| Peer dropped | 90 s without a frame |
| Carrier B payload cache | 60 s minimum per peer address |
| Negative-resolution cache | until end of current epoch |
| Epoch rotation | on the UTC 15-minute boundary, no jitter (`KEY_SCHEDULE.md` §3) |

### 5.2 Wave delivery

Online path first when the network is available (server delivers, peer gets a
push). Offline path over GATT otherwise. Both produce the same state changes;
only `transport` differs (`net` / `ble`), and the UI labels it (glossary).

| Step | Timeout |
|---|---|
| GATT connect | 10 s |
| ATT MTU exchange | 3 s (`MTU < 185` ⇒ `E_MTU_TOO_SMALL`, tear down) |
| Service / characteristic discovery | 5 s |
| `wave_inbox` write (all chunks) | 5 s |
| `wave_receipt` read or indication | 5 s |
| **Total per attempt** | **20 s hard cap** |

Retry backoff: 2 s, 8 s, 30 s. Maximum 3 attempts per sighting. After that the
wave is queued on-device with a **7-day TTL** and retried when the peer is next
resolved. A queued wave MUST NOT be surfaced to the user as delivered.

### 5.3 Handshake

| Step | Timeout |
|---|---|
| X3DH prekey exchange | 15 s |
| First ratchet message, each direction | 15 s |
| **Total** | **30 s** |

3 consecutive failures ⇒ suspend handshake attempts with this peer for 10 min.
This is a battery guard and an abuse guard: an attacker who can make handshakes
fail repeatedly would otherwise have a free connection-drain primitive.

### 5.4 Connection budget

Per `SPEC.md` §7.4: 2 concurrent inbound connections, 1 per peer address per
30 s, 10 inbound per minute globally then refuse for 5 min, 3 failed
verifications ⇒ teardown and 10 min address quarantine. **All address-keyed
quarantine and cache structures are cleared at the epoch boundary** — retaining
them across a rotation would reintroduce cross-rotation linkage on our own side
(`KEY_SCHEDULE.md` §4.2).

---

## 6. Failure edges

### 6.1 Things that must produce *no observable effect*

| Condition | Behaviour |
|---|---|
| Frame fails decode (`SPEC.md` §3.6) | drop silently, count by error code, no peer instance |
| eid does not resolve | drop, cache negative for the epoch, **no peer instance** |
| eid resolves to a **blocked** account | **indistinguishable from unresolvable.** No peer instance, no UI, no counter visible to the user, no log entry containing the account. Enforced by the key simply not being in the candidate set (invariant 7). |
| Bloom-filter hit in Open mode that is in the local blocklist | same as blocked — exact blocklist re-check happens *after* the filter hit and before any peer instance exists |
| Unsupported protocol version | drop silently |

An unresolvable or blocked frame MUST NOT increment any user-visible "people
nearby" count, and MUST NOT appear as an anonymous blip. Surfacing unresolved
presence would let a user census Radius devices in a space and would leak the
presence of people who have blocked them.

### 6.2 Things that indicate an attack

| Condition | Behaviour |
|---|---|
| Wave signature verification fails | drop, do not surface, increment failure counter, tear down connection after 3, quarantine address 10 min |
| Handshake crypto failure | as P9; never fall back to an unauthenticated path |
| Connection rate limits exceeded | refuse; do not queue |
| Reassembly limits exceeded (8 KB / 2 in flight / 10 s partial) | tear down connection |
| Peer advertises a reserved eid or illegal `tx_power_cal` | drop as malformed; these are not values a conformant implementation emits |
| **Observed eid is one of OUR OWN, in our own accepted window** | drop as `E_SELF_EID` **before resolution**. No peer instance, never surfaced, never counted. Keep a per-epoch counter (counter only, no payload). A non-zero count means either a duplicate-broadcast bug (`KEY_SCHEDULE.md` §9) or somebody is replaying our identity (§6.3 of `KEY_SCHEDULE.md`) — **both are worth knowing about, and this is the only place we can notice either.** New in v0.1. |

**No failure path may ever fall back to a weaker check.** There is no
"unverified wave" state, no "trust on first use", and no degraded handshake. A
verification failure is a dead end, always.

### 6.3 Things that are ordinary

| Condition | Behaviour |
|---|---|
| Peer disappears mid-wave | wave queues (§5.2), peer machine returns to `RESOLVE` or is dropped after 90 s |
| Peer disappears mid-handshake | P9, retry when seen again |
| Peer disappears mid-session | `SESSION / LINK_DOWN`, messages queue |
| MTU negotiated below 185 | tear down, report, retry once next sighting; do not chunk smaller |
| Clock skew within ±1 epoch | resolves normally (`KEY_SCHEDULE.md` §6.2) |
| Clock skew beyond the accepted window | peer simply not discovered. Surface a "check your device time" hint after repeated total-discovery failure, not per peer. |

---

## 7. App backgrounding

Backgrounding changes **parameters and capability tier, never the state graph.**
The machine does not gain a `BACKGROUND` state; peer machines are suspended and
resumed with their state intact.

### 7.1 Both platforms

| Change | Foreground → Background |
|---|---|
| Advertising interval | 250 ms → 1000 ms |
| Scan duty | 30 % → platform-governed |
| Capability tier | Tier 1 → Tier 2 or 3 (`SPEC.md` §5.4) |
| Discovery latency target | < 5 s → < 60 s |

Adaptive duty cycling applies on top: reduce further when stationary, below 20 %
battery, or when no peer has been seen for 10 minutes.

### 7.2 iOS

- Requires `bluetooth-central` and `bluetooth-peripheral` background modes.
- **The advertised service UUID moves to the overflow area.** Only another iOS
  device explicitly scanning for that exact UUID can match it. Android cannot.
  This is Tier 3 and is not fixable (`SPEC.md` §5.0, §5.4).
- Scanning MUST specify the service UUID filter. A nil filter returns nothing in
  the background.
- `CBCentralManagerOptionRestoreIdentifierKey` MUST be set, and the restoration
  delegate MUST rebuild peer machines from persisted state rather than assuming
  a cold start. Sessions are durable (§4.1) and must survive restoration.
- In-flight GATT work gets a short background execution window. Any operation
  that cannot complete MUST fail cleanly to a retry, never leave a half-written
  wave. Wave writes are idempotent by wave id for this reason.
- **If the user force-quits the app, CoreBluetooth stops entirely and there is
  no restoration.** No background discovery of any kind. Nothing recovers this;
  the product must not promise otherwise.

### 7.3 Android

- Background scanning requires a foreground service with its mandatory
  persistent notification.
- Scan filters are **mandatory**: unfiltered background scans return nothing on
  API 26+.
- Doze and app standby batch and defer scan results. Discovery latency in Doze
  is unbounded in practice.
- API 31+ requires `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`.
  `BLUETOOTH_SCAN` SHOULD be declared with `neverForLocation` — we do not derive
  location and must not request permissions implying we do (invariant 1).

### 7.4 Termination

`TERMINATED` → device `IDLE(terminated)`. All peer machines suspended, all
ratchet and wave-queue state already persisted. iOS may relaunch into the
background via state restoration; Android may be restarted by its foreground
service. Neither is guaranteed and neither may be promised.

---

## 8. Bluetooth off, permission revoked

| Event | Behaviour |
|---|---|
| Bluetooth turned off | device → `IDLE(radio_off)`. Tear down connections. Suspend peer machines. Persist ratchet and wave-queue state. Surface the real reason. |
| Permission revoked | device → `IDLE(permission_denied)`. Same teardown. |
| Adapter reset / stack crash | treat as radio off, then re-init on recovery |
| Bluetooth turned back on | device → `SCAN` **only if all guards true**. Full re-scan. |

**On recovery, peer *radio* state is rebuilt from scratch.** Address-keyed
caches, peer handles and quarantine lists MUST NOT be restored across a
Bluetooth power cycle, and MUST NOT be restored at all if the epoch changed
while the radio was off — a stale eid→peer map spanning a rotation is a
linkability structure, and it is one we would have built ourselves.

Application state — sessions, verified waves, queued waves — **is** restored.
The distinction is exactly: *radio-layer identifiers are ephemeral, cryptographic
relationships are durable.*

The UI MUST state the real reason. "Bluetooth is off" is a fact the user can act
on; a generic "Radar unavailable" is not, and it trains people to ignore it.

---

## 9. Ghost mode and blackout zones

| Mode | Advertising | Scanning | Effect |
|---|---|---|---|
| Normal | on | on | discoverable and discovering |
| Ghost (invariant 10) | **off** | on | see but not seen |
| Blackout zone | **off** | on (or off, by user preference) | no emission near home/work |

Both suppress the **peripheral role entirely** — advertising stops, it is not
merely thinned. A device that advertises less often is still trackable; a device
that does not advertise is not present on air at all.

Existing peer machines are unaffected: an established `SESSION` continues to
work, because the peer already knows you and consent already exists. Ghost mode
prevents *new* discovery of you; it is not an undo of relationships you have.

Ghost mode MUST be reachable in **one tap from the Radar screen** (invariant 10)
and MUST take effect immediately — advertising stopped before the tap animation
finishes, not at the next epoch boundary.

---

## 10. Diagram

```
        guards true / Radar opened
  IDLE ─────────────────────────────► SCAN ◄── epoch boundary: rotate eid + MAC
   ▲                                   │        (D5, both or neither)
   │   radio off · permission revoked  │
   └───────────────────────────────────┤ frame decoded + eid resolved
       ghost(scan off) · unverified    │ (unresolvable/blocked ⇒ nothing happens)
                                       ▼
                                   RESOLVE ─────── 90 s silence ──► destroyed
                                    │     │
                    user taps Wave  │     │  verified inbound wave
                                    ▼     ▼
                            WAVE_SENT     WAVE_RECV
                                    │     │
              verified inbound wave │     │ user waves back
                                    ▼     ▼
                                  HANDSHAKE ──30 s / crypto fail──► back, backoff
                                       │  X3DH + authenticated first message,
                                       │  verified independently by both sides
                                       ▼
                                   SESSION  (durable)
                                   ├─ LINK_UP    messages over BLE
                                   └─ LINK_DOWN  queued / network path
```

---

## 11. Conformance notes

- The machine is deterministic. Same event sequence ⇒ same state sequence, on
  both platforms, because there is one implementation (ADR-007).
- Timeouts are measured on a monotonic clock, never wall-clock. Wall-clock is
  used only for epoch derivation.
- Every transition that destroys a peer instance MUST also clear that peer's
  banding filter state. Reusing filter state across a destroy/recreate cycle
  would carry an RSSI history across a rotation boundary.
- **None of this is validated until it runs on real hardware between two real
  devices at real distances.** A simulator has no radio: it cannot produce a
  connection failure, an MTU negotiation, a Doze deferral, an overflow-area
  advertisement, or a lost peer. Simulator runs exercise the arithmetic and the
  transition table — which is what `vectors/` is for — and prove nothing about
  this machine's behaviour on air.
