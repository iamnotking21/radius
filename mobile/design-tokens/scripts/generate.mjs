#!/usr/bin/env node
/**
 * Radius design-tokens generator.
 *
 * WHY A HAND-ROLLED SCRIPT AND NOT STYLE DICTIONARY:
 * mobile/CLAUDE.md's caveman header names Style Dictionary as the eventual toolchain
 * (tokens.json + Style Dictionary -> Swift, Kotlin, TS). It is not wired here because
 * ORCHESTRATION.md lists "new 3rd-party dependency of any kind" as something that
 * requires escalation, and this repo has no npm/node project anywhere yet (no
 * package.json exists outside node_modules-free tooling). Adding Style Dictionary
 * means adding a package.json, a lockfile, and an npm dependency tree to review --
 * that is a real decision, not a rubber stamp, and it is not mine to make unilaterally
 * for a first token drop. This script does the same job (read tokens.json, resolve
 * references, emit platform source) using only Node's standard library, which is
 * already the toolchain the website/ workspace uses. When Style Dictionary is approved,
 * tokens.json's shape (primitives + semantic aliases with {a.b.c} references) is close
 * enough to its token model that migrating is a config file, not a rewrite.
 *
 * WHAT THIS DOES, IN ORDER:
 *  1. Loads tokens.json.
 *  2. Resolves every `{dot.path}` reference to a literal value (cycle-checked).
 *  3. Re-derives WCAG 2.1 contrast ratios for every documented colour pairing and
 *     prints a table. This is the "checked, not asserted" mechanism: if a future edit
 *     to a primitive quietly breaks a documented guarantee, this script's exit code
 *     goes non-zero and says exactly which pairing broke and by how much.
 *  4. Emits build/android/RadiusDesignTokens.kt.
 *  5. Emits build/tokens.resolved.json (flat, fully-resolved -- not required by the
 *     current handoff, but free, and it is what a future Swift/TS generator would
 *     read instead of re-implementing reference resolution).
 *
 * RUN: node scripts/generate.mjs   (from mobile/design-tokens/)
 */

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const TOKENS_PATH = path.join(ROOT, "tokens.json");
const BUILD_DIR = path.join(ROOT, "build");

// ---------------------------------------------------------------------------
// 1. load
// ---------------------------------------------------------------------------
const tokens = JSON.parse(readFileSync(TOKENS_PATH, "utf8"));

// ---------------------------------------------------------------------------
// 2. reference resolution
// ---------------------------------------------------------------------------
const REF = /^\{([a-zA-Z0-9_.]+)\}$/;

function walk(root, dottedPath) {
  const segs = dottedPath.split(".");
  let node = root;
  for (const seg of segs) {
    if (node == null || !(seg in node)) {
      throw new Error(`Broken reference: "{${dottedPath}}" -- no such path (stuck at "${seg}")`);
    }
    node = node[seg];
  }
  return node;
}

/** Resolves a raw token value (literal, or a `{path}` reference, possibly chained) to a literal. */
function resolveValue(root, raw, seen = new Set()) {
  if (typeof raw !== "string") return raw;
  const m = raw.match(REF);
  if (!m) return raw; // literal (hex, string, number-as-string never happens here)
  const refPath = m[1];
  if (seen.has(refPath)) {
    throw new Error(`Reference cycle detected at "{${refPath}}" (chain: ${[...seen].join(" -> ")})`);
  }
  seen.add(refPath);
  let target = walk(root, refPath);
  if (typeof target === "object" && target !== null && "value" in target) {
    target = target.value;
  }
  return resolveValue(root, target, seen);
}

/** Recursively resolves every `value` field in a semantic subtree, leaving metadata untouched. */
function resolveTree(root, node) {
  if (node === null || typeof node !== "object") return node;
  if (Array.isArray(node)) return node.map((n) => resolveTree(root, n));
  const out = {};
  for (const [k, v] of Object.entries(node)) {
    if (k === "value" && typeof v === "string") {
      out[k] = resolveValue(root, v);
      out.$resolvedFrom = v.match(REF) ? v : undefined;
    } else {
      out[k] = resolveTree(root, v);
    }
  }
  return out;
}

