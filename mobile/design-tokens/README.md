# mobile/design-tokens

Single source of truth for colour, type, spacing, radius, and elevation. Owner: `design-system`.

> A raw hex or magic dp/sp number anywhere outside this directory is a bug. If you find one in
> platform code, file it against the owning agent — don't quietly fix it in their file (agent
> write boundaries, root CLAUDE.md).

## What's here

```
mobile/design-tokens/
  tokens.json              <- the source of truth. primitives (exact, from Figma) + a
                               hand-designed semantic layer (roles, not appearances).
  scripts/generate.mjs     <- reads tokens.json, resolves references, verifies contrast,
                               emits platform output. zero npm dependencies.
  build/                   <- generated. do not hand-edit anything under here.
    android/RadiusDesignTokens.kt
    tokens.resolved.json   <- flat, fully-resolved (bonus artifact, see below)
```

## Incident log

**2026-08-05 — first `RadiusDesignTokens.kt` drop did not compile.** android-kotlin wired it into
`:android:preBuild` and got a green build only because they added three asserting-rewrite
workarounds in the Android build script (each fails loudly if its match count drops, so a real fix
here is expected to make the shim delete itself). All three were bugs in `generate.mjs`, not in
`tokens.json`:

1. `import Color` (no package) — unresolvable. Fixed: `import androidx.compose.ui.graphics.Color as ComposeColor`.
2. The nested `public object Color` shadowed the Compose `Color` type in all 30 `val` type
   annotations. Fixed by using the `ComposeColor` alias for every type annotation *and*
   constructor call, while keeping the nested object named `Color` — that's the name
   `RadiusDesignTokens.Color.Surface.canvas` (the HANDOFF table below) depends on; renaming the
   object instead would have been the obvious fix and the wrong one.
3. **Kotlin block comments nest; Java's/JS's do not.** `Accent.Radar`'s KDoc contained the prose
   "signal/\*" (a glob, not a comment token) — its embedded `/*` opened a nested comment, the
   KDoc's own closing `*/` closed that nested one instead of the outer block, and the rest of the
   file was swallowed. Surfaced as `Missing '}'` at EOF, nowhere near the cause. **Fixed generally,
   not as a one-off patch**: every doc comment in `generate.mjs` is now built through a
   `kdocLines()` helper that runs all prose through a sanitiser first (`/*` → `/ *`, `*/` → `* /`).
   There is no other code path in the emitter that produces a `/** */` block, so there's nothing
   left to forget to escape by hand — this protects every future doc comment, not just the one
   that already broke.

Also flagged by android-kotlin rather than silently patched around, and correctly left at M3
defaults rather than have a value invented for them: this system has **no stroke-width scale** (a
`BorderStroke` needs a dp, and none exists here — genuinely missing, not a documentation gap), and
**no elevation/shadow dp values, no motion tokens, and nothing mapped to M3's `errorContainer` /
`onErrorContainer` / `scrim` / `surfaceDim` / `surfaceBright`.** None of these exist as Figma
variables either, so none are invented here — same rule as the status-colour-ramp gap above. Filed
as open work, not fixed by guessing.

## Regenerating

```
cd mobile/design-tokens
node scripts/generate.mjs
```

Node only, no install step — this repo has no `package.json`/npm project anywhere yet, and adding
one (e.g. to bring in Style Dictionary, which is what `mobile/CLAUDE.md`'s caveman header names as
the eventual toolchain) is a new 3rd-party dependency, which per `.claude/ORCHESTRATION.md` §8
requires escalation, not a unilateral call on a first token drop. `generate.mjs` does the same job
— resolve references, emit per-platform source — with the Node standard library only. `tokens.json`'s
shape (primitives + `{a.b.c}` semantic references) is close enough to Style Dictionary's model that
adopting it later is a config file, not a rewrite.

The script does three things, in order, every run:
1. Resolves every `{dot.path}` reference in `tokens.json` to a literal (cycle-checked).
2. **Re-derives WCAG 2.1 contrast ratios for every documented pairing and gates on them.** This is
   the "checked, not asserted" mechanism the brief asked for, made permanent: if a future edit to a
   primitive quietly breaks a documented guarantee, `generate.mjs` exits non-zero and names the
   exact pairing and the ratio it dropped to. It is not a one-time report — it reruns every time.
