# devops/ci/ — TEST + GATE stages (qa-test)

**Ownership:** qa-test owns TEST + GATE stages in this directory (root `CLAUDE.md` REPO MAP carve-out:
`devops/ci/ TEST stages → qa-test`). devops-tencent owns the RUNNER and infra/deploy stages, and the
actual Gitea wiring that makes `workflows/android.yml` execute — see that file's header for the
specific handoff.

**NOT YET ENFORCED.** Every gate in this directory runs correctly when invoked by hand or by a
person/agent running this README's commands — verified against the live repo, not just written and
assumed. **None of them run automatically on push or PR yet**, because `workflows/android.yml` is
not wired into Gitea's workflow discovery (see that file's header). Until devops-tencent completes
that wiring, "the gates exist" and "the gates are enforced" are different claims — do not conflate
them in a founder update or a release decision.

## Layout

```
devops/ci/
  workflows/android.yml           Gitea Actions workflow — job definitions for every gate below
  gates/                          the gate scripts themselves, runnable standalone or from CI
    release_uuid_gate.sh          decision 34 — 0xFDA9 must not ship
    release_uuid_gate.allowlist   documented exceptions for the above (currently empty)
    no_map_no_bearing_gate.sh     safety invariant 1 — no map/lat-lng/bearing API in mobile source
    internal_escape_gate.sh       decision 43 / B11 — no mangled internal-JVM-symbol calls outside tests
    the_line_gate.sh              decision 41 — Crypto.kt's import/top-level surface is PINNED
    conformance_gate.sh           40-contracts "all vectors run" — manifest pre-check + codec execution
    battery_gate.sh               <4%/hr scanning, <1%/day idle — STUB until hardware rig exists
    comment_nesting_gate.sh       Kotlin block comments NEST (Java's don't) — hit this repo 4x.
                                   depth-tracking scan, .kt/.kts under mobile/backend/devops.
                                   NOT YET WIRED into fast-gates — see B13, HANDOFF to devops-tencent.
    lib/
      common.sh                   shared bash helpers (repo-root resolution, output formatting,
                                   qa_strip_comments — comment stripping shared by 3 gates.
                                   NOT used by comment_nesting_gate.sh — see that gate's header for why
                                   stripping comments is exactly wrong for a gate that checks THEM)
      comment_depth_scan.awk      char-by-char block-comment nesting depth scanner (awk, no toolchain)
      vectors_manifest_check.js   fast pre-check mirroring VectorManifestTest.kt's counting rule (Node)
      battery_threshold_check.js  numeric threshold + provenance enforcement (Node)
  tests/gates/                    self-tests for the gates above, against SYNTHETIC fixtures only
    run_all.sh                    runs every test_*.sh in this directory
    test_*.sh                     one per gate
```

**Gates 1-4 (release-uuid, no-map-no-bearing, conformance, battery) were the original qa-test CI
deliverable and are accepted.** Gates 5 (`internal_escape_gate.sh`) and 6 (`the_line_gate.sh`) were
added from the security review — decisions 43 and 41, logged as blocker B11 — both in the
`devops/ci/gates/` carve-out.

## Running locally

```
# fast, no build required
bash devops/ci/tests/gates/run_all.sh              # gate self-tests (~2s, synthetic fixtures)
bash devops/ci/gates/no_map_no_bearing_gate.sh
bash devops/ci/gates/internal_escape_gate.sh
bash devops/ci/gates/the_line_gate.sh
bash devops/ci/gates/comment_nesting_gate.sh        # ~2.7s against the whole live repo today
bash devops/ci/gates/release_uuid_gate.sh --skip-artifact   # source phase only

# needs JDK 21 + Node on PATH (mobile toolchain — see qa-test task brief / mobile/CLAUDE.md)
node devops/ci/gates/lib/vectors_manifest_check.js   # fast pre-check only, not authoritative
bash devops/ci/gates/conformance_gate.sh             # phase 2 runs VectorManifestTest.kt — authoritative

# needs a release build first
cd mobile && ./gradlew.bat :android:assembleRelease --no-daemon && cd ..
bash devops/ci/gates/release_uuid_gate.sh --skip-source

# always fails today — that is correct, see battery_gate.sh's header
bash devops/ci/gates/battery_gate.sh
```

