# ADR-005 · Peer-to-peer WebRTC calling, invited and never recorded

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** CTO, security lead

## Context

Voice and video calling is the bridge between "we're texting" and "we're meeting." It is the first moment two people encounter each other as real humans, and it is where a large share of dating-app matches either convert into dates or quietly die. It is also, for a proximity dating app, a significant new abuse surface.

We have two constraints from ADR-003: no third-party services, and near-zero recurring cost. A CPaaS provider would solve calling in a week and cost real money per minute forever.

## Decision

**1:1 calls use peer-to-peer WebRTC with no media server.** Media flows directly between the two devices. Signalling — SDP offer/answer and ICE candidates — travels as protobuf frames over the WebSocket connection the gateway already holds open. We run **coturn** for STUN and for TURN relay, which is needed for roughly 10–20% of calls where symmetric NAT prevents a direct path. Encryption is DTLS-SRTP, mandatory; on a peer-to-peer call this is genuinely end-to-end, because the media never touches our infrastructure at all.

**We do not deploy an SFU for 1:1 calling.** LiveKit is excellent, self-hostable, Apache 2.0, and written in Go — and it is the right answer if we ever ship group calls or an events mode in Phase 4. For two participants it adds cost, latency, and an operational burden in exchange for nothing.

**Calls are invited, never cold-rung.** The signalling flow has an explicit request/accept phase before any SDP is exchanged. The recipient's phone does not ring until they have accepted a request that arrived as a notification and an in-thread card. This is a product decision with a protocol consequence, and it must not be "simplified" away later.

**Calls are never recorded.** The `calls` table has no content column and never will. This is partly a trust position and partly a legal necessity — recording in two-party-consent jurisdictions is a wiretap exposure we have no reason to accept.

**The `calling` service never touches media.** It does exactly four things: authorises the call (are these two matched, has the recipient allowed calls, is either party blocked), issues short-lived TURN credentials, relays signalling, and writes a ledger row on completion.

**Radar Voice is push-to-talk, not calling.** Full-duplex voice over BLE is not achievable at acceptable quality — practical BLE throughput is on the order of 100–300 kbps, and duplex audio with jitter buffering needs more sustained low-latency bandwidth than that. We will not fake it. Phase 4 ships short push-to-talk clips (Opus at 8–12 kbps; a 15-second clip is roughly 15 KB over GATT), labelled honestly in the UI.

## Alternatives considered

**A CPaaS provider (Twilio, Agora, Daily).** Fastest path, best quality out of the box. Rejected under ADR-003's no-third-party constraint, and because per-minute pricing on a feature we want people to use heavily is a structurally bad cost curve.

**An SFU for all calls (LiveKit, mediasoup, Janus).** Simpler client logic, easier recording and moderation, better for poor networks. Rejected for 1:1 because it routes all media through our servers — expensive, higher latency, and it destroys the end-to-end property that makes "we never see your call" a true statement rather than a promise.

**Cold ringing, like a phone.** Familiar and higher immediate engagement. Rejected: an unexpected video call from a near-stranger is intimidating, disproportionately so for women, and it is the most common reason dating-app calling features are switched off and never re-enabled.

**Recording calls for moderation.** Would materially improve abuse enforcement. Rejected on wiretap law, on trust, and because a schema that *cannot* hold a recording is a much stronger guarantee than a policy saying we don't.

## Consequences

**Good.** Calling costs essentially nothing to run at 1:1 scale. Media is genuinely end-to-end encrypted. No phone numbers are ever exchanged, which is a real safety property we can state plainly. The invited-not-rung flow removes the main reason these features fail. No vendor dependency.

**Bad / accepted costs.**

*TURN egress is the one real cost line.* A relayed video call at 800 kbps is roughly 0.7 GB per call-hour in each direction, and relay means it passes through twice. Mitigations: default to voice, cap video bitrate, prefer peer-to-peer aggressively, and put coturn on the edge node behind a monitored egress budget with an alert. Model this before launch; an unbudgeted viral week of video calling is expensive.

*We cannot inspect call content for abuse.* Enforcement is therefore report-driven. A report captures call metadata, the reporter's account, and — only with their explicit consent — evidence their own device holds. Repeat call reports must be weighted heavily in the behavioural ban model, since we have no other signal.

*Peer-to-peer exposes IP addresses to the other party* during ICE negotiation, as it does in every WebRTC application. Mitigate by preferring relay candidates for users who enable a "hide my network" privacy option, accepting the bandwidth cost for those who want it.

**Reversibility:** Cheap. Adding an SFU later is additive; the client already speaks WebRTC.

## Revisit when

Group calls or events mode are scoped, TURN egress exceeds budget consistently, or call-related abuse reports show that report-driven enforcement is insufficient.
