# ADR-008 · account_key is server-issued

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** founder
**Relates to:** ADR-004 (BLE proximity protocol), safety invariants 4 and 5

## Context

`account_key` is the root of the BLE identity schedule:

```
account_key --HKDF--> daily_key --HKDF(day, epoch)--> ephemeral_id (16B, rotates 15 min)
```

Everything a device broadcasts over BLE, for the entire life of the account, derives from this one value.

Provenance was never settled. `docs/TECH_STACK_AND_PLAN.md` §6.2 says server-issued. ADR-004 is silent. BLE wire spec v0 assumed device-generated and flagged the conflict rather than resolving it (blocker B10). The two options are not interchangeable — they produce materially different privacy products.

## Decision

**`account_key` is server-issued.** The server generates it, transmits it to the device at account creation, and retains it.

This was a founder decision, taken with the consequence below stated in advance.

## What this costs — stated plainly, because we sell honesty

**The server can derive every ephemeral ID any user will ever broadcast.** Past, present, and future. Given a BLE capture from any time and place, the operator can determine which account produced it.

The concrete consequences:

1. **Retroactive deanonymisation is possible.** A captured `ephemeral_id` from six months ago can be attributed to an account, because `daily_key` and every `ephemeral_id` are deterministic functions of `account_key`, the day, and the epoch.

2. **We cannot say "we are unable to identify who was broadcasting."** We can. Under subpoena, warrant, or a National Security Letter equivalent, the honest answer is that we hold the capability. Device-generated keys would have let us answer "we cannot." We have chosen not to be able to give that answer.

3. **A database breach is a permanent, retroactive location-history breach**, not a point-in-time credential breach. `account_key` cannot be invalidated the way a password can — an attacker with a historical BLE capture plus a stolen key table reconstructs where users were. Rotation limits future exposure, never past.

4. **The privacy policy and any in-product privacy copy must reflect this.** The onboarding screen currently reads "Your location is never shared / Pure proximity detection." That remains true — no coordinates are transmitted or stored. But any copy implying *we ourselves* cannot correlate broadcasts to an account would be false and must not ship. Marketing review is a release gate, not a nicety. See invariant list and the dark-patterns ban in `CLAUDE.md`: the same honesty standard that governs the paywall governs privacy claims.

**What is genuinely gained.** Multi-device support: a second device derives the same identity without a key-transfer dance. Account recovery: losing a phone does not orphan the identity, matches, or encounter history. Abuse investigation: a credible harassment report can be tied to a broadcasting device. Key rotation and revocation are server-driven and therefore actually operable. Under device-generated keys, each of these ranges from awkward to impossible.

This is a real trade, not a mistake. It buys operability and recoverability with unlinkability-from-the-operator. It does **not** buy anything from third parties — a passive BLE eavesdropper still learns nothing, which is what invariants 4 and 5 exist to guarantee. The threat model that weakens is *the operator, and anyone who compels or breaches the operator*.

## Mandatory mitigations

These are not optional hardening. They are the conditions under which this decision is acceptable.

**M1 — Encrypted at rest, separately.** `account_key` is stored encrypted with a data-encryption key held in OpenBao, never in the application database's own encryption domain. A Postgres dump alone must not yield usable keys.

**M2 — Dedicated table, no joins by default.** `account_key` lives in its own table, not on the accounts row. No ORM-style eager load can drag it into an unrelated query result. (`sqlc` + raw SQL makes this enforceable by review.)

**M3 — Access is audited and break-glass.** No application service reads `account_key` in normal operation — the device holds its own copy after issuance. Server-side reads happen only in an explicit, logged, alerting break-glass path. A read that is not accompanied by an audit row is an incident.

**M4 — Rotation must exist and must be tested.** `account_key` is rotatable. Rotation invalidates future derivation only; this limits blast radius going forward and must not be described internally or externally as undoing past exposure.

**M5 — Hard delete means hard delete.** Account deletion destroys `account_key` at the end of the 30-day grace period, including in backups, per the existing retention rule. A surviving key in a backup is a surviving ability to deanonymise.

**M6 — Transparency.** The privacy policy states, in plain language, that Radius can associate proximity broadcasts with an account, and under what circumstances it would. A law-enforcement response policy exists before launch, not after the first request arrives.

**M7 — Issuance is TLS-pinned and single-use.** `account_key` crosses the network exactly once, on a certificate-pinned channel, and is never re-fetchable by an authenticated client. A "re-download my key" endpoint would turn every account takeover into a full identity compromise.

## Alternatives considered

**Device-generated.** Strongest privacy: the operator cannot derive what it never held, and can truthfully say so. Rejected by the founder for the operability cost — multi-device and account recovery both become hard problems, and abuse investigation becomes near-impossible.

**Split derivation — `account_key = HKDF(server_seed, device_secret)`.** The server alone cannot derive; the device contributes entropy the server never sees. This preserves most of the operator-unlinkability property while keeping server-side issuance and revocation. Rejected for v1 because recovery requires escrowing `device_secret`, and escrowing it under anything the server can reach collapses the scheme back to server-issued — while escrowing under a user passphrase reintroduces the "lost passphrase = lost account" problem the founder rejected. **This is the most likely revisit.** It is written down here so the option is not lost.

## Consequences

**Good.** Multi-device, recovery, revocation, and abuse investigation all become tractable. Fewer support paths that end in "your account is unrecoverable."

**Bad / accepted.** The operator is now inside the threat model for BLE unlinkability. Legal-compulsion exposure is real and permanent. A key-table breach is retroactive. Privacy copy is constrained by what is actually true.

**Unchanged.** Invariants 4 and 5 are untouched — they govern what a *third-party observer* on the air can learn, and that is still nothing. Decisions 26-30 (no bonding, UTC-synchronised rotation, RSSI handling) all stand.

**Reversibility:** Moderate for new accounts, poor for existing ones. Migrating to device-generated or split derivation later means re-keying every account and accepting that historical broadcasts remain derivable from retained keys. Cheapest before launch.

## Revisit when

Split derivation (above) becomes viable — most likely once passkey/secure-enclave-backed recovery is good enough that "recover without the operator holding the root" stops being a support nightmare. Or on any legal-compulsion event that makes the retained capability a liability worth engineering away.
