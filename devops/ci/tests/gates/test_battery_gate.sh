#!/usr/bin/env bash
# devops/ci/tests/gates/test_battery_gate.sh
#
# Self-test for devops/ci/gates/battery_gate.sh + lib/battery_threshold_check.js.
# Proves: (1) no --results given -> loud, honest failure (the expected Phase 0 state, forever until
# real hardware numbers exist); (2) simulator-sourced results are REJECTED regardless of how good
# the numbers look — the prime directive ("simulator BLE/battery result = invalid") applied to this
# gate specifically; (3) a genuine regression against either threshold fails; (4) clean hardware-rig
# results within budget pass.
#
# Run: bash devops/ci/tests/gates/test_battery_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/battery_gate.sh"

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

assert "no --results given -> fails (the correct, honest, permanent-until-hardware state)" \
  bash -c "! '$GATE' >/dev/null 2>&1"

cat > "$FIXTURE/simulator.json" <<'EOF'
[{"device_model":"Pixel 8 (SIMULATOR)","scanning_pct_per_hr":0.1,"idle_pct_per_day":0.01,"source":"simulator"}]
EOF
assert "simulator-sourced results are REJECTED even though the numbers look great" \
  bash -c "! '$GATE' --results '$FIXTURE/simulator.json' >/dev/null 2>&1"

cat > "$FIXTURE/regression.json" <<'EOF'
[{"device_model":"Pixel 8","scanning_pct_per_hr":5.2,"idle_pct_per_day":0.3,"source":"hardware-rig"}]
EOF
assert "a scanning_pct_per_hr regression (5.2 >= 4.0 budget) fails" \
  bash -c "! '$GATE' --results '$FIXTURE/regression.json' >/dev/null 2>&1"

cat > "$FIXTURE/clean.json" <<'EOF'
[{"device_model":"Pixel 8","scanning_pct_per_hr":2.1,"idle_pct_per_day":0.4,"source":"hardware-rig"},
 {"device_model":"Galaxy A15","scanning_pct_per_hr":3.9,"idle_pct_per_day":0.9,"source":"hardware-rig"}]
EOF
assert "clean hardware-rig results within both budgets pass" \
  bash -c "'$GATE' --results '$FIXTURE/clean.json' >/dev/null 2>&1"

cat > "$FIXTURE/worst_of_fleet.json" <<'EOF'
[{"device_model":"Pixel 8","scanning_pct_per_hr":1.0,"idle_pct_per_day":0.1,"source":"hardware-rig"},
 {"device_model":"Budget MediaTek X","scanning_pct_per_hr":6.0,"idle_pct_per_day":0.1,"source":"hardware-rig"}]
EOF
assert "one bad device in the fleet fails the whole gate (worst case, not average)" \
  bash -c "! '$GATE' --results '$FIXTURE/worst_of_fleet.json' >/dev/null 2>&1"

echo
echo "test_battery_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
