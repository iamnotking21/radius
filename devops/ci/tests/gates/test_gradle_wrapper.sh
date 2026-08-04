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
# whichever OS actually runs it, without needing to execute on Linux or macOS to prove the logic.
# That is a real limitation, stated honestly: this proves the STRING-MATCHING RULE is correct for
# each `uname -s` value, not that a real Linux box reports one of these strings — it does
# (well-established `uname -s` output: "Linux", "Darwin") but this test cannot itself observe that
# on any single host.
#
# CORRECTION (B12 follow-up, caught by CI run 2 on ubuntu-latest, not locally — see
# .claude/memory/60-blockers.md B12): this file used to ALSO assert that the REAL, un-overridden
# `uname` on "this machine" resolves to `./gradlew.bat`, i.e. it baked in "this machine is Windows"
# as a hardcoded expectation. That is exactly the defect class B12 exists to remove, reintroduced
# inside the fix's own test — it passed 45/45 on the Windows dev box that wrote it and failed the
# instant a second OS (ubuntu-latest, fast-gates job) ran it, precisely because the CODE UNDER TEST
# was correctly returning `./gradlew` there. Replaced below with an assertion that holds on ANY
# host: the real, un-overridden result is one of the two valid wrapper names, AND that file actually
# exists (and is executable, on POSIX hosts) under mobile/ — a real property of the machine, not a
# restatement of which OS wrote this test.
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
assert_true() {
  local desc="$1"; shift
  if "$@"; then
    echo "  ok  - $desc"
    pass_count=$((pass_count + 1))
  else
    echo "  FAIL - $desc"
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

# And: with the REAL, un-overridden uname of WHATEVER HOST RUNS THIS TEST, the rule must resolve to
# a wrapper name that actually exists (and, where the unix x-bit is meaningful, is executable) under
# mobile/ — a property of "did the rule pick something real", checkable on any OS, rather than a
# restatement of which OS wrote the test.
MOBILE_DIR="$REPO_ROOT/mobile"
real_result="$(bash -c "source '$COMMON'; qa_gradle_wrapper")"

case "$real_result" in
  ./gradlew|./gradlew.bat)
    echo "  ok  - real uname on this host resolves to a recognised wrapper name ($real_result)"
    pass_count=$((pass_count + 1))
    ;;
  *)
    echo "  FAIL - real uname on this host resolved to an unrecognised wrapper name ('$real_result')"
    fail_count=$((fail_count + 1))
    ;;
esac

wrapper_path="$MOBILE_DIR/${real_result#./}"
assert_true "$real_result exists under mobile/" [ -f "$wrapper_path" ]

# gradlew (the POSIX wrapper) is invoked via the unix x-bit, so it must carry one — checked here as
# a real, host-observable fact (git commits it as mode 100755; Linux/macOS/MSYS all honour that).
# gradlew.bat is invoked via Windows' own .bat file association, NOT the unix x-bit, so it is
# deliberately not checked for +x — doing so would itself be a Windows-only assumption baked into a
# test whose whole point is not baking in host assumptions.
if [[ "$real_result" == "./gradlew" ]]; then
  assert_true "$real_result is executable" [ -x "$wrapper_path" ]
fi

echo
echo "test_gradle_wrapper.sh: $pass_count passed, $fail_count failed"
[[ $fail_count -eq 0 ]]
