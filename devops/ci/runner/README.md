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

> **ADR PROPOSAL — for orchestrator.** `docs/adr/` is orchestrator-only and this is a decision with
> a stack-document contradiction in it, so it is proposed here rather than written there:
> *"ADR-00X — Interim GitHub Actions for CI while B3 is open."* Context: gates exist, nothing runs
> them, no Gitea instance exists. Decision: dual-target CI, all logic in-repo, GitHub interim and
> secret-free, Gitea the destination, migrate when B3 closes or the moment CI needs a secret —
> whichever is first. Consequence: root `CLAUDE.md`'s "CI: Gitea Actions self-host" needs a
> parenthetical, and the REPO MAP needs a line assigning `.github/` and `.gitea/` to devops-tencent
> (workflow discovery is inherently the runner's job, but it is currently unowned).

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

This cannot hide a broken gate script: `gate-selftests` runs qa-test's 39-assertion self-test suite
against synthetic fixtures, fully blocking, on every path.

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
| Node | 20+ | `conformance_gate.sh` phase 1, `battery_gate.sh` threshold check |
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
```

On the Windows dev machine, export the toolchain first — the shell does not inherit it:

```bash
export JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
export PATH="$JAVA_HOME/bin:$PATH"
```

---

## 5. OPEN HANDOFFS

**HANDOFF-1 → qa-test (blocks the Linux/Gitea conformance stage).**
`devops/ci/gates/conformance_gate.sh` phase 2 invokes `./gradlew.bat`, a Windows-only entrypoint, on
a script whose own workflow header specifies a Linux runner. It cannot execute on the self-hosted
Linux runner. That file is in the qa-test carve-out and is not ours to edit.

Consequences and the fix:

* Interim: the GitHub `android` job runs on `windows-latest`. That is also the exact toolchain the
  whole Android path was verified green on, so it is not a bad interim — but it is a *forced* choice
  and it costs 2x billed minutes on a private repo.
* Fix: make the wrapper call OS-aware (`run-stage.sh`'s `gradle_wrapper()` is a three-line
  reference implementation). Then flip `runs-on: windows-latest` → `ubuntu-latest` in
  `.github/workflows/ci.yml` — one line — and `.gitea/workflows/ci.yml` starts working at the
  same time.
* `run-stage.sh` detects this exact condition and prints the reason rather than a bare "no such
  file". The detection stops matching by itself once the gate is fixed; nothing to clean up.

**HANDOFF-2 → orchestrator.** `devops/ci/workflows/android.yml` is now superseded. It is qa-test's
file, so it has not been touched. Its job definitions were the input to `run-stage.sh`'s stage table
and its per-job comments carry real history worth keeping — but two workflow definitions for the
same gates is the drift risk this whole layering exists to eliminate. It should be reduced to a
pointer, by its owner.

**HANDOFF-3 → orchestrator.** REPO MAP has no owner for `.github/` or `.gitea/`. Claimed here as
runner territory; needs a line in root `CLAUDE.md` to be real.

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
minutes. The `android` job is the only expensive one, and it is on Windows solely because of
HANDOFF-1 — closing that handoff halves this line item.

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
