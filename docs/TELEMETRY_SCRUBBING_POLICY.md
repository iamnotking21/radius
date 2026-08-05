# Telemetry & Crash-Report Scrubbing Policy

**Status:** Binding requirement. Applies before any crash reporter, analytics SDK, or remote log sink is integrated.
**Owner:** orchestrator (policy) · devops-tencent (GlitchTip config) · platform agents (client-side scrubbing)
**Closes:** CLAIMS_REGISTER.md gap G4

---

## Why this exists

Every privacy protection in this project has a defined boundary. Crash reporting crosses all of them at once.

A crash report is not a stack trace. It is a stack trace **plus** local variables, plus breadcrumbs, plus the last N log lines, plus arbitrary "context" attached by whatever code was running. It is, by design, a mechanism for exfiltrating the contents of a process to a server so a human can understand what went wrong.

That is exactly the thing we spent this project preventing. `RawSighting.toString()` redacting RSSI is currently the *only* barrier between a debug breadcrumb and a durable server-side record of signal strength — and a redaction that exists in one `toString` does not survive a library that reflects over object fields.

The failure mode is not malice. It is an engineer adding a crash reporter on a Tuesday to fix a bug, with three lines of setup and no idea that decision 28 exists.

---

## The rules

### R1 — Nothing is uploaded that we would not put in the privacy policy

If a field cannot be described in plain language to a user, it does not leave the device. This is the general rule; everything below is a specific case of it.

### R2 — Absolutely never uploaded

These are not "scrub if present". They must be structurally incapable of reaching a reporter.

| Never | Why |
|---|---|
| `account_key`, `daily_key`, or any key material | Root of the entire BLE identity system. ADR-008. |
| `ephemeral_id`, in any encoding — raw, hex, base64, truncated | Decision 36 (P1). Uploading observed ephemeral IDs is the specific thing we forbade by name. |
| Raw, filtered, or adjusted **RSSI** | Decision 28. RSSI is location data — three receivers multilaterate. |
| Bluetooth hardware addresses (own or peer) | A stable identifier. Invariant 4 and 5. |
| Message plaintext or any decrypted content | Invariant 9. |
| Any coordinate, bearing, or heading | Invariant 1. Should not exist to be uploaded, but assert it anyway. |
| SQLCipher keys, Keystore material, auth tokens | Obvious, stated so the list is complete. |

### R3 — Uploaded only when aggregated or coarsened

| Allowed form | Not allowed |
|---|---|
| Proximity **band** (HERE/CLOSE/AROUND/EDGE) | The dBm it came from |
| Epoch index, or a timestamp rounded to the epoch | Millisecond timestamps correlated with a peer |
| Count of peers seen | Which peers, or any per-peer identifier |
| Coarse region (geohash-5 at most) | Anything finer |

### R4 — Redaction lives in the type, not at the call site

A `toString()` redaction is necessary but not sufficient, because reflective serializers ignore it. Any type carrying protected data must **also** be excluded from reporting by explicit denylist configuration in the reporter itself.

Two independent mechanisms. If one is bypassed the other holds.

### R5 — Breadcrumbs are logs, and logs are subject to R2

Crash reporters capture recent log output as breadcrumbs. A `Log.d` that was harmless on a developer's machine becomes a durable server-side record the moment a reporter is added. **Every rule above applies to logging, in every build variant, with no exception for debug.**

The one deliberate exception is the Phase 0 spike harness, which writes RSSI and ephemeral IDs to a local file by design — it is confined to `src/debug`, requests no network permission, and its output leaves only via a USB cable. That exception ends when the harness does.

### R6 — Self-hosted is not an exemption

GlitchTip is self-hosted, which means a crash report reaches *our* server rather than a third party's. That reduces the number of parties involved. It does **not** make the data safe to collect.

Under ADR-008 we already hold the capability to link a broadcast to an account. A server-side store of observed ephemeral IDs is precisely the corpus that would make that capability dangerous, and decision 36 forbids holding one. **A crash reporter that captures ephemeral IDs would build that corpus by accident.** Self-hosting makes it our corpus rather than someone else's, which is worse, not better.

### R7 — Opt-in, and revocable

Crash reporting is off by default and requires explicit consent. Consent is revocable from Settings, and revoking it deletes reports already collected.

### R8 — No reporter ships without a gate

Before any crash reporter, analytics SDK, or remote log sink is integrated, the RSSI egress gate (G1) must be extended to cover it, and this document must be re-read and, if necessary, revised. Integrating a reporter is a **contract-touching change** and goes through the orchestrator.

---

## Verification required at integration

- [ ] Reporter denylist configured for every protected type (R4)
- [ ] Breadcrumb capture reviewed against R2 and R5
- [ ] A deliberately crashed build inspected — the **actual uploaded payload**, not the configuration. Read what arrived at the server.
- [ ] Consent flow implemented and revocation tested (R7)
- [ ] G1 gate extended to the reporter's own API surface (R8)
- [ ] Privacy policy §1 "Technical data" updated to describe what is actually collected
- [ ] `CLAIMS_REGISTER.md` row added, with the trigger that would falsify it

The third item is the one that matters. Configuration describes intent; the uploaded payload is fact. Every crash-reporting privacy failure in the industry has been a gap between the two.

---

## Escalation

Any proposal to relax R2 goes to the founder, not to a code review. R2 is not a performance or convenience trade — each row is a safety invariant or a numbered decision, and weakening one is weakening the product's stated premise.
