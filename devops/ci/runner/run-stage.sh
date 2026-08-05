#!/usr/bin/env bash
# devops/ci/runner/run-stage.sh
#
# THE SINGLE CI STAGE DISPATCHER. Owner: devops-tencent (root CLAUDE.md REPO MAP:
# "devops-tencent owns the RUNNER + infra/deploy stages" inside the devops/ci/ carve-out).
#
# WHY THIS FILE EXISTS
#   We run the same gates from two different CI providers (.github/workflows/ for GitHub today,
#   .gitea/workflows/ for the self-hosted Gitea instance once Tencent is provisioned — blocker B3).
#   Two workflow files that each contain their own commands WILL drift, and a gate that has silently
#   stopped matching what it claims to enforce is worse than no gate. So:
#
#     * the workflow YAML files contain triggers, runner labels, and calls to this script. Nothing else.
#     * this script contains WHICH COMMAND each stage runs, and the release/non-release blocking split.
#     * the CHECK LOGIC ITSELF lives in devops/ci/gates/*.sh and is owned by qa-test. This script
#       NEVER reimplements, reinterprets, duplicates or approximates a check. It shells out.
#
#   If you are tempted to inline a grep here because "it's only one line" — that is exactly the
#   mechanism by which a gate stops matching its own definition. Add it to a gate script instead
#   (which means asking qa-test, because devops/ci/gates/ is theirs).
#
# USAGE
#   bash devops/ci/runner/run-stage.sh <stage>
#   bash devops/ci/runner/run-stage.sh --list
#
# ENVIRONMENT
#   RADIUS_CI_STRICT=1   Disable the known-red tolerance below. Set by the RELEASE workflow only.
#   BATTERY_RESULTS_JSON Path to hardware-rig battery output, consumed by battery_gate.sh itself.
#                        Unset today: no rig exists (mobile/CLAUDE.md P1).
#   JAVA_HOME            Optional. If set, its bin/ is prepended to PATH before any Gradle stage.
#   ANDROID_HOME / ANDROID_SDK_ROOT   Required for Gradle stages on a CI checkout, because
#                        mobile/local.properties is .gitignored and therefore absent on every runner.
#
# EXIT: 0 pass, 1 fail. See KNOWN-RED below for the one place where a failing gate exits 0.

set -euo pipefail

# ---- repo root ---------------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v git >/dev/null 2>&1 && git -C "$SCRIPT_DIR" rev-parse --show-toplevel >/dev/null 2>&1; then
  REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
else
  REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
fi
cd "$REPO_ROOT"

STRICT="${RADIUS_CI_STRICT:-0}"

log()  { printf '[runner] %s\n' "$*"; }
err()  { printf '[runner][FAIL] %s\n' "$*" >&2; }

# Step summaries. GitHub Actions and Gitea Actions both expose $GITHUB_STEP_SUMMARY. When neither
# does (a human running this locally), fall back to stdout so the message is never lost.
summary() {
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY"
  fi
  printf '%s\n' "$*"
}

