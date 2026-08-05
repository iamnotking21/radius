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
# SCOPE, WIDENED 2026-08-05 (CLAIMS_REGISTER.md gap G3): mobile/ (.kt, .kts, .swift) — UNCHANGED
# behaviour, see below — PLUS backend/ (.go, .sql, .proto) and website/ (.ts, .tsx, .js, .jsx).
#
# WHY THE WIDENING, AND WHY IT WAS SAFE TO DO TODAY: this gate previously set SCAN_ROOT to
# `mobile/` and matched only `.kt`/`.kts`/`.swift`. It could not see `backend/`, `website/`, or any
# `.go`/`.sql`/`.proto` file — which means it checked the one directory where invariant 1 was never
# actually at risk (mobile displays a band, never a coordinate, by construction — see PATTERNS
# below) and NONE of the directories where a coordinate could actually get durably stored: root
# CLAUDE.md SAFETY INVARIANT 1 is "NO map. NO bearing. NO lat/lng SERVER-SIDE", and
# `backend/CLAUDE.md` states the same rule in backend-owned language: "NEVER store lat/lng. city =
# geohash5. encounters store BAND only." `backend/` contains no source yet (CLAIMS_REGISTER row B1:
# "First `.sql` or `.proto` lands" is the falsification trigger) and `website/` contains none either
# — so widening costs nothing today and is in place before the first line lands, rather than being
# added retroactively after a coordinate column has already shipped.
#
# THE MOBILE BEHAVIOUR ITSELF IS UNCHANGED: the original `find` call against `mobile/` with its
# original extension list and exclusions runs exactly as it did before. The new roots are additional
# `find` passes through the SAME detection/exclusion pipeline (`strip_comments_and_idiom` +
# `PATTERNS`), not a parallel implementation, so a bugfix to either applies everywhere at once — the
# same reasoning `qa_strip_comments`'s header gives for being centralised in lib/common.sh.
#
# GEOHASH IS ALLOWED. LAT/LNG IS NOT. THAT DISTINCTION IS LOAD-BEARING FOR THE NEW ROOTS, NOT AN
# AFTERTHOUGHT: `backend/CLAUDE.md` requires `geohash5` to exist in backend source ("city =
# geohash5"), and PostGIS/geohash column and function names routinely contain the substrings this
# gate would otherwise be tempted to ban wholesale (`geo`, `geography`, `geom`). PATTERNS below does
# NOT add any bare `geo`/`coordinate`/`location`-shaped term for exactly that reason — every pattern
# added for the new roots is the SAME identifier list already used for mobile (`latitude`,
# `longitude`, `LatLng`, `lat_lng`, `bearing`, `altitude`, plus the map/location-API patterns, which
# will simply never match Go/SQL/TS and cost nothing there). `geohash5`, `geohash_5`, `ST_GeoHash`,
# `city_geohash` etc. do not contain `latitude`/`longitude`/`lat_lng`/`bearing`/`altitude` as
# substrings and are UNAFFECTED. Regression-pinned in the self-test — see
# `test_no_map_no_bearing_gate.sh` "geohash5 in SQL is NOT flagged" / "Go geohash column is NOT
# flagged" alongside "lat/lng column in SQL / Go IS flagged".
#
# SQL COMMENT SYNTAX: `.sql` files use `--` line comments, not `//` — `qa_strip_comments` (tuned to
# Kotlin/Swift/Go, which all share C-family `//` and `/* */`) does not strip those, so `.sql` files
# use the dedicated `qa_strip_sql_comments` (lib/common.sh) instead, same contract, same reason
# comments are stripped for the mobile scan: a PostGIS column comment or `-- no lat/lng here, see
# invariant 1` must not self-trigger the gate that documents the rule it enforces. `.go` and
# `.proto` share Kotlin/Swift's `//` and `/* */` syntax, so they reuse `qa_strip_comments` unchanged.
#
# EXCLUDES (all roots):
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
#   devops/ci/gates/no_map_no_bearing_gate.sh [--source-dir DIR] [--backend-dir DIR] [--website-dir DIR]
#   --source-dir DIR    Override the MOBILE scan root (defaults to <repo>/mobile). Original flag,
#                        UNCHANGED meaning — existing self-tests and any external caller keep working.
#   --backend-dir DIR   Override the BACKEND scan root (defaults to <repo>/backend). Self-test only.
#   --website-dir DIR   Override the WEBSITE scan root (defaults to <repo>/website). Self-test only.
#
# A root that does not exist is SKIPPED, not a hard failure — unlike the original mobile-only
# behaviour (mobile/ is required to exist; a missing repo checkout is a real error). `backend/` and
# `website/` legitimately not existing yet is exactly today's live state (CLAIMS_REGISTER.md: "First
# `.sql` or `.proto` lands" / row B1 has not happened), and a gate that hard-failed on an owner's
# empty directory would train people to ignore it long before there is anything to check.
#
# EXIT CODE: 0 if clean. Non-zero, with every offending file:line printed, otherwise.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