const resolved = {
  color: tokens.color,
  type: tokens.type,
  spacing: tokens.spacing,
  radius: tokens.radius,
  elevation: resolveTree(tokens, tokens.elevation),
  semantic: resolveTree(tokens, tokens.semantic),
};

// convenience accessor: get the resolved literal hex for a semantic dark-theme leaf by short path
// e.g. get("surface.canvas") -> "#0b0b10"
function get(shortPath) {
  const node = walk(resolved.semantic.color.dark, shortPath);
  if (typeof node === "object" && node !== null && "value" in node) return node.value;
  return node;
}

// ---------------------------------------------------------------------------
// 3. contrast verification (WCAG 2.1, sRGB relative luminance)
// ---------------------------------------------------------------------------
function relLuminance(hex) {
  const h = hex.replace("#", "");
  const r = parseInt(h.slice(0, 2), 16) / 255;
  const g = parseInt(h.slice(2, 4), 16) / 255;
  const b = parseInt(h.slice(4, 6), 16) / 255;
  const f = (c) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

function contrastRatio(hexA, hexB) {
  const lA = relLuminance(hexA);
  const lB = relLuminance(hexB);
  const lighter = Math.max(lA, lB);
  const darker = Math.min(lA, lB);
  return (lighter + 0.05) / (darker + 0.05);
}

// [label, fgPath, bgPath, minExpected, tier]
// tier is documentation only; minExpected is the number this script actually gates on.
const CHECKS = [
  ["content.primary on surface.canvas", "content.primary", "surface.canvas", 4.5, "AA text"],
  ["content.primary on surface.base", "content.primary", "surface.base", 4.5, "AA text"],
  ["content.primary on surface.raised", "content.primary", "surface.raised", 4.5, "AA text"],
  ["content.primary on surface.overlay", "content.primary", "surface.overlay", 4.5, "AA text"],
  ["content.primary on surface.modal", "content.primary", "surface.modal", 4.5, "AA text"],

  ["content.secondary on surface.canvas", "content.secondary", "surface.canvas", 4.5, "AA text"],
  ["content.secondary on surface.base", "content.secondary", "surface.base", 4.5, "AA text"],
  ["content.secondary on surface.raised", "content.secondary", "surface.raised", 4.5, "AA text"],
  ["content.secondary on surface.overlay", "content.secondary", "surface.overlay", 4.5, "AA text"],
  ["content.secondary on surface.modal", "content.secondary", "surface.modal", 4.5, "AA text"],

  ["content.tertiary on surface.canvas", "content.tertiary", "surface.canvas", 4.5, "AA text"],
  ["content.tertiary on surface.base", "content.tertiary", "surface.base", 4.5, "AA text"],
  ["content.tertiary on surface.raised", "content.tertiary", "surface.raised", 3.0, "AA large/UI only (documented restriction)"],
  ["content.tertiary on surface.overlay", "content.tertiary", "surface.overlay", 3.0, "AA large/UI only (documented restriction)"],
  ["content.tertiary on surface.modal", "content.tertiary", "surface.modal", 3.0, "AA large/UI only (documented restriction)"],

  ["content.disabled on surface.canvas", "content.disabled", "surface.canvas", 0, "WCAG-exempt (disabled state)"],
  ["content.disabled on surface.base", "content.disabled", "surface.base", 0, "WCAG-exempt (disabled state)"],
  ["content.disabled on surface.raised", "content.disabled", "surface.raised", 0, "WCAG-exempt (disabled state)"],

  ["border.hairline on surface.canvas", "border.hairline", "surface.canvas", 0, "decorative divider, not a UI component"],
  ["border.hairline on surface.base", "border.hairline", "surface.base", 0, "decorative divider, not a UI component"],
  ["border.subtle on surface.canvas", "border.subtle", "surface.canvas", 0, "must pair with a fill/elevation difference"],
  ["border.subtle on surface.base", "border.subtle", "surface.base", 0, "must pair with a fill/elevation difference"],
  ["border.interactive on surface.canvas", "border.interactive", "surface.canvas", 3.0, "AA non-text (1.4.11)"],
  ["border.interactive on surface.base", "border.interactive", "surface.base", 3.0, "AA non-text (1.4.11)"],
  ["border.danger on surface.canvas", "border.danger", "surface.canvas", 3.0, "AA non-text (1.4.11)"],
  ["border.danger on surface.base", "border.danger", "surface.base", 3.0, "AA non-text (1.4.11)"],

  ["accent.discover.default on surface.canvas", "accent.discover.default", "surface.canvas", 4.5, "AA text (used as link/icon colour)"],
  ["accent.discover.default on surface.base", "accent.discover.default", "surface.base", 4.5, "AA text"],
  ["accent.radar.default on surface.canvas", "accent.radar.default", "surface.canvas", 4.5, "AA text"],
  ["accent.radar.default on surface.base", "accent.radar.default", "surface.base", 4.5, "AA text"],
  ["accent.like.default on surface.canvas", "accent.like.default", "surface.canvas", 4.5, "AA text"],
  ["accent.like.default on surface.base", "accent.like.default", "surface.base", 4.5, "AA text"],
  ["accent.threads.default on surface.canvas", "accent.threads.default", "surface.canvas", 4.5, "AA text"],
  ["accent.threads.default on surface.base", "accent.threads.default", "surface.base", 4.5, "AA text"],

  ["status.success.default on surface.canvas", "status.success.default", "surface.canvas", 4.5, "AA text"],
  ["status.warning.default on surface.canvas", "status.warning.default", "surface.canvas", 4.5, "AA text"],
  ["status.danger.default on surface.canvas", "status.danger.default", "surface.canvas", 4.5, "AA text"],
  ["status.info.default on surface.canvas", "status.info.default", "surface.canvas", 4.5, "AA text"],

  ["content.onFill on accent.discover.default (fill)", "content.onFill", "accent.discover.default", 4.5, "AA text on filled control"],
  ["content.onFill on accent.radar.default (fill)", "content.onFill", "accent.radar.default", 4.5, "AA text on filled control"],
  ["content.onFill on accent.like.default (fill)", "content.onFill", "accent.like.default", 4.5, "AA text on filled control"],
  ["content.onFill on status.success.default (fill)", "content.onFill", "status.success.default", 4.5, "AA text on filled control"],
  ["content.onFill on status.warning.default (fill)", "content.onFill", "status.warning.default", 4.5, "AA text on filled control"],
  ["content.onFill on status.danger.default (fill)", "content.onFill", "status.danger.default", 4.5, "AA text on filled control"],
  ["content.onFill on status.info.default (fill)", "content.onFill", "status.info.default", 4.5, "AA text on filled control"],

  ["content.onWash on accent.discover.wash", "content.onWash", "accent.discover.wash", 4.5, "AA text on wash surface"],
  ["content.onWash on accent.radar.wash", "content.onWash", "accent.radar.wash", 4.5, "AA text on wash surface"],
  ["content.onWash on accent.like.wash", "content.onWash", "accent.like.wash", 4.5, "AA text on wash surface"],
];

let failures = 0;
const rows = [];
for (const [label, fgPath, bgPath, min, tier] of CHECKS) {
  const fg = get(fgPath);
  const bg = get(bgPath);
  const ratio = contrastRatio(fg, bg);
  const pass = ratio >= min;
  if (!pass) failures++;
  rows.push({ label, ratio: ratio.toFixed(2), min, tier, pass });
}

function printReport() {
  console.log("\nRadius design-tokens -- WCAG 2.1 contrast verification\n");
  const w = Math.max(...rows.map((r) => r.label.length)) + 2;
  for (const r of rows) {
    const status = r.min === 0 ? "  --  " : r.pass ? " PASS " : " FAIL ";
    console.log(
      `${r.label.padEnd(w)} ${String(r.ratio + ":1").padEnd(8)} min ${String(r.min).padEnd(4)} [${status}] ${r.tier}`,
    );
  }
  console.log(`\n${rows.length} pairings checked, ${failures} regression(s) against documented floors.\n`);
}

printReport();

if (failures > 0) {
  console.error(`FAILED: ${failures} colour pairing(s) dropped below their documented minimum. Fix tokens.json before shipping.`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// 4. Kotlin/Compose emission
// ---------------------------------------------------------------------------

// BUG HISTORY (2026-08-05, reported by android-kotlin, all three below were mine):
//
//  1. `import Color` (no package) is unresolvable -- it needed to be
//     `androidx.compose.ui.graphics.Color`.
//
//  2. The nested `public object Color` inside RadiusDesignTokens shadows the Compose `Color`
//     type. Every `public val x: Color = Color(0xFF...)` had its TYPE position resolve to the
//     enclosing object's classifier, not the Compose class -- 30 type mismatches. Fixed by
//     importing the real type under an alias (`ComposeColor`) and using the alias for every type
//     annotation *and* constructor call, while leaving the nested object's name as `Color` --
//     that keeps `RadiusDesignTokens.Color.Surface.canvas` (the path the README HANDOFF table
//     documents) stable. Renaming the object would have been the "obvious" fix and the wrong one.
//
//  3. Kotlin block comments NEST -- Java's (and C's, and JS's) do not. Accent.Radar's KDoc
//     contained the prose "do not reuse signal/* anywhere else" -- "signal/*" is a glob, not a
//     comment token, but its "/*" opened a NESTED comment; the KDoc's own closing "*/" closed
//     that nested one instead of the outer block, and the rest of the file -- everything after --
//     was swallowed. It surfaced as "Missing '}'" near EOF, nowhere near the actual cause.
//
//     THE FIX IS GENERAL, NOT A PATCH OF THAT ONE STRING: every doc comment below is built
//     through kdocLines(), which runs the text through docSafe() first. Any future doc comment
//     that mentions a glob, a path, or anything else containing a literal "/*" or "*/" is
//     protected automatically -- there is no other code path in this emitter that produces a
//     `/** ... */` block, so there is nothing left to forget to escape by hand.

// Neutralises the two-character sequences that open ("/" + "*") or close ("*" + "/") a Kotlin
// block comment, by inserting a space so the characters are no longer adjacent. Visually
// "signal/*" becomes "signal/ *" -- the meaning survives, the lexer no longer sees a token.
// (Written as a line comment on purpose: a block comment describing these exact two-character
// sequences would risk closing itself early -- see bug #3 above, and note this is JS, not Kotlin,
// so it isn't even protected by kdocLines(). The bug this function fixes almost recurred while
// documenting the function that fixes it.)
function docSafe(text) {
  return text.replace(/\/\*/g, "/ *").replace(/\*\//g, "* /");
}

/** Renders `text` ("\n"-joined for multiple lines) as a docSafe()'d KDoc block at `indent`. */
function kdocLines(indent, text) {
  const safe = docSafe(text);
  const paragraphLines = safe.split("\n");
  if (paragraphLines.length === 1) {
    return [`${indent}/** ${paragraphLines[0]} */`];
  }
  const out = [`${indent}/**`];
  for (const l of paragraphLines) out.push(l.length ? `${indent} * ${l}` : `${indent} *`);
  out.push(`${indent} */`);
  return out;
}

function toKotlinColor(hex) {
  const h = hex.replace("#", "").toUpperCase();
  return `ComposeColor(0xFF${h})`;
}

function dp(n) {
  return `${n}.dp`;
}

function sp(n) {
  return `${n}f.sp`;
}

const L = [];
const push = (...ls) => L.push(...ls);

push(
  "package com.radius.designtokens.generated",
  "",
  "// =============================================================================",
  "// GENERATED FILE. DO NOT HAND-EDIT.",
  "//",
  "// Produced by mobile/design-tokens/scripts/generate.mjs from",
  "// mobile/design-tokens/tokens.json. To change a value, edit tokens.json and",
  "// re-run `node scripts/generate.mjs` from mobile/design-tokens/, then copy this",
  "// file over the previous copy in mobile/android/.",
  "//",
  "// OWNERSHIP: this file is generated by design-system but does not live in a",
  "// Gradle sourceSet -- mobile/design-tokens is not a Gradle module (see",
  "// mobile/settings.gradle.kts, owned by android-kotlin). android-kotlin owns",
  "// where and how this gets wired into :android. See the HANDOFF section of",
  "// mobile/design-tokens/README.md for the exact integration steps.",
  "//",
  "// Package name is a placeholder (com.radius.designtokens.generated) precisely",
  "// so it does not silently collide with anything under com.radius.android --",
  "// rename it when you wire this in, that is your call to make.",
  "//",
  "// Color alias: the nested `object Color` below deliberately keeps that name -- it is the",
  "// RadiusDesignTokens.Color.* access path this file's README HANDOFF table documents. That",
  "// means an unqualified `Color` is unusable inside this file (it would resolve to the nested",
  "// object, not the Compose type), so the real type is imported under an alias below and used",
  "// for every property type and constructor call in this file.",
  "// =============================================================================",
  "",
  "import androidx.compose.ui.graphics.Color as ComposeColor",
  "import androidx.compose.ui.unit.Dp",
  "import androidx.compose.ui.unit.dp",
  "import androidx.compose.ui.unit.TextUnit",
  "import androidx.compose.ui.unit.sp",
  "",
);

push(
  ...kdocLines(
    "",
    [
      "Semantic colour roles, dark theme. Name things by ROLE, never by appearance --",
      'that is the whole point of this file existing instead of hex literals in',
      "RadiusTheme.kt. See tokens.json + README.md for the \"why\" behind every value",
      "and the measured WCAG contrast ratio it was chosen to satisfy.",
      "",
      "Light theme: not included. Figma Foundations has not bound a light-mode",
      "variable set yet (checked -- only dark-first primitives exist). Do not",
      "fabricate one; see README.md#light-theme.",
    ].join("\n"),
  ),
  "public object RadiusDesignTokens {",
  "",
  "    public object Color {",
  "",
);

push(...kdocLines("        ", 'Elevation ladder. elev/0..4, "surface lightening + hairline first, shadow second."'));
push("        public object Surface {");
push(...kdocLines("            ", "elev/0. App background."));
push(`            public val canvas: ComposeColor = ${toKotlinColor(get("surface.canvas"))}`);
push(...kdocLines("            ", "Recessed wells: text-input fill, code/mono blocks."));
push(`            public val sunken: ComposeColor = ${toKotlinColor(get("surface.sunken"))}`);
push(...kdocLines("            ", "elev/1. Default content surface."));
push(`            public val base: ComposeColor = ${toKotlinColor(get("surface.base"))}`);
push(...kdocLines("            ", "elev/2. Cards, chips, tiles."));
push(`            public val raised: ComposeColor = ${toKotlinColor(get("surface.raised"))}`);
push(...kdocLines("            ", "elev/3. Menus, tooltips, transient overlays."));
push(`            public val overlay: ComposeColor = ${toKotlinColor(get("surface.overlay"))}`);
push(...kdocLines("            ", "elev/4. Sheets, dialogs, modals."));
push(`            public val modal: ComposeColor = ${toKotlinColor(get("surface.modal"))}`);
push("        }", "");

push("        public object Content {");
push(...kdocLines("            ", "Default text/icon. Safe on every surface (14.1-18.4:1 measured)."));
push(`            public val primary: ComposeColor = ${toKotlinColor(get("content.primary"))}`);
push(...kdocLines("            ", "De-emphasised but informational text. Safe on every surface (5.6-7.3:1)."));
push(`            public val secondary: ComposeColor = ${toKotlinColor(get("content.secondary"))}`);
push(
  ...kdocLines(
    "            ",
    [
      "Least-emphasis readable text. AA (4.5:1) ONLY on Surface.canvas and Surface.base.",
      "On Surface.raised/overlay/modal it drops to 4.29/3.98/3.60:1 -- large text or",
      "icons only on those three, never small body copy. See README.md for the table.",
    ].join("\n"),
  ),
);
push(`            public val tertiary: ComposeColor = ${toKotlinColor(get("content.tertiary"))}`);
push(
  ...kdocLines(
    "            ",
    [
      "Disabled-state text/icon. FAILS WCAG AA and the 3:1 large-text/UI floor on every",
      "surface (2.48-2.71:1 measured) -- legal only under WCAG's disabled-control",
      "exemption. Never the sole disabled cue; never used for readable live information.",
    ].join("\n"),
  ),
);
push(`            public val disabled: ComposeColor = ${toKotlinColor(get("content.disabled"))}`);
push(...kdocLines("            ", "Text/icon on any saturated accent or status FILL. Verified 5.14-9.96:1 on all seven."));
push(`            public val onFill: ComposeColor = ${toKotlinColor(get("content.onFill"))}`);
push(...kdocLines("            ", "Text/icon on an accent WASH surface (not a fill). Verified 5.20-7.02:1 on all three."));
push(`            public val onWash: ComposeColor = ${toKotlinColor(get("content.onWash"))}`);
push("        }", "");

push("        public object Border {");
push(...kdocLines("            ", "Decorative dividers only. 1.14-1.18:1 -- fails 3:1, legal because it is not a UI-component edge."));
push(`            public val hairline: ComposeColor = ${toKotlinColor(get("border.hairline"))}`);
push(...kdocLines("            ", "Default resting border; ALWAYS pair with a fill/elevation difference. 1.44-1.49:1 -- fails 3:1 alone."));
push(`            public val subtle: ComposeColor = ${toKotlinColor(get("border.subtle"))}`);
push(...kdocLines("            ", "The sole visual edge of an interactive control (inputs, OutlinedButton). 4.52-4.69:1, clears 3:1."));
push(`            public val interactive: ComposeColor = ${toKotlinColor(get("border.interactive"))}`);
push(...kdocLines("            ", "Error-state field border. 5.14-5.33:1. Same primitive as Status.Danger.default, by design."));
push(`            public val danger: ComposeColor = ${toKotlinColor(get("border.danger"))}`);
push("        }", "");

push(
  ...kdocLines(
    "        ",
    [
      "One accent per screen (root CLAUDE.md). Four accents exist, each scoped to a mode or",
      "moment; they replace one another per screen, they do not co-occur as competing primaries.",
    ].join("\n"),
  ),
);
push("        public object Accent {");
push(...kdocLines("            ", "DISCOVER mode. Root CLAUDE.md: accent=gold ember/400."));
push("            public object Discover {");
push(`                public val default: ComposeColor = ${toKotlinColor(get("accent.discover.default"))}`);
push(`                public val pressed: ComposeColor = ${toKotlinColor(get("accent.discover.pressed"))}`);
push(`                public val wash: ComposeColor = ${toKotlinColor(get("accent.discover.wash"))}`);
push("            }");
push(
  ...kdocLines(
    "            ",
    [
      "RADAR mode. Root CLAUDE.md: accent=teal signal/400, RESERVED for Radar/BLE/offline --",
      "do not reuse signal/* anywhere else, that breaks the mental model.",
    ].join("\n"),
  ),
);
push("            public object Radar {");
push(`                public val default: ComposeColor = ${toKotlinColor(get("accent.radar.default"))}`);
push(`                public val pressed: ComposeColor = ${toKotlinColor(get("accent.radar.pressed"))}`);
push(`                public val wash: ComposeColor = ${toKotlinColor(get("accent.radar.wash"))}`);
push("            }");
push(
  ...kdocLines(
    "            ",
    [
      "The like/match moment (Likes You, Standouts, match celebration, mutual-wave). Its own",
      "accent, not an ember sub-shade -- a screen dedicated to that moment may make this its",
      "ONE primary accent, replacing ember for that screen.",
    ].join("\n"),
  ),
);
push("            public object Like {");
push(`                public val default: ComposeColor = ${toKotlinColor(get("accent.like.default"))}`);
push(`                public val pressed: ComposeColor = ${toKotlinColor(get("accent.like.pressed"))}`);
push(`                public val wash: ComposeColor = ${toKotlinColor(get("accent.like.wash"))}`);
push("            }");
push(...kdocLines("            ", "THREADS: one inbox, favours neither origin. Deliberately neutral, not a hue."));
push("            public object Threads {");
push(`                public val default: ComposeColor = ${toKotlinColor(get("accent.threads.default"))}`);
push("            }");
push("        }", "");

push(
  ...kdocLines(
    "        ",
    [
      "Figma Foundations bound only a single /400 stop for each status colour (unlike the",
      "accent ramps, which have 3+). No pressed/wash tier exists here without inventing hex --",
      "see README.md#status-ramp-gap. Use Content.onFill for anything drawn on a status fill.",
    ].join("\n"),
  ),
);
push("        public object Status {");
push(`            public object Success { public val default: ComposeColor = ${toKotlinColor(get("status.success.default"))} }`);
push(`            public object Warning { public val default: ComposeColor = ${toKotlinColor(get("status.warning.default"))} }`);
push(`            public object Danger  { public val default: ComposeColor = ${toKotlinColor(get("status.danger.default"))} }`);
push(`            public object Info    { public val default: ComposeColor = ${toKotlinColor(get("status.info.default"))} }`);
push("        }");
push("    }", "");

push(
  ...kdocLines(
    "    ",
    [
      "4pt base grid, exactly as specified in Figma Foundations. Note it is NOT a pure multiple-of-4",
      "ramp -- 6 is a deliberate half-step. This replaces RadiusSpacing's current xs/sm/md/lg/xl",
      "placeholder ramp (4/8/16/24/32), which does not match Foundations and is a second set of",
      "placeholder values hardening -- see README.md#spacing-mismatch.",
    ].join("\n"),
  ),
);
push("    public object Spacing {");
for (const n of [0, 2, 4, 6, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80]) {
  push(`        public val space${n}: Dp = ${dp(n)}`);
}
push("    }", "");

push(
  ...kdocLines(
    "    ",
    "xs/badges, s/inputs+small buttons, m/buttons+cards, l/cards+tiles+bubbles, xl/sheets+modals, 2xl/hero media, full/avatars+pills+FABs.",
  ),
);
push("    public object Radius {");
push(`        public val xs: Dp = ${dp(tokens.radius.xs.value)}`);
push(`        public val s: Dp = ${dp(tokens.radius.s.value)}`);
push(`        public val m: Dp = ${dp(tokens.radius.m.value)}`);
push(`        public val l: Dp = ${dp(tokens.radius.l.value)}`);
push(`        public val xl: Dp = ${dp(tokens.radius.xl.value)}`);
push(`        public val xxl: Dp = ${dp(tokens.radius["2xl"].value)}`);
push(`        public val full: Dp = ${dp(tokens.radius.full.value)}`);
push("    }", "");

push(
  ...kdocLines(
    "    ",
    [
      'Type scale. `family` is a LOGICAL name ("display" | "ui"), not a bound FontFamily -- Fraunces',
      "and Inter font files are not wired into :android yet (open item, needs its own go-ahead per",
      "ORCHESTRATION §8, since bundling font resources or wiring Downloadable Fonts is a new",
      "resource/dependency decision). Map `family` to a real FontFamily once that lands.",
      "",
      "`letterSpacing` unit is assumed px per the Figma export convention (unconfirmed -- see",
      "README.md#overline-tracking); applied here as an absolute TextUnit via .sp, Compose's",
      "closest analogue to px-based tracking.",
    ].join("\n"),
  ),
);
push(
  "    public data class TypeSpec(",
  "        public val family: String,",
  "        public val weight: Int,",
  "        public val size: TextUnit,",
  "        public val lineHeight: TextUnit,",
  "        public val letterSpacing: TextUnit,",
  "        public val uppercase: Boolean = false,",
  "    )",
  "",
  "    public object Type {",
);
for (const [key, spec] of Object.entries(tokens.type.scale)) {
  const propName = key.replace(/\.(\w)/g, (_, c) => c.toUpperCase()).replace(/\./g, "");
  push(
    `        public val ${propName}: TypeSpec = TypeSpec(family = "${spec.family}", weight = ${spec.weight}, size = ${sp(spec.size)}, lineHeight = ${sp(spec.lineHeight)}, letterSpacing = ${sp(spec.letterSpacing)}, uppercase = ${spec.uppercase ?? false})`,
  );
}
push("    }", "}", "");

const kt = L.join("\n");

mkdirSync(path.join(BUILD_DIR, "android"), { recursive: true });
writeFileSync(path.join(BUILD_DIR, "android", "RadiusDesignTokens.kt"), kt, "utf8");

// ---------------------------------------------------------------------------
// 5. flat resolved JSON (bonus, for a future Swift/TS generator)
// ---------------------------------------------------------------------------
writeFileSync(path.join(BUILD_DIR, "tokens.resolved.json"), JSON.stringify(resolved, null, 2), "utf8");

console.log(`Wrote build/android/RadiusDesignTokens.kt`);
console.log(`Wrote build/tokens.resolved.json`);