## Current gate status (last swept 2026-08-04, end of session — will drift, re-run before trusting)

The mobile codebase is under **active, concurrent, parallel development by ble-protocol and
android-kotlin.** Every row below reflects the LAST sweep of the session (all gates re-run together,
same sitting) rather than a mix of earlier and later checks — several numbers moved multiple times
earlier in the session (see the individual gate scripts' headers for the specific corrections that
came out of watching them move). Re-run before trusting this table for anything beyond "here is what
these gates catch and why it matters."

| Gate | Status at last sweep | Detail |
|---|---|---|
| `no_map_no_bearing_gate.sh` | **PASS** | No banned map/location/bearing API in mobile source. |
| `internal_escape_gate.sh` | **PASS** | No mangled-JVM-symbol (`accountKey$`, `dailyKey$`, `ephemeralIdFor$`, `conformanceState$`, or any `$shared_debug`/`$shared_release`) referenced outside test source. ble-protocol has, correctly, documented the B11 finding inline as KDoc on `KeySchedule.dailyKey`/`ephemeralIdFor` and `Banding.conformanceState` — that documentation is prose, comments never compile, and this gate strips comments before scanning for exactly that reason (see the script's CALIBRATED note, found the same way the map/bearing gate's false positives were found: by running it and reading what it flagged). The underlying visibility hole itself (decision 43) is a design-level fix, not something this gate performs — it only guarantees OUR source never contains the escape. |
| `the_line_gate.sh` | **PASS** | `Crypto.kt`'s imports (zero) and top-level declarations (`Sha256`, `hmacSha256`, `hkdfSha256`, `constantTimeEquals`) exactly match the pinned set (decision 41). |
| `comment_nesting_gate.sh` | **PASS on the working tree as of 2026-08-05, NOT YET WIRED into CI (B13)** — see full note below the table. |
| `release_uuid_gate.sh` (source) | **FAIL — real, open, unchanged all session** | `mobile/shared/src/commonMain/kotlin/com/radius/shared/protocol/Advertisement.kt:66` — `public const val SERVICE_UUID16: Int = 0xFDA9`, unconditional, on the release path, no `BuildConfig.DEBUG` guard, not in the allowlist. This is decision 34's exact scenario, and it is the finding that led to blocker B11 being opened. HANDOFF to ble-protocol/android-kotlin: guard it behind `BuildConfig.DEBUG` (and add it to `release_uuid_gate.allowlist` with the required justification comment) until the real SIG-allocated UUID exists, or wire the real value in if it's ready. |
| `release_uuid_gate.sh` (artifact) | **PASS, with an important caveat** | The literal string does not appear in the assembled release APK's dex/resources — **because R8 decomposes the 16-bit int constant into two independent single-byte loads before it becomes one grep-able token, confirmed by disassembling the actual release build with `dexdump`.** Not an adversarial-evasion gap — it is what today's ordinary, non-adversarial code already produces. **The source scan is the load-bearing check for this class of violation, not the artifact scan.** Full writeup in `gates/release_uuid_gate.sh`'s header. A second false positive (a real match inside `libsqlcipher.so`, a third-party native binary, pure coincidence in dense compiled machine code) was found and fixed by scoping the artifact scan to `classes*.dex`/`resources.arsc`/`res/`/`assets/` only, excluding `lib/**/*.so`. |
| `conformance_gate.sh` phase 1 (fast pre-check) | **PASS** | `vectors_manifest_check.js` now mirrors `VectorManifestTest.kt`'s counting rule exactly (see below) instead of maintaining its own, and agrees with it. |
| `conformance_gate.sh` phase 2 (codec execution — authoritative) | **PASS** | `:shared:testDebugUnitTest` is green, 56/56 tests, 0 failures, including `VectorManifestTest` (3/3). **The vector count is NOT hardcoded anywhere in this repo's CI tooling** — it moved at least four times during this session alone (98 → 99-declared-but-wrong → 110 → 116) as ble-protocol actively developed against it, which is exactly why. The gate reports whatever `VectorManifestTest`'s own `println` says, live, every run — at last sweep: **116 executable cases across 7 files.** Treat that number as a snapshot, not a constant; the gate is what stays true, not this sentence. |
| `lint` (`:shared:lintDebug`, `:android:lintDebug`) | **PASS** | Earlier in the session `:shared:lintDebug` failed with 4 `MissingPermission` errors in `BleRadio.android.kt` — real, reproduced, not a flake, fixed concurrently by another agent before this sweep. |
| `unit-test` (`:shared:testDebugUnitTest`, `:android:testDebugUnitTest`) | **PASS — 56/56** | Grew from the original 9 tests as ble-protocol/android-kotlin landed the codec, radio guards, and vector harness. Mid-session, a forced re-run (`--rerun-tasks`) surfaced a real, reproducible compile break (`RadarModelsTest.kt` referencing an unresolved `ProximityBand`) — not qa-test's to fix, and fixed concurrently before this sweep. `:android:testDebugUnitTest` remains `NO-SOURCE` (no unit tests under `mobile/android/` yet) — expected, not a gap this gate should paper over. |
| `battery_gate.sh` | **FAIL — intentional, permanent until hardware exists** | No hardware rig in CI. This is the correct, designed state — see the script's header. |