# ---- KNOWN-RED REGISTRY ------------------------------------------------------------------------
#
# READ THIS BEFORE CHANGING IT. Two gates in this repo are RED BY DESIGN right now, for reasons that
# are true facts about the world and not bugs:
#
#   gate-release-uuid-source  decision 34 / blockers B9 + B11. 0xFDA9 is a PROVISIONAL BLE service
#                             UUID. There is no SIG-allocated UUID to guard toward yet, because the
#                             SIG Adopter application is sequenced behind legal-entity formation.
#                             The gate is correctly reporting "we cannot ship". It will keep
#                             reporting that for months.
#   gate-battery              <4%/hr scanning, <1%/day idle cannot be measured without the hardware
#                             rig (mobile/CLAUDE.md P1, unbuilt). A green battery gate that measured
#                             nothing would be a lie; battery_gate.sh fails loudly instead.
#
# THE PROBLEM WITH JUST LEAVING THEM RED: a check that is red on every single build for months
# teaches everyone to ignore red. By the time 0xFDA9 actually matters — the day we try to ship — the
# gate has been decoration for a quarter and nobody reads it. That is a worse outcome than not
# having the gate.
#
# THE PROBLEM WITH SILENCING THEM: obvious. The gate stops meaning anything at all.
#
# WHAT WE DO INSTEAD (the xfail pattern, borrowed from test frameworks):
#   On the ordinary push/PR path (RADIUS_CI_STRICT unset) these stages ASSERT THEIR EXPECTED STATE.
#     gate fails  -> EXPECTED. Stage exits 0. The reason is printed in full, every run, into the job
#                    log and the run summary. Nobody is trained to ignore anything, because the
#                    signal is "still blocked, here is why", not an unexplained red X.
#     gate passes -> UNEXPECTED, and it is the single most important event this file can detect:
#                    the world changed. Stage exits 1 with instructions. Yes, this means CI goes red
#                    on good news, exactly once, and the fix is deleting one line from the registry
#                    below. That is deliberate. The alternative is a tolerance that outlives its
#                    cause and silently absorbs a REAL future reintroduction of 0xFDA9 — which is
#                    the precise disaster the gate exists to prevent.
#   On the RELEASE path (RADIUS_CI_STRICT=1) the tolerance does not apply at all. These gates block.
#   They will block. We cannot ship today and CI should say so in the loudest available way at the
#   only moment where saying so is actionable.
#
# THIS TOLERANCE CANNOT HIDE A BROKEN GATE SCRIPT: `gate-selftests` runs qa-test's own full
# self-test suite against synthetic fixtures, it is fully blocking on every path, and it is what
# proves these scripts still detect what they claim to detect.
#
# NOR CAN IT HIDE A BROKEN RUNNER, by construction: require_node() and require_android_toolchain()
# call `exit` directly, which terminates the script before the tolerance below is ever reached. So a
# missing Node or SDK on a known-red stage fails the build with a toolchain message instead of being
# quietly absorbed as "expected red". Verified by running gate-battery with node stripped from PATH:
# exit 1, toolchain message, no EXPECTED-RED summary. That ordering is load-bearing — do not "tidy"
# these preflights into something that returns a status.
#
# TO RETIRE AN ENTRY: delete its line here. Nothing else. Do not edit the workflow YAML.
known_red_reason() {
  case "$1" in
    gate-release-uuid-source)
      echo "decision 34 / B9 / B11 — 0xFDA9 is PROVISIONAL, no SIG-allocated UUID exists to guard toward yet. Live violation: mobile/shared/src/commonMain/kotlin/com/radius/shared/protocol/Advertisement.kt SERVICE_UUID16. Retire this entry when the real UUID lands OR the constant is guarded + allowlisted." ;;
    gate-battery)
      echo "no hardware rig in CI (mobile/CLAUDE.md P1, unbuilt). Nothing to measure. Retire this entry when the rig publishes hardware-rig-sourced results and BATTERY_RESULTS_JSON is wired." ;;
    *) return 1 ;;
  esac
}

# ---- gradle invocation -------------------------------------------------------------------------
# The Gradle wrapper has two entrypoints and CI has two operating systems. This is a RUNNER concern,
# so it is resolved here rather than by asking anyone to change the build.
gradle_wrapper() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) echo "./gradlew.bat" ;;
    *)                    echo "./gradlew" ;;
  esac
}

# require_node <why>  — assert Node is on PATH before a stage that cannot work without it.
#
# Node is a REAL build dependency of this repo now, not just a gate-script convenience. Two
# independent reasons, and they fail in completely different places:
#   1. EVERY `:android` Gradle task. mobile/android/build.gradle.kts hangs `generateDesignTokens`
#      off `preBuild`, which runs mobile/design-tokens/scripts/generate.mjs — so tokens.json is the
#      only place in the repo a colour value exists. VERIFIED by task-graph inspection, not assumed:
#      `--dry-run` puts generateDesignTokens in the graph for testDebugUnitTest, lintDebug,
#      assembleDebug, assembleRelease AND processDebug/ReleaseManifestForPackage. `:shared` alone
#      does NOT pull it, which is why gate-conformance's node requirement below is a different one.
#   2. The JS halves of two gates: vectors_manifest_check.js, battery_threshold_check.js.
#
# WHY THIS PREFLIGHT EXISTS AT ALL, given Gradle would fail anyway: a missing `node` would surface
# from deep inside an :android Gradle task and read as an ANDROID BUILD FAILURE — i.e. as a code
# regression — which is precisely the misattribution README §5b exists to prevent. One second and a
# sentence beats six minutes and a stack trace.
require_node() {
  command -v node >/dev/null 2>&1 || {
    err "no 'node' on PATH. This stage needs it for: $1"
    err "Node is a build dependency of :android itself (design tokens are GENERATED from"
    err "mobile/design-tokens/tokens.json at preBuild), not only of the gate scripts."
    err "This is a RUNNER/TOOLCHAIN failure, not a code regression — see README.md sections 4 and 5b."
    exit 1
  }
}

