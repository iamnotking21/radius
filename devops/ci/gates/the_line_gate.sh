#!/usr/bin/env bash
# devops/ci/gates/the_line_gate.sh
#
# "THE LINE" GATE — decision 41. Crypto.kt hand-implements SHA-256/HMAC-SHA256/HKDF-SHA256 and
# draws a boundary in its own header comment: "this file must never grow a cipher, a signature
# scheme, a key exchange, or a ratchet." The boundary is correct today (differential-fuzzed against
# the JDK by the security review, zero mismatches). The risk is not today's code — it is month nine,
# when someone needs AES for the encrypted DB and this file is sitting right there looking like the
# obvious place to add it. "A comment does not survive deadline pressure; a red build does."
#
# WHAT THIS GATE DOES: pins the EXACT set of imports and top-level declarations Crypto.kt is
# allowed to have, as of the last time a human deliberately reviewed and endorsed that set (see
# PINNED_* below). Any import or top-level declaration in the live file that is not in the pinned
# set fails the build. Widening the boundary is still possible — it just cannot happen silently:
# the PR has to touch THIS script too, which is qa-test's directory, which makes the widening a
# visible, reviewable, cross-owner diff instead of one line added to a file nobody is re-reading
# line-by-line under a deadline. "Pin the expected symbol list explicitly so the diff is the review."
#
# WHAT THIS GATE DOES NOT DO: judge whether a NEW import or symbol is safe. That is exactly the
# judgement decision 41 says must not be made silently under pressure — it goes back to a review
# (security-privacy / architect), same weight as a proto change (root CLAUDE.md "CONTRACT FIRST").
# This script enforces that the conversation has to happen, not what its answer should be.
#
# INCOMING, PER ORCHESTRATOR (decision 40, NOT YET LANDED, do not build for it early): crypto is
# moving to expect/actual over platform crypto (javax.crypto.Mac / CryptoKit), with today's
# hand-written implementation retained in commonTest as a differential oracle run every CI build
# against the platform backend. WHEN THAT LANDS: the pinned top-level set below changes (some
# symbols move from commonMain to commonTest, `internal` visibility may change, and imports for the
# oracle comparison appear in the TEST file, not this one) and the oracle-comparison run belongs in
# conformance_gate.sh, not here. Until the orchestrator says so, this gate targets ONLY the current
# commonMain/Crypto.kt shape.
#
# USAGE:
#   devops/ci/gates/the_line_gate.sh [--crypto-file PATH]
#   --crypto-file PATH   Override the target file (defaults to the real Crypto.kt). Self-test only.
#
# EXIT CODE: 0 if the live file's imports and top-level symbols are exactly the pinned sets (or a
# subset — REMOVING a pinned symbol only warns, since a removal cannot re-widen the surface).
# Non-zero if anything NEW is present.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
CRYPTO_FILE="$REPO_ROOT/mobile/shared/src/commonMain/kotlin/com/radius/shared/protocol/Crypto.kt"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --crypto-file) CRYPTO_FILE="$2"; shift 2 ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

[[ -f "$CRYPTO_FILE" ]] || qa_hardfail "Crypto.kt not found at: $CRYPTO_FILE"

# =====================================================================================================
# THE PIN. Last reviewed 2026-08-04 against the live file (SHA-256/HMAC-SHA256/HKDF-SHA256,
# constant-time compare). Changing this block IS the review decision 41 requires — do not edit it
# to make a red build green without the corresponding design conversation happening first.
# =====================================================================================================

# Zero imports today, and that absence is load-bearing: Crypto.kt has ZERO dependencies, which is
# part of why it is auditable in isolation. Any import at all is a widening of the boundary, even a
# harmless-looking stdlib one (e.g. kotlin.math.*), and must be reviewed, not waved through.
declare -a PINNED_IMPORTS=()

# The exact top-level declarations (object/fun/class/interface/val/var at column 0 — i.e. not
# nested inside another declaration) Crypto.kt is allowed to have.
declare -a PINNED_TOP_LEVEL=(
  "Sha256"
  "hmacSha256"
  "hkdfSha256"
  "constantTimeEquals"
)
# =====================================================================================================

qa_info "THE LINE GATE — checking $CRYPTO_FILE against the pinned import/symbol set"

# ---- extract actual imports ----------------------------------------------------------------------
mapfile -t actual_imports < <(
  qa_strip_comments "$CRYPTO_FILE" | grep -E '^import[[:space:]]' | sed -E 's/^import[[:space:]]+//' || true
)

# ---- extract actual top-level declarations -------------------------------------------------------
# Column-0 only (`grep -vE '^[[:space:]]'` first) — nested members (e.g. Sha256's `digest`, `pad`,
# `rotr`) are indented and are NOT part of the pinned surface; only the file's top-level shape is.
mapfile -t actual_top_level < <(
  qa_strip_comments "$CRYPTO_FILE" \
    | grep -vE '^[[:space:]]' \
    | grep -E '^(internal|public|private)?[[:space:]]*(object|fun|class|interface|val|var)[[:space:]]+[A-Za-z_]' \
    | sed -E 's/^(internal|public|private)?[[:space:]]*(object|fun|class|interface|val|var)[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\3/' \
  || true
)

contains() {
  local needle="$1"; shift
  local x
  for x in "$@"; do [[ "$x" == "$needle" ]] && return 0; done
  return 1
}

violations=0

for imp in "${actual_imports[@]}"; do
  if ! contains "$imp" "${PINNED_IMPORTS[@]}"; then
    qa_fail "NEW import not in the pinned set: 'import $imp'"
    violations=1
  fi
done

for sym in "${actual_top_level[@]}"; do
  if ! contains "$sym" "${PINNED_TOP_LEVEL[@]}"; then
    qa_fail "NEW top-level declaration not in the pinned set: '$sym'"
    violations=1
  fi
done

# Hygiene warning only (never fails the build): a pinned symbol that has disappeared means the pin
# is stale and should be tightened in the same spirit, even though a removal cannot re-widen THE
# LINE. Keeps the pinned list an accurate diff surface rather than a monotonically growing one.
for sym in "${PINNED_TOP_LEVEL[@]}"; do
  if ! contains "$sym" "${actual_top_level[@]}"; then
    qa_warn "pinned symbol '$sym' no longer exists in Crypto.kt — tighten the pin in the same PR"
  fi
done

if [[ $violations -ne 0 ]]; then
  qa_fail "THE LINE GATE: FAILED. Crypto.kt's import or top-level surface widened beyond the pinned"
  qa_fail "set (decision 41). This requires a design review (security-privacy/architect), same"
  qa_fail "weight as a proto/contract change — not a silent addition under deadline pressure. If the"
  qa_fail "widening is approved, update PINNED_IMPORTS / PINNED_TOP_LEVEL in THIS script in the same"
  qa_fail "PR so the diff carries the decision."
  exit 1
fi

qa_pass "THE LINE GATE: PASSED. Crypto.kt's surface matches the pinned set exactly (or a subset)."
exit 0
