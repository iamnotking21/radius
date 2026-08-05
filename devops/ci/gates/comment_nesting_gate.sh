#!/usr/bin/env bash
# devops/ci/gates/comment_nesting_gate.sh
#
# COMMENT-NESTING GATE. "Kotlin block comments nest, Java's do not." A literal `/*` written inside
# a KDoc opens a NESTED block comment; the KDoc's own `*/` closes only that inner one, and the
# outer comment stays open — SILENTLY, no syntax error at the point of the mistake — swallowing
# everything after it to the end of the file.
#
# THIS HAS HIT FOUR TIMES, INDEPENDENTLY, WHICH IS WHY IT IS A GATE AND NOT A REMINDER:
#   1. mobile/design-tokens/scripts/generate.mjs emitted a KDoc containing `signal/*` — swallowed
#      100 lines of GENERATED Kotlin. Fixed with a docSafe() sanitiser on the generator side.
#   2. design-system hit it AGAIN, writing a JS comment ABOUT the bug, while fixing it.
#   3. android-kotlin hit it writing a KDoc ABOUT design-system's bug — inside
#      mobile/android/build.gradle.kts. It silently commented out the generateDesignTokens task
#      registration, the preBuild hook, and the entire dependencies{} block. GRADLE REPORTED NO
#      SYNTAX ERROR — it reported a missing Hilt dependency, because from its point of view the
#      dependency genuinely was not declared. Diagnosed only by probing with a throwaway init
#      script: 13 declared dependencies had silently become 0.
#   4. mobile/shared/src/iosTest/kotlin/com/radius/shared/ble/IosRadioContractTest.kt:16 contains
#      the glob `vectors/*.json` in a KDoc — same trap, live in this repo until android-kotlin's
#      fix lands (see the README status table / 20-state.md for current status). It sits in
#      `iosTest`, the one source set no local build compiles, so the first macOS CI job reports
#      "unclosed comment / expecting a top level declaration" at EOF — nowhere near line 16.
#
# THE GENERATOR'S OWN OUTPUT (hit 1) IS NOW PROTECTED (docSafe()). HAND-WRITTEN KOTLIN IS NOT
# COVERED BY ANYTHING, and hits 2-4 were all hand-written, including one INSIDE THE FIX for hit 1.
# Vigilance visibly does not survive contact with this bug — the third and fourth hits happened
# while people were actively thinking about the first two. Hence a mechanical gate.
#
# WHY A GATE, GIVEN THE COMPILER EVENTUALLY CATCHES IT ANYWAY: the failure is SILENT in the worst
# cases and MISATTRIBUTED in all of them. Hit 3 read as a missing Gradle dependency, not a comment
# bug — nothing about "Hilt dependency not found" points a reader at a doc comment. Hit 4 will read
# as a compile error at end-of-file, ten-plus lines from its actual cause, in a source set (iosTest)
# that nothing on this machine compiles, so it will not even surface until the first macOS runner
# exists. A gate that runs on every push, in seconds, with no toolchain, catches this BEFORE either
# misleading failure mode ever has a chance to cost someone an afternoon.
#
# HOW THIS GATE DETECTS IT, AND WHY THAT APPROACH (not the regex) was chosen: see
# devops/ci/gates/lib/comment_depth_scan.awk's header for the full comparison. Short version: a
# char-by-char state machine tracks block-comment NESTING DEPTH across the WHOLE file (not
# line-by-line), correctly ignoring `/*`/`*/`/`//` that appear inside a string or char literal, and
# reports depth != 0 at EOF — the exact condition that makes the rest of the file vanish. The
# reviewer's regex starting point (`grep -rnE '^[[:space:]]*\*.*(/\*|\*/)'`) finds today's live
# instance, but only because it happens to land on a `*`-prefixed continuation line; it cannot tell
# whether a nest it finds actually balances by EOF, and it has no way to except a legitimate string
# literal without a second bolted-on heuristic. The depth-tracking approach answers the only
# question that matters (does it balance?) directly, and gets the string/char exception for free.
#
# WHAT THIS GATE DOES NOT REUSE, DELIBERATELY: `qa_strip_comments` (lib/common.sh). Every other
# gate in this directory strips comments before scanning because a comment EXPLAINING a banned
# pattern is not an instance of it. This gate is checking the STRUCTURE of comments themselves —
# stripping them first would blind it to the exact thing it exists to find. Convergent with hit 2:
# design-system independently discovered that "write about the bug inside a comment" is itself how
# to reproduce the bug, which is exactly why this gate reads real source, not a stripped copy of it.
#
# CALIBRATED AGAINST THE FOUR REQUIRED FALSE-POSITIVE CLASSES (see test_comment_nesting_gate.sh —
# root CLAUDE.md's own invariant-1 gate shipped flagging `androidx.compose.ui.semantics.heading()`
# and the English idiom "load-bearing" on its first run; a gate that cries wolf gets disabled by the
# first engineer it blocks, so all four are pinned as regression fixtures, not just asserted once):
#   1. a legitimate `/**` opener — passes (depth returns to 0 at its own `*/`).
#   2. a legitimate `*/` closer — same case, same fixture.
#   3. a string literal containing `/*` (e.g. `val glob = "vectors/*.json"`) — passes; the scanner
#      is inside STRING state when it reaches the `/*`, so it is never evaluated as a comment token.
#   4. a `//` line comment containing `/*` (e.g. `// see vectors/*.json`) — passes; `//` is matched
#      before `/*` at that scan position and the rest of the line is skipped outright.
#   PLUS: a comment that legitimately NESTS AND CLOSES (`/* outer /* inner */ still outer */`) is
#   NOT flagged — nesting is legal Kotlin; only an unbalanced nest is the bug this gate exists for.
#
# SCOPE: every `.kt` and `.kts` under mobile/, backend/, devops/ (per HANDOFF). DELIBERATELY
# INCLUDES TEST SOURCE — every other comment/pattern gate in this directory excludes `*Test.kt` /
# any `*/test*/*` path, because those gates check PRODUCTION concerns (an internal-visibility
# escape, a banned location API) that a test fixture may legitimately reference as a string. This
# gate does the opposite on purpose: hit 4, the live violation, IS in test source
# (mobile/shared/src/iosTest/...), and a broken comment in test source is exactly as dangerous as
# one in production source — arguably more so, since it is also the source set least likely to be
# locally compiled before a push (see hit 4's own description above). The only exclusion is
# generated build output.
#
# EXCLUDES: `**/build/**` only (generated/copied Kotlin, not hand-written, out of scope by
# construction — see the header note above about what this gate is and is not for).
#
# USAGE:
#   devops/ci/gates/comment_nesting_gate.sh [--mobile-dir DIR] [--backend-dir DIR] [--devops-dir DIR]
#   Each flag overrides that root (self-test harness only). A root that does not exist is SKIPPED,
#   not a hard failure, same reasoning as no_map_no_bearing_gate.sh's backend/website roots: an
#   owner's directory legitimately not existing yet (or not existing in a synthetic fixture) must
#   never read the same as "checked and clean".
#
# EXIT CODE: 0 if every file's block-comment nesting balances. Non-zero, with every offending
# file:line:col and the WHOLE open-comment stack (outermost first — the actual `/**` to fix is
# always the first line printed for that file), otherwise.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

