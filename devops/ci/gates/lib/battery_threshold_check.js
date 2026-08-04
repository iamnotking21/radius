#!/usr/bin/env node
// devops/ci/gates/lib/battery_threshold_check.js
//
// Threshold + provenance enforcement for battery_gate.sh. See that script's header for the full
// contract. Kept as a separate tiny script (not inlined in bash) because bash has no native
// floating-point comparison and hand-rolling one for a safety-adjacent gate is exactly the kind of
// "clever" that hides a bug for a year.
//
// Usage: node battery_threshold_check.js <results.json>
// Exit 0 iff every entry: source === "hardware-rig", scanning_pct_per_hr < 4.0, idle_pct_per_day < 1.0.

const fs = require("fs");

const SCANNING_BUDGET_PCT_PER_HR = 4.0; // root CLAUDE.md HARD NUMBERS
const IDLE_BUDGET_PCT_PER_DAY = 1.0; // root CLAUDE.md HARD NUMBERS

const path = process.argv[2];
if (!path) {
  console.error("[battery-threshold] usage: node battery_threshold_check.js <results.json>");
  process.exit(2);
}

let raw;
try {
  raw = fs.readFileSync(path, "utf8");
} catch (e) {
  console.error(`[battery-threshold] cannot read ${path}: ${e.message}`);
  process.exit(2);
}

let entries;
try {
  entries = JSON.parse(raw);
} catch (e) {
  console.error(`[battery-threshold] ${path} is not valid JSON: ${e.message}`);
  process.exit(2);
}

if (!Array.isArray(entries) || entries.length === 0) {
  console.error(`[battery-threshold] ${path} must be a non-empty JSON array of device results.`);
  process.exit(2);
}

let failed = false;

for (const [i, e] of entries.entries()) {
  const tag = e.device_model ? `[${e.device_model}]` : `[entry ${i}]`;

  // Provenance check FIRST and INDEPENDENT of the numbers. A great-looking number from a simulator
  // is not a borderline pass, it is not evidence at all — same standing as SPEC.md §5.4's rule that
  // a simulator BLE result must never be recorded as a pass, applied to battery.
  const source = String(e.source || "").toLowerCase();
  if (source.includes("simulator") || source.includes("emulator") || source !== "hardware-rig") {
    console.error(
      `[battery-threshold] ${tag} source="${e.source}" REJECTED. Only "hardware-rig" is ` +
        `accepted. A simulator/emulator has no radio and no battery; its output is not a ` +
        `battery measurement, it is a category error.`
    );
    failed = true;
    continue; // do not also evaluate numbers from a source we've already rejected
  }

  if (typeof e.scanning_pct_per_hr !== "number" || !Number.isFinite(e.scanning_pct_per_hr)) {
    console.error(`[battery-threshold] ${tag} missing/invalid scanning_pct_per_hr`);
    failed = true;
  } else if (e.scanning_pct_per_hr >= SCANNING_BUDGET_PCT_PER_HR) {
    console.error(
      `[battery-threshold] ${tag} scanning_pct_per_hr=${e.scanning_pct_per_hr} >= budget ` +
        `${SCANNING_BUDGET_PCT_PER_HR} — REGRESSION`
    );
    failed = true;
  }

  if (typeof e.idle_pct_per_day !== "number" || !Number.isFinite(e.idle_pct_per_day)) {
    console.error(`[battery-threshold] ${tag} missing/invalid idle_pct_per_day`);
    failed = true;
  } else if (e.idle_pct_per_day >= IDLE_BUDGET_PCT_PER_DAY) {
    console.error(
      `[battery-threshold] ${tag} idle_pct_per_day=${e.idle_pct_per_day} >= budget ` +
        `${IDLE_BUDGET_PCT_PER_DAY} — REGRESSION`
    );
    failed = true;
  }
}

process.exit(failed ? 1 : 0);