REPO_ROOT="$(qa_repo_root)"
SCAN_ROOT="$REPO_ROOT/mobile"
BACKEND_ROOT="$REPO_ROOT/backend"
WEBSITE_ROOT="$REPO_ROOT/website"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir) SCAN_ROOT="$2"; shift 2 ;;
    --backend-dir) BACKEND_ROOT="$2"; shift 2 ;;
    --website-dir) WEBSITE_ROOT="$2"; shift 2 ;;
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
#   3. CASE-INSENSITIVE AS OF THE G3 WIDENING, found the same way #1 and #2 were — by running the
#      widened self-test against a realistic fixture, not by inspection. The original bare-identifier
#      patterns (`\blatitude\b` etc.) were matched CASE-SENSITIVELY, which is invisible in Kotlin/
#      Swift because both languages' naming convention puts a property named after these words in
#      lowerCamelCase (`val latitude: Double`) — but Go's convention for an EXPORTED struct field is
#      UpperCamelCase (`Latitude float64`), and a case-sensitive `\blatitude\b` never matches
#      `Latitude`. A first draft of the widened gate shipped exactly that gap; the self-test fixture
#      `test_no_map_no_bearing_gate.sh` "Go struct with Latitude/Longitude fields IS flagged" caught
#      it failing to flag before this fix landed. Verified safe to flip globally (not just for the
#      new roots) by re-running the full pattern set, case-insensitively, with comments stripped,
#      against the live `mobile/` tree first: zero matches — every case-varying hit that exists today
#      is inside a comment, which is stripped before matching either way (see the mobile CALIBRATED
#      note #2 above), so mobile's effective behaviour is unchanged even though the flag is now on
#      unconditionally, and this class of miss can no longer resurface in ANY of the four languages
#      this gate reads.
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

# SQL variant, added for the G3 widening — `.sql` uses `--` line comments, not `//`
# (`qa_strip_sql_comments`, lib/common.sh), everything else about the idiom neutralisation is
# identical.
strip_sql_comments_and_idiom() {
  qa_strip_sql_comments "$1" | sed -E -e 's#[Ll]oad-bearing#load_bearing#g'
}

found=0

# scan_root ROOT STRIP_FN NAME_EXPR...  — one root, one comment-stripper, one or more `find -name`
# expressions. Shared by all three roots so a bugfix applies everywhere at once (see header).
scan_root() {
  local root="$1" strip_fn="$2"
  shift 2
  local -a name_exprs=("$@")
  local -a find_args=()
  local first=1
  for expr in "${name_exprs[@]}"; do
    if [[ $first -eq 1 ]]; then first=0; else find_args+=(-o); fi
    find_args+=(-name "$expr")
  done

  while IFS= read -r -d '' file; do
    case "$file" in
      */tests/*|*Test.kt|*Test.swift|*/build/*) continue ;;
    esac
    if "$strip_fn" "$file" | grep -inE "$JOINED" >/tmp/qa_map_hits.$$ 2>/dev/null; then
      found=1
      rel="${file#"$REPO_ROOT"/}"
      qa_fail "banned map/location/bearing API in $rel:"
      sed 's/^/    /' /tmp/qa_map_hits.$$ >&2
    fi
    rm -f /tmp/qa_map_hits.$$
  done < <(find "$root" -type f \( "${find_args[@]}" \) -print0)
}

# -- mobile: UNCHANGED extensions, UNCHANGED stripper, UNCHANGED exclusions (see header) -----------
scan_root "$SCAN_ROOT" strip_comments_and_idiom '*.kt' '*.kts' '*.swift'

# -- backend: NEW. .go/.proto share Kotlin/Swift comment syntax; .sql needs the SQL-aware stripper -
[[ -d "$BACKEND_ROOT" ]] && {
  scan_root "$BACKEND_ROOT" strip_comments_and_idiom '*.go' '*.proto'
  scan_root "$BACKEND_ROOT" strip_sql_comments_and_idiom '*.sql'
}

# -- website: NEW. TypeScript/JavaScript share Kotlin/Swift comment syntax -------------------------
[[ -d "$WEBSITE_ROOT" ]] && scan_root "$WEBSITE_ROOT" strip_comments_and_idiom '*.ts' '*.tsx' '*.js' '*.jsx'

if [[ $found -eq 1 ]]; then
  qa_fail "NO-MAP-NO-BEARING GATE: FAILED. Safety invariant 1 is violated by the above."
  qa_fail "See root CLAUDE.md SAFETY INVARIANTS #1-3 and SPEC.md §1 non-goals. This is not a style"
  qa_fail "nit — a proposed change touching any of the above requires a new ADR overturning"
  qa_fail "invariants 1, 2 and 4, not a code review approval."
  exit 1
fi

qa_pass "NO-MAP-NO-BEARING GATE: PASSED. No map/location/bearing API in mobile, backend or website source."
exit 0
