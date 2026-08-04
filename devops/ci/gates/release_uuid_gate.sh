#!/usr/bin/env bash
# devops/ci/gates/release_uuid_gate.sh
#
# RELEASE GATE — decision 34 (.claude/memory/30-decisions.md #34), blocker B9, SPEC.md §4.1.
#
#   0xFDA9 is a PROVISIONAL BLE service UUID, not SIG-allocated. It exists only because a 16-bit
#   UUID is mandatory for Carrier A (128-bit does not fit the 31-byte legacy advertisement budget —
#   SPEC.md §4.1) and the real SIG allocation is sequenced behind legal-entity formation. It is fine
#   in a DEBUG build. It MUST NEVER appear in anything we ship. That is the whole gate.
#
# WHAT THIS SCRIPT DOES, in order:
#   1. SOURCE SCAN  — greps release-shipping Kotlin/Swift source for the literal value, in every
#      encoding a human is likely to type it in. THIS IS THE PRIMARY, LOAD-BEARING CHECK. See the
#      correction below for why — it was not originally designed to be the primary check, but the
#      artifact scan turned out not to be able to carry that role.
#   2. ARTIFACT SCAN — greps the actual assembled release APK (classes.dex, resources.arsc, native
#      libs, assets) for the same patterns. SECONDARY / DEFENSE-IN-DEPTH ONLY — see the correction.
#
# CORRECTION, 2026-08-04, written up because getting this wrong quietly would be worse than getting
# it right loudly: this script originally called the artifact scan "authoritative" on the theory
# that a compiled release binary cannot lie about what it ships, and source review can be fooled by
# indirection. That theory is correct for STRING literals (a UUID typed as `"0000fda9-...-...-..."`
# stays a literal UTF-8 entry in the dex string table straight through R8) and WRONG for the actual
# shape ble-protocol's real implementation takes. Verified against the live codebase the same day
# this gate was written:
#
#   `mobile/shared/src/commonMain/kotlin/com/radius/shared/protocol/Advertisement.kt:66` declares
#   `public const val SERVICE_UUID16: Int = 0xFDA9` and consumes it two lines later via
#   `(SERVICE_UUID16 and 0xFF).toByte()` / `(SERVICE_UUID16 ushr 8) and 0xFF).toByte()` — i.e. the
#   ordinary, non-adversarial way you build a little-endian 2-byte field from a 16-bit constant.
#
#   Built `:android:assembleRelease` (R8 minified, exactly the shipping config) and disassembled the
#   resulting classes.dex with `dexdump -d` (Android SDK build-tools). Neither `fda9` nor `64937`
#   (its decimal form) appears anywhere as a recognisable operand — grepping the raw disassembly text
#   for the bare substring "fda9" DOES match, but only as noise inside unrelated hex byte-offset
#   addresses (dexdump prints `0fda90:`-shaped offset columns; "fda9" is a coincidental substring of
#   an address, not the constant). Anchored patterns that only match a real operand (`#fda9\b`,
#   `#int 64937\b`) match ZERO times. R8 constant-folds the AND/USHR into two independent single-byte
#   loads (0xA9 and 0xFD, i.e. -87 and -3 as signed bytes) at the two use sites; the 16-bit value
#   0xFDA9 is never materialised as one token anywhere in the compiled output for the compiler to
#   leave a trace of. THIS IS NOT A CONTRIVED EVASION. It is what today's real, non-adversarial code
#   already produces, and it means a bytecode/artifact scan cannot be trusted to catch this class of
#   violation — full stop, not "usually, with rare exceptions."
#
# WHAT THIS MEANS PRACTICALLY: the SOURCE SCAN is not a fast pre-check ahead of an authoritative
# artifact scan. It is the check. Do not skip it in CI to save build time, and do not treat a clean
# artifact scan as meaningful on its own — see how source_scan() below is what actually catches
# today's real violation (Advertisement.kt:66) while the artifact scan of that exact same release
# build reports clean. Both facts are printed by this script every run, on purpose, so nobody has to
# rediscover this by reading the comment.
#
# WHAT NEITHER PHASE CLAIMS TO CATCH: a second, equally-provisional-looking UUID a future engineer
# picks without going through the SIG Adopter process. This gate only knows about the one specific
# value that is pre-rejected in writing today (decision 34). If the value changes, PATTERNS below
# must change with it — grep for the literal value, not a symbol name, precisely because symbol
# names get renamed and this constant will not magically follow.
#
# USAGE:
#   devops/ci/gates/release_uuid_gate.sh [--apk PATH] [--source-dir DIR] [--skip-source]
#                                        [--skip-artifact]
#
#   --apk PATH        Path to an already-built release APK. If omitted, the script looks under
#                      mobile/android/build/outputs/apk/release/*.apk and, if none exists, HARD
#                      FAILS rather than silently skipping the authoritative check (hard constraint:
#                      a gate that cannot run must fail loudly, not pass silently).
#   --source-dir DIR   Override the repo root for the source scan. Used by the self-test harness in
#                      devops/ci/tests/gates/ against synthetic fixtures — never used in real CI.
#   --skip-source / --skip-artifact   Run only one phase. Used by the self-test harness.
#
# EXIT CODE: 0 only if both phases run and find nothing. Non-zero otherwise, including when a
# required phase could not run at all.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
APK_PATH=""
SOURCE_ROOT="$REPO_ROOT"
RUN_SOURCE=1
RUN_ARTIFACT=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) APK_PATH="$2"; shift 2 ;;
    --source-dir) SOURCE_ROOT="$2"; shift 2 ;;
    --skip-source) RUN_SOURCE=0; shift ;;
    --skip-artifact) RUN_ARTIFACT=0; shift ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

