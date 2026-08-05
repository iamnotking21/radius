# devops/ci/runner/ — CI RUNNER + WORKFLOW DISCOVERY

**Owner: devops-tencent.** Root `CLAUDE.md` REPO MAP: *"devops-tencent owns the runner + infra/deploy
stages; qa-test owns the test + gate stages."* Everything in this directory is the runner half.
The gates themselves are qa-test's, in `devops/ci/gates/`, and **nothing here reimplements a check**.

---

## 1. THE PROBLEM THIS DIRECTORY SOLVES

Six gates existed and passed their own self-tests. **None of them ran automatically.** `devops/ci/
workflows/android.yml` was a correct workflow definition sitting in a directory no CI provider looks
at — Gitea discovers workflows at `.gitea/workflows/`, GitHub at `.github/workflows/`, and `devops/ci/`
is neither. Meanwhile the code had been pushed to `github.com/iamnotking21/radius` with no `.github/`
directory at all.

Gates that do not run are worse than no gates. They invite a founder update or a release decision to
say "CI is green" when nothing has executed. That is the exact failure mode the gates were written
to prevent, reintroduced one directory level up.

---

## 2. WHAT WAS DECIDED, AND WHY — GitHub *and* Gitea, one shared script

Three options were on the table:

| | |
|---|---|
| **A. GitHub only** | Gates run today. But CI drifts to a third-party provider with nothing pulling it back, and ADR-003's intent erodes by default rather than by decision. |
| **B. Gitea only** | Ideologically clean. Gates do not execute until blocker **B3** (Tencent not provisioned) closes — realistically months of a codebase under active parallel development by four agents with **zero** enforced gates. Rejected: it is option "no gates", spelled expensively. |
| **C. Both, sharing one script** ✅ | Gates run today on GitHub. `.gitea/workflows/` is committed *now*, so migration is a runner-label change and not a rewrite. Drift is impossible by construction because neither workflow file contains any command. |

**C, adopted.** With one refinement over the brief as posed: the shared logic is split across two
layers, not one.

* **check logic** — `devops/ci/gates/*.sh`. qa-test's. Untouched.
* **stage definitions** (which command each stage runs, and the release/non-release blocking split)
  — `devops/ci/runner/run-stage.sh`. Ours. **One file.**
* **workflow files** — triggers, runner labels, checkout. Nothing executable beyond
  `bash devops/ci/runner/run-stage.sh <stage>`.

