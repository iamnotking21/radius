#!/usr/bin/env bash
# devops/ci/tests/gates/test_no_map_no_bearing_gate.sh
#
# Self-test for devops/ci/gates/no_map_no_bearing_gate.sh, against synthetic fixtures. Encodes the
# two real false-positive classes found by running an earlier draft against the live repo (see that
# script's CALIBRATED comment) as regression cases, so they cannot silently come back.
#
# Run: bash devops/ci/tests/gates/test_no_map_no_bearing_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/no_map_no_bearing_gate.sh"

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

new_fixture() { mkdir -p "$FIXTURE/$1"; echo "$FIXTURE/$1"; }

# ---- clean, ordinary UI code -> PASS --------------------------------------------------------------
d="$(new_fixture "clean")"
cat > "$d/Screen.kt" <<'EOF'
package com.radius.android.ui
fun greeting() = "hi"
EOF
assert "clean UI code passes" bash -c "'$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- real violation: MapKit import -> FAIL --------------------------------------------------------
d="$(new_fixture "mapkit")"
cat > "$d/V.swift" <<'EOF'
import MapKit
struct X { var m: MKMapView }
EOF
assert "MapKit import is detected" bash -c "! '$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- real violation: Android location manager -> FAIL ---------------------------------------------
d="$(new_fixture "location")"
cat > "$d/L.kt" <<'EOF'
val lm = android.location.LocationManager
EOF
assert "LocationManager is detected" bash -c "! '$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- real violation: bare 'bearing' identifier in CODE -> FAIL ------------------------------------
d="$(new_fixture "bearing_code")"
cat > "$d/B.kt" <<'EOF'
val bearing = computeSomething()
EOF
assert "a real 'bearing' identifier in code is detected" bash -c "! '$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- REGRESSION 1: Compose a11y heading() must NOT be flagged (found in real repo scan) -----------
d="$(new_fixture "compose_heading")"
cat > "$d/S.kt" <<'EOF'
import androidx.compose.ui.semantics.heading
val m = Modifier.semantics { heading() }
EOF
assert "REGRESSION: androidx Compose a11y heading() is NOT flagged" \
  bash -c "'$GATE' --source-dir '$d' >/dev/null 2>&1"

# ---- REGRESSION 2: doc comments explaining the invariant must NOT be flagged -----------------------
d="$(new_fixture "docs_comment")"
cat > "$d/C.kt" <<'EOF'
package x
/**
 * Safety invariant 1: no latitude, no longitude, no bearing, anywhere, ever.
 * This design is load-bearing for the whole privacy model.
 */
class RadarModel
// no bearing calculation happens here either
EOF
assert "REGRESSION: comments explaining the ban (incl. 'load-bearing' idiom) are NOT flagged" \
  bash -c "'$GATE' --source-dir '$d' >/dev/null 2>&1"

echo
echo "test_no_map_no_bearing_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
