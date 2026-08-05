#!/usr/bin/env bash
# devops/ci/gates/lib/common.sh
#
# Shared helpers for the qa-test gate scripts in devops/ci/gates/. Sourced, never executed
# directly. POSIX-ish bash, no non-stdlib dependency, because the one thing every runner in this
# project is guaranteed to have is bash + coreutils (Gitea Actions self-hosted, per devops/CLAUDE.md)
# and a JDK (mobile toolchain). Anything needing more than that (Node, for the vectors JSON check)
# says so explicitly where it is used.
#
# Owner: qa-test. Lives under devops/ci/ (TEST + GATE stages carve-out, root CLAUDE.md REPO MAP).

set -euo pipefail

# ---- repo root -------------------------------------------------------------------------------
# Resolve the repo root without assuming a git checkout is available in every context (the
# self-test harness under devops/ci/tests/ runs against synthetic fixtures, not the live repo).
qa_repo_root() {
  if command -v git >/dev/null 2>&1 && git rev-parse --show-toplevel >/dev/null 2>&1; then
    git rev-parse --show-toplevel
    return
  fi
  # Fallback (no git): walk up from this file looking for the ROOT CLAUDE.md specifically. Several
  # subdirectories (devops/, mobile/) have their OWN CLAUDE.md, so "a CLAUDE.md exists" is not
  # sufficient — the root is the directory that ALSO has both mobile/ and backend/ as siblings.
  local dir
  dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  while [[ "$dir" != "/" ]]; do
    if [[ -f "$dir/CLAUDE.md" && -d "$dir/mobile" && -d "$dir/backend" ]]; then
      echo "$dir"
      return
    fi
    dir="$(dirname "$dir")"
  done
  echo "FATAL: could not locate repo root (no .git, no root CLAUDE.md found walking up from $0)" >&2
  exit 2
}

# ---- output ------------------------------------------------------------------------------------
# No colour library dependency — Gitea Actions log viewers vary in ANSI support. Plain, loud,
# grep-able prefixes instead. A gate's failure mode must be legible in a raw log dump, not just a
# pretty terminal.
qa_info()  { printf '[qa-gate] %s\n' "$*"; }
qa_warn()  { printf '[qa-gate][WARN] %s\n' "$*" >&2; }
qa_fail()  { printf '[qa-gate][FAIL] %s\n' "$*" >&2; }
qa_pass()  { printf '[qa-gate][PASS] %s\n' "$*"; }

# qa_hardfail <message...>  — print and exit 1. The single way every gate in this directory dies.
# Centralised so "a gate that fails, fails loudly and exits non-zero" is enforced by construction
# instead of by every script remembering to do both.
qa_hardfail() {
  qa_fail "$*"
  exit 1
}

# ---- comment stripping --------------------------------------------------------------------------
# qa_strip_comments <file>  — prints the file to stdout with comment content BLANKED (never
# deleted — line numbers must stay aligned with the original file for `grep -n` to report
# correctly downstream). Line-oriented heuristic tuned to this codebase's actual style (`//` line
# comments, `/** ... * ... */` KDoc blocks), not a full tokenizer. Extracted here because more than
# one gate needs it: `no_map_no_bearing_gate.sh` (a prose mention of a banned word in a comment is
# not a violation of the invariant it explains) and `internal_escape_gate.sh` /
# `the_line_gate.sh` (this very file's comments legitimately discuss the mangled symbol names and
# the pinned-import mechanism by name, in prose, and must not self-trigger).
#
# KNOWN GAP: does not handle a `/*` that opens mid-line after real code, or a multi-line block
# comment that does NOT use a leading `*` on continuation lines. Acceptable — a human reviewing a
# gate's flagged hits can tell a comment from code at a glance; the risk this heuristic manages is
# gate-wide noise, not a missed real violation (a violation is never ONLY inside a comment, because
# comments do not execute).
qa_strip_comments() {
  sed -E \
    -e 's#//.*$##' \
    -e 's#^[[:space:]]*\*.*$##' \
    -e 's#^[[:space:]]*/\*\*?.*\*/[[:space:]]*$##' \
    -e 's#^[[:space:]]*/\*\*?.*$##' \
    -- "$1"
}

# ---- gradle invocation -------------------------------------------------------------------------
# qa_gradle_wrapper — echoes the correct wrapper entrypoint for THIS OS ("./gradlew.bat" on
# Windows, "./gradlew" everywhere else). The Gradle wrapper ships two entrypoints and CI runs on
# more than one OS family (GitHub's windows-latest today, a self-hosted Linux Gitea runner once B3
# closes — devops/ci/runner/run-stage.sh). That file's `gradle_wrapper()` (devops-tencent, a runner
# concern) makes the identical decision for the runner's own Gradle calls; this is the same
# three-line rule, kept here rather than sourced from theirs so ownership stays one-writer-per-file,
# but DELIBERATELY not reinvented — two independent implementations of "how do we pick a wrapper"
# is the same defect class this file's `qa_strip_comments` neighbour and the vectors-count history
# (see conformance_gate.sh's header) both already taught this codebase to avoid. If this rule ever
# needs to change, change run-stage.sh's copy too — same day, same PR discussion.
qa_gradle_wrapper() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) echo "./gradlew.bat" ;;
    *)                    echo "./gradlew" ;;
  esac
}

# ---- SQL comment stripping ----------------------------------------------------------------------
# qa_strip_sql_comments <file>  — same contract as qa_strip_comments (blank, never delete, so line
# numbers stay aligned), but for `.sql`/`.sq` syntax: `--` line comments instead of `//`, plus the
# same `/* ... */` block-comment heuristic (SQL and C-family block comments are identical syntax).
# Added for G3 (widened invariant-1 scan, `.sql` under backend/) and G1 (RSSI egress gate,
# SQLDelight `.sq` files) — both need "a word in a SQL comment explaining a rule is not the rule
# being broken" for exactly the reason `qa_strip_comments` exists for Kotlin/Swift KDoc.
qa_strip_sql_comments() {
  sed -E \
    -e 's#--.*$##' \
    -e 's#^[[:space:]]*\*.*$##' \
    -e 's#^[[:space:]]*/\*\*?.*\*/[[:space:]]*$##' \
    -e 's#^[[:space:]]*/\*\*?.*$##' \
    -- "$1"
}
