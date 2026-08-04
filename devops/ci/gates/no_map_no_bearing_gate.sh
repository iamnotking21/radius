#!/usr/bin/env bash
# devops/ci/gates/no_map_no_bearing_gate.sh
#
# SAFETY INVARIANT 1 GATE (root CLAUDE.md): "NO map. NO bearing. NO lat/lng server-side."
# Invariant 3: radar node angle is RANDOM per session, never real direction.
# SPEC.md §1 non-goals: "location, coordinates, bearing, heading, altitude, velocity, a metric
# distance... If a future change would add any of these, it is not a change to this protocol — it
# is a different protocol, and it requires a new ADR that overturns safety invariants 1, 2 and 4."
#
# This gate exists because invariant 1 is stated as something reviewers must enforce by vigilance,
# and the whole point of a mechanical gate is that vigilance is exactly the thing that fails under
# deadline pressure. "Mechanical enforcement, not reviewer vigilance" — this is that mechanism.
#
# SCOPE: mobile/ source code ONLY (.kt, .kts, .swift). Applies to ALL of mobile/, including test
# source, because invariant 1 says "ANYWHERE" and does not carve out a debug/test exception the way
# decision 34 does for the provisional UUID. Excludes:
#   - this gate's own fixtures under devops/ci/tests/ and **/tests/ (qa-test's, may legitimately
#     reference the banned symbol names as strings when testing the gate itself)
#   - documentation (*.md, *.json) — mobile/protocol/SPEC.md and KEY_SCHEDULE.md are REQUIRED to
#     discuss bearing/lat/lng/altitude at length to explain why they are forbidden. A gate that
#     flagged its own spec for saying "no bearing" would be worse than useless.
#   - generated build output (**/build/**)
#
# WHAT COUNTS AS A VIOLATION, and why each pattern is here:
#   - Map framework imports/types: MapKit, MKMapView and friends (iOS); Google Maps / Mapbox / HERE
#     Android SDKs. There is no legitimate reason for any of these to appear — radar has no map view
#     by design (root CLAUDE.md BANNED list).
#   - Location-API usage: CLLocationManager/CLLocation/CLGeocoder (iOS), android.location.Location /
#     LocationManager / FusedLocationProviderClient / LocationRequest / Geocoder (Android). Note:
#     the ACCESS_FINE_LOCATION *permission string* is legitimately required pre-API31 for BLE scan
#     results to be delivered at all (documented in BleRadio.android.kt) — that is a manifest
#     permission declaration, not a location-API call, and this gate does not scan manifests or flag
#     permission constants for exactly that reason.
#   - Bare identifiers: latitude, longitude, LatLng, bearing, heading, altitude — SPEC.md §1's exact
#     non-goals list. Word-boundary-matched so "latency" and "headingFont"-style camelCase neighbours
#     do not false-positive.
#
# USAGE:
#   devops/ci/gates/no_map_no_bearing_gate.sh [--source-dir DIR]
#   --source-dir DIR   Override the scan root (defaults to <repo>/mobile). Used by the self-test
#                       harness against synthetic fixtures.
#
# EXIT CODE: 0 if clean. Non-zero, with every offending file:line printed, otherwise.

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

