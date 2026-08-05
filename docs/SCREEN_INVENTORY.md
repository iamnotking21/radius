# Screen Inventory — Figma

**File:** `lvh2NvQKYn4byiBREyzaYL` · enumerated 2026-08-05
**Purpose:** size the UI work for P1 planning. Closes the "what are pages 11:116-11:122?" question that had been open since the repo was created.

**Roughly 75 screens across 8 sections, plus a foundations page.** All dark-first. Nothing here is built — the Android app is three empty tabs.

---

| Page | Section | Screens | Notes |
|---|---|---|---|
| `0:1` | 🎨 **Foundations** | 14 frames | Colour, type scale, spacing, radius, elevation + 9 component frames (buttons, forms, chips, cards, nav, messaging, radar, feedback, calling) |
| `11:115` | **A · Onboarding** | 14 | Splash → value prop → phone → OTP → name → DOB → gender → who to meet → intentions → photos → prompts → verification → Bluetooth permission → welcome |
| `11:116` | **B · Discover** | ~8 | Profile card, expanded profile, preferences, likes-you, standouts, search, travel mode. **Gold accent, as specified.** |
| `11:117` | **C · Radar** | ~10 | Radar canvas, nearby list, wave, "You're both here", ghost mode, safety, "How Radar works". **Teal accent, as specified.** |
| `11:118` | **D · Threads** | ~9 | Match, inbox with Radar/Unread filters, search, chat with transport label, voice note, "Suggest a plan", profile menu, report flow |
| `11:119` | **E · Calling** | ~10 | "Talk before you meet", call request, accept/decline, connecting, in-call, safety controls, call ended + rating, schedule a call, push-to-talk over Radar |
| `11:120` | **F · Monetization** | ~7 | Tier comparison, out-of-comments, store, boost active, subscription management, welcome-to-Gold, no-card gift week |
| `11:121` | **G · Profile & Settings** | ~9 | Profile, edit, preferences, notifications, privacy & data, safety centre, blocked & hidden, account settings |
| `11:122` | **H · Empty / error / edge** | ~8 | No likes yet, no connection, Discover-needs-internet, Bluetooth off, profile paused, battery saver, force update |

---

## What the designs get right, checked against our invariants

Spot-checked visually, not audited. These are encouraging, not cleared.

- **Radar is teal, Discover is gold** — matches the spec's mode accents.
- **No map anywhere.** No bearing, no coordinates, no direction indicator on any Radar screen. Invariant 1 holds visually.
- **Calling is invited, never cold-rung.** "Ask to call now" / "Suggest a time" precede any ring. Matches C3.
- **In-call safety controls are present and one tap** — blur background, turn off camera, report, block-and-end. Matches C4 and C6.
- **Ghost mode is on the Radar surface**, not buried in settings. Invariant 10.
- **The no-card gift exists** — "A week of Gold, on us. No card, nothing to cancel." That is ADR-006's E7, built as specified rather than quietly replaced with a card-required trial.
- **Cancellation is visible** in subscription management rather than hidden, and renewal terms appear on the pricing screen rather than below a fold.
- **Page H is honest about failure.** "Radar works offline — open it" when the internet is down, and a battery-saver screen that *tells the user* Radar is scanning less often instead of silently degrading. That screen is the honest-product thesis in one frame.

## Two follow-ups this raises

**1. `mobile/design-tokens/` is empty, and the Foundations page is not.**
Colour, type, spacing, radius and elevation scales all exist in Figma. Every colour currently in the Android theme is a placeholder hex with a `TODO(design-tokens)` against it. Extracting the tokens is cheap, unblocks every future screen, and stops a second set of placeholder values hardening into the codebase. Owner: `design-system`.

**2. The monetization page needs a proper ADR-006 audit, not a glance.**
What I can see looks compliant — real limits, honest countdowns on a genuinely time-boxed boost, visible cancellation, the no-card gift. But ADR-006's banned list is specific, and "looks fine in a screenshot" is not an audit. Anchoring and per-day framing are *allowed*; fake scarcity, confirmshaming, and obstructed cancellation are not, and the difference is often one line of copy. Owner: `growth-conversion`.

## Sequencing note

**None of this is buildable yet.** Phase 0 gates it, and correctly: if B8 comes back bad, section C changes shape and section H grows a screen explaining why this phone cannot be seen. Building screens before the spike answers would mean rebuilding them after.

Token extraction is the exception — tokens are independent of the spike result.
