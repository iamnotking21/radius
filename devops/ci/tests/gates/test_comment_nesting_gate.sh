#!/usr/bin/env bash
# devops/ci/tests/gates/test_comment_nesting_gate.sh
#
# Self-test for devops/ci/gates/comment_nesting_gate.sh (and its lib/comment_depth_scan.awk engine),
# against synthetic fixtures — never the live repo, per this directory's own design principle #5
# (devops/ci/README.md): the live repo's state is volatile (this exact gate landed while the file it
# was written about was being fixed underneath it, mid-session — see 20-state.md) and a self-test
# that hardcodes "expect the live repo to currently fail this way" goes stale the moment someone
# lands the fix. The counting/detection LOGIC is what gets pinned here.
#
# Encodes, as regression fixtures:
#   - the real bug, reproduced faithfully from the actual pre-fix content of
#     mobile/shared/src/iosTest/kotlin/com/radius/shared/ble/IosRadioContractTest.kt (hit 4), so
#     this test would have caught the live violation, not just a toy example of the same shape.
#   - hit 3's shape specifically: the SAME trap inside a `.kts` build script, because `.kts` is
#     explicitly in scope (root CLAUDE.md HANDOFF) and is where the failure is most confusing
#     (a silently-emptied `dependencies {}` block reads as a missing-library bug, not a comment bug).
#   - the four false-positive classes the HANDOFF specifically requires: a legitimate `/**` opener,
#     a legitimate `*/` closer, a string literal containing `/*`, and a `//` line comment containing
#     `/*`. All four must pass, or this gate will get disabled by the first engineer it blocks — see
#     no_map_no_bearing_gate.sh's own first-run history (androidx heading() / "load-bearing") for why
#     that is not a hypothetical.
#   - a comment that legitimately NESTS AND CLOSES — nesting itself is valid Kotlin; only an
#     UNBALANCED nest is the bug.
#
# Run: bash devops/ci/tests/gates/test_comment_nesting_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/comment_nesting_gate.sh"
AWK_PROG="$REPO_ROOT/devops/ci/gates/lib/comment_depth_scan.awk"

FIXTURE="$(mktemp -d)"
trap 'rm -rf "$FIXTURE"' EXIT

pass_count=0
fail_count=0
assert() {
  local desc="$1"; shift
  if "$@"; then
    echo "  ok  - $desc"
    pass_count=$((pass_count + 1))
  else
    echo "  FAIL - $desc"
    fail_count=$((fail_count + 1))
  fi
}

# refute DESC FN ARGS...  — asserts FN ARGS... FAILS (non-zero exit).
refute() {
  local desc="$1"; shift
  if ! "$@"; then
    echo "  ok  - $desc"
    pass_count=$((pass_count + 1))
  else
    echo "  FAIL - $desc"
    fail_count=$((fail_count + 1))
  fi
}

new_fixture() { mkdir -p "$FIXTURE/$1"; echo "$FIXTURE/$1"; }

EMPTY_BACKEND="$(new_fixture "empty_backend")"
EMPTY_DEVOPS="$(new_fixture "empty_devops")"

run_mobile_only() {
  # args: mobile_dir
  "$GATE" --mobile-dir "$1" --backend-dir "$EMPTY_BACKEND" --devops-dir "$EMPTY_DEVOPS" >/dev/null 2>&1
}

# ---- ordinary clean code, no comments at all -> PASS ------------------------------------------
d="$(new_fixture "clean")"
cat > "$d/Screen.kt" <<'EOF'
package com.radius.android.ui
fun greeting() = "hi"
EOF
assert "clean code with no comments passes" run_mobile_only "$d"

# ---- REGRESSION 1 (required): legitimate /** opener + legitimate */ closer -> PASS -------------
d="$(new_fixture "clean_kdoc")"
cat > "$d/Doc.kt" <<'EOF'
package x

/**
 * An ordinary KDoc. Opens once, closes once, says nothing dangerous.
 */
class Documented
EOF
assert "REGRESSION: legitimate /** opener and */ closer are NOT flagged" run_mobile_only "$d"

# ---- REGRESSION 2 (required): string literal containing /* -> PASS -----------------------------
d="$(new_fixture "string_glob")"
cat > "$d/Glob.kt" <<'EOF'
package x
val vectorGlob = "mobile/protocol/vectors/*.json"
class Glob
EOF
assert "REGRESSION: a string literal containing /* is NOT flagged" run_mobile_only "$d"

# ---- REGRESSION 3 (required): // line comment containing /* -> PASS ----------------------------
d="$(new_fixture "line_comment_glob")"
cat > "$d/Note.kt" <<'EOF'
package x
// see mobile/protocol/vectors/*.json for the vector files
class Note
EOF
assert "REGRESSION: a // line comment containing /* is NOT flagged" run_mobile_only "$d"

# ---- legitimate nested-AND-closed block comment -> PASS (nesting is legal, only imbalance isn't)
d="$(new_fixture "nested_closed")"
cat > "$d/Nested.kt" <<'EOF'
package x
/* outer /* inner */ still outer */
class NestedButClosed
EOF
assert "a comment that nests AND closes is NOT flagged" run_mobile_only "$d"

