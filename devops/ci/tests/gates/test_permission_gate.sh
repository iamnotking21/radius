#!/usr/bin/env bash
# devops/ci/tests/gates/test_permission_gate.sh
#
# Self-test for devops/ci/gates/permission_gate.sh, against synthetic merged-manifest fixtures.
# Every fixture mimics the REAL AGP output layout this gate reads from:
#   <root>/debug/processDebugManifestForPackage/AndroidManifest.xml
#   <root>/release/processReleaseManifestForPackage/AndroidManifest.xml
# including the real tool's habit of splitting a tag's attributes across multiple lines — several
# cases below are regression fixtures for a real bug this gate's OWN development found (see case 6).
#
# Run: bash devops/ci/tests/gates/test_permission_gate.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
GATE="$REPO_ROOT/devops/ci/gates/permission_gate.sh"

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

# The allowlisted 9, formatted exactly like real AGP merger output: some single-line, some split
# across lines (see permission_gate.sh header — the multi-line case is what real output looks like).
ALLOWLISTED_BLOCK='    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission
        android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission
        android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />'

manifest() {
  local applicationId="$1" extra="$2"
  cat <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$applicationId">
$ALLOWLISTED_BLOCK
$extra
    <application android:name=".RadiusApplication" />
</manifest>
EOF
}

# new_case NAME -> $FIXTURE/NAME (the --manifest-root argument)
write_variant() {
  local root="$1" variant="$2" applicationId="$3" extra="$4"
  local dir="$root/$variant/process${variant^}ManifestForPackage"
  mkdir -p "$dir"
  manifest "$applicationId" "$extra" > "$dir/AndroidManifest.xml"
}

run_gate() {
  "$GATE" --manifest-root "$1" >/tmp/qa_perm_test_out.$$ 2>&1
  local rc=$?
  cat /tmp/qa_perm_test_out.$$
  rm -f /tmp/qa_perm_test_out.$$
  return $rc
}

# ---- 1: both variants, allowlist-only -> PASS ------------------------------------------------------
root="$FIXTURE/case1_clean"
write_variant "$root" debug "com.radius.android.debug" ""
write_variant "$root" release "com.radius.android" ""
assert "both variants, allowlisted permissions only, passes" run_gate "$root"

# ---- 2: DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION present (the real live finding) -> still PASS -----
root="$FIXTURE/case2_dynamic_receiver"
write_variant "$root" debug "com.radius.android.debug" \
'    <permission android:name="com.radius.android.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature" />
    <uses-permission android:name="com.radius.android.debug.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />'
write_variant "$root" release "com.radius.android" \
'    <permission android:name="com.radius.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" android:protectionLevel="signature" />
    <uses-permission android:name="com.radius.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />'
assert "REGRESSION: the AndroidX self-scoped DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION guard permission (found on the live repo, not invented) is allowed" \
  run_gate "$root"

# ---- 3: INTERNET added -> FAIL, and the failure names the consequence -----------------------------
root="$FIXTURE/case3_internet"
write_variant "$root" debug "com.radius.android.debug" \
'    <uses-permission android:name="android.permission.INTERNET" />'
write_variant "$root" release "com.radius.android" \
'    <uses-permission android:name="android.permission.INTERNET" />'
out="$("$GATE" --manifest-root "$root" 2>&1)" || true
refute "INTERNET permission fails the gate" run_gate "$root"
assert "INTERNET failure message names PRIVACY_POLICY_DRAFT.md §3b" \
  bash -c "printf '%s' \"\$1\" | grep -q 'PRIVACY_POLICY_DRAFT.md §3b'" _ "$out"
assert "INTERNET failure message names CLAIMS_REGISTER.md row A4" \
  bash -c "printf '%s' \"\$1\" | grep -q 'CLAIMS_REGISTER.md row A4'" _ "$out"

# ---- 4: an unrelated unexpected permission -> FAIL with the generic (not INTERNET-specific) message
root="$FIXTURE/case4_unknown"
write_variant "$root" debug "com.radius.android.debug" \
'    <uses-permission android:name="android.permission.READ_CONTACTS" />'
write_variant "$root" release "com.radius.android" ""
out="$("$GATE" --manifest-root "$root" 2>&1)" || true
refute "an unrelated unallowlisted permission (READ_CONTACTS) fails the gate" run_gate "$root"
assert "generic-unexpected-permission message names decision 50" \
  bash -c "printf '%s' \"\$1\" | grep -q 'decision 50'" _ "$out"

# ---- 5: release variant entirely missing -> FAIL, loudly, not a silent skip -----------------------
root="$FIXTURE/case5_missing_release"
write_variant "$root" debug "com.radius.android.debug" ""
mkdir -p "$root"  # no release/ subdir at all
refute "a missing variant fails the gate (never a silent pass)" run_gate "$root"
out="$("$GATE" --manifest-root "$root" 2>&1)" || true
assert "missing-variant failure says which check did not run" \
  bash -c "printf '%s' \"\$1\" | grep -q \"did NOT run for 'release'\"" _ "$out"

# ---- 6: REGRESSION — multi-line uses-permission tags must parse without corrupting attribute text.
# An early draft of extract_permission_tags() used a `tr '\x01' ...` sentinel that some `tr`
# implementations do not treat as a single hex-escaped byte, silently shredding any attribute value
# containing the literal characters \, x, 0 or 1 — which 'maxSdkVersion="30"' always does. That
# produced a false ACCESS_FINE_LOCATION hygiene WARN on the real merged manifest even though the
# attribute was present and correct. This fixture is exactly that shape. -----------------------------
root="$FIXTURE/case6_multiline_attrs"
write_variant "$root" debug "com.radius.android.debug" ""
write_variant "$root" release "com.radius.android" ""
out="$("$GATE" --manifest-root "$root" 2>&1)"
assert "REGRESSION: multi-line uses-permission tags parse cleanly, no false maxSdkVersion WARN" \
  bash -c "! printf '%s' \"\$1\" | grep -q 'WITHOUT maxSdkVersion'" _ "$out"

echo
echo "test_permission_gate.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
