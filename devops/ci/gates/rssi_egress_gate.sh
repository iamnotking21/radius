#!/usr/bin/env bash
# devops/ci/gates/rssi_egress_gate.sh
#
# RSSI EGRESS GATE — decision 28, CLAIMS_REGISTER.md gap G1, BANDING.md §6.3.
#
#   "Raw/filtered/adjusted RSSI must never cross the shared boundary, reach a server, or appear in
#   logs, analytics, or debug builds." That rule is currently enforced by exactly one redacted
#   `toString` (`RawSighting.toString`, BleRadio.kt) and one inverted test
#   (`RawSightingTest.toString_never_leaks_the_rssi_either`) — both of which prove ONE class does
#   the right thing today. Neither stops a SECOND class, a log line, or a `.sq` column from
#   reintroducing the leak tomorrow. `grep -rin "rssi" devops/ci/` returned nothing before this
#   file. That is the gap this closes.
#
#   Why this matters beyond the source code: CLAIMS_REGISTER.md row A2 ("raw RSSI never leaves the
#   device") backs a sentence in the published privacy policy. A `PARTIAL` claim enforced by
#   reviewer memory is a false statement waiting for someone to ship a feature under deadline
#   pressure and not notice what they just invalidated.
#
# WHAT COUNTS AS A VIOLATION, and why:
#
#   1. LOG / PRINT SINK — a line (comments stripped) that BOTH calls a recognised logging/print
#      function AND mentions an rssi-shaped identifier anywhere on that same line. Catches the
#      direct-argument form (`Log.d(TAG, sighting.rssiDbm.toString())`), the concatenation form
#      (`Log.d(TAG, "rssi=" + rssiDbm)`) and the interpolation form, all in one check, because the
#      log/print token is the narrow, unambiguous half of the condition — see the CALIBRATION note
#      below for why this line-scoped check is exactly what does NOT flag the correct redaction in
#      `RawSighting.toString()`.
#   2. STRING-TEMPLATE SINK, independent of a log call — an rssi-shaped identifier used inside a
#      Kotlin `$name` / `${expr}` interpolation, or a Swift `\(expr)` interpolation, ANYWHERE, not
#      only inside a recognised log call. A string built today and logged, breadcrumbed, or
#      attached to a crash report tomorrow is the egress the moment it exists, not the moment it is
#      printed — `AndroidRadioDiagnostics.kt`'s own header makes exactly this argument about why a
#      "debug-only accessor" is not a weaker case.
#   3. STORAGE / SERIALIZATION SINK — the one CLAIMS_REGISTER.md G1 calls out as the one that
#      matters long term ("the leak that ends up in a database is worse than the one in logcat"):
#        a. any `.sq` file (SQLDelight, raw SQL) containing an rssi-shaped identifier anywhere,
#           full stop — a `.sq` file IS its own schema/query text, there is no "just a mention" case
#           the way there is in commented prose.
#        b. any `.kt`/`.kts` file that contains BOTH an `@Serializable` (or `@ProtoNumber`,
#           kotlinx.serialization wire-format) marker AND an rssi-shaped identifier, anywhere in the
#           file. File-scoped rather than property-scoped on purpose — see CALIBRATION below.
#
# WHAT "RSSI-SHAPED IDENTIFIER" MEANS HERE, DELIBERATELY NARROW: the case-insensitive substring
# `rssi` (matches `rssiDbm`, `rssi_dbm`, `RSSI`, `peerRssi`, ...). NOT the bare substring `dbm` —
# `txPowerCalDbm` / `tx_power_cal_dbm` / `TX_REF_DBM` / `THRESHOLD_HERE`-style constants are a
# CALIBRATED value transmitted deliberately inside the 19-byte frame (safety invariant 4's
# `txpower` byte; `Frame.kt`, `Banding.kt`) — a completely different, explicitly-allowed field that
# happens to share a unit suffix. A bare `dbm` pattern would flag `mobile/shared/src/commonMain/
# kotlin/com/radius/shared/protocol/Frame.kt` and `Banding.kt` on their very first line and teach
# everyone to ignore this gate within a week. `rssi` is specific to the received-signal-strength
# value decision 28 is actually about.
#
# SCOPE, PER ORCHESTRATOR: `mobile/shared/src/commonMain` and every `mobile/**/src/main` directory
# (found dynamically, so a future platform's `src/main` is covered without editing this file).
# EXCLUDES `src/debug` — `mobile/android/src/debug/spike/SpikeWriter.kt` writes `rssi_dbm` to a CSV
# column deliberately and correctly (Phase 0 measurement harness; that build carries no INTERNET
# permission, gate G2's job) — and excludes `**/build/**` and any `*Test.kt`/`*Test.swift` path.
#
# NOTE ON WHAT THIS SCOPE DOES NOT COVER, STATED RATHER THAN HIDDEN: `mobile/shared/src/androidMain`
# and `mobile/shared/src/iosMain` are KMP source-set names, not `src/main` directories, and are
# outside the literal scope given for this gate. That is where `AndroidRadioDiagnostics.kt` and
# `BleRadio.android.kt` legitimately carry an `rssiDbm` field as the Phase 0 diagnostics containment
# boundary (that file's own header: gated behind `setDiagnosticsEnabled`, unreachable from `RadiusCore`
# or the UI, deletion-before-ship obligation) — scanning it today would require an allowlist entry for
# code that is already narrowly justified in writing. If that scope needs widening once `androidMain`/
# `iosMain` carry ordinary product code alongside the diagnostics carve-out, that is a HANDOFF, not a
# silent edit here.
#
# CALIBRATION, checked against the live repo before this header was final:
#   - `RawSighting.toString()` (`BleRadio.kt`) renders the literal text `rssiDbm=<redacted>` — no
#     log/print token on that line and no `$`/`${}` interpolation of an rssi-shaped identifier (the
#     interpolations on that line are `${payload.size}` and `$observedAtEpochMs`, neither of which
#     mentions rssi). Checks 1 and 2 both correctly pass this line. A naive "flag any line
#     mentioning both a sink-shaped token and the substring rssi" design was tried first and DOES
#     flag this line — the exact redaction decision 28 requires — which is why check 2 matches only
#     real interpolation syntax, not textual adjacency.
#   - Check 3b is FILE-scoped, not property-scoped: precisely locating "this property, inside this
#     annotated class" from a regex is unreliable, and today's real repo has zero `@Serializable`
#     files, so a coarser check costs nothing yet and is not currently a false-positive risk. Revisit
#     with a per-property check when the Connect-RPC client / first `@Serializable` message lands.
#
# WHAT THIS GATE DOES NOT CATCH, STATED RATHER THAN HIDDEN:
#   - multi-line indirection (`val msg = "rssi=" + x; laterCall(msg)`) — only same-line concatenation
#     is matched.
#   - a crash-reporter SDK call that is not one of the recognised log/print tokens (CLAIMS_REGISTER
#     gap G4, "no crash-report scrubbing policy", is the tracked follow-up for that class).
#   - third-party dependency bytecode. Same stated limit as `internal_escape_gate.sh`.
#
# USAGE:
#   devops/ci/gates/rssi_egress_gate.sh [--source-dir DIR]
#   --source-dir DIR   Override the repo root the scan roots are resolved under. Self-test only.
#
# EXIT CODE: 0 if clean. Non-zero, with every offending file:line and the reason, otherwise.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
SOURCE_ROOT="$REPO_ROOT"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir) SOURCE_ROOT="$2"; shift 2 ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

