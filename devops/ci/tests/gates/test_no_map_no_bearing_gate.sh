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

# refute DESC FN ARGS...  — asserts FN ARGS... FAILS (non-zero exit). Separate from assert() because
# bash cannot pass the shell keyword `!` through a `"$@"` expansion.
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

# =====================================================================================================
# G3 REGRESSION FIXTURES — CLAIMS_REGISTER.md gap G3, widened scan roots (backend/.go/.sql/.proto,
# website/.ts/.tsx/.js/.jsx). Each case below drives the gate with --backend-dir / --website-dir
# pointed at a synthetic root, --source-dir pointed at an empty mobile dir so the (unchanged, already
# covered above) mobile scan contributes nothing to these assertions.
# =====================================================================================================
EMPTY_MOBILE="$(new_fixture "empty_mobile_for_g3")"

run_g3() {
  # args: backend_dir website_dir
  "$GATE" --source-dir "$EMPTY_MOBILE" --backend-dir "$1" --website-dir "$2" >/dev/null 2>&1
}

# ---- G3.1: geohash5 in a .sql file is ALLOWED (backend/CLAUDE.md requires it) ---------------------
d="$(new_fixture "g3_sql_geohash")"
cat > "$d/schema.sql" <<'EOF'
-- Safety invariant 1: NEVER store lat/lng. city = geohash5. see root CLAUDE.md.
CREATE TABLE encounters (
    id ULID PRIMARY KEY,
    city_geohash5 VARCHAR(5) NOT NULL,
    closest_band SMALLINT NOT NULL
);
EOF
assert "geohash5 column in a .sql file is NOT flagged" run_g3 "$d" "$FIXTURE/nowhere_website"

# ---- G3.2: latitude/longitude columns in a .sql file ARE flagged ----------------------------------
d="$(new_fixture "g3_sql_latlng")"
cat > "$d/schema.sql" <<'EOF'
CREATE TABLE encounters (
    id ULID PRIMARY KEY,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL
);
EOF
refute "latitude/longitude columns in a .sql file ARE flagged" run_g3 "$d" "$FIXTURE/nowhere_website"

# ---- G3.3: PATTERNS is not widened with a new bare 'lat'/'lng' term for the new roots (KNOWN GAP,
# same list as mobile — see gate header "Add the new roots and extensions", not new patterns).
# `lat_lng` (already in PATTERNS) still fires in a compound column name. ---------------------------
d="$(new_fixture "g3_sql_lat_lng_compound")"
cat > "$d/schema.sql" <<'EOF'
CREATE TABLE encounters ( id ULID PRIMARY KEY, lat_lng POINT NOT NULL );
EOF
refute "compound 'lat_lng' column in a .sql file IS flagged (same pattern list as mobile)" \
  run_g3 "$d" "$FIXTURE/nowhere_website"

# ---- G3.4: a SQL comment explaining the ban is NOT flagged (SQL '--' comments, not '//') -----------
d="$(new_fixture "g3_sql_comment")"
cat > "$d/schema.sql" <<'EOF'
-- We never store latitude or longitude here. bearing is also banned. see invariant 1.
CREATE TABLE encounters ( id ULID PRIMARY KEY, city_geohash5 VARCHAR(5) NOT NULL );
EOF
assert "REGRESSION: a SQL '--' comment explaining the ban is NOT flagged" \
  run_g3 "$d" "$FIXTURE/nowhere_website"

# ---- G3.5: a geohash column in Go source is ALLOWED, a lat/lng field IS flagged --------------------
d="$(new_fixture "g3_go_mixed")"
cat > "$d/model.go" <<'EOF'
package discovery

// CityGeohash5 is the only spatial field we persist. See root CLAUDE.md invariant 1.
type Encounter struct {
	CityGeohash5 string
}
EOF
assert "Go struct with only a geohash5 field is NOT flagged" run_g3 "$d" "$FIXTURE/nowhere_website"

d2="$(new_fixture "g3_go_latlng")"
cat > "$d2/model.go" <<'EOF'
package discovery

type Encounter struct {
	Latitude  float64
	Longitude float64
}
EOF
refute "Go struct with Latitude/Longitude fields IS flagged" run_g3 "$d2" "$FIXTURE/nowhere_website"

# ---- G3.6: a .proto file with bearing/lat/lng IS flagged, geohash5 is NOT --------------------------
d="$(new_fixture "g3_proto")"
cat > "$d/encounter.proto" <<'EOF'
syntax = "proto3";
package radius.discovery.v1;

message Encounter {
  string city_geohash5 = 1;
  int32 closest_band = 2;
}
EOF
assert "proto message with only geohash5/band fields is NOT flagged" run_g3 "$d" "$FIXTURE/nowhere_website"

d2="$(new_fixture "g3_proto_bearing")"
cat > "$d2/encounter.proto" <<'EOF'
syntax = "proto3";
message Encounter {
  double bearing = 1;
}
EOF
refute "proto message with a bearing field IS flagged" run_g3 "$d2" "$FIXTURE/nowhere_website"

# ---- G3.7: website/ (.tsx) is now in scope ----------------------------------------------------------
d="$(new_fixture "g3_website_map")"
cat > "$d/Map.tsx" <<'EOF'
import { GoogleMap } from "@react-google-maps/api";
export function Page() { return <GoogleMap />; }
EOF
refute "GoogleMap import in a website .tsx file IS flagged" run_g3 "$FIXTURE/nowhere_backend" "$d"

d2="$(new_fixture "g3_website_clean")"
cat > "$d2/Page.tsx" <<'EOF'
export function Page() { return <div>hello</div>; }
EOF
assert "clean website .tsx file is NOT flagged" run_g3 "$FIXTURE/nowhere_backend" "$d2"

# ---- G3.8: an absent backend/ or website/ root is skipped, not a hard failure ----------------------
assert "an absent backend/website root does not hard-fail the gate" \
  run_g3 "$FIXTURE/does_not_exist_backend" "$FIXTURE/does_not_exist_website"

echo
echo "test_no_map_no_bearing_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
