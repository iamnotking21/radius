# ADR-009 · GitHub Actions as interim CI, Gitea as destination

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** orchestrator, on devops-tencent's recommendation
**Relates to:** ADR-003 (self-hosted OSS on Tencent), blocker B3

## Context

Six CI gates exist and pass 39 self-test assertions. None of them ran automatically. `qa-test` had written the workflow for Gitea Actions, but Gitea discovers workflows at `.gitea/workflows/` and `devops/ci/` is an ownership carve-out, so nothing picked it up.

Meanwhile the code is on GitHub, and no Gitea instance exists — B3, Tencent is unprovisioned and will stay that way for some time.

So the practical state was: **gates that are decorative**. That is worse than no gates, because it invites everyone — including future agents reading the repo — to believe checks are running when nothing is.

ADR-003 specifies self-hosted Gitea Actions, chosen to avoid third-party SaaS lock-in. Using GitHub Actions is a deviation from that and needs to be recorded rather than absorbed silently.

## Decision

**Both providers, one implementation. GitHub runs today; Gitea is the destination.**

Three layers, one writer each:

- **check logic** — `devops/ci/gates/*.sh`, owned by `qa-test`. Never reimplemented in a workflow.
- **stage definitions and the blocking split** — `devops/ci/runner/run-stage.sh`, owned by `devops-tencent`. One file.
- **workflow files** — `.github/workflows/` and `.gitea/workflows/`. Triggers, runner labels, checkout. **Zero commands.** Every `run:` is `bash devops/ci/runner/run-stage.sh <stage>`.

Drift between the two providers is structurally impossible: their files differ in exactly three places — runner labels, `setup-java` presence, and one handoff comment.

## Why this does not violate ADR-003

ADR-003 objects to two things: vendor lock-in, and a third party holding our users' data. Neither applies to a build runner executing our own bash against our own source.

No GitHub-proprietary surface is used: no environments, no OIDC, no deploy targets, no marketplace actions beyond `checkout` and `setup-java`. Permissions are `contents: read`. The release APK emerges unsigned. Deleting `.github/` costs two YAML files.

**The line that must not be crossed:** the day CI needs a signing key, a registry credential, or production access, it moves to Gitea **first**. That is the point at which a CI provider begins holding something that matters. This is written into the workflow headers and the runner README, not only here.

## The known-red treatment

Two gates are expected to fail indefinitely: the `0xFDA9` release gate (decision 59 — there is no real UUID to guard toward until SIG allocation) and the battery gate (no hardware in CI).

Marking them non-blocking would leave CI permanently red, which trains people to ignore it. Instead `run-stage.sh` **asserts the expected state**:

- Gate fails → expected. The full reason is written to the build summary on **every** run, so the signal is a sentence explaining what we are blocked on rather than an unexplained red mark. Stage passes.
- Gate passes → the world changed. Stage **fails**, with instructions to delete that one tolerance line.

CI goes red on good news, exactly once. This is deliberate: a tolerance that outlives its cause silently absorbs the next genuine reintroduction of `0xFDA9`, which is precisely the failure the gate exists to prevent.

This cannot mask a broken gate script, because `gate-selftests` — `qa-test`'s 39 assertions — blocks on every path.

## Consequences

**Good.** Gates are enforced today rather than after Tencent provisioning. One implementation of every check. Cost $0/month. No secrets anywhere in the repo.

**Bad / accepted.** Two workflow directories to keep in step, mitigated by both being command-free. A deviation from ADR-003 that must be actively unwound rather than forgotten — the Gitea files exist and are dormant precisely so that unwinding is a switch rather than a project.

**Known issue, logged as B12.** `conformance_gate.sh` hardcodes `./gradlew.bat`, a Windows-only entrypoint, while its own header specifies a Linux runner. The GitHub `android` job is therefore pinned to `windows-latest` — which is also the toolchain everything was verified green on, but costs 2× billed minutes on a private repo. The fix is a three-line OS-aware wrapper call in `qa-test`'s file; then one line flips to `ubuntu-latest` and the Gitea conformance stage starts working at the same time.

**One trap recorded before it bites:** path-filtered jobs combined with required status checks produce a PR blocked forever on a run that never starts.

**Reversibility:** Trivial. Delete `.github/`.

## Revisit when

Tencent is provisioned and a Gitea runner exists (B3), or CI needs any credential that matters — whichever comes first. The second condition is not negotiable.