MOBILE_ROOT="$SOURCE_ROOT/mobile"
[[ -d "$MOBILE_ROOT" ]] || qa_hardfail "scan root does not exist: $MOBILE_ROOT"

# ---- resolve scan roots ---------------------------------------------------------------------------
declare -a SCAN_ROOTS=()
[[ -d "$MOBILE_ROOT/shared/src/commonMain" ]] && SCAN_ROOTS+=("$MOBILE_ROOT/shared/src/commonMain")
while IFS= read -r -d '' d; do
  SCAN_ROOTS+=("$d")
done < <(find "$MOBILE_ROOT" -type d -path '*/src/main' -not -path '*/build/*' -print0 2>/dev/null)

if [[ ${#SCAN_ROOTS[@]} -eq 0 ]]; then
  qa_hardfail "no scan roots found (expected mobile/shared/src/commonMain and/or a mobile/**/src/main)"
fi

# ---- the sink patterns ------------------------------------------------------------------------
# Log/print tokens. Case-sensitive — these are real API names, not prose.
LOG_SINK_PATTERN='\bLog\.[vdiwe]\(|\bLog\.wtf\(|\bLogger\.|\bprintln\(|\bprint\(|\bSystem\.out\.print|\bNSLog\(|\bos_log\(|\bdebugPrint\('
# rssi-shaped identifier, case-insensitive substring — see header for why not bare "dbm".
RSSI_IDENT='rssi'
# Interpolation / concatenation forms that build a string carrying an rssi-shaped value, matched
# independently of any log call (check 2). Case-insensitive.
# NOTE: the identifier-fragment classes below are `[A-Za-z0-9_]*` (ZERO or more), not "one mandatory
# leading char then more" — an earlier draft required >=1 char before the literal "rssi" and could
# therefore never match an identifier that STARTS with rssi (`$rssiDbm` itself!), because that
# mandatory leading class always consumed at least the 'r' before the "rssi" literal was tried.
# Found by running the self-test, not by inspection — see test_rssi_egress_gate.sh case 5.
RSSI_INTERP_PATTERN='\$\{[^}]*rssi[^}]*\}|\$[A-Za-z0-9_]*rssi[A-Za-z0-9_]*|\\\([^)]*rssi[^)]*\)|"[^"]*"[[:space:]]*\+[[:space:]]*[A-Za-z0-9_]*rssi[A-Za-z0-9_]*|[A-Za-z0-9_]*rssi[A-Za-z0-9_]*[[:space:]]*\+[[:space:]]*"'
# Serialization markers for check 3b (file-scoped — see CALIBRATION above).
SERIALIZER_MARKER_PATTERN='@Serializable|@ProtoNumber|kotlinx\.serialization'

found=0

report() {
  local kind="$1" rel="$2"
  shift 2
  found=1
  qa_fail "$kind in $rel:"
  printf '%s\n' "$@" | sed 's/^/    /' >&2
}

scan_code_file() {
  local file="$1" rel="$2"
  local stripped
  stripped="$(qa_strip_comments "$file")"

  # check 1: log/print sink
  local hits1
  hits1="$(printf '%s\n' "$stripped" | grep -nE "$LOG_SINK_PATTERN" | grep -iE "$RSSI_IDENT" || true)"
  if [[ -n "$hits1" ]]; then
    report "RSSI reaching a log/print call" "$rel" "$hits1"
  fi

  # check 2: string-template / concatenation sink, independent of a log call
  local hits2
  hits2="$(printf '%s\n' "$stripped" | grep -inE "$RSSI_INTERP_PATTERN" || true)"
  if [[ -n "$hits2" ]]; then
    report "RSSI-shaped identifier interpolated/concatenated into a string" "$rel" "$hits2"
  fi

  # check 3b: @Serializable / proto marker co-located with an rssi-shaped identifier, file-scoped
  if [[ "$file" == *.kt || "$file" == *.kts ]]; then
    if printf '%s\n' "$stripped" | grep -qE "$SERIALIZER_MARKER_PATTERN" \
       && printf '%s\n' "$stripped" | grep -qiE "$RSSI_IDENT"; then
      local hits3
      hits3="$(printf '%s\n' "$stripped" | grep -niE "$RSSI_IDENT" || true)"
      report "rssi-shaped identifier in a file carrying a serialization marker (@Serializable/@ProtoNumber)" "$rel" "$hits3"
    fi
  fi
}

scan_sq_file() {
  local file="$1" rel="$2"
  local stripped
  stripped="$(qa_strip_sql_comments "$file")"
  local hits
  hits="$(printf '%s\n' "$stripped" | grep -niE "$RSSI_IDENT" || true)"
  if [[ -n "$hits" ]]; then
    report "rssi-shaped identifier in a SQLDelight .sq file" "$rel" "$hits"
  fi
}

for root in "${SCAN_ROOTS[@]}"; do
  while IFS= read -r -d '' file; do
    case "$file" in
      */src/debug/*|*/tests/*|*Test.kt|*Test.swift|*Test.java|*/build/*) continue ;;
    esac
    rel="${file#"$REPO_ROOT"/}"
    scan_code_file "$file" "$rel"
  done < <(find "$root" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.swift' -o -name '*.java' \) -print0)

  while IFS= read -r -d '' file; do
    case "$file" in
      */src/debug/*|*/tests/*|*/build/*) continue ;;
    esac
    rel="${file#"$REPO_ROOT"/}"
    scan_sq_file "$file" "$rel"
  done < <(find "$root" -type f -name '*.sq' -print0)
done

if [[ $found -eq 1 ]]; then
  qa_fail "RSSI EGRESS GATE: FAILED. Decision 28 / CLAIMS_REGISTER.md row A2: raw/filtered/adjusted"
  qa_fail "RSSI must never cross the shared boundary, reach a server, or appear in logs, analytics,"
  qa_fail "or a serialized/persisted field. BANDING.md §6.3: three receivers with raw RSSI"
  qa_fail "multilaterate to a POSITION, not a band. Redact it (see RawSighting.toString for the"
  qa_fail "pattern), or route it through BandingPipeline and consume only the resulting Band/PeerReading."
  exit 1
fi

qa_pass "RSSI EGRESS GATE: PASSED. No rssi-shaped identifier reaching a log, string build, or"
qa_pass "  serialized/.sq field in the scanned roots."
exit 0