# Extended regex, applied per-file with grep -E (case-sensitive on purpose for the class names —
# "Heading" the iOS/Android type vs "heading" the English word are the same risk either way, so
# case sensitivity here is not a safety choice, just noise reduction: HeadingFont-style identifiers
# are far more common in camelCase Kotlin/Swift than genuine lowercase "heading" prose, and the
# word-boundary anyway protects camelCase compounds — see header).
#
# CALIBRATED AGAINST THE LIVE REPO, 2026-08-04 — two real false-positive classes found by actually
# running an earlier draft of this gate, both fixed here rather than left as theoretical caveats:
#
#   1. Bare `\bheading\b` collided with `androidx.compose.ui.semantics.heading()` — the Jetpack
#      Compose ACCESSIBILITY API that marks a UI element as a heading for TalkBack (mobile/CLAUDE.md
#      a11y requirements use exactly this pattern). It is used for real, legitimately, in
#      DiscoverScreen.kt/RadarScreen.kt/ThreadsScreen.kt. There is no reliable regex distinction
#      between "the a11y heading API" and "a compass-heading field" at the bare-word level, so the
#      bare word is DROPPED from the pattern list entirely. `\bCLHeading\b` (the actual iOS compass
#      type) stays — it has no legitimate homonym.
#   2. This codebase is heavily commented, and its own comments legitimately use the exact banned
#      words to EXPLAIN the invariant ("no bearing calculation", "Safety invariant 1: no latitude,
#      no longitude..."), plus the ordinary English idiom "load-bearing" (structurally important) —
#      hyphenated, so `\bbearing\b`'s word boundary matches it too. A prose mention cannot ship a
#      location API; only executable code can. So: (a) comments are stripped before scanning — see
#      strip_comments() below, a line-oriented heuristic tuned to this codebase's actual `//` and
#      `/** ... * ... */` style, not a full tokenizer — and (b) "load-bearing" is neutralised
#      separately as a targeted idiom exception, because it can appear on a code-adjacent comment
#      line the heuristic does not fully strip.
declare -a PATTERNS=(
  # -- map frameworks --
  '\bMKMapView\b' '\bMKMapItem\b' '\bMKCoordinateRegion\b' '\bMKAnnotation\b'
  'import[[:space:]]+MapKit'
  'import[[:space:]]+com\.google\.android\.gms\.maps'
  'import[[:space:]]+com\.google\.android\.libraries\.maps'
  'import[[:space:]]+com\.google\.maps\.android'
  'import[[:space:]]+com\.mapbox' '\bMapboxMap\b'
  'import[[:space:]]+com\.here\.android'
  '\bGoogleMap\b' '\bSupportMapFragment\b' '\bGMSMapView\b'
  # -- location / coordinate APIs --
  '\bCLLocationManager\b' '\bCLLocationCoordinate2D\b' '\bCLLocation\b' '\bCLGeocoder\b'
  '\bCLHeading\b' '\bCLLocationDegrees\b'
  '\bandroid\.location\.Location\b' '\bLocationManager\b' '\bFusedLocationProviderClient\b'
  '\bLocationRequest\b' '\brequestLocationUpdates\b' '\bgetLastLocation\b'
  '\bandroid\.location\.Geocoder\b'
  # -- bare identifiers, SPEC.md §1's exact non-goals list. NOT "heading" — see note above. --
  '\blatitude\b' '\blongitude\b' '\bLatLng\b' '\blat_lng\b'
  '\bbearing\b' '\baltitude\b'
)

# Build one alternation for a single grep pass per file (fast) rather than N passes.
JOINED="$(IFS='|'; echo "${PATTERNS[*]}")"

# Comment stripping is shared logic (qa_strip_comments, lib/common.sh) — several gates need it and
# a bugfix to the heuristic should apply everywhere at once. This wrapper adds the one thing that IS
# specific to this gate: neutralising the "load-bearing" idiom on whatever text remains, see the
# CALIBRATED note above.
strip_comments_and_idiom() {
  qa_strip_comments "$1" | sed -E -e 's#[Ll]oad-bearing#load_bearing#g'
}

found=0
while IFS= read -r -d '' file; do
  case "$file" in
    */tests/*|*Test.kt|*Test.swift|*/build/*) continue ;;
  esac
  if strip_comments_and_idiom "$file" | grep -nE "$JOINED" >/tmp/qa_map_hits.$$ 2>/dev/null; then
    found=1
    rel="${file#"$REPO_ROOT"/}"
    qa_fail "banned map/location/bearing API in $rel:"
    sed 's/^/    /' /tmp/qa_map_hits.$$ >&2
  fi
  rm -f /tmp/qa_map_hits.$$
done < <(find "$SCAN_ROOT" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.swift' \) -print0)

if [[ $found -eq 1 ]]; then
  qa_fail "NO-MAP-NO-BEARING GATE: FAILED. Safety invariant 1 is violated by the above."
  qa_fail "See root CLAUDE.md SAFETY INVARIANTS #1-3 and SPEC.md §1 non-goals. This is not a style"
  qa_fail "nit — a proposed change touching any of the above requires a new ADR overturning"
  qa_fail "invariants 1, 2 and 4, not a code review approval."
  exit 1
fi

qa_pass "NO-MAP-NO-BEARING GATE: PASSED. No map/location/bearing API in mobile source."
exit 0