AWK_PROG="$SCRIPT_DIR/lib/comment_depth_scan.awk"

REPO_ROOT="$(qa_repo_root)"
MOBILE_ROOT="$REPO_ROOT/mobile"
BACKEND_ROOT="$REPO_ROOT/backend"
DEVOPS_ROOT="$REPO_ROOT/devops"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mobile-dir) MOBILE_ROOT="$2"; shift 2 ;;
    --backend-dir) BACKEND_ROOT="$2"; shift 2 ;;
    --devops-dir) DEVOPS_ROOT="$2"; shift 2 ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

command -v awk >/dev/null 2>&1 || qa_hardfail "no 'awk' on PATH — this gate needs it (no other toolchain required)."
[[ -f "$AWK_PROG" ]] || qa_hardfail "missing $AWK_PROG"

found=0

# scan_root ROOT — one root, both extensions, the one shared exclusion (generated build output).
scan_root() {
  local root="$1"
  [[ -d "$root" ]] || return 0
  while IFS= read -r -d '' file; do
    case "$file" in
      */build/*) continue ;;
    esac
    if ! hits="$(awk -f "$AWK_PROG" -- "$file" 2>/dev/null)"; then
      found=1
      rel="${file#"$REPO_ROOT"/}"
      qa_fail "unbalanced block-comment nesting in $rel:"
      printf '%s\n' "$hits" | sed 's/^/    /' >&2
    fi
  done < <(find "$root" -type f \( -name '*.kt' -o -name '*.kts' \) -print0)
}

scan_root "$MOBILE_ROOT"
scan_root "$BACKEND_ROOT"
scan_root "$DEVOPS_ROOT"

if [[ $found -eq 1 ]]; then
  qa_fail "COMMENT-NESTING GATE: FAILED. One or more files have a block comment still open at EOF."
  qa_fail "This is the 'Kotlin comments nest, Java's do not' trap — it has hit this project FOUR"
  qa_fail "times independently (see this script's header). The line printed FIRST for each file"
  qa_fail "(nest level 1, outermost) is the one to fix — remove or reword the literal '/*' inside"
  qa_fail "it (spell it out in words: 'slash star', not the two characters) rather than trying to"
  qa_fail "add another closer, which is exactly how hit 3 happened."
  exit 1
fi

qa_pass "COMMENT-NESTING GATE: PASSED. Every .kt/.kts block comment under mobile/, backend/, devops/ balances."
exit 0