3. Emits `build/android/RadiusDesignTokens.kt` and a bonus `build/tokens.resolved.json` (flat,
   fully resolved — not required by the current handoff, but it's what a future Swift/TS generator
   would read instead of re-implementing reference resolution from scratch).

## The semantic layer — how it's organised, and why

Figma's Foundations page only binds *primitives* as variables (`ink/950`, `ember/400`, …). Its own
Color System frame says "never use primitives directly — use semantic aliases," but those aliases
aren't defined anywhere in the file. Designing them was this task. They're organised by **role**,
never by appearance, so a palette change never forces a rename:

- **`surface.*`** — the elevation ladder (`canvas` → `sunken` → `base` → `raised` → `overlay` →
  `modal`), one ink step lighter per level, per Figma's own note: *"on dark, elevation = surface
  lightening + hairline first, shadow second."* No shadow blur/offset/opacity values exist as Figma
  variables, so none are invented here.
- **`content.*`** — text/icon roles: `primary`, `secondary`, `tertiary` (restricted, see below),
  `disabled` (restricted), `onFill` (text on a saturated accent/status fill), `onWash` (text on a
  tinted accent wash).
- **`border.*`** — `hairline` (decorative dividers), `subtle` (resting borders, must pair with a
  fill/elevation change), `interactive` (the sole edge of a tappable/focusable control — this one
  had to be a different primitive than `hairline`/`subtle`, see the border finding below), `danger`
  (error-state field border).
- **`accent.*`** — four accents, each **scoped to a mode or moment**, never co-primary on one
  screen: `discover` (ember, DISCOVER mode), `radar` (signal, RADAR mode, reserved per root
  CLAUDE.md), `like` (bloom, the like/match moment specifically — see note below), `threads`
  (deliberately a neutral ink tone, not a hue — Threads "favours neither" origin).
- **`status.*`** — `success`/`warning`/`danger`/`info`, `default` only (see the status-ramp finding
  below).
- **`state.*`** — `pressed`/`disabled`/`focus`, expressed as references to the tokens above (a
  state is "which named token," never a computed alpha overlay — there are no alpha primitives to
  compute from).

### Why `bloom` is its own accent, not an ember sub-shade

The colour brief itself labels `bloom/400` "**likes**." `docs/SCREEN_INVENTORY.md` lists a
dedicated Likes You / Standouts flow inside Discover. "One accent per screen" (root CLAUDE.md)
doesn't mean *one accent color exists in the whole app* — it means a screen doesn't get two accents
fighting each other. A like/match-moment screen may make `accent.like` its one primary accent,
**replacing** ember for that screen, not joining it. That's the reading encoded in `tokens.json`.

### Why Threads has no hue

Root CLAUDE.md: "THREADS — one inbox, both origins. label transport." Giving Threads its own
saturated colour would visually promote it to a third mode competing with Discover/Radar, which
contradicts the point of a unified inbox. `accent.threads.default` is `ink/200` — neutral, but
distinctly visible (10.75–11.15:1 against canvas/base), enough to drive a selected-tab indicator
without introducing a fourth hue.

## Accessibility — checked, not asserted

