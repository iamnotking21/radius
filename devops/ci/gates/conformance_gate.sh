#!/usr/bin/env bash
# devops/ci/gates/conformance_gate.sh
#
# CONFORMANCE GATE — 40-contracts.md "BLE wire v0.1": "all vectors run; any failure fails the
# build. Divergence between what the shared codec produces and what a vector asserts is a build
# failure, never a warning." Two phases, run in order, either one failing fails the whole gate:
#
#   PHASE 1 — vectors_manifest_check.js, a FAST PRE-CHECK, NOT the authority. Is
#   mobile/protocol/vectors/index.json internally consistent with the vector files' actual
#   contents? THE AUTHORITATIVE ANSWER TO THAT QUESTION IS `VectorManifestTest.kt`
#   (mobile/shared/src/androidUnitTest/.../protocol/vectors/VectorManifestTest.kt), which
#   ble-protocol owns, runs as part of phase 2 below, and recomputes the count from the files on
#   every CI run. vectors_manifest_check.js exists only so a manifest drift can be caught in
#   milliseconds without a JDK, for local iteration — it deliberately mirrors that Kotlin test's
#   counting rule rather than inventing its own (see that script's header for the incident that
#   taught us why two independent implementations of the same count is itself a bug class).
#
#   NO SPECIFIC VECTOR COUNT IS HARDCODED ANYWHERE IN THIS SCRIPT OR ITS OUTPUT, DELIBERATELY.
#   The count has moved at least three times in one working session while ble-protocol actively
#   developed against it (98 -> 99 declared-but-wrong -> 110 -> 116, each superseding the last
#   within hours) — hardcoding any of those numbers here would have been wrong again within the
#   same day. The gate reports whatever VectorManifestTest.kt's own `println` says, live, every run.
#
#   PHASE 2 — does a Kotlin test harness actually EXERCISE the vectors against the codec? Runs
#   `:shared:testDebugUnitTest` (Android-target JVM tests, includes commonTest) and lets Gradle's
#   own JUnit failure reporting decide pass/fail — "any failure fails the build" falls straight out
#   of standard Gradle/JUnit behaviour once the tests exist, no bespoke logic needed. If no
#   conformance-vector-driven test exists at all under mobile/shared/src, this phase FAILS LOUDLY
#   rather than silently passing or skipping — an untested codec and a verified-clean codec must
#   never look the same in a CI log. After a successful run, this phase greps the JUnit XML for
#   VectorManifestTest's own "VECTORS TOTAL: N executable cases" line and reports N as the current,
#   live, authoritative count — never a number typed into this script.
#
#   iOS: DEFERRED (decision 33), not run here. Once iOS is back in scope this phase must also run
#   `:shared:iosTest` on a macOS runner (see mobile/shared/build.gradle.kts isMacHost guard) — until
#   then, an Android-only conformance pass is what "all vectors run" honestly means here, and this
#   script says so in its output rather than implying full-platform coverage.
#
# USAGE: devops/ci/gates/conformance_gate.sh [--mobile-dir DIR] [--skip-manifest] [--skip-codec]
# EXIT: 0 only if both phases pass.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
MOBILE_DIR="$REPO_ROOT/mobile"
RUN_MANIFEST=1
RUN_CODEC=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mobile-dir) MOBILE_DIR="$2"; shift 2 ;;
    --skip-manifest) RUN_MANIFEST=0; shift ;;
    --skip-codec) RUN_CODEC=0; shift ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

overall_status=0

# ---- phase 1 --------------------------------------------------------------------------------
if [[ $RUN_MANIFEST -eq 1 ]]; then
  qa_info "PHASE 1/2 — vectors manifest fast pre-check (authority is VectorManifestTest.kt, phase 2)"
  command -v node >/dev/null 2>&1 || qa_hardfail "node is required for the manifest check and is not on PATH"
  if node "$SCRIPT_DIR/lib/vectors_manifest_check.js" --dir "$MOBILE_DIR/protocol/vectors"; then
    qa_pass "phase 1: fast pre-check found the manifest internally consistent"
  else
    qa_fail "phase 1: fast pre-check FAILED (see diff above)."
    qa_fail "If VectorManifestTest.kt (phase 2) also fails the same way: HANDOFF to ble-protocol,"
    qa_fail "mobile/protocol/vectors/ is not qa-test's to edit."
    qa_fail "If VectorManifestTest.kt PASSES while this fails: this script's COUNTED_SECTIONS has"
    qa_fail "drifted from that test's rule — fix THIS script, the vectors are not the problem."
    overall_status=1
  fi
fi

# ---- phase 2 --------------------------------------------------------------------------------
if [[ $RUN_CODEC -eq 1 ]]; then
  qa_info "PHASE 2/2 — is any Kotlin test actually exercising the vectors against a codec?"

  # Detection heuristic, documented rather than magic: a real conformance harness will, at minimum,
  # reference the vectors directory or index.json from Kotlin test source. Absence of any such
  # reference is treated as absence of the harness.
  harness_files=""
  if [[ -d "$MOBILE_DIR/shared/src" ]]; then
    harness_files="$(grep -rl -E 'vectors/index\.json|vectors/.*\.json|ConformanceVector|VectorManifestTest' \
      --include='*.kt' "$MOBILE_DIR/shared/src" 2>/dev/null || true)"
  fi

  if [[ -z "$harness_files" ]]; then
    qa_fail "phase 2: NOT YET WIRED. No Kotlin test under mobile/shared/src references the vector"
    qa_fail "files. 0 vectors are exercised by any test right now. This is not a pass and not a skip."
    overall_status=1
  else
    qa_info "  found candidate harness file(s):"
    echo "$harness_files" | sed 's/^/    /'
    gw="$(qa_gradle_wrapper)"
    qa_info "  running $gw :shared:testDebugUnitTest (Android-target JVM tests, incl. commonTest)..."
    (
      cd "$MOBILE_DIR"
      if [[ -n "${JAVA_HOME:-}" ]]; then
        export PATH="$JAVA_HOME/bin:$PATH"
      fi
      "$gw" :shared:testDebugUnitTest --no-daemon
    ) || { qa_fail "phase 2: :shared:testDebugUnitTest FAILED — a vector diverged from the codec, or the harness does not compile"; overall_status=1; }

    if [[ $overall_status -eq 0 ]]; then
      qa_pass "phase 2: codec test task passed. iOS is DEFERRED (decision 33) and not run here."

      # Report the LIVE count from VectorManifestTest's own JUnit XML output — never a number typed
      # into this script. If the test class or its println shape changes, this simply has nothing
      # to report; it must never fall back to a guessed or cached number.
      xml_report="$MOBILE_DIR/shared/build/test-results/testDebugUnitTest/TEST-com.radius.shared.protocol.vectors.VectorManifestTest.xml"
      if [[ -f "$xml_report" ]]; then
        total_line="$(grep -o 'VECTORS TOTAL: [0-9]* executable cases across [0-9]* files' "$xml_report" || true)"
        if [[ -n "$total_line" ]]; then
          qa_info "  live count, from VectorManifestTest itself: $total_line"
        fi
      fi
    fi
  fi
fi

if [[ $overall_status -ne 0 ]]; then
  qa_fail "CONFORMANCE GATE: FAILED."
  exit 1
fi

qa_pass "CONFORMANCE GATE: PASSED."
exit 0