Putting the stage table in the gate scripts (the brief's suggestion) would not have worked: the
blocking split, the OS-portable Gradle wrapper selection, and the Android-SDK preflight are all
*runner* concerns, and writing them into `devops/ci/gates/` would mean devops-tencent editing
qa-test's files on every runner change. The layering keeps exactly one writer per file.

### Does this violate ADR-003?

ADR-003 rejects third-party services on two grounds: **lock-in**, and **a vendor holding our users'
data**. Test both against this:

* *Data.* A build runner executing our own bash against our own source holds no user data. Nothing
  in either workflow touches a secret, a signing key, a database, or a user record — see §6, and
  keep it that way.
* *Lock-in.* Zero GitHub-proprietary surface is used. No environments, no OIDC, no deployment
  targets, no marketplace action beyond `checkout` and `setup-java`. All logic is bash we own.
  Deleting `.github/` costs us the two YAML files and nothing else. That is a weekend measured in
  minutes, which is precisely the reversibility standard ADR-003 sets for the whole stack.

The line that must not be crossed: **the day CI needs a signing key, a registry credential, or
production access, it moves to Gitea first.** At that point the third-party objection becomes real,
because that is when a CI provider starts holding something that matters.

> **This is now settled in writing: `docs/adr/ADR-009-interim-github-actions-ci.md`, Accepted.**
> Dual-target CI, all logic in-repo, GitHub interim and secret-free, Gitea the destination, migrate
> when B3 closes or the moment CI needs a secret — whichever comes first. The paragraphs above are
> the operational summary; ADR-009 is the authority. Still outstanding from it: the REPO MAP line
> assigning `.github/` and `.gitea/` to devops-tencent (HANDOFF-3).

---

## 3. THE BLOCKING SPLIT — read this before "fixing" a red build

Two gates are **RED BY DESIGN**, for reasons that are facts about the world rather than bugs:

| gate | why it is red | closes when |
|---|---|---|
| `gate-release-uuid-source` | **B9 / B11, decision 34.** `0xFDA9` is a *provisional* BLE service UUID. There is no SIG-allocated UUID to guard toward, because the Bluetooth SIG Adopter application is sequenced behind legal-entity formation. The gate is correctly reporting *we cannot ship*. | real UUID allocated, or the constant is `BuildConfig.DEBUG`-guarded and allowlisted |
| `gate-battery` | No hardware rig exists (`mobile/CLAUDE.md` P1, unbuilt). No device has ever run this app. There is nothing to measure, and a green battery gate that measured nothing would be a lie. | rig lands and publishes `source: "hardware-rig"` results |

Neither is silenced and neither blocks ordinary work:

```
push / PR   (.github|.gitea)/workflows/ci.yml             known-red tolerance ACTIVE
tag / release/**  (.github|.gitea)/workflows/release-gates.yml   RADIUS_CI_STRICT=1 — everything blocks
```

On the push path `run-stage.sh` **asserts the expected state** rather than swallowing the result:

* gate **fails** → expected. The full reason is written into the run summary *every single build*,
  the stage passes. Nobody is trained to ignore anything, because the signal is a sentence
  explaining what we are still blocked on, not an unexplained red X.
* gate **passes** → the world changed. The stage **fails**, with instructions: delete that entry from
  `known_red_reason()`. CI goes red on good news, exactly once. Deliberate — a tolerance that
  outlives its cause silently absorbs the next *real* reintroduction of `0xFDA9`, which is the
  precise disaster the gate exists to prevent.

This cannot hide a broken gate script: `gate-selftests` runs qa-test's full self-test suite against
synthetic fixtures, fully blocking, on every path. (Deliberately not quoting the assertion count —
it went 39 → 45 → 80 in two days. `run_all.sh` prints the live number every run; that is the
authority, per `devops/ci/README.md` design principle 9.)

`release-gates.yml` **is expected to fail today.** Do not "fix" it. It is the mechanical statement
that Radius cannot ship, made at the only moment where that statement is actionable.

---

## 4. RUNNER REQUIREMENTS

**No Mac. No macOS job on any path.** iOS is DEFERRED (blocker B4); Kotlin/Native iOS targets are
host-guarded and skip with a loud warning. A macOS job would have nothing to do, and adding one that
pretends otherwise is the same category of dishonesty as a green battery gate.

### Android path (the only path today)

| need | version | notes |
|---|---|---|
| JDK | **21** (Temurin verified) | AGP 8.9 / Kotlin 2.1.20 / Gradle wrapper 8.13 |
| Android SDK | **platform 35** + build-tools | `ANDROID_HOME` or `ANDROID_SDK_ROOT` **must** be set — `mobile/local.properties` is `.gitignored` (correctly; it holds machine-local absolute paths) and is therefore absent on every runner. `run-stage.sh` preflights this and fails in one second with the fix in the message, instead of failing six minutes into an AGP error. |
| Node | 20+ | **A build dependency of `:android` itself, not just a gate helper.** `mobile/android/build.gradle.kts` hangs `generateDesignTokens` off `preBuild`, running `mobile/design-tokens/scripts/generate.mjs` — so `tokens.json` is the only place in the repo a colour value exists. Verified by task-graph inspection (`--dry-run`): the generator is in the graph for `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease` **and** `processDebug/ReleaseManifestForPackage`. `:shared`-only work does not pull it. Also needed by `conformance_gate.sh` phase 1 and `battery_gate.sh`. Zero npm dependencies. `run-stage.sh` asserts it per-stage with the reason named. |
| bash + coreutils + git | any | every gate; `git rev-parse` for repo-root resolution |
| disk | ~15 GB | Gradle caches, SDK, two APK variants (debug ~51 MB, release ~23 MB) |

Gitea runner labels to register: `self-hosted`, `linux`, `android`.

Gitea also needs `DEFAULT_ACTIONS_URL` set, or `actions/checkout` mirrored into a local Gitea org.
**Mirror it.** A self-hosted CI that fetches actions from github.com on every run has quietly
reintroduced the dependency we self-hosted to remove.

### Local reproduction — identical commands, no CI required

```bash
bash devops/ci/runner/run-stage.sh --list          # stage table + which stages are known-red
bash devops/ci/runner/run-stage.sh gate-selftests
bash devops/ci/runner/run-stage.sh gate-conformance
RADIUS_CI_STRICT=1 bash devops/ci/runner/run-stage.sh gate-battery   # what release will do

# the permission gate reads build output, so its producer must run first (CI does this as the
# step immediately above it). Cheap — manifest merge only, no compile, no R8.
bash devops/ci/runner/run-stage.sh build-manifests
bash devops/ci/runner/run-stage.sh gate-permission
```

### Stage pairs — stages that are not independently runnable

Almost every stage stands alone. Two do not, and both read build output written by the stage before
them **in the same job and workspace**:

| gate | producer that must run first | why not folded together |
|---|---|---|
| `gate-permission` | `build-manifests` | a Gradle/AGP failure must report as a BUILD failure, never as a permission violation (§5b attribution) |
| `gate-release-uuid-artifact` | `build-assemble-release` | same reasoning; release-only, so it is not on the push path at all |

`gate-permission` prints a runner-side wiring note when its input is absent — *before* running the
gate, never instead of it, so it can only add information and never produce a verdict of its own.
A missing manifest must never be able to read as "the permission check passed"; the gate itself
fails closed on a missing variant, which is qa-test's design and the right one.

**Why `build-manifests` and not `assembleRelease`.** The permission gate needs the merged manifest
for *both* variants, and the push path only builds debug. Running a full release build on every push
to obtain a 4 KB XML file would put R8 on the critical path for a check that takes milliseconds.
`processDebugManifestForPackage` / `processReleaseManifestForPackage` are AGP's manifest-merge-only
tasks and are literally the producers — the output directory is named after the task
(`packaged_manifests/debug/processDebugManifestForPackage/`). Measured on the dev box: **18
actionable tasks vs 86 for `assembleRelease`**, no compile, no dex, no R8, no signing.

On the Windows dev machine, export the toolchain first — the shell does not inherit it:

```bash
export JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
export PATH="$JAVA_HOME/bin:$PATH"
```

---

## 5. OPEN HANDOFFS

**HANDOFF-1 / B12 — CLOSED.** `conformance_gate.sh` phase 2 used to hardcode `./gradlew.bat`, a
Windows-only entrypoint, which forced the GitHub `android` job onto `windows-latest` at 2x billed
minutes and made the Gitea conformance stage unrunnable. qa-test replaced it with
`qa_gradle_wrapper()` in `devops/ci/gates/lib/common.sh` — same `uname -s` rule as
`run-stage.sh`'s `gradle_wrapper()`, reused rather than reinvented, with `test_gradle_wrapper.sh`
covering every branch. Both GitHub jobs are now
`ubuntu-latest`. The tripwire in `run-stage.sh`'s `gate-conformance` case retired itself the moment
the literal left that file, and is kept, dormant, so a reintroduction re-arms it automatically.

**HANDOFF-2 — CLOSED** by its owner. `devops/ci/workflows/android.yml` is a pointer now, not a
workflow. Emptied rather than deleted, which is the better call than the one this file originally
asked for: it preserves the location someone would look in while removing the second,
authoritative-looking copy of the job list.

**HANDOFF-3 → orchestrator.** REPO MAP has no owner for `.github/` or `.gitea/`. Claimed here as
runner territory; needs a line in root `CLAUDE.md` to be real. (ADR-009 covers the interim-CI
decision itself.)

---

## 5b. FIRST UBUNTU RUN — ATTRIBUTION

**No Android build in this project has ever executed on Linux.** Not once, by anyone — nobody on the
team owns a Linux machine. Every green result on record comes from one Windows dev box with a local
SDK and warm Gradle caches.

So the first `ubuntu-latest` run is a genuine experiment, and it has a specific failure mode worth
naming in advance: **a runner-image problem that gets written down as a code regression.** Once
"the Android build broke" is in a commit message or an issue title, the next person spends a day in
Kotlin instead of thirty seconds in the runner config. Triage before you file:

| what you see | it is | what to do |
|---|---|---|
| `[runner][FAIL] neither ANDROID_HOME nor ANDROID_SDK_ROOT is set` (fails in ~1s) | **runner** | image lacks the SDK env. Add an SDK step. Not a code change. |
| AGP: `Failed to install the following SDK components` / licence not accepted | **runner** | add `sdkmanager "platforms;android-35" "build-tools;35.0.0"` before the Gradle steps. Deliberately *not* pre-added — an untested provisioning step on an image that probably already has it just adds a new thing that can break the first run. |
| `Permission denied: ./gradlew` | **runner/checkout** | file mode lost in transit. Blob is committed 100755, LF-only, `#!/bin/sh` — verified against `git show HEAD:mobile/gradlew`, not the working tree, so `core.autocrlf` is not masking a CR. |
| `bad interpreter: /bin/sh^M` | **runner/checkout** | CRLF injected by checkout config. Not the committed blob. |
| `[runner][FAIL] no 'node' on PATH. This stage needs it for: generateDesignTokens...` (fails in ~1s) | **runner** | image lacks Node. `:android` genuinely cannot build without it — design tokens are generated, not vendored. Not a code change. |
| `generate.mjs failed` **and** the log shows `N pairings checked, M regression(s)` with M > 0 | **code — an ACCESSIBILITY regression** | this is the WCAG contrast gate, not a toolchain problem. A colour pairing in `mobile/design-tokens/tokens.json` dropped below its documented floor. File it against design-system. |
| `Could not run node` from inside the Gradle task (i.e. the preflight was bypassed or the image lost Node mid-run) | **runner** | same as row above, caught later than it should have been. Tell devops-tencent the preflight was outflanked. |
| Kotlin compile error, lint error, failing unit test, conformance vector divergence | **code** | file it against the owning agent. These are OS-independent and would fail on Windows too. |

**The `generate.mjs` rows above matter more than they look.** Two completely different findings now
surface from the same Gradle task: *Node is missing* (runner, nothing to do with the product) and
*a WCAG contrast pairing regressed* (code, a real accessibility defect that should block). The
generator prints `N pairings checked, M regression(s)` on a successful run, so that line is the
discriminator — **if it is present, the toolchain worked and you are looking at a real finding.**
Worth knowing that an accessibility regression can now fail an Android build at all; that is a
deliberate property of generating tokens rather than vendoring them, not a side effect.

**One-line bisect.** If a failure is ambiguous, flip `runs-on: ubuntu-latest` → `windows-latest` on
the `android` job in `.github/workflows/ci.yml` and re-run. Windows passes ⇒ runner. Windows also
fails ⇒ code. `workflow_dispatch` is enabled on both workflows, so the re-run is a click.

**Why we flipped anyway,** since the safe-looking option was to stay on Windows until a run was
observed: staying would not have preserved a proven toolchain, because the proven toolchain is a
*local dev machine*, not GitHub's `windows-latest` — which has no `local.properties` either and is a
far less-trodden path for Android CI than `ubuntu-latest`. The real choice was between two unproven
hosted images, and Ubuntu is the better-trodden one, at half the billed minutes, on the same OS
family as the Gitea runner everything migrates to. Staying on Windows would have bought a feeling of
control rather than control, and would have required a second unobserved experiment later anyway.

---

## 6. SECRETS

**None. Zero. Neither workflow reads a secret, and that is a design constraint, not a coincidence.**

* `permissions: contents: read` on the GitHub workflows. Checkout uses the auto-provided ephemeral
  `GITHUB_TOKEN`; nothing else authenticates to anything.
* No signing key. `:android:assembleRelease` produces an **unsigned/debug-signed** artifact, which is
  sufficient for the `0xFDA9` artifact scan and for nothing else. Release signing does **not** get
  wired into GitHub — that is the line in §2 that moves CI to Gitea.
* When secrets are eventually needed (registry push, deploy, signing): **SOPS + age**, decrypted on
  a self-hosted runner that holds the age key in its own filesystem, provisioned by Ansible.
  OpenBao at P2. Never a repo file, never a CI provider's secret store as the source of truth.
* `BATTERY_RESULTS_JSON` is a *path*, not a credential. When the hardware rig exists it will publish
  results to a path the runner can read.

---

## 7. COST

**$0/mo today.** No infrastructure is provisioned (B3). GitHub-hosted runners are free on public
repos; on a **private** repo Linux bills 1x and **Windows 2x**, against the plan's free monthly
minutes. **Every job is now `ubuntu-latest` (1x)** — the `android` job was on Windows only while
B12 was open, and closing it halved this line item before a single minute was ever billed.

Mitigations already in place: `concurrency` cancels superseded runs; path filters keep doc-only
pushes from triggering a build; `cache: gradle` avoids cold dependency downloads.

Escalation thresholds (unchanged, root brief): >$500/mo total, >2x baseline, or any new paid
service ⇒ human before committing. None are close.

> **Path-filter trap, for whoever enables branch protection.** These workflows use `paths:` filters.
> A PR touching only `docs/` never triggers the `android` job, so a *required* status check of that
> name would block the PR forever waiting for a run that will never start. Either do not mark
> path-filtered jobs required, or drop the filters on the required ones and let them run and
> no-op. Left as-is for now because Phase 0 has no branch protection — but it is a 3am-shaped
> problem, so it is written down before it happens rather than after.
