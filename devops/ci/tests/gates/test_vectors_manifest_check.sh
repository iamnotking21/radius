#!/usr/bin/env bash
# devops/ci/tests/gates/test_vectors_manifest_check.sh
#
# Self-test for devops/ci/gates/lib/vectors_manifest_check.js, against SYNTHETIC fixture vector
# files (never the live mobile/protocol/vectors/ — that directory's real, current state is checked
# separately, and as of 2026-08-04 it fails this check for a real reason; see conformance_gate.sh's
# header and devops/ci/README.md). Hard-coding "expect failure" against the live files here would
# make this test go stale the moment ble-protocol reconciles the manifest — testing the COUNTING
# LOGIC against known-good synthetic fixtures is the stable thing to assert.
#
# Run: bash devops/ci/tests/gates/test_vectors_manifest_check.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
CHECK="$REPO_ROOT/devops/ci/gates/lib/vectors_manifest_check.js"

command -v node >/dev/null 2>&1 || { echo "SKIP: node not on PATH"; exit 0; }

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

# ---- fixture A: consistent manifest -> PASS --------------------------------------------------------
A="$FIXTURE/consistent"
mkdir -p "$A"
cat > "$A/simple.json" <<'EOF'
{ "cases": [ {"name":"a"}, {"name":"b"}, {"name":"c"} ] }
EOF
cat > "$A/index.json" <<'EOF'
{ "files": [ {"file":"simple.json","cases":3} ], "case_total": 3 }
EOF
assert "consistent manifest passes" \
  bash -c "node '$CHECK' --dir '$A' --index '$A/index.json' >/dev/null 2>&1"

# ---- fixture B: file declares more cases than it has -> FAIL --------------------------------------
B="$FIXTURE/undercount"
mkdir -p "$B"
cat > "$B/simple.json" <<'EOF'
{ "cases": [ {"name":"a"}, {"name":"b"} ] }
EOF
cat > "$B/index.json" <<'EOF'
{ "files": [ {"file":"simple.json","cases":3} ], "case_total": 3 }
EOF
assert "declared-vs-actual mismatch is detected" \
  bash -c "! node '$CHECK' --dir '$B' --index '$B/index.json' >/dev/null 2>&1"

# ---- fixture C: per-file counts correct but case_total wrong -> FAIL -------------------------------
C="$FIXTURE/badtotal"
mkdir -p "$C"
cat > "$C/simple.json" <<'EOF'
{ "cases": [ {"name":"a"}, {"name":"b"} ] }
EOF
cat > "$C/index.json" <<'EOF'
{ "files": [ {"file":"simple.json","cases":2} ], "case_total": 99 }
EOF
assert "case_total mismatch is detected even when every per-file count is right" \
  bash -c "! node '$CHECK' --dir '$C' --index '$C/index.json' >/dev/null 2>&1"

# ---- fixture D: key_rotation.json's special multi-section counting rule ---------------------------
D="$FIXTURE/keyrotation"
mkdir -p "$D"
cat > "$D/key_rotation.json" <<'EOF'
{
  "cases": [ {"name":"a"}, {"name":"b"} ],
  "skew_window_across_seam": [ {"name":"c"} ],
  "self_eid_rejection": { "cases": [ {"name":"d"}, {"name":"e"} ] },
  "invalid": [ {"name":"f"} ],
  "accepted_not_rejected": [ {"name":"g"} ]
}
EOF
cat > "$D/index.json" <<'EOF'
{ "files": [ {"file":"key_rotation.json","cases":7} ], "case_total": 7 }
EOF
assert "key_rotation.json 5-section sum (2+1+2+1+1=7) is computed correctly" \
  bash -c "node '$CHECK' --dir '$D' --index '$D/index.json' >/dev/null 2>&1"

# ---- fixture D2: REGRESSION — property_assertions/destruction_at_seam are GENERIC, not special- --
# ---- cased to key_rotation.json. This is the actual bug: the earlier version of this script only --
# ---- special-cased key_rotation.json and treated every OTHER file as "cases array only", which --
# ---- silently missed property_assertions on key_schedule.json and display_jitter.json in the ----
# ---- real repo. Any file can carry any counted section; the rule must be file-name-agnostic. ----
D2="$FIXTURE/generic_sections"
mkdir -p "$D2"
cat > "$D2/some_other_file.json" <<'EOF'
{
  "cases": [ {"name":"a"} ],
  "property_assertions": [ {"name":"b"}, {"name":"c"} ],
  "destruction_at_seam": [ {"name":"d"} ]
}
EOF
cat > "$D2/index.json" <<'EOF'
{ "files": [ {"file":"some_other_file.json","cases":4} ], "case_total": 4 }
EOF
assert "REGRESSION: property_assertions + destruction_at_seam count on ANY file, not just key_rotation.json" \
  bash -c "node '$CHECK' --dir '$D2' --index '$D2/index.json' >/dev/null 2>&1"

# ---- fixture E: a file listed in index.json but missing on disk -> FAIL ---------------------------
E="$FIXTURE/missingfile"
mkdir -p "$E"
cat > "$E/index.json" <<'EOF'
{ "files": [ {"file":"does_not_exist.json","cases":3} ], "case_total": 3 }
EOF
assert "a manifested file that does not exist on disk is detected" \
  bash -c "! node '$CHECK' --dir '$E' --index '$E/index.json' >/dev/null 2>&1"

echo
echo "test_vectors_manifest_check.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