All ratios below are from `node scripts/generate.mjs`'s own contrast pass (WCAG 2.1 relative
luminance, sRGB), re-derived from `tokens.json` every run — not hand-computed once and pasted here
to rot. 51 pairings are checked; the table below is that same run, unedited. (48 through the first
android-kotlin wiring pass; +3 `accent.*.onInverse` pairings added after android-kotlin found the
gap below them — see finding #5.)

### Your specific question: `ink/500 #55556a` on `ink/900 #101017`

**2.61:1.** Not marginal — a clear fail against both AA normal-text (4.5:1) and even the AA
large-text/non-text floor (3:1). It only clears at all because WCAG explicitly exempts *disabled*
controls from SC 1.4.3 and SC 1.4.11. `content.disabled` = `ink/500` is legal **only** as a
disabled-state cue, and root CLAUDE.md's "never colour alone" law still applies on top of that
exemption: disabled state must also be carried structurally (no elevation, no interactivity,
`enabled=false` semantics), never by this colour by itself. It must never be used for text meant to
be read as live information. This restriction is written directly into `tokens.json`'s
`content.disabled` node and into the generated Kotlin KDoc, not just here.

### Full pass/fail table (of the 51; failures/restrictions annotated)

| Pairing | Ratio | Floor | Status |
|---|---|---|---|
| `content.primary` on all 5 surfaces | 14.12 – 18.36:1 | 4.5 | PASS (AAA everywhere) |
| `content.secondary` on all 5 surfaces | 5.60 – 7.29:1 | 4.5 | PASS |
| `content.tertiary` on canvas / base | 4.69 / 4.52:1 | 4.5 | PASS (base is only 0.02 above the floor) |
| `content.tertiary` on raised / overlay / modal | 4.29 / 3.98 / 3.60:1 | 4.5 | **FAIL** for body text. Restricted to large text / icons only on these three surfaces (all clear 3:1). |
| `content.disabled` on canvas / base / raised | 2.71 / 2.61 / 2.48:1 | 4.5 (3.0 large) | **FAIL both floors.** WCAG-exempt disabled-state use only — see above. |
| `border.hairline` on canvas / base | 1.18 / 1.14:1 | 3.0 (non-text) | **FAIL.** By design: decorative content dividers are not a "UI component" under SC 1.4.11. Never use as the sole edge of an interactive element. |
| `border.subtle` on canvas / base | 1.49 / 1.44:1 | 3.0 | **FAIL.** Must always pair with a fill/elevation difference; never the sole boundary cue. |
| `border.interactive` on canvas / base | 4.69 / 4.52:1 | 3.0 | PASS |
| `border.danger` on canvas / base | 5.33 / 5.14:1 | 3.0 | PASS |
| `accent.discover/radar/like/threads.default` on canvas / base | 6.60 – 11.15:1 | 4.5 | PASS all |
| `status.success/warning/danger/info.default` on canvas | 5.33 – 9.42:1 | 4.5 | PASS all |
| `content.onFill` on all 7 saturated fills (ember/signal/bloom/success/warning/danger/info) | 5.14 – 9.96:1 | 4.5 | PASS all — one token, safe everywhere |
| `content.onWash` on all 3 accent washes | 5.20 – 7.02:1 | 4.5 | PASS all |
| `accent.discover/radar/like.onInverse` on `content.primary` (M3 `inverseSurface`) | 5.20 – 7.02:1 | 4.5 | PASS all 3. No `threads.onInverse` — refused, see finding #5. |

Run `node scripts/generate.mjs` for the live, unabridged 51-row table with exact figures.

### Findings from doing the math instead of eyeballing it

1. **`border.*` needed a real split, not one token.** My first instinct was one `outline` colour
   for both decorative dividers and interactive-control edges (that's what the current
   `RadiusColors.Placeholder.outline` field does today). The math kills that: every ink step
   subtle/dark enough to *look* like a quiet hairline (`ink/700`, `ink/800`) fails the 3:1 non-text
   floor outright (1.14–1.49:1). The only ink step that clears 3:1 against `canvas`/`base` is
   `ink/400` (4.52–4.69:1) — three steps lighter than what "should" look like a subtle border by
   eye. `tokens.json` now has `border.hairline` / `border.subtle` (decorative, sub-3:1, by design)
   and `border.interactive` (`ink/400`, the only one legal as a sole component edge). **Flagging
   for android-kotlin:** `OutlinedButton`'s stroke in `RadarScreen.kt` currently maps to the single
   `outline` field — once split, it needs `border.interactive`, not `border.hairline`/`subtle`,
   or its tap boundary is invisible to anyone relying on contrast to find it.
2. **Status colours (`success`/`warning`/`danger`/`info`) only have one stop each** — `/400` — vs.
   3–7 stops for `ink`/`ember`/`bloom`/`signal`. That blocks defining a `pressed` or `wash` tier
   for status colours without inventing hex, which I was told not to do. `status.*` in
   `tokens.json` is deliberately limited to `default` (+ shared `content.onFill` for anything drawn
   on a status fill) until Figma extends the ramps. Not silently patched — filed here and in
   `tokens.json`'s `status.$note`.
3. **A naive "coloured icon on its own wash" pattern is inconsistent across accents and must not
   be used.** I checked whether `accent.<mode>.default` (e.g. `signal/400`) could sit directly on
   its own `wash` (`signal/600`) for a "lit icon on tinted background" look. `ember/400` on
   `ember/700` clears 3:1 (3.48:1), but `signal/400` on `signal/600` (2.82:1) and `bloom/400` on
   `bloom/600` (2.25:1) both fail it. Rather than ship a pattern that's fine on one accent and
   broken on two, `content.onWash` (`ink/50`, verified 5.20–7.02:1 on all three washes) is the one
   rule for text *and* icon on any wash surface.
4. **The spacing scale already hardcoded into `RadiusTheme.kt` does not match Figma's real 4pt
   grid**, and this is exactly the "second set of placeholder values… hardening" this task exists
   to stop. `RadiusSpacing` today is `xs=4, sm=8, md=16, lg=24, xl=32` (plus `touchTarget=48`) —
   missing the `2, 6, 12, 20, 40, 64, 80` steps Figma actually specifies, and using different names
   for the steps it does have. `RadiusDesignTokens.Spacing` in the generated Kotlin carries the
   real 14-step scale (`space0`…`space80`). `touchTarget` itself (48dp) is not part of the 4pt
   scale in Figma's Foundations — it's an accessibility floor, not a spacing step — so it isn't in
   this file; keep it where it is in `RadiusTheme.kt`, it already satisfies "min touch target 44pt
   ALWAYS" (48dp > the 44pt/44dp Apple-HIG-derived floor).
5. **`accent.*.onInverse` — a foreground role that was missing entirely, found by android-kotlin,
   not by this generator.** M3's `inversePrimary` slot (a Snackbar action label, drawn on
   `inverseSurface`, which is mapped to `content.primary`/`ink-50`) has no counterpart in the
   original 48 checks — nothing in this file's role set was ever "an accent-coloured foreground on
   a light inverted surface," because every other accent-as-text pairing here is against a *dark*
   surface. android-kotlin hand-verified that `signal/400` (the obvious choice, `accent.radar.default`)
   measures 1.84:1 there — invisible — and wired the Snackbar to `accent.radar.wash` (`signal/600`,
   5.20:1) instead, because it was the one stop that worked. That value was right, but it was
   *borrowed*: `wash` is designed as a background role (a tinted chip fill on a dark surface), used
   here as a foreground on a light one, by coincidence of the primitive rather than by verified
   design intent — and it wasn't gated, so a future edit to the wash tier for its actual purpose
   could silently break a Snackbar nobody is watching.
   `accent.discover.onInverse` / `accent.radar.onInverse` / `accent.like.onInverse` now exist as
   their own named roles, each picked by measuring every stop in its ramp against `content.primary`
   (`ink/50`) directly — not by aliasing to `wash`, so the two can diverge safely if either ever
   needs to move independently:
   - `ember/600` → 4.14:1 (large-text-only, fails AA body); `ember/700` → **7.02:1**, the pick.
   - `signal/500` → 2.76:1 (fails outright); `signal/600` → **5.20:1**, the pick (matches
     android-kotlin's hand-verified value, now gated).
   - `bloom/500` → 3.88:1 (large-text-only, fails AA body); `bloom/600` → **6.04:1**, the pick.
   All three land on the same primitive already used for that accent's `wash` — every ramp here
   happens to get *darker* toward its high-numbered stops, and "dark enough to read as a subtle
   wash near a dark canvas" and "dark enough to contrast against a near-white inverse surface" turn
   out to select the same stop. That is a property of these three ramps today, not a rule — it is
   not assumed to hold after any future ramp edit, which is exactly why `onInverse` references
   `{color.<ramp>.<stop>}` directly in `tokens.json` rather than `{semantic...wash}`.
   **`threads.onInverse` does not exist, and I did not fill it.** `threads.default` is a single
   borrowed `ink/200` stop, not the top of a dedicated ramp — Threads is deliberately not a hue (see
   above), so there is no accent-specific ramp to search for a foreground stop *from*. Some other
   `ink` stop clears AA against `content.primary` (`ink/500`, 6.79:1, is the lightest that does),
   but using it would just be generic dark-neutral text wearing this role's name, not "Threads, on
   an inverse surface." Same refusal as the status-ramp gap and the light theme: a role the palette
   can't honestly satisfy is a finding about the palette, not an invitation to interpolate one.
   Threads also has no M3 colour-scheme slot wired at all yet (see the "primary is Radar's accent
   app-wide" known gap in `RadiusTheme.kt`), so nothing consumes this role today either.
6. **Another M3 slot in the same shape, found while auditing this one, not yet fixed:**
   `surfaceDim` / `surfaceBright` are unmapped in `RadiusTheme.kt` (M3 baseline default), but M3's
   own components pair them with `onSurface` — which **is** mapped, to `content.primary`. That
   means a *mapped* foreground (verified 14.12–18.36:1 against our five real surfaces) can land on
   an *unmapped*, off-brand M3 baseline tone the moment any component reaches for tonal-elevation
   surfaces (`Surface(tonalElevation = …)`, some `Scaffold`/sheet variants) — the same "mapped
   foreground crosses an unmapped surface" shape as `inversePrimary`, just not yet exercised by a
   component in this codebase. I could not verify exact M3 baseline hex for `surfaceDim`/
   `surfaceBright` from this environment (no built Compose Material3 artifact to inspect, and I am
   not going to publish a contrast ratio computed from a guessed hex — same rule as everything
   else here). Recommend android-kotlin either (a) map `surfaceDim`/`surfaceBright` to two of our
   already-verified surfaces (e.g. `surface.canvas`/`surface.overlay`, or similar) so they inherit
   an existing gated pairing instead of an unverified one, or (b) if left unmapped, confirm in a
   real build that no component in the current screen set renders on them. `errorContainer` /
   `onErrorContainer` and `scrim`, by contrast, look safe left unmapped: `errorContainer` /
   `onErrorContainer` are always consumed as a matched M3 pair (both unmapped baseline, internally
   consistent by M3's own guarantee) as long as no call site mixes one of them with a *mapped* token
   from the other side of the pair, and `scrim` has no `onScrim` M3 role at all — nothing draws text
   on it, so there is no contrast pairing to be unwatched in the first place.

### `overline` tracking (8) — is it a unit error?

Read literally, `letterSpacing` for `overline` (8) is a 5x jump from `label.s` (1.5), with no
intermediate step, while every other row in the ramp moves in ~0.5 increments. I don't have Figma
access to check the API's `unit` field directly, so I can't fully resolve the ambiguity, but here's
what I concluded and why I didn't "fix" it:

- If the unit is **px** (my working assumption, consistent with the rest of the ramp reading like
  small px values, not percentages), 8px of tracking on an 11px uppercase label is dramatic but not
  unheard of — wide-tracked all-caps micro-labels ("eyebrow" text) are a standard move in editorial
  type systems, and Fraunces (a literary/editorial serif) + this brand's vocabulary (ink/ember/bloom)
  reads as exactly that kind of system.
- If the unit is actually **percent-of-size** (Figma's other native mode), 8% of an 11px font is
  ≈0.88px absolute — genuinely modest, in line with (even slightly more conservative than) Material
  Design's own overline spec.
- Both readings land on "plausible, probably intentional." Neither reading suggests a stray extra
  digit (a true typo would more likely read as a doubled value like 1.5→3, not a jump to 8).

**Conclusion: applied literally (`8f.sp` in the generated Kotlin), flagged, not silently altered.**
This is called out inline in `tokens.json`'s `type.scale.overline.flag` and in the generated file's
KDoc. Confirm against the live Figma file before treating it as load-bearing for a shipped screen.

## Light theme

Not included. The brief said light theme should be "a full second mode, not an afterthought" — but
Figma Foundations has not bound a single light-mode variable; I checked the file's variable
collection and only the dark-first ramp above exists. Fabricating light-mode hex values wasn't an
option (same rule as everything else: no raw value invented outside what was extracted). `tokens.json`
has `semantic.color.light: null` as an explicit placeholder — the shape is ready, the values aren't.
This means `RadiusTheme.kt` forcing dark unconditionally today is correct, not a shortcut to fix
later — it's honestly representing what's actually designed.

## Font delivery — explicitly not decided here

`type.family` in `tokens.json` and `RadiusDesignTokens.Type` in the generated Kotlin carry Fraunces
and Inter as **logical family names only** (`"display"` / `"ui"`), not bound `FontFamily` objects.
Both are open-licence (OFL) — Fraunces (Undercase Type) and Inter (Rasmus Andersson), both
distributed via Google Fonts / their own GitHub repos — but *how* they get into the app (bundled
`.ttf`/`.otf` resources vs. Android's Downloadable Fonts API vs. something else) is a new
resource/dependency decision, which per ORCHESTRATION §8 needs a go-ahead, not a unilateral call
made inside a token-extraction task. Flagging as an open item for android-kotlin / orchestrator.

## HANDOFF — what android-kotlin needs to do

**I did not edit `RadiusTheme.kt`.** It's your file (`mobile/android → android-kotlin`, root
CLAUDE.md repo map); I only write in `mobile/design-tokens/`. Here's the exact swap:

1. Copy `mobile/design-tokens/build/android/RadiusDesignTokens.kt` into your `:android` sourceSet
   (e.g. `mobile/android/src/main/kotlin/com/radius/android/ui/theme/`), and update its `package`
   line to wherever you land it — I left it as a clearly-non-colliding placeholder
   (`com.radius.designtokens.generated`) on purpose so it can't silently shadow anything of yours.
2. In `RadiusColors.Placeholder`, replace the hardcoded hex with references into the generated
   object. The current flat field set maps 1:1 like this:

   | Current `RadiusColors` field | Replace with |
   |---|---|
   | `background` | `RadiusDesignTokens.Color.Surface.canvas` |
   | `surface` | `RadiusDesignTokens.Color.Surface.base` |
   | `surfaceRaised` | `RadiusDesignTokens.Color.Surface.raised` |
   | `ink` | `RadiusDesignTokens.Color.Content.primary` |
   | `inkMuted` | `RadiusDesignTokens.Color.Content.secondary` |
   | `accentDiscover` | `RadiusDesignTokens.Color.Accent.Discover.default` |
   | `accentRadar` | `RadiusDesignTokens.Color.Accent.Radar.default` |
   | `accentThreads` | `RadiusDesignTokens.Color.Accent.Threads.default` |
   | `danger` | `RadiusDesignTokens.Color.Status.Danger.default` |
   | `outline` | **See finding #1 above before you pick one.** `HorizontalDivider` in `RadarScreen.kt` wants `border.hairline`; `OutlinedButton` wants `border.interactive` (`ink/400`) — the current single `outline` field can't correctly serve both, since only `border.interactive` clears the 3:1 non-text floor. Recommend splitting `outline` into two fields rather than picking one value that's wrong for one of the two call sites. |

3. Update `RadiusColors` (the data class) and `RadiusSpacing` to grow into the fuller role set once
   you're ready — `Border.{hairline,subtle,interactive,danger}`, `Accent.Like.*`,
   `Status.{Success,Warning,Info}`, and the full `Spacing.space0…space80` ramp are all generated and
   waiting; nothing forces you to wire them all in this pass, but they exist so the next 74 screens
   in `docs/SCREEN_INVENTORY.md` don't need a second token-extraction round.
4. `RadiusSpacing`'s current values (`xs=4, sm=8, md=16, lg=24, xl=32`) don't match the real
   4pt-grid — see finding #4. Recommend replacing with `RadiusDesignTokens.Spacing` wholesale rather
   than patching individual values.
5. Radius/shape tokens (`RadiusDesignTokens.Radius`) and the type scale
   (`RadiusDesignTokens.Type`) don't have a home in `RadiusTheme.kt` yet (it currently defers to
   stock M3 shapes/typography). They're generated and ready whenever you want to wire them; no
   urgency implied, your call on sequencing.
6. Once wired, delete the `!! PLACEHOLDER VALUES !!` banner and the `RadiusColors.Placeholder`
   naming — it stops being a placeholder at that point.
7. **New, addressing the `inversePrimary` finding:** `RadiusDesignTokens.Color.Accent.{Discover,
   Radar,Like}.onInverse` are generated and gated (see finding #5 above). In `RadiusTheme.kt`,
   swap:
   ```kotlin
   inversePrimary = colors.accent.radar.wash,
   ```
   for a new field on `RadiusAccent`/`RadiusAccents` (mirroring `default`/`pressed`/`wash`) —
   `onInverse`, sourced from `RadiusDesignTokens.Color.Accent.Radar.onInverse` — and use
   `colors.accent.radar.onInverse` at the call site instead. The value is identical today
   (`signal/600`) so this is a no-visual-diff change; what changes is that it's now a named,
   build-gated role instead of a borrowed `wash` value with a comment explaining why it's safe.
   `Discover.onInverse` / `Like.onInverse` are generated too, ready for whenever the "`primary` is
   Radar's accent app-wide" gap gets its per-mode M3 scheme. Delete the long `inversePrimary`
   comment block once wired — the reasoning now lives in `tokens.json` and this README instead.
8. See finding #6 above for `surfaceDim`/`surfaceBright` — recommend mapping them to existing
   verified surfaces rather than leaving them at unmapped M3 baseline, since `onSurface` (mapped, to
   `content.primary`) is what M3 pairs them with.

I have not touched `mobile/android/` — verify the above compiles on your side; I don't have a path
to run `:android:assembleDebug` without writing into a directory I don't own.
