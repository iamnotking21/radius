# ADR-004 · BLE proximity protocol and privacy design

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** CTO, security lead

## Context

This is the company. Radius must let two users within roughly 100 metres discover each other and exchange messages with no server involvement and no internet connection, while guaranteeing that neither party — nor any passive observer — learns anyone's location.

The threat model for a proximity dating app is unusually severe and unusually concrete. The adversaries are not abstract: a stranger with a Bluetooth scanner, an abusive ex-partner, an organised scam operation, a data broker, and — importantly — someone with physical access to a victim's unlocked phone. A design flaw here is not a bug report. It is someone getting hurt, followed by the end of the company.

## Decision

**Identity on the radio.** Adopt the key-derivation structure of the Apple/Google Exposure Notification protocol, which is the most heavily audited proximity privacy system ever deployed at scale:

```
account_key  --HKDF-->  daily_key (24h)  --HKDF(day, epoch)-->  ephemeral_id (16B, 15 min)
```

Only the ephemeral ID is broadcast. The advertising payload is at most 26 bytes and contains a version byte, the 16-byte ephemeral ID, a calibrated TX power byte, and flags. **Nothing else.** No name, no photo reference, no account ID, no coordinates.

BLE MAC addresses use **Resolvable Private Addresses rotating in lockstep with the ephemeral ID**. Rotating the payload without rotating the MAC defeats the entire scheme, so the two are treated as a single atomic control.

**Distance is banded, never measured.** RSSI passes through a Kalman filter over a 10-sample window, is normalised by advertised TX power, and maps to one of four bands with hysteresis: HERE (≥ −55 dBm), CLOSE (≥ −70), AROUND (≥ −82), EDGE (≥ −95). Any metre figure shown in the UI is generated from the band midpoint with deliberate jitter. **Bearing is never computed. The angle at which a person appears on the Radar canvas is randomised per session and carries no information.** These are security controls that happen to be rendered as UI.

**Mutual consent before any connection.** Discovery is connectionless. A GATT connection is opened only after both parties have waved. Each device independently verifies the other's wave via a signature it can check with a key it already holds — neither device trusts the other's claim of mutuality. Key agreement is X3DH, followed by Double Ratchet sessions via **vodozemac** (Apache 2.0, chosen over libsignal's AGPL).

**Single hop for v1.** Multi-hop mesh relay through intermediate devices is deferred to Phase 4. It is a substantial expansion of both the protocol and the abuse surface, and it is not required for the core product promise.

**Radar is a foreground destination.** See consequences below.

## Alternatives considered

**Ultrasonic or Wi-Fi Aware.** Better ranging in some conditions. Rejected on device support and on background execution restrictions that are worse than BLE's.

**Server-mediated proximity via GPS.** Vastly simpler, and what every competitor does. Rejected because it requires collecting precise location, which is exactly the data we have decided must not exist, and because it does not work offline — which is the entire differentiator.

**libsignal for E2EE.** The reference implementation, extremely well audited. Rejected on licence: AGPL is incompatible with shipping closed-source applications.

**Persistent per-user broadcast ID.** Trivially simple. Rejected — it is a permanent tracking beacon, and would be indefensible.

## Consequences

**Good.** No location data exists to leak, subpoena, or breach. Passive observers learn nothing durable. The offline capability is genuinely differentiated and difficult to copy. The privacy design is explainable to users, journalists, and regulators in plain language, which is itself a marketing asset.

**Bad / accepted costs.**

*The iOS background restriction is the dominant cost, and it is not fully solvable.* When an iOS app is backgrounded, the service UUID moves into an Apple-private "overflow" advertising area visible only to other iOS devices explicitly scanning for that exact UUID. The practical consequence is that **an Android device scanning for a backgrounded iPhone will frequently not find it.** No engineering effort removes this; it is a platform decision by Apple.

We accept this and design around it:
- Radar is presented as a **foreground destination you open**, not a passive background service. The product framing must never promise continuous background discovery.
- `CBCentralManager` state restoration and region-monitoring wakeups provide opportunistic background discovery where the platform allows.
- iOS↔iOS background discovery does work, and our target audience skews iOS, which softens the impact.

*Other accepted costs.* Banded distance frustrates users who want precision — the UI copy must set expectations honestly. Battery is a permanent engineering concern requiring adaptive duty cycling and a CI regression gate at 4%/hour scanning. End-to-end encryption means we cannot proactively scan message content for abuse; reporting must therefore be client-side, attaching decrypted evidence with the reporter's explicit consent.

**Reversibility:** The privacy architecture is a one-way door and should be. Loosening it later would be a betrayal of the stated product promise. The transport and hop count are reversible.

## Validation gate

**No implementation proceeds until the Phase 0 spike delivers real measured numbers**: discovery latency at p50 and p95, RSSI stability and band accuracy per distance, battery drain per hour, and — the number that can kill the project — the Android→backgrounded-iOS discovery rate. If that number is unacceptable, the product thesis changes and we need to know in week three, not month nine.

## Revisit when

Apple changes background BLE behaviour, Phase 4 mesh relay is scoped, or the spike numbers force a redesign.
