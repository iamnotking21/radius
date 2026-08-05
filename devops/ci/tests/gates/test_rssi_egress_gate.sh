#!/usr/bin/env bash
# devops/ci/tests/gates/test_rssi_egress_gate.sh
#
# Self-test for devops/ci/gates/rssi_egress_gate.sh, against synthetic fixtures. Each fixture is a
# full `mobile/` tree rooted at a temp dir, because the gate resolves its own scan roots
# (`mobile/shared/src/commonMain`, every `mobile/**/src/main`) from `--source-dir`, same pattern as
# `test_no_map_no_bearing_gate.sh`.
#
# Run: bash devops/ci/tests/gates/test_rssi_egress_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/rssi_egress_gate.sh"

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

# refute DESC FN ARGS...  — asserts FN ARGS... FAILS (non-zero exit). A separate function because
# bash cannot pass the shell keyword `!` through a `"$@"` expansion inside assert() above.
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

# new_case NAME -> prints the fixture's "mobile/" root path, freshly made with commonMain +
# android/src/main scaffolding so every case can drop a file straight in.
new_case() {
  local base="$FIXTURE/$1/mobile"
  mkdir -p "$base/shared/src/commonMain/kotlin/x"
  mkdir -p "$base/android/src/main/kotlin/x"
  mkdir -p "$base/android/src/debug/kotlin/x"
  echo "$base"
}

run_gate() {
  local mobile_root="$1"
  "$GATE" --source-dir "$(dirname "$mobile_root")" >/tmp/qa_rssi_test_out.$$ 2>&1
  local rc=$?
  cat /tmp/qa_rssi_test_out.$$
  rm -f /tmp/qa_rssi_test_out.$$
  return $rc
}

# ---- 1: the real redaction pattern (RawSighting.toString-shaped) -> PASS -------------------------
m="$(new_case case1_clean_redaction)"
cat > "$m/shared/src/commonMain/kotlin/x/S.kt" <<'EOF'
package x
class RawSighting(val payload: ByteArray, val rssiDbm: Int, val at: Long) {
    override fun toString(): String =
        "RawSighting(payload=<${payload.size}B redacted>, rssiDbm=<redacted>, at=$at)"
}
EOF
assert "redacted toString (literal text 'rssiDbm=<redacted>', no real interpolation) passes" \
  run_gate "$m"

# ---- 2: Log.d with a direct rssi field reference -> FAIL ------------------------------------------
m="$(new_case case2_log_direct)"
cat > "$m/android/src/main/kotlin/x/L.kt" <<'EOF'
package x
fun onSighting(s: Any) {
    android.util.Log.d("TAG", s.rssiDbm.toString())
}
EOF
refute "Log.d(...) carrying a direct rssiDbm reference is detected" run_gate "$m"

# ---- 3: println with rssi identifier -> FAIL -------------------------------------------------------
m="$(new_case case3_println)"
cat > "$m/shared/src/commonMain/kotlin/x/P.kt" <<'EOF'
package x
fun debug(rssiDbm: Int) {
    println(rssiDbm)
}
EOF
refute "println(rssiDbm) is detected" run_gate "$m"

# ---- 4: brace interpolation outside any log call -> FAIL -------------------------------------------
m="$(new_case case4_brace_interp)"
cat > "$m/shared/src/commonMain/kotlin/x/M.kt" <<'EOF'
package x
fun breadcrumb(s: Any): String = "peer seen at ${s.rssiDbm}"
EOF
refute "brace interpolation of an rssi field (no log call) is detected" run_gate "$m"

# ---- 5: bare interpolation -> FAIL -------------------------------------------------------------
m="$(new_case case5_bare_interp)"
cat > "$m/shared/src/commonMain/kotlin/x/N.kt" <<'EOF'
package x
fun breadcrumb(rssiDbm: Int): String = "debug: $rssiDbm"
EOF
refute "bare \$rssiDbm interpolation is detected" run_gate "$m"

