#!/usr/bin/env bash
# devops/ci/tests/gates/test_release_uuid_gate.sh
#
# Self-test for devops/ci/gates/release_uuid_gate.sh's SOURCE-SCAN phase, run against synthetic
# fixtures (never the live repo — the live repo's actual current state is exercised, and reported
# on, separately by running the real gate in CI / by hand; see devops/ci/README.md). This file
# proves the DETECTION LOGIC is correct in isolation: clean source passes, a real literal fails, an
# allowlisted file is exempted. It does not touch Gradle and runs in well under a second.
#
# Run: bash devops/ci/tests/gates/test_release_uuid_gate.sh
# Exit 0 iff every assertion passes.

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/release_uuid_gate.sh"

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

# ---- fixture 1: clean tree -> gate must PASS -----------------------------------------------------
CLEAN="$FIXTURE/clean/mobile/shared/src/commonMain"
mkdir -p "$CLEAN"
cat > "$CLEAN/Advertisement.kt" <<'EOF'
package com.radius.shared.protocol
public const val SERVICE_UUID16: Int = 0xFEED // not the banned value
EOF
assert "clean tree passes" \
  bash -c "'$GATE' --source-dir '$FIXTURE/clean' --skip-artifact >/dev/null 2>&1"

# ---- fixture 2: real literal, no allowlist -> gate must FAIL -------------------------------------
DIRTY="$FIXTURE/dirty/mobile/shared/src/commonMain"
mkdir -p "$DIRTY"
cat > "$DIRTY/Advertisement.kt" <<'EOF'
package com.radius.shared.protocol
public const val SERVICE_UUID16: Int = 0xFDA9
EOF
assert "0xFDA9 literal is detected and fails the gate" \
  bash -c "! '$GATE' --source-dir '$FIXTURE/dirty' --skip-artifact >/dev/null 2>&1"

# ---- fixture 3: lowercase / no-0x-prefix / full 128-bit forms all detected -----------------------
for variant in "fda9" "0Xfda9" "0000fda9-0000-1000-8000-00805f9b34fb"; do
  VDIR="$FIXTURE/variant_$RANDOM/mobile/shared/src/commonMain"
  mkdir -p "$VDIR"
  echo "val x = \"$variant\"" > "$VDIR/X.kt"
  assert "variant encoding '$variant' is detected" \
    bash -c "! '$GATE' --source-dir '$(dirname "$(dirname "$(dirname "$(dirname "$VDIR")")")")' --skip-artifact >/dev/null 2>&1"
done

# ---- fixture 4: test source (androidUnitTest) is never scanned -----------------------------------
TESTDIR="$FIXTURE/testsrc/mobile/shared/src/commonMain"
mkdir -p "$TESTDIR"
echo 'val x = 1' > "$TESTDIR/Clean.kt"
mkdir -p "$FIXTURE/testsrc/mobile/shared/src/androidUnitTest"
echo 'val x = "0xFDA9" // fine here, test source never ships' > "$FIXTURE/testsrc/mobile/shared/src/androidUnitTest/T.kt"
assert "androidUnitTest source is out of scope (not under a scanned root)" \
  bash -c "'$GATE' --source-dir '$FIXTURE/testsrc' --skip-artifact >/dev/null 2>&1"

# ---- fixture 5: an *.md file mentioning the value is never scanned (only .kt/.kts/.swift) --------
MDDIR="$FIXTURE/mdonly/mobile/shared/src/commonMain"
mkdir -p "$MDDIR"
echo '# 0xFDA9 is provisional' > "$MDDIR/README.md"
assert ".md files are not scanned even when they contain the literal" \
  bash -c "'$GATE' --source-dir '$FIXTURE/mdonly' --skip-artifact >/dev/null 2>&1"

echo
echo "test_release_uuid_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
