# ORCHESTRATION PROTOCOL  (v1.1 — 14 agents)
<!-- CAVEMAN. how 14 agents work together without stepping on each other. -->
<!-- orchestrator owns this file. everyone obeys it. -->

## 1 · ROSTER
| agent | owns (WRITE) | reads |
|---|---|---|
| orchestrator | docs/adr/, .claude/**, memory structure+pruning | all |
| ble-protocol | mobile/protocol/, mobile/shared/protocol/ | backend/proto, mobile/* |
| ios-swift | mobile/ios/ | shared API, protocol, proto, tokens |
| android-kotlin | mobile/android/, mobile/shared/ (minus protocol/) | protocol, proto, tokens |
| backend-go | backend/ (minus discovery/ranking, calling/, billing/, tests) | protocol, memory |
| calling-webrtc | backend/services/calling/, devops/tofu/coturn/ | proto, mobile/*, devops |
| growth-conversion | backend/services/billing/, paywall+upsell surfaces | proto, design-tokens, analytics |
| devops-tencent | devops/ (minus ci/ test+gate stages, minus tofu/coturn/) | all (deploy needs) |
| web-next | website/ | proto, tokens |
| design-system | mobile/design-tokens/ | design spec |
| data-ml | backend/services/discovery/ranking/, analytics/ | rest of backend/ (READ ONLY) |
| security-privacy | — (REVIEW ONLY, no write tools) | all |
| qa-test | **/tests/, devops/ci/ TEST+GATE stages | all |
| code-reviewer | — (REVIEW ONLY) | all |

## 2 · WRITE LAW
- agent writes ONLY its own dir per the roster above. carve-outs are explicit; honour them.
- shared-append files (memory/20,30,60) are the ONE exception: every agent appends, nobody rewrites
  another agent's line. orchestrator owns structure + pruning + conflict merge.
- need change outside own dir ⇒ do NOT edit. emit HANDOFF (§4) to orchestrator.
- 2 agents never write same file same session. orchestrator serialises.
- security-privacy + code-reviewer have NO write tools at all. findings only.
  want an ADR written? propose it; orchestrator writes it.

## 3 · CONTRACT-FIRST LAW (the thing that makes parallel work possible)
CONTRACTS = backend/proto · mobile/protocol (BLE spec+vectors) · design-tokens ·
            **mobile/shared PUBLIC API** (KMP, ADR-007 — 2 consumers, so it is a contract)
order is fixed:
  1. propose contract change (proto / ble spec / tokens / shared API)
  2. orchestrator + architect review
  3. ADR if irreversible
  4. merge contract ALONE
  5. notify every consumer agent
  6. THEN implementations start, in parallel
never: implement → then fix contract. that is the failure mode. reject it.

## 4 · HANDOFF FORMAT (only legal way to cross a boundary)
```
HANDOFF
from: <agent>            to: <agent>
need: <one line>
why: <one line>
contract-touched: yes/no  (yes ⇒ orchestrator MUST gate)
blocking-me: yes/no
accept-when: <testable condition>
```
orchestrator queues, orders, dispatches. no direct agent→agent writes.

## 5 · SESSION RITUAL (every agent, every session)
START
  1. read CLAUDE.md (root) + own folder CLAUDE.md
  2. read memory/20-state.md — NOW + NEXT6 + blockers
  3. read memory/40-contracts.md — check contract version drift
  4. claim your task in 20-state IN FLIGHT
WORK
  5. do it. contract-first. own dir only.
END (non-negotiable)
  6. update 20-state: move task DONE, rewrite NOW, refresh NEXT6
  7. new irreversible decision ⇒ append 30-decisions (+ ADR if big)
  8. blocked ⇒ append 60-blockers + HANDOFF to orchestrator
  9. contract changed ⇒ update 40-contracts + list consumers to notify
  10. LOG line in 20-state: `<date> <agent> · <what changed>`
session that skips step 6-10 = incomplete. redo it.

## 6 · PARALLEL SAFE / UNSAFE
SAFE together:
  ios-swift ∥ android-kotlin ∥ backend-go ∥ web-next ∥ devops-tencent ∥
  calling-webrtc ∥ growth-conversion
  (different dirs, contracts already locked)
UNSAFE — serialise:
  anything touching proto/ or mobile/protocol/ or mobile/shared/ — ONE agent at a time
  ios-swift + android-kotlin are only parallel-safe while shared API is frozen.
  shared API in flight ⇒ ios-swift blocks until it lands.
  migrations — one at a time, sequential version numbers
  20-state writes — orchestrator merges if concurrent
GATE:
  security-privacy + code-reviewer run AFTER implementation, before merge.
  qa-test runs after both.

## 7 · SYNC POINTS (calendar, not optional)
daily   — orchestrator rewrites 20-state NOW block
weekly  — parity review: ios vs android vs BLE spec. divergence ⇒ blocker row.
weekly  — RADIO DAY. real hardware, real world. mobile team + qa-test.
biweekly— demo on REAL DEVICES. simulator demo = not a demo.
phase-end — orchestrator writes phase memo, prunes 20-state LOG into 30-decisions.

## 8 · ESCALATE TO HUMAN (do not decide alone)
- any SAFETY INVARIANT (1-10) or CALLING INVARIANT (C1-C8) would be weakened
- anyone asks for a banned dark pattern ⇒ REFUSE, cite regulation + refund risk, escalate
- spend > $500/mo total, or > 2x current baseline, or any new paid service
- schema change that loses data
- new 3rd-party dependency of any kind
- BLE spike result = NO-GO
- anything touching biometrics, legal, or law enforcement

## 9 · DEFINITION OF DONE
code + tests + contract updated + memory updated + reviewer pass +
security pass (if touches auth/crypto/PII/BLE) + CI green + ADR (if irreversible).
missing any ⇒ not done. say "not done".