# =====================================================================================================
# THE REAL BUG — reproduced from the actual pre-fix content of
# mobile/shared/src/iosTest/kotlin/com/radius/shared/ble/IosRadioContractTest.kt (hit 4), so this
# pins the exact shape that bit the project, not a simplified stand-in for it.
# =====================================================================================================
d="$(new_fixture "hit4_iostest")"
cat > "$d/IosRadioContractTest.kt" <<'EOF'
package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * iOS-side placeholder.
 *
 * !! NEVER COMPILED, NEVER RUN !! Kotlin/Native iOS targets build on macOS only (blocker B4).
 *
 * There is deliberately almost nothing here. A simulator cannot do BLE.
 *
 * What SHOULD eventually live in this source set: the shared-core conformance-vector run
 * (mobile/protocol/vectors/*.json) executed on the Kotlin/Native target, proving the iOS build of
 * the codec is byte-identical to the JVM build. That is the regression net ADR-007 keeps.
 */
class IosRadioContractTest {

    @Test
    fun placeholder_until_conformance_vectors_exist() {
        assertTrue(true, "replace with the conformance vector run")
    }
}
EOF
refute "REAL BUG (hit 4 shape): unbroken glob '/*.json' inside a KDoc swallows the rest of the file" \
  run_mobile_only "$d"
# The diagnostic must point at line 6 (the outer /** — the one to fix), not merely fail silently.
assert "diagnostic names line 6 (the outer /** that stays open), not just the /* that nested inside it" \
  bash -c "'$GATE' --mobile-dir '$d' --backend-dir '$EMPTY_BACKEND' --devops-dir '$EMPTY_DEVOPS' 2>&1 | grep -q '^    6:1:'"

# ---- the SAME file with the actual landed fix (backtick-split glob) -> PASS --------------------
d="$(new_fixture "hit4_fixed")"
cat > "$d/IosRadioContractTest.kt" <<'EOF'
package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * iOS-side placeholder.
 *
 * What SHOULD eventually live in this source set: the shared-core conformance-vector run
 * (`mobile/protocol/vectors/` `*.json`) executed on the Kotlin/Native target, proving the iOS build
 * of the codec is byte-identical to the JVM build. That is the regression net ADR-007 keeps.
 */
class IosRadioContractTest {

    @Test
    fun placeholder_until_conformance_vectors_exist() {
        assertTrue(true, "replace with the conformance vector run")
    }
}
EOF
assert "the landed fix (glob split by backticks) is NOT flagged" run_mobile_only "$d"

# =====================================================================================================
# HIT 3's SHAPE, SPECIFICALLY: the same trap inside a .kts build script — the one that read as a
# missing Hilt dependency, not a comment bug, because Gradle reported dependencies{} as genuinely
# absent. .kts is explicitly in scope (root CLAUDE.md HANDOFF); this pins detection in that
# extension specifically, not just .kt.
# =====================================================================================================
d="$(new_fixture "hit3_kts")"
cat > "$d/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
}

/**
 * design-system's generate.mjs used to emit a KDoc containing signal/* which nests and swallows
 * everything after it, including this file's own preBuild hook and dependencies block.
 */
android {
    namespace = "com.radius.android"
}

dependencies {
    implementation("com.google.dagger:hilt-android:2.51")
}
EOF
refute "REGRESSION (hit 3 shape): unbroken '/*' in a build.gradle.kts KDoc swallows dependencies{}" \
  run_mobile_only "$d"

# a CLEAN .kts (no violation) still passes, proving the extension itself is scanned, not just flagged
d="$(new_fixture "clean_kts")"
cat > "$d/settings.gradle.kts" <<'EOF'
// ordinary settings script, nothing dangerous here
rootProject.name = "radius"
include(":android", ":shared")
EOF
assert "a clean .kts file passes" run_mobile_only "$d"

# =====================================================================================================
# ROOT PLUMBING: --mobile-dir / --backend-dir / --devops-dir, and an absent root is skipped, not a
# hard failure (same reasoning as no_map_no_bearing_gate.sh's backend/website roots).
# =====================================================================================================
d_mobile="$(new_fixture "roots_mobile_clean")"
cat > "$d_mobile/A.kt" <<'EOF'
package x
class A
EOF
d_backend="$(new_fixture "roots_backend_broken")"
cat > "$d_backend/model.go.kt" <<'EOF'
/**
 * not actually go, but this repo's backend/ has no .kt today — synthetic /*
 */
class NeverReal
EOF
assert "clean mobile root + absent backend/devops roots: PASS" \
  bash -c "'$GATE' --mobile-dir '$d_mobile' --backend-dir '$FIXTURE/does_not_exist_backend' --devops-dir '$FIXTURE/does_not_exist_devops' >/dev/null 2>&1"
refute "a violation in --backend-dir is caught even though backend/ has no real .kt today" \
  bash -c "'$GATE' --mobile-dir '$d_mobile' --backend-dir '$d_backend' --devops-dir '$FIXTURE/does_not_exist_devops' >/dev/null 2>&1"

# =====================================================================================================
# **/build/** exclusion — generated/copied output is out of scope (hand-written source is the target)
# =====================================================================================================
d="$(new_fixture "build_output_excluded")"
mkdir -p "$d/build/generated"
cat > "$d/build/generated/Generated.kt" <<'EOF'
/**
 * this would be flagged anywhere else: /*
 */
class Generated
EOF
assert "a violation under **/build/** is excluded (generated output, not hand-written source)" \
  run_mobile_only "$d"

# =====================================================================================================
# THE AWK ENGINE DIRECTLY — exit-code contract, exercised standalone in case a future refactor of
# comment_nesting_gate.sh's shell wrapper stops calling it the same way.
# =====================================================================================================
f="$FIXTURE/direct_awk_clean.kt"
printf 'package x\nclass X\n' > "$f"
assert "comment_depth_scan.awk exits 0 on a clean file" bash -c "awk -f '$AWK_PROG' -- '$f' >/dev/null 2>&1"

f="$FIXTURE/direct_awk_broken.kt"
printf '/**\n * see vectors/*.json\n */\nclass X\n' > "$f"
refute "comment_depth_scan.awk exits 1 on an unclosed nest" bash -c "awk -f '$AWK_PROG' -- '$f' >/dev/null 2>&1"

echo
echo "test_comment_nesting_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