# Preflight for anything that runs Gradle. mobile/local.properties is .gitignored (correctly — it
# holds machine-local absolute paths), so on a fresh CI checkout the Android SDK location can only
# come from the environment. Without this check the failure surfaces as an AGP error several minutes
# into a build; with it, it surfaces in one second with the fix in the message.
require_android_toolchain() {
  if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  command -v java >/dev/null 2>&1 || {
    err "no 'java' on PATH and JAVA_HOME is unset. This runner needs JDK 21 (Temurin verified)."
    err "See devops/ci/runner/README.md 'RUNNER REQUIREMENTS'."
    exit 1
  }
  if [[ ! -f "$REPO_ROOT/mobile/local.properties" ]] \
     && [[ -z "${ANDROID_HOME:-}" ]] && [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
    err "no mobile/local.properties (correctly .gitignored) and neither ANDROID_HOME nor"
    err "ANDROID_SDK_ROOT is set. The Android Gradle Plugin cannot locate an SDK."
    err "This runner needs Android SDK platform 35. See devops/ci/runner/README.md."
    exit 1
  fi
}

# ---- stage table -------------------------------------------------------------------------------
# One line per stage. The right-hand side is the command; all check logic lives inside it.
run_stage() {
  local stage="$1"
  local gw
  case "$stage" in

    # -- fast gates: bash + coreutils only, no JDK, no SDK, seconds ------------------------------
    gate-selftests)
      bash devops/ci/tests/gates/run_all.sh ;;
    gate-no-map-no-bearing)
      bash devops/ci/gates/no_map_no_bearing_gate.sh ;;
    gate-internal-escape)
      bash devops/ci/gates/internal_escape_gate.sh ;;
    gate-the-line)
      bash devops/ci/gates/the_line_gate.sh ;;
    gate-rssi-egress)
      bash devops/ci/gates/rssi_egress_gate.sh ;;

    # B13. Kotlin block comments NEST (unlike Java's): a literal `/*` inside a KDoc opens a nested
    # comment, the KDoc's `*/` closes only that one, and the outer comment swallows the file to EOF.
    # It is a gate rather than a review note because in the worst case the compiler reports a
    # DIFFERENT PROBLEM IN A DIFFERENT PLACE — in build.gradle.kts it silently ate the whole
    # `dependencies {}` block and Gradle reported a missing Hilt dependency, not a syntax error.
    # NOTE FOR TRIAGE: qa-test's scanner is a char-by-char depth state machine, not a regex, and its
    # documented gaps all fail toward FALSE POSITIVE — never toward hiding a real unclosed comment.
    # So if this goes red, treat it as REAL until proven otherwise; a false positive is a gate bug to
    # fix, never a reason to skip the stage. It also deliberately does NOT exclude test sources (the
    # live violation was in iosTest, the one source set nobody has ever compiled) — do not "tidy"
    # that exclusion in.
    gate-comment-nesting)
      bash devops/ci/gates/comment_nesting_gate.sh ;;
    gate-release-uuid-source)
      bash devops/ci/gates/release_uuid_gate.sh --skip-artifact ;;

    # -- battery: needs node, no JDK. Known-red, see registry above ------------------------------
    gate-battery)
      require_node "battery_gate.sh's threshold check (lib/battery_threshold_check.js)"
      bash devops/ci/gates/battery_gate.sh ;;

    # -- gradle stages ---------------------------------------------------------------------------
    # EVERY one of these touches an `:android` task, and every `:android` task pulls
    # generateDesignTokens via preBuild. Confirmed per-stage by task-graph inspection, not by
    # assuming preBuild covers everything — build-manifests in particular looks like it might not,
    # and does. `:shared`-only work does NOT need node for this reason.
    build-unit-test)
      require_android_toolchain
      require_node "generateDesignTokens (:android:testDebugUnitTest -> preBuild)"
      gw="$(gradle_wrapper)"
      ( cd mobile && "$gw" :shared:testDebugUnitTest :android:testDebugUnitTest --no-daemon ) ;;
    build-lint)
      require_android_toolchain
      require_node "generateDesignTokens (:android:lintDebug -> preBuild)"
      gw="$(gradle_wrapper)"
      ( cd mobile && "$gw" :android:lintDebug --no-daemon ) ;;
    build-assemble-debug)
      require_android_toolchain
      require_node "generateDesignTokens (:android:assembleDebug -> preBuild)"
      gw="$(gradle_wrapper)"
      ( cd mobile && "$gw" :android:assembleDebug --no-daemon ) ;;
    build-assemble-release)
      require_android_toolchain
      require_node "generateDesignTokens (:android:assembleRelease -> preBuild)"
      gw="$(gradle_wrapper)"
      ( cd mobile && "$gw" :android:assembleRelease --no-daemon ) ;;

    # MERGED MANIFESTS, BOTH VARIANTS, WITHOUT BUILDING EITHER APP.
    #
    # permission_gate.sh (decision 50, CLAIMS_REGISTER G2) reads
    # build/intermediates/packaged_manifests/{debug,release}/**/AndroidManifest.xml and needs BOTH
    # present in the SAME workspace. The obvious wiring — make the push path run assembleRelease —
    # is the wrong one: it puts R8 on every push to obtain a 4 KB XML file.
    #
    # These are AGP's manifest-merge-only tasks, and they are literally the producers: the output
    # directory is named after the task (`packaged_manifests/debug/processDebugManifestForPackage/`).
    # No Kotlin compile, no dex, no R8, no signing. MEASURED on this machine: 18 actionable tasks vs
    # 86 for assembleRelease.
    #
    # Kept as its OWN stage rather than folded into gate-permission so that a Gradle/AGP failure is
    # reported as a BUILD failure, not as a permission violation — same attribution discipline as
    # README §5b. A missing manifest must never be able to read as "the permission check passed".
    build-manifests)
      require_android_toolchain
      require_node "generateDesignTokens (:android:process*ManifestForPackage -> preBuild)"
      gw="$(gradle_wrapper)"
      ( cd mobile && "$gw" :android:processDebugManifestForPackage \
                           :android:processReleaseManifestForPackage --no-daemon ) ;;

    # DECISION 50 / CLAIMS_REGISTER G2. Backs PRIVACY_POLICY_DRAFT.md §3b bound 1 ("the app is
    # incapable of uploading anything — it requests no network permission") and register row A4.
    # The gate is not there to stop INTERNET landing; it is there so it cannot land SILENTLY.
    gate-permission)
      # Runner-side context only. The gate's own missing-variant message is already good and tells
      # you to build; what it cannot know is which CI STAGE produces its input. Printed BEFORE the
      # gate runs and never in place of it, so this can only ever add information, never a verdict.
      if [[ ! -d mobile/android/build/intermediates/packaged_manifests/debug ]] \
         || [[ ! -d mobile/android/build/intermediates/packaged_manifests/release ]]; then
        err "WIRING NOTE (runner): one or both merged manifests are absent from this workspace."
        err "In CI they are produced by the 'build-manifests' stage, which must run in the SAME JOB"
        err "immediately before this one. If that step is missing from the workflow, this is a"
        err "WIRING bug, not a permission violation. The gate's own message follows."
      fi
      bash devops/ci/gates/permission_gate.sh ;;

    # -- conformance: needs node (phase 1) + JDK/SDK (phase 2, authoritative) ---------------------
    gate-conformance)
      require_android_toolchain
      # NOTE the reason differs from the build-* stages above: this gate's Gradle work is
      # `:shared:testDebugUnitTest`, which does NOT pull generateDesignTokens. Node is needed here
      # for the gate's own phase-1 manifest pre-check.
      require_node "conformance_gate.sh phase 1 (lib/vectors_manifest_check.js)"
      # SELF-RETIRED TRIPWIRE, currently dormant. B12/HANDOFF-1: conformance_gate.sh used to invoke
      # `./gradlew.bat` directly, a Windows-only entrypoint, which made this stage unrunnable on a
      # POSIX runner — and would have failed with a useless "no such file" rather than the reason.
      # qa-test fixed it (qa_gradle_wrapper() in devops/ci/gates/lib/common.sh), the literal is gone
      # from that file, and this condition therefore stopped matching BY ITSELF. Nothing was cleaned
      # up; nothing needed to be.
      #
      # KEPT, not deleted: it re-arms automatically if a hardcoded .bat is ever reintroduced there.
      # Cost is one grep. DELIBERATELY SCOPED TO conformance_gate.sh ONLY — `lib/common.sh` contains
      # the literal legitimately, inside the OS-aware helper, and widening this grep to the whole
      # gates/ tree would make it fire forever on the correct implementation.
      if [[ "$(uname -s)" != MINGW* && "$(uname -s)" != MSYS* && "$(uname -s)" != CYGWIN* ]] \
         && grep -q 'gradlew\.bat' devops/ci/gates/conformance_gate.sh; then
        err "REGRESSION: conformance_gate.sh hardcodes ./gradlew.bat again and this runner is $(uname -s)."
        err "The gate cannot execute a Windows batch file here. This was B12; it was fixed by using"
        err "qa_gradle_wrapper() from devops/ci/gates/lib/common.sh. Use it again."
        err "Not silently skipping: an unrun conformance gate and a passing one must never look alike."
        exit 1
      fi
      bash devops/ci/gates/conformance_gate.sh ;;

    # -- release-only artifact scan. Requires build-assemble-release first, same job -------------
    gate-release-uuid-artifact)
      bash devops/ci/gates/release_uuid_gate.sh --skip-source ;;

    *)
      err "unknown stage: $stage"
      err "run '$0 --list' for the stage table."
      exit 2 ;;
  esac
}