# ---- the patterns -------------------------------------------------------------------------------
# Case-insensitive, extended regex. Every encoding a human or a code generator plausibly produces
# for the 16-bit value 0xFDA9 and its 128-bit Bluetooth-Base-UUID expansion (SPEC.md §4.1).
#   - bare hex, with or without 0x prefix: FDA9, 0xFDA9, 0XFDA9
#   - the full 128-bit expansion as CBUUID/ParcelUuid would stringify it: 0000fda9-0000-1000-8000-00805f9b34fb
PRIMARY_PATTERN='(0x)?fda9|0000fda9-0000-1000-8000-00805f9b34fb'
# Heuristic-only, WARN not FAIL (see rationale below): decimal form and on-air little-endian byte
# order. Both have real false-positive risk (64937 is an unremarkable-looking number; "a9fd" is a
# plausible substring of unrelated hex) so they do not block the pipeline by themselves, but they
# are printed for a human to eyeball on every run — cheap insurance, zero cost when clean.
HEURISTIC_PATTERN='\b64937\b|a9fd'

# ---- phase 1: source scan ------------------------------------------------------------------------
# Only paths that actually ship into a release build. Explicitly excludes:
#   - mobile/protocol/  (ble-protocol's spec + vectors; documentation and fixtures, never compiled
#     into the app, and SPEC.md itself is REQUIRED to say "0xFDA9" repeatedly to document the ban)
#   - **/tests/, **/*Test.kt, **/*Test.swift, androidUnitTest/, commonTest/, iosTest/, androidTest/
#     (test-only source sets never ship in a release artifact)
#   - devops/  (this gate's own scripts and fixtures)
#   - build/  (generated output, covered by the artifact scan instead, and scanning it here would
#     just re-detect whatever the artifact scan already authoritatively checks)
source_scan() {
  qa_info "SOURCE SCAN — scanning release-shipping Kotlin/Swift source under mobile/ for '0xFDA9'"

  local search_roots=(
    "$SOURCE_ROOT/mobile/shared/src/commonMain"
    "$SOURCE_ROOT/mobile/shared/src/androidMain"
    "$SOURCE_ROOT/mobile/shared/src/iosMain"
    "$SOURCE_ROOT/mobile/android/src/main"
    "$SOURCE_ROOT/mobile/ios/Sources"
  )
  local allowlist="$SCRIPT_DIR/release_uuid_gate.allowlist"
  local found=0
  local matches=""

  for root in "${search_roots[@]}"; do
    [[ -d "$root" ]] || continue
    while IFS= read -r -d '' file; do
      # Never scan test source even if it lives under a main-looking root by mistake.
      case "$file" in
        *Test.kt|*Test.swift|*/test/*|*/androidTest/*|*/androidUnitTest/*|*/commonTest/*|*/iosTest/*)
          continue ;;
      esac
      local rel="${file#"$REPO_ROOT"/}"
      if grep -qi -E "$PRIMARY_PATTERN" -- "$file"; then
        if [[ -f "$allowlist" ]] && grep -qxF "$rel" "$allowlist"; then
          qa_info "  allowlisted (see release_uuid_gate.allowlist): $rel"
          continue
        fi
        found=1
        matches+="$(grep -ni -E "$PRIMARY_PATTERN" -- "$file" | sed "s|^|  $rel:|")"$'\n'
      fi
    done < <(find "$root" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.swift' \) -print0)
  done

  if [[ $found -eq 1 ]]; then
    qa_fail "0xFDA9 literal found in release-shipping source, not covered by the allowlist:"
    printf '%s' "$matches" >&2
    qa_fail "Either this is genuinely a release-path leak (fix it), or it is legitimately debug-only"
    qa_fail "code (guard it with BuildConfig.DEBUG and add the file to release_uuid_gate.allowlist"
    qa_fail "with a comment explaining the guard — see that file's header)."
    return 1
  fi

  qa_pass "source scan: no un-allowlisted 0xFDA9 literal in release-shipping source"
  return 0
}

# ---- phase 2: artifact scan (defense-in-depth, NOT authoritative — see header correction) --------
artifact_scan() {
  qa_info "ARTIFACT SCAN — scanning the assembled release APK for '0xFDA9' string-literal leaks"
  qa_warn "this phase catches STRING-typed leaks only. It CANNOT see a plain 'const val X: Int ="
  qa_warn "0xFDA9' consumed byte-by-byte (R8 decomposes it before a single grep-able token exists —"
  qa_warn "confirmed against the live codebase, see this script's header). SOURCE SCAN is primary."

  if [[ -z "$APK_PATH" ]]; then
    local candidates=()
    if [[ -d "$REPO_ROOT/mobile/android/build/outputs/apk/release" ]]; then
      while IFS= read -r -d '' f; do candidates+=("$f"); done \
        < <(find "$REPO_ROOT/mobile/android/build/outputs/apk/release" -name '*.apk' -print0)
    fi
    if [[ ${#candidates[@]} -eq 0 ]]; then
      qa_fail "no release APK found and none passed via --apk."
      qa_fail "This phase was REQUESTED and cannot be skipped silently just because nothing was built"
      qa_fail "(it's a real check even though the source scan is primary — see header)."
      qa_fail "Build one first: (cd mobile && ./gradlew.bat :android:assembleRelease --no-daemon)"
      return 1
    fi
    APK_PATH="${candidates[0]}"
  fi

  [[ -f "$APK_PATH" ]] || qa_hardfail "APK not found at: $APK_PATH"
  command -v unzip >/dev/null 2>&1 || qa_hardfail "unzip not on PATH — cannot inspect $APK_PATH"

  qa_info "  scanning: $APK_PATH"
  local extract_dir
  extract_dir="$(mktemp -d)"
  trap 'rm -rf "$extract_dir"' RETURN
  unzip -oq "$APK_PATH" -d "$extract_dir"

  # SCOPE, corrected 2026-08-04 after a real false positive: an early version of this scan ran over
  # lib/**/*.so unconditionally and flagged a hit inside libsqlcipher.so — SQLCipher's own compiled,
  # third-party native binary, which we do not write and cannot meaningfully review. The "match" was
  # four coincidental bytes in dense compiled machine code; a multi-megabyte .so file will contain
  # essentially every short byte sequence somewhere by chance, and grep cannot tell "we put this
  # here" apart from "this occurred at random in someone else's compiled binary". Scanning native
  # libs this way produces noise, not signal, and noisy gates get ignored. Restricted to the parts of
  # the APK whose CONTENT WE ACTUALLY WRITE: classes*.dex (our Kotlin, D8/R8-compiled), resources.arsc
  # and res/ (our resources), assets/ (our bundled assets). lib/ (native code, all third-party in
  # this project — SQLCipher, per mobile/shared/build.gradle.kts) and META-INF/ (dependency metadata,
  # not ours) are excluded.
  local scan_targets=()
  for p in classes.dex classes2.dex classes3.dex resources.arsc res assets; do
    [[ -e "$extract_dir/$p" ]] && scan_targets+=("$extract_dir/$p")
  done
  # Also pick up any additional classesN.dex beyond the three named above.
  while IFS= read -r -d '' f; do scan_targets+=("$f"); done \
    < <(find "$extract_dir" -maxdepth 1 -name 'classes*.dex' -print0 2>/dev/null)

  if [[ ${#scan_targets[@]} -eq 0 ]]; then
    qa_hardfail "artifact scan found nothing to scan under $extract_dir — unexpected APK layout"
  fi

  # -a: treat binary as text — the UUID would appear as a contiguous ASCII string inside classes.dex
  #     or resources.arsc if present as a STRING constant (R8/D8 keep string literals as literal
  #     bytes in the dex string pool). Does NOT catch a byte-decomposed int constant — see header.
  if grep -rai -E "$PRIMARY_PATTERN" -- "${scan_targets[@]}" >/tmp/qa_release_uuid_hits.$$ 2>/dev/null; then
    qa_fail "0xFDA9 found INSIDE THE ASSEMBLED RELEASE APK (our code/resources, not a native lib)."
    qa_fail "This build MUST NOT ship (decision 34)."
    sed "s|$extract_dir|<apk>|" /tmp/qa_release_uuid_hits.$$ | sed 's/^/  /' >&2
    rm -f /tmp/qa_release_uuid_hits.$$
    return 1
  fi
  rm -f /tmp/qa_release_uuid_hits.$$

  qa_pass "artifact scan: no 0xFDA9 STRING literal in our dex/resources/assets (does not rule out a"
  qa_pass "  byte-decomposed int constant — see header. Rely on the source scan for that class.)"

  # Heuristic pass — printed, non-blocking, see header rationale. Same scope restriction applies.
  if grep -rai -E "$HEURISTIC_PATTERN" -- "${scan_targets[@]}" >/dev/null 2>&1; then
    qa_warn "heuristic pattern (decimal 64937 / little-endian 'a9fd') matched something in our"
    qa_warn "dex/resources/assets. NOT a failure by itself (false-positive risk) — human glance only:"
    grep -rlai -E "$HEURISTIC_PATTERN" -- "${scan_targets[@]}" | sed "s|$extract_dir|  <apk>|" >&2 || true
  fi

  return 0
}

# ---- run ------------------------------------------------------------------------------------------
overall_status=0

if [[ $RUN_SOURCE -eq 1 ]]; then
  source_scan || overall_status=1
fi

if [[ $RUN_ARTIFACT -eq 1 ]]; then
  artifact_scan || overall_status=1
fi

if [[ $overall_status -ne 0 ]]; then
  qa_fail "RELEASE UUID GATE: FAILED. 0xFDA9 must not ship (decision 34). Blocking release."
  exit 1
fi

qa_pass "RELEASE UUID GATE: PASSED."
exit 0