**Net: of the gates that CAN currently pass, everything is green except one real, open, unfixed
decision-34 violation (`release_uuid_gate.sh` source phase) and the intentionally-permanent battery
stub.** Gates 5 and 6 (security review) are green today because the codebase does not yet violate
decisions 43/41 in production source — their job is to keep it that way as a mechanical backstop,
not a one-time finding.

**`comment_nesting_gate.sh` note, in full, because the story is the point of the gate:** Kotlin
block comments NEST (Java's do not) — a literal `/*` written inside a KDoc opens a nested comment,
the KDoc's own `*/` closes only the inner one, and everything after it vanishes silently to EOF.
This has hit the project four independent times (mobile/design-tokens/scripts/generate.mjs;
design-system's own bugfix comment about it; android-kotlin's `build.gradle.kts`, which zeroed a
`dependencies{}` block with **no syntax error at all** — Gradle reported a missing Hilt dependency
instead; and `mobile/shared/src/iosTest/.../IosRadioContractTest.kt:16`). At the moment this gate
was written, instance 4 was **live and committed at HEAD** — confirmed by running the gate against
`git show HEAD:<path>` directly, which fails exactly as expected (`6:1: block comment opened here …
is still open at end of file`). **Mid-session, android-kotlin fixed it in the working tree**
(uncommitted at the time of writing — split the glob with backticks and added a KDoc explaining
why), which is why the table above reports PASS against the CURRENT working tree rather than a red
result: this is the gate correctly reporting a fix that happened to land while it was being tested,
not a gate that never caught anything. Self-test `test_comment_nesting_gate.sh` pins the ORIGINAL
committed content verbatim as a fixture (not a simplified stand-in) precisely so this class of
"the live tree got fixed out from under the gate" can never quietly make the regression case
disappear — it will keep failing on the pinned fixture regardless of what HEAD says. **NOT YET
WIRED into `fast-gates`** — `devops/ci/runner/run-stage.sh` is devops-tencent's file (RUNNER
concern), so this is a HANDOFF (`.claude/memory/60-blockers.md` B13), not an edit made here.

## The point of that last table

None of the red rows above are qa-test bugs to silently work around, and none of the green rows
should be read as a durable guarantee — **this is what a CI gate is for.** The alternative to this
table is a founder or an agent claiming "CI is green" from a stale memory of one run three days ago,
which is precisely the failure mode root `CLAUDE.md`'s testing philosophy exists to prevent. Every
gate here is designed to fail loudly and specifically rather than pass by omission; where a gate
found something real during this session, it is recorded above with enough detail (file, line,
exact value) that the owning agent does not have to reproduce the investigation.

