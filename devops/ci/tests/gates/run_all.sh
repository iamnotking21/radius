#!/usr/bin/env bash
# devops/ci/tests/gates/run_all.sh
#
# Runs every gate self-test in this directory. This is the "DONE MEANS test written + running in
# CI" mechanism for devops/ci/gates/ — wired into CI as the `gate-selftests` stage (see
# devops/ci/runner/run-stage.sh, invoked from .github/workflows/ci.yml and .gitea/workflows/ci.yml;
# devops/ci/workflows/android.yml is superseded, now a pointer to those, see that file). All
# fixtures are synthetic; none of this touches the live repo or Gradle, so it runs in a couple of
# seconds and has no false dependency on mobile/ build state.

set -euo pipefail
THIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

overall=0
for t in "$THIS_DIR"/test_*.sh; do
  echo "=== $(basename "$t") ==="
  if ! bash "$t"; then
    overall=1
  fi
  echo
done

if [[ $overall -ne 0 ]]; then
  echo "[qa-gate] GATE SELF-TESTS: FAILED — a gate's own detection logic is broken. Fix the gate" >&2
  echo "[qa-gate] before trusting anything it reports about the real repo." >&2
  exit 1
fi

echo "[qa-gate] GATE SELF-TESTS: all passed."
exit 0