STAGES="gate-selftests gate-no-map-no-bearing gate-internal-escape gate-the-line gate-rssi-egress
gate-comment-nesting gate-release-uuid-source gate-battery gate-conformance
build-unit-test build-lint build-assemble-debug build-assemble-release gate-release-uuid-artifact
build-manifests gate-permission"

if [[ $# -ne 1 ]] || [[ "$1" == "--list" || "$1" == "-h" || "$1" == "--help" ]]; then
  echo "usage: $0 <stage>"
  echo "stages:"
  for s in $STAGES; do
    if reason="$(known_red_reason "$s" 2>/dev/null)"; then
      echo "  $s   [KNOWN-RED on push/PR, BLOCKING on release] $reason"
    else
      echo "  $s"
    fi
  done
  [[ $# -ne 1 ]] && exit 2
  exit 0
fi

STAGE="$1"

log "stage=$STAGE  strict=$STRICT  os=$(uname -s)  root=$REPO_ROOT"

set +e
run_stage "$STAGE"
rc=$?
set -e

# ---- known-red handling ------------------------------------------------------------------------
if reason="$(known_red_reason "$STAGE" 2>/dev/null)" && [[ "$STRICT" != "1" ]]; then
  if [[ $rc -ne 0 ]]; then
    summary ""
    summary "### \`$STAGE\` — EXPECTED RED (not a build failure)"
    summary ""
    summary "This gate ran, failed, and **failing is currently the correct answer**."
    summary ""
    summary "> $reason"
    summary ""
    summary "Non-blocking on push/PR. **BLOCKING on release/tag builds** — see"
    summary "\`.github/workflows/release-gates.yml\` and \`devops/ci/runner/README.md\`."
    summary "We cannot ship while this is red. That is the point of it."
    summary ""
    log "KNOWN-RED: '$STAGE' failed as expected. Stage reports success on the push/PR path."
    exit 0
  fi
  summary ""
  summary "### \`$STAGE\` — UNEXPECTED PASS. Action required."
  summary ""
  summary "This gate is registered as known-red, and it just **passed**. The world changed."
  summary ""
  summary "> was: $reason"
  summary ""
  summary "Delete the \`$STAGE\` entry from \`known_red_reason()\` in"
  summary "\`devops/ci/runner/run-stage.sh\` so it becomes fully blocking, and close the blocker in"
  summary "\`.claude/memory/60-blockers.md\`. This job fails until you do, on purpose: a tolerance"
  summary "that outlives its cause silently absorbs the next real violation."
  summary ""
  err "KNOWN-RED registry is stale for '$STAGE' — it passes now. Remove the entry. See summary."
  exit 1
fi

exit $rc
