#!/usr/bin/env bash
# devops/ci/gates/battery_gate.sh
#
# BATTERY GATE — root CLAUDE.md HARD NUMBERS: "<4%/hr scanning · <1%/day idle. CI FAILS on
# regression." mobile/CLAUDE.md repeats the same contract and ties it to the hardware rig
# (6 devices, fixed distances, controller-driven scenario, results -> Grafana).
#
# THE CONTRACT THIS GATE ENFORCES (once it CAN enforce anything):
#   scanning_pct_per_hr   MUST be <  4.0   (percent of battery consumed per hour while scanning)
#   idle_pct_per_day      MUST be <  1.0   (percent of battery consumed per day while idle)
#   measurement source    MUST be "hardware-rig". Anything claiming simulator or emulator origin is
#                          a HARD FAIL of the gate itself, independent of the numbers — a simulator
#                          cannot produce a real battery measurement (root CLAUDE.md, mobile/CLAUDE.md
#                          "testing" section: "simulator BLE result = invalid, never claim pass").
#                          Battery draw is a hardware/radio-firmware property; a simulator has no
#                          radio and no battery, so a "simulator battery measurement" is not merely
#                          untrustworthy, it is a category error, and this gate treats it as one.
#
# CURRENT STATE OF THE WORLD, 2026-08-04: THERE IS NO HARDWARE RIG YET (mobile/CLAUDE.md "hardware
# rig" is a P1 deliverable, not built). No device has ever run this app. There is therefore NOTHING
# to measure, and this gate CANNOT pass today. It does not pass anyway by being skipped, disabled,
# or hard-coded green — that would be a green gate measuring nothing, which is worse than a red one
# that measures nothing honestly, because people stop distrusting it (explicit instruction from the
# orchestrator, and the correct call regardless of who gave it). IT FAILS LOUDLY, ON PURPOSE, EVERY
# RUN, UNTIL REAL RESULTS EXIST. This is expected and is not a bug in this script.
#
# HOW THIS GATE ACTIVATES ITSELF once the rig exists (devops-tencent's Grafana pipeline is the
# intended producer): pass a results file via --results PATH or $BATTERY_RESULTS_JSON, containing a
# JSON array of objects:
#   [{ "device_model": "Pixel 8",
#      "scanning_pct_per_hr": 2.7,
#      "idle_pct_per_day": 0.6,
#      "measured_at": "2026-09-01T00:00:00Z",
#      "source": "hardware-rig" }, ...]
# One entry per device model measured in the run. Every entry is checked independently; the worst
# device in the fleet is the one that determines pass/fail, same reasoning as KEY_SCHEDULE.md §4.3.4
# "INCONSISTENT: take the worst observed result... do not average, do not take the best."
#
# USAGE:
#   devops/ci/gates/battery_gate.sh [--results PATH]
#
# EXIT CODE: 1 today, always, with no --results given (or file missing) — the honest stub state.
#            1 if any entry fails a threshold or claims a non-hardware-rig source.
#            0 only if a results file is given, every entry is hardware-rig-sourced, and every
#              entry is within both thresholds.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"

RESULTS_PATH="${BATTERY_RESULTS_JSON:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --results) RESULTS_PATH="$2"; shift 2 ;;
    *) qa_hardfail "unknown argument: $1" ;;
  esac
done

if [[ -z "$RESULTS_PATH" || ! -f "$RESULTS_PATH" ]]; then
  qa_fail "BATTERY GATE: NOT YET MEASURABLE. No hardware rig exists in CI (mobile/CLAUDE.md P1,"
  qa_fail "unbuilt as of 2026-08-04). No device has run this app. There is nothing to measure."
  qa_fail "Contract when the rig lands: scanning <4%/hr, idle <1%/day, source=hardware-rig only."
  qa_fail "This is an INTENTIONAL, PERMANENT-UNTIL-REPLACED failure, not a bug. A green battery gate"
  qa_fail "that measures nothing is worse than this red one — see devops/ci/README.md."
  qa_fail "Pass --results <hardware-rig-output.json> or set \$BATTERY_RESULTS_JSON once real"
  qa_fail "numbers exist. Simulator/emulator output is REJECTED even if provided (see below)."
  exit 1
fi

command -v node >/dev/null 2>&1 || \
  qa_hardfail "node is required to parse $RESULTS_PATH and is not on PATH (see devops/ci/README.md)"

# All threshold logic and source-provenance rejection lives in one small, auditable Node script so
# the numeric comparison isn't hand-rolled in bash floating point (which does not exist natively).
node "$SCRIPT_DIR/lib/battery_threshold_check.js" "$RESULTS_PATH"
status=$?

if [[ $status -ne 0 ]]; then
  qa_fail "BATTERY GATE: FAILED."
  exit 1
fi

qa_pass "BATTERY GATE: PASSED — all entries hardware-rig-sourced and within budget."
exit 0
