#!/usr/bin/env bash
# devops/ci/gates/internal_escape_gate.sh
#
# INTERNAL-ESCAPE GATE — decision 43, blocker B11 (security review). "A comment is not a
# mechanism; this is the mechanism."
#
# THE FINDING THIS GATE EXISTS FOR: Kotlin `internal` visibility is a COMPILE-TIME property, not a
# JVM access-control mechanism. On the JVM, an `internal` member compiles to a `public` method with
# its name mangled by the compiler as `<name>$<moduleName>_<variant>` (verified against the live
# build with `javap -p`, 2026-08-04):
#
#   KeyRingEntry.accountKey()   -> public final byte[] accountKey$shared_debug()      (debug)
#                                  public final byte[] accountKey$shared_release()    (release)
#   KeySchedule.dailyKey(...)   -> public final byte[] dailyKey$shared_debug(...)
#   KeySchedule.ephemeralIdFor(...) -> public final byte[] ephemeralIdFor$shared_debug(...)
#   BandingPipeline.conformanceState() -> public final ... conformanceState$shared_debug()
#
# The security review proved this is exploitable from a FOREIGN compilation unit with NO
# reflection: any JVM code sharing the process — including any third-party SDK on root CLAUDE.md's
# approved list (push, IAP, crash reporting) — can call `entry.accountKey$shared_debug()` directly,
# exactly like calling any other public method, and read the raw 32-byte `account_key` out of a
# live `KeyRingEntry`, mutate it, or derive an arbitrary epoch's `ephemeral_id` on demand. This is
# the single most sensitive value in the system (`KEY_SCHEDULE.md` §2.2: "the value is by
# construction the most sensitive number in the system") escaping the ONE visibility boundary the
# source code claims to enforce.
#
# WHAT THIS GATE DOES NOT FIX: `internal` visibility is not made real by this script. That is a
# design-level fix (Android Keystore-backed access, a real module boundary, or simply never handing
# out raw key material through a method callable at all) and is ble-protocol's / android-kotlin's
# call, not qa-test's. This gate is the backstop that makes a REGRESSION — someone calling the
# mangled name directly, in our own source, the exact way the reviewer's PoC did — an immediate,
# specific, unmissable build failure instead of a silent reintroduction of the exact hole B11
# documents.
#
# SCOPE LIMIT, stated rather than hidden: this gate greps OUR source tree. It cannot and does not
# audit the compiled bytecode of third-party dependency JARs/AARs (the push/IAP/crash-reporting
# SDKs themselves) for the same call pattern — that is a supply-chain bytecode-scanning problem,
# a different tool, and out of scope here. What this gate guarantees is narrower and still real:
# OUR OWN source never contains the escape, in production or in review.
#
# WHAT COUNTS AS A VIOLATION:
#   - the four named mangled symbols from the finding, with a `$` suffix (any variant):
#     `accountKey$`, `dailyKey$`, `ephemeralIdFor$`, `conformanceState$`
#   - the generic catch-all: literal `$shared_debug` or `$shared_release` ANYWHERE — this covers
#     every OTHER `internal` symbol in :shared that could be escaped the same way, not only the
#     four the reviewer happened to demonstrate. New internal functions are covered automatically;
#     nobody has to remember to extend a list when they add the fifth one.
#
# SCOPE: mobile/**/*.kt, *.kts, *.swift, *.java outside `src/*Test*/` (any path segment containing
# "Test" — androidUnitTest, commonTest, iosTest, androidInstrumentedTest, *Test.kt, *Test.swift all
# match) and outside `**/build/**` (generated output). The PoC/regression test that proves this
# escape is real is EXPECTED to live under a `*Test*` path and is intentionally not scanned — see
# `KeyEscapePoCTest`-shaped files if/when ble-protocol lands one; this gate does not require it to
# exist, it only refuses to let the pattern leak outside test source.
#
# USAGE:
#   devops/ci/gates/internal_escape_gate.sh [--source-dir DIR]
#   --source-dir DIR   Override the scan root (defaults to <repo>/mobile). Self-test harness only.
#
# EXIT CODE: 0 if clean. Non-zero, with every offending file:line, otherwise.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
SCAN_ROOT="$REPO_ROOT/mobile"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir) SCAN_ROOT="$2"; shift 2 ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

[[ -d "$SCAN_ROOT" ]] || qa_hardfail "scan root does not exist: $SCAN_ROOT"

# CALIBRATED AGAINST THE LIVE REPO, 2026-08-04 — an earlier draft of this gate did NOT strip
# comments, on the theory that there is no legitimate reason for production source to contain the
# mangled token even in prose. Running it against the real repo immediately proved that theory
# wrong in the way `no_map_no_bearing_gate.sh`'s theory was wrong: ble-protocol had ALREADY, and
# correctly, documented this exact finding inline as KDoc on `KeySchedule.dailyKey` /
# `ephemeralIdFor` and `Banding.conformanceState` — good practice, explaining in the source why
# `internal` cannot be trusted there, using the literal mangled names as evidence. Comments never
# compile to anything and are categorically incapable of performing the escape (unlike the
# map/bearing gate's "load-bearing" idiom collision, there is no ambiguous case to hand-neutralise
# here — ALL comment occurrences are safe by construction). Comments are therefore stripped before
# scanning, exactly like `no_map_no_bearing_gate.sh`, via the same shared helper.
# Case-sensitive: the mangled names are exact compiler output, not prose that varies in casing.
declare -a PATTERNS=(
  '\baccountKey\$'
  '\bdailyKey\$'
  '\bephemeralIdFor\$'
  '\bconformanceState\$'
  '\$shared_debug'
  '\$shared_release'
)
JOINED="$(IFS='|'; echo "${PATTERNS[*]}")"

found=0
while IFS= read -r -d '' file; do
  case "$file" in
    # NOTE: the glob needs a wildcard on BOTH sides of Test/test — "androidUnitTest",
    # "commonTest", "iosTest" all contain the substring "Test" but do not START with it, so
    # */[Tt]est*/* (anchored at the start of the segment) would silently fail to exclude them.
    */*[Tt]est*/*|*Test.kt|*Test.swift|*Test.java|*/build/*) continue ;;
  esac
  if qa_strip_comments "$file" | grep -nE "$JOINED" >/tmp/qa_escape_hits.$$ 2>/dev/null; then
    found=1
    rel="${file#"$REPO_ROOT"/}"
    qa_fail "internal-visibility escape (mangled JVM symbol) referenced in $rel:"
    sed 's/^/    /' /tmp/qa_escape_hits.$$ >&2
  fi
  rm -f /tmp/qa_escape_hits.$$
done < <(find "$SCAN_ROOT" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.swift' -o -name '*.java' \) -print0)

if [[ $found -eq 1 ]]; then
  qa_fail "INTERNAL-ESCAPE GATE: FAILED. Decision 43 / B11: 'internal' is compile-time only on the"
  qa_fail "JVM. A mangled-name reference outside test source is either the exact security hole the"
  qa_fail "review found, reintroduced, or new production code exploiting an internal symbol the same"
  qa_fail "way. Neither is acceptable without a design-level fix, reviewed and landed first."
  exit 1
fi

qa_pass "INTERNAL-ESCAPE GATE: PASSED. No mangled-JVM-symbol reference outside test source."
exit 0