# ---- 6: Swift interpolation -> FAIL -------------------------------------------------------------
m="$(new_case case6_swift_interp)"
mkdir -p "$m/ios/Sources/BLE"
cat > "$m/ios/Sources/BLE/X.swift" <<'EOF'
struct S { let rssi: Int }
func breadcrumb(s: S) -> String { "peer rssi \(s.rssi)" }
EOF
# NOTE: mobile/ios/Sources is NOT under mobile/**/src/main and is therefore out of THIS gate's
# scope by design (see the gate's header) — verifying it is a no-op today, and re-asserting the
# scope decision explicitly rather than leaving it silently untested.
assert "mobile/ios/Sources is out of scope (documented gap, not src/main) -> passes" run_gate "$m"

# ---- 6b: same Swift interpolation, but placed under a src/main-shaped path -> FAIL -----------------
m="$(new_case case6b_swift_interp_in_scope)"
mkdir -p "$m/ios/src/main"
cat > "$m/ios/src/main/X.swift" <<'EOF'
struct S { let rssi: Int }
func breadcrumb(s: S) -> String { "peer rssi \(s.rssi)" }
EOF
refute "Swift interpolation IS detected when the file is under a src/main-shaped path" run_gate "$m"

# ---- 7: string concatenation form -> FAIL -----------------------------------------------------
m="$(new_case case7_concat)"
cat > "$m/android/src/main/kotlin/x/C.kt" <<'EOF'
package x
fun breadcrumb(rssiDbm: Int): String {
    return "rssi=" + rssiDbm
}
EOF
refute "string concatenation with an rssi identifier is detected" run_gate "$m"

# ---- 8: .sq file with an rssi-shaped column -> FAIL ------------------------------------------------
m="$(new_case case8_sq)"
mkdir -p "$m/shared/src/commonMain/sqldelight/x"
cat > "$m/shared/src/commonMain/sqldelight/x/Sighting.sq" <<'EOF'
CREATE TABLE sighting (
    id TEXT NOT NULL PRIMARY KEY,
    rssi_dbm INTEGER NOT NULL
);
EOF
refute "rssi_dbm column in a .sq file is detected" run_gate "$m"

# ---- 9: @Serializable file carrying an rssi-shaped identifier -> FAIL ------------------------------
m="$(new_case case9_serializable)"
cat > "$m/shared/src/commonMain/kotlin/x/Msg.kt" <<'EOF'
package x
import kotlinx.serialization.Serializable

@Serializable
data class SightingReport(val rssiDbm: Int, val eid: String)
EOF
refute "@Serializable class carrying an rssiDbm field is detected" run_gate "$m"

# ---- REGRESSION 1: txPowerCalDbm (contains 'dbm' but NOT 'rssi') must NOT be flagged, even in a
# log call -- it is a real, deliberately-transmitted protocol field (safety invariant 4's txpower
# byte), not RSSI. See the gate's "WHAT RSSI-SHAPED IDENTIFIER MEANS" note. --------------------------
m="$(new_case case10_txpower_not_rssi)"
cat > "$m/shared/src/commonMain/kotlin/x/Tx.kt" <<'EOF'
package x
class Frame(val txPowerCalDbm: Int) {
    override fun toString(): String = "Frame(txCal=$txPowerCalDbm)"
}
fun debugLog(f: Frame) {
    android.util.Log.d("TAG", "txCal=${f.txPowerCalDbm}")
}
EOF
assert "REGRESSION: txPowerCalDbm (dbm, not rssi) is never flagged, even inside a log call" \
  run_gate "$m"

# ---- REGRESSION 2: src/debug is out of scope -- the spike harness writes rssi_dbm to CSV
# deliberately and correctly, and must not be flagged. --------------------------------------------
m="$(new_case case11_debug_excluded)"
cat > "$m/android/src/debug/kotlin/x/Spike.kt" <<'EOF'
package x
fun writeRow(rssiDbm: Int) {
    android.util.Log.d("SPIKE", "rssi_dbm=$rssiDbm")
}
EOF
assert "REGRESSION: src/debug (Phase 0 spike harness) is out of scope and never flagged" run_gate "$m"

# ---- REGRESSION 3: a *Test.kt file under a src/main-shaped path is still excluded -------------------
m="$(new_case case12_test_excluded)"
cat > "$m/android/src/main/kotlin/x/SightingTest.kt" <<'EOF'
package x
fun t(rssiDbm: Int) { println(rssiDbm) }
EOF
assert "REGRESSION: *Test.kt is excluded even under src/main" run_gate "$m"

echo
echo "test_rssi_egress_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
