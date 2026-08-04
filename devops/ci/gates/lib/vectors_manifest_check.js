#!/usr/bin/env node
// devops/ci/gates/lib/vectors_manifest_check.js
//
// CONFORMANCE GATE, fast pre-check — fixture integrity, not codec correctness.
//
// CORRECTED 2026-08-04, PER ORCHESTRATOR: this script used to hand-roll its OWN counting rule,
// special-casing key_rotation.json and treating every other file as "cases array only". Running it
// against the live repo found a real manifest bug (see conformance_gate.sh) — but ble-protocol had
// independently found the SAME bug and fixed it properly: `VectorManifestTest.kt`
// (mobile/shared/src/androidUnitTest/.../protocol/vectors/VectorManifestTest.kt) now recomputes the
// count from the files on every CI run and is the OWNED, AUTHORITATIVE implementation of "what
// counts as a case" for mobile/protocol/vectors/. Continuing to maintain a second, independent
// counting implementation here was exactly the divergence-prone duplication this whole exercise
// warns against (ADR-007's core argument: ONE implementation, not two, kills the divergence class
// of bug) — and it had already silently drifted from VectorManifestTest.kt's rule (that test adds a
// `property_assertions` section this script did not know about, and a `destruction_at_seam` section
// added since).
//
// THE FIX: this script no longer invents its own rule. It MIRRORS VectorManifestTest.kt's rule
// exactly (COUNTED_SECTIONS below is a direct transcription of that file's `countedSections`,
// applied generically to every file rather than special-cased per file) so that if the two are ever
// checked side by side they agree. But the actual authority is the Kotlin test, not this script —
// this script exists ONLY as a fast, no-JDK-required pre-check for local iteration and does not, by
// itself, constitute proof that "all vectors ran" (only a Gradle-run VectorManifestTest does that;
// see conformance_gate.sh). If VectorManifestTest.kt's counted-sections list ever changes, THIS
// FILE'S COUNTED_SECTIONS MUST CHANGE WITH IT — that is a real maintenance obligation, stated
// plainly rather than hidden, and is the cost of having a fast pre-check at all.
//
// NO SPECIFIC CASE COUNT IS HARDCODED ANYWHERE IN THIS SCRIPT, ON PURPOSE. The number of vectors is
// whatever the files + index.json currently say, checked for internal consistency; it is expected
// to change as ble-protocol adds vectors, and a script that prints "99!" or "110!" as a banner
// invites exactly the "I remember it was N" staleness this file exists to prevent.
//
// Usage: node vectors_manifest_check.js [--dir VECTORS_DIR] [--index INDEX_FILE]
//   Defaults: <repo>/mobile/protocol/vectors, <vectors-dir>/index.json.
//   --dir/--index overrides exist for the self-test harness (synthetic fixtures), never used in CI.
// Exit 0 iff every file's actual count matches its declared count AND the sum matches case_total.

const fs = require("fs");
const path = require("path");

// Transcribed from VectorManifestTest.kt's `countedSections`, 2026-08-04. Keep in sync — see the
// header. `self_eid_rejection` is handled separately below because its cases nest one level down,
// exactly as that file does.
const COUNTED_SECTIONS = [
  "cases",
  "invalid",
  "property_assertions",
  "skew_window_across_seam",
  "destruction_at_seam",
  "accepted_not_rejected",
];

function findRepoRoot(startDir) {
  // NOTE: several subdirectories (devops/, mobile/) have their OWN CLAUDE.md — checking for the
  // mere existence of a CLAUDE.md walking upward stops at the first one of those, not the repo
  // root. The root CLAUDE.md is distinguished by content ("# RADIUS — ROOT MEMORY" header) and by
  // being the directory that also contains both mobile/ and backend/ as siblings.
  let dir = startDir;
  while (dir !== path.parse(dir).root) {
    const candidate = path.join(dir, "CLAUDE.md");
    if (
      fs.existsSync(candidate) &&
      fs.existsSync(path.join(dir, "mobile")) &&
      fs.existsSync(path.join(dir, "backend"))
    ) {
      return dir;
    }
    dir = path.dirname(dir);
  }
  throw new Error("could not locate repo root (walked up from " + startDir + ")");
}

const args = process.argv.slice(2);
let vectorsDir = null;
let indexFile = null;
for (let i = 0; i < args.length; i++) {
  if (args[i] === "--dir") vectorsDir = args[++i];
  else if (args[i] === "--index") indexFile = args[++i];
}
if (!vectorsDir) {
  const repoRoot = findRepoRoot(__dirname);
  vectorsDir = path.join(repoRoot, "mobile", "protocol", "vectors");
}
if (!indexFile) indexFile = path.join(vectorsDir, "index.json");

function loadJson(p) {
  return JSON.parse(fs.readFileSync(p, "utf8"));
}

// Mirrors VectorManifestTest.kt's countCases() exactly: sum the length of every counted section
// that exists on this file, plus self_eid_rejection.cases nested one level down.
function countCases(j) {
  const parts = {};
  let total = 0;
  for (const section of COUNTED_SECTIONS) {
    if (Array.isArray(j[section])) {
      parts[section] = j[section].length;
      total += j[section].length;
    }
  }
  if (j.self_eid_rejection && Array.isArray(j.self_eid_rejection.cases)) {
    parts["self_eid_rejection.cases"] = j.self_eid_rejection.cases.length;
    total += j.self_eid_rejection.cases.length;
  }
  return { total, parts };
}

let index;
try {
  index = loadJson(indexFile);
} catch (e) {
  console.error(`[vectors-manifest] cannot read/parse ${indexFile}: ${e.message}`);
  process.exit(2);
}

if (!Array.isArray(index.files)) {
  console.error(`[vectors-manifest] ${indexFile} has no "files" array — nothing to check`);
  process.exit(2);
}

let ok = true;
let sumActual = 0;
let sumDeclared = 0;

for (const entry of index.files) {
  const filePath = path.join(vectorsDir, entry.file);
  if (!fs.existsSync(filePath)) {
    console.error(`[vectors-manifest] FAIL: ${entry.file} listed in index.json but does not exist`);
    ok = false;
    continue;
  }
  const j = loadJson(filePath);
  const result = countCases(j);

  sumActual += result.total;
  sumDeclared += entry.cases;

  if (result.total !== entry.cases) {
    ok = false;
    console.error(
      `[vectors-manifest] FAIL: ${entry.file} — index.json declares "cases": ${entry.cases}, ` +
        `actual count is ${result.total} (breakdown: ${JSON.stringify(result.parts)})`
    );
  } else {
    console.log(`[vectors-manifest] ok: ${entry.file} — ${result.total} cases, matches index.json`);
  }
}

if (typeof index.case_total === "number" && sumActual !== index.case_total) {
  ok = false;
  console.error(
    `[vectors-manifest] FAIL: index.json declares case_total=${index.case_total}, ` +
      `actual sum across all files = ${sumActual} (declared-per-file sum = ${sumDeclared})`
  );
}

if (!ok) {
  console.error(
    "[vectors-manifest] MANIFEST INTEGRITY CHECK FAILED (fast pre-check). This does not by itself " +
      "mean the real gate fails or passes — VectorManifestTest.kt, run via Gradle, is the " +
      "authoritative check (see conformance_gate.sh). If ONLY this script disagrees and " +
      "VectorManifestTest.kt passes, COUNTED_SECTIONS above has drifted from that file's rule and " +
      "needs updating, not the vectors."
  );
  process.exit(1);
}

console.log(`[vectors-manifest] fast pre-check PASSED — ${sumActual} cases, consistent with case_total.`);
process.exit(0);
