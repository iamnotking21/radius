#!/usr/bin/env bash
# devops/ci/tests/gates/test_gradle_wrapper.sh
#
# Self-test for qa_gradle_wrapper() in devops/ci/gates/lib/common.sh — the OS-aware Gradle wrapper
# selection that conformance_gate.sh phase 2 uses (B12: it used to hardcode `./gradlew.bat`, which
# does not execute on a Linux CI runner). Mirrors devops/ci/runner/run-stage.sh's own
# `gradle_wrapper()`, deliberately: two independent implementations of "how do we pick a wrapper"
# is the same defect class as the vectors-count drift documented in conformance_gate.sh's header.
#
# uname is overridden as a shell FUNCTION for each case so this test exercises every branch on
# whichever OS actually runs it (this repo's only CI-capable machine today is Windows/MSYS) without
# needing to execute on Linux or macOS to prove the logic. That is a real limitation, stated
# honestly: this proves the STRING-MATCHING RULE is correct for each `uname -s` value, not that a
# real Linux box reports one of these strings — it does (well-established `uname -s` output: "Linux",
# "Darwin"), but this test cannot itself observe that on a Windows machine.
#
# Run: bash devops/ci/tests/gates/test_gradle_wrapper.sh

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$THIS_DIR/../../../.." && pwd)"
COMMON="$REPO_ROOT/devops/ci/gates/lib/common.sh"

pass_count=0
fail_count=0
assert_eq() {
  local desc="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "  ok  - $desc"
    pass_count=$((pass_count + 1))
  else
    echo "  FAIL - $desc (expected '$expected', got '$actual')"
    fail_count=$((fail_count + 1))
  fi
}

# Run each case in its own subshell so overriding `uname` as a function never leaks into the next
# case or into the rest of this test harness's own commands (mktemp, etc. elsewhere in the suite).
check_one() {
  local desc="$1" uname_s="$2" expected="$3"
  local actual
  actual="$(
    # shellcheck disable=SC1090
    source "$COMMON"
    uname() { echo "$uname_s"; }
    qa_gradle_wrapper
  )"
  assert_eq "$desc" "$expected" "$actual"
}

check_one "MSYS (git-bash, this machine's real value) -> gradlew.bat" "MSYS_NT-10.0-26200" "./gradlew.bat"
check_one "MINGW64_NT -> gradlew.bat"                                  "MINGW64_NT-10.0"    "./gradlew.bat"
check_one "CYGWIN_NT -> gradlew.bat"                                   "CYGWIN_NT-10.0"     "./gradlew.bat"
check_one "Linux -> gradlew (no .bat)"                                 "Linux"              "./gradlew"
check_one "Darwin -> gradlew (no .bat)"                                "Darwin"             "./gradlew"

# And: on THIS machine, with the REAL uname (no override), it must resolve to the batch wrapper —
# the concrete regression this self-test exists to catch (conformance_gate.sh must still work here,
# the only machine that can run it today; see task B12).
real_result="$(bash -c "source '$COMMON'; qa_gradle_wrapper")"
assert_eq "real uname on this machine resolves to gradlew.bat" "./gradlew.bat" "$real_result"

echo
echo "test_gradle_wrapper.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
