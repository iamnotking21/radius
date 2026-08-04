# mobile/protocol — BLE wire spec + conformance vectors

**Owner:** ble-protocol. Exactly one writer.
**This directory is prose and data. No implementation code lives here.**

The single Kotlin implementation lives in `mobile/shared/protocol/` (commonMain,
ADR-007) and must pass every vector in `vectors/`. Both platforms consume that
one implementation; the vectors are the regression net that proves it still
matches the spec.

## Read in this order

| File | What it settles |
|---|---|
| `SPEC.md` | byte layout, flags, TX power quantisation, service UUID, carriers, GATT table, radio parameters. **§5 is the go/no-go section.** |
| `KEY_SCHEDULE.md` | `account_key → daily_key → ephemeral_id`; where `account_key` comes from (§2, ADR-008); MAC/RPA co-rotation (invariant 5, §4-§5, **B8**); resolution, clock skew, replay window; **root-key rotation and the seam (§8)**; **multi-device (§9)**; **threat model, operator included (§10)** |
| `STATE_MACHINE.md` | IDLE → … → SESSION; timeouts; failure edges; backgrounding; Bluetooth-off; ghost mode |
| `BANDING.md` | Kalman → normalise → 4 bands + hysteresis → display jitter; why RSSI never leaves the module |
| `vectors/index.json` | manifest, error codes, provenance of every generated value |
| `calibration/` | TX power class table. **placeholder, no measured data** |

## Status

**v0.1 DRAFT. Spec only. Contract-first: this lands before implementation.**

- No Kotlin has been written against it.
- No vector has ever been executed by any product code — none exists yet.
- Nothing in it has touched a radio.

v0.1 (2026-08-04) absorbed two founder decisions: **ADR-008** — `account_key` is
server-issued (decision 32) — and **android-first** (decision 33).
**No byte of the frame changed and no existing vector was regenerated.** 34
vectors were *added*, for rules that did not exist before ADR-008 (key ring,
rotation seam, self-eid rejection). See `vectors/index.json`
→ `unaffected_by_ADR_008` for why churning the other 65 would have been harmful.

The values in `vectors/key_schedule.json`, `vectors/key_rotation.json` and
`vectors/display_jitter.json` are real HKDF-SHA256 and HMAC-SHA256 outputs
generated with OpenSSL 3.5.6 (verified first against RFC 5869 test case 1), and
`vectors/banding.json` was produced by executing a reference implementation of
`BANDING.md`. They are computed, not invented — and the key-schedule and
key-rotation values have since been re-derived by a second, independent
implementation that agrees. That makes them trustworthy as known answers; it does
not make them evidence that the protocol works.

## The four things most likely to be got wrong

1. **`ephemeral_id` and the MAC rotate together, or neither rotates.** One
   un-rotated field defeats every rotating one and reassembles a permanent track
   out of "rotating" identifiers. `KEY_SCHEDULE.md` §4.1 has the attack written
   out. Neither OS gives an app control over this, and on Android the behaviour
   is controller-firmware dependent. It needs a sniffer to answer.
   **Under android-first this is the top technical risk in the project (B8);
   §4.3 and §5 are written to be executed first.**
2. **Never bond, never pair.** A BLE bond hands the peer an IRK — a permanent
   key for resolving this device's private address. One `createBond()` voids
   invariant 5 completely, regardless of how correct everything else is.
   `SPEC.md` §7.3.
3. **Exactly one device per account may advertise.** Two devices sharing an
   `account_key` broadcast an identical `ephemeral_id` from two places, which
   permanently bridges their MACs and turns a stationary second device into a
   live-eid oracle at a known address. `KEY_SCHEDULE.md` §9.
4. **The key ring is evaluated per epoch, never cached across a rotation
   boundary.** Resolving the active key once per table build produces exactly one
   wrong epoch per rotation and passes every test that does not straddle a seam.
   `KEY_SCHEDULE.md` §8.4; `vectors/key_rotation.json` straddles seams on
   purpose.

## Testing

Vectors run on the JVM and prove the codec, the key schedule and the banding
arithmetic are correct and identical across consumers.

**They prove nothing about BLE.** Discovery latency, battery, band accuracy
versus real distance, and RPA co-rotation require real devices at real distances,
plus an external BLE sniffer for the rotation check. **Simulator BLE results are
worthless and must never be reported as a pass** (`CLAUDE.md` golden rules,
`mobile/CLAUDE.md` testing).