## Design principles these gates follow (so the next person extending one keeps them)

1. **Grep for the literal value, not a symbol name** (`release_uuid_gate.sh`) — symbol names get
   renamed, values that are pre-rejected in writing (decision 34) do not change without a new ADR.
2. **A gate that cannot run must fail loudly, never pass silently** (`battery_gate.sh`,
   `conformance_gate.sh` phase 2 when no harness exists yet). An untested/unmeasured state and a
   verified-clean state must never look the same in a CI log.
3. **Scope the check to what you actually control.** The release-UUID artifact scan was narrowed
   after it flagged a coincidental match inside a third-party `.so` — scan `classes*.dex`/
   `resources.arsc`/`res/`/`assets/` (ours), not `lib/**/*.so` (not ours, and dense compiled binaries
   produce essentially every short byte sequence by chance).
4. **Strip comments before scanning prose for banned code patterns**
   (`no_map_no_bearing_gate.sh`) — a comment *explaining* an invariant using the invariant's own
   forbidden words is not a violation of it, and this codebase documents its invariants heavily
   enough that failing to account for this makes the gate permanently red on harmless text.
5. **Test the gate against synthetic fixtures, not the live repo** (`devops/ci/tests/gates/`) — the
   live repo's actual state is volatile (see the table above) and a self-test that hardcodes
   "expect the live repo to currently fail this way" goes stale the moment someone fixes it. The
   counting/detection *logic* is what gets tested; the live repo's current state gets reported
   separately, with a timestamp, exactly as the table above does.
6. **Simulator/emulator output is rejected by construction, not by policy note**
   (`battery_gate.sh`'s `source` field check) — the prime directive ("BLE tested on real hardware
   only") is enforced mechanically wherever a gate could otherwise be fooled by a great-looking fake
   number.
7. **Never maintain two independent implementations of the same count/rule.** `vectors_manifest_
   check.js` used to invent its own counting logic; it now mirrors `VectorManifestTest.kt`'s rule
   exactly and says so in its header, because the two had already silently diverged once (this
   script said 98/99, the live vectors said 116) and a second implementation of "what counts as a
   vector" is exactly the kind of drift the whole gate exists to catch — including in itself.
8. **Pin an explicit allow-list so a widening diff cannot be silent** (`the_line_gate.sh`,
   `release_uuid_gate.allowlist`) — when the boundary legitimately needs to move, the PR has to
   touch qa-test's file too, which makes the widening a visible, cross-owner review instead of one
   line added under deadline pressure that nobody re-reads.
9. **No number is ever hardcoded where a live count is available instead**
   (`conformance_gate.sh`, `vectors_manifest_check.js`) — the vector count moved four times in one
   working session; any hardcoded figure would have been wrong again within hours. Report what the
   authoritative source says, live, every run.
10. **Know when NOT to reuse a shared helper.** `comment_nesting_gate.sh` deliberately does not call
    `qa_strip_comments` even though every other pattern-matching gate in this file does — this gate's
    whole job is checking the STRUCTURE of comments, and stripping them first would blind it to the
    exact thing it exists to find. "Reuse the shared helper" is a default, not a rule; state the
    exception in writing when a gate is the one legitimate case that breaks it.
11. **A regex that only sees one line at a time cannot answer a question about the whole file.**
    (`comment_nesting_gate.sh` vs. the starting-point regex `grep -rnE '^[[:space:]]*\*.*(/\*|\*/)'`)
    — whether a nested comment ever closes again is a property of the WHOLE file, not of any single
    line. A char-by-char state machine that tracks depth across the file answers that question
    directly, and gets the string/char-literal exception for free instead of needing a second
    bolted-on heuristic to avoid flagging a glob path as a "banned" pattern.
