# Radius — Project Scaffold

This is the operating system for the Radius build: the technical plan, the permanent
project memory, and fourteen expert sub-agents that coordinate through it.

## What's here

```
CLAUDE.md                  Root memory. Caveman-compressed. Read on every session.
README.md                  This file.

docs/
  TECH_STACK_AND_PLAN.md   The CTO document. Full architecture, stack, phasing,
                           team, cost, risk. Readable prose — share this with
                           investors and new hires.
  adr/                     Architecture Decision Records. 6 seeded + template.

.claude/
  ORCHESTRATION.md         How the 14 agents work together without collisions.
  memory/
    00-project.md          Immutable project facts
    10-stack.md            Locked stack decisions
    20-state.md            LIVE STATE — updated every session, by every agent
    30-decisions.md        Append-only decision log
    40-contracts.md        Shared interfaces — the only legitimate coupling point
    50-glossary.md         Exact vocabulary (wave, handshake, band, people)
    60-blockers.md         Live blockers + standing risks
  agents/                  14 expert sub-agent definitions

backend/CLAUDE.md          Per-folder memory. Loads when working in that folder.
mobile/CLAUDE.md
website/CLAUDE.md
devops/CLAUDE.md
```

## Setup

Copy `CLAUDE.md`, `README.md`, `docs/`, `.claude/` and the four folder-level
`CLAUDE.md` files into your existing repo alongside `backend/ mobile/ website/ devops/`.

Then, in Claude Code from the repo root:

```
> read CLAUDE.md and .claude/ORCHESTRATION.md, then use the orchestrator agent
> to plan Phase 0
```

The orchestrator reads the memory, states the current blocker, and dispatches.

## The 14 agents

| Agent | Owns | Model |
|---|---|---|
| `orchestrator` | Routing, contracts, ADRs, memory | opus |
| `ble-protocol` | `mobile/protocol/` — the BLE wire law | opus |
| `ios-swift` | `mobile/ios/` | opus |
| `android-kotlin` | `mobile/android/` | opus |
| `backend-go` | `backend/` | opus |
| `calling-webrtc` | `backend/services/calling/`, coturn | opus |
| `growth-conversion` | `backend/services/billing/`, paywalls | opus |
| `devops-tencent` | `devops/` | opus |
| `web-next` | `website/` | sonnet |
| `design-system` | `mobile/design-tokens/` | sonnet |
| `data-ml` | ranking + analytics | opus |
| `security-privacy` | Review-only gate. No write tools. | opus |
| `qa-test` | Tests, hardware rig, release gates | sonnet |
| `code-reviewer` | Review-only. No write tools. | opus |

Exactly one writer per path. Cross-boundary changes go through the orchestrator
as a HANDOFF (format in `ORCHESTRATION.md` §4).

## The three lines that don't move

The root `CLAUDE.md` carries three sets of invariants that block a merge if violated:
the **10 safety invariants** (no map, no bearing, no precise location, banded distance only),
the **8 calling invariants** (numbers never exchanged, never recorded, invited not cold-rung),
and the **banned dark patterns** list. Any agent asked to weaken one refuses and escalates
to a human. `ADR-004`, `ADR-005` and `ADR-006` explain why each line is where it is.

## The two rules that make this work

**1. Contract first.** A protobuf or BLE spec change is reviewed, merged alone,
and its consumers notified — *before* any implementation depends on it. Never the
reverse. This is what lets a dozen people and fourteen agents work in parallel.

**2. Memory updated every session.** Every agent rewrites `20-state.md` before
finishing. A session that skips it is incomplete. This is the permanent memory —
without the ritual it decays into fiction within a week.

## Where to start

Phase 0 is a **BLE feasibility spike** and it blocks everything else. Three weeks,
real hardware, real numbers. The one that can kill the thesis is the
Android→backgrounded-iOS discovery rate. See `docs/adr/ADR-004` and
`docs/TECH_STACK_AND_PLAN.md` §6.7 and §11.
