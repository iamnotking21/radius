# 60 · BLOCKERS & RISKS (live)
<!-- agent hits blocker → add row + notify orchestrator. orchestrator clears. -->

## OPEN
| id | raised | by | blocker | blocks | owner | status |
|---|---|---|---|---|---|---|
| B1 | 2026-08-04 | orchestrator | BLE spike unrun — iOS bg viability unknown | EVERYTHING | ble-protocol | open |
| B2 | 2026-08-04 | orchestrator | 0/2 BLE specialists hired. long pole. | phase1 | founder | open |
| B3 | 2026-08-04 | orchestrator | Tencent acct/region not provisioned | all infra | devops-tencent | open |
| B4 | 2026-08-04 | orchestrator | NO MAC. Kotlin/Native iOS targets compile on macOS ONLY (ADR-007). no Windows/Linux path exists. | ALL iOS, forever | founder | open |
| B5 | 2026-08-04 | orchestrator | no JDK17+ on dev machine. Gradle cannot run ⇒ nothing Kotlin builds, KMP scaffold UNVERIFIED | all mobile | founder | **CLOSED 2026-08-04** — Temurin JDK21 + wrapper 8.13. :shared + :android compile, APK builds, 9 tests pass, lint 0 errors. Gradle self-installed SDK platform 35. |
| B6 | 2026-08-04 | orchestrator | no Go1.23+ toolchain on dev machine | all backend | founder | open |
| B7 | 2026-08-04 | ios-swift + android-kotlin (INDEPENDENT CONVERGENCE) | iOS CBPeripheralManager honours ONLY localName + serviceUUIDs keys. service-data and mfr-data silently dropped — FOREGROUND TOO, not just bg. ⇒ v0 payload [ver][eid:16][txpower][flags] is UNTRANSMITTABLE from iPhone in EVERY state. UUID-smuggling workaround fits the 16B eid exactly but leaves ver/txpower/flags homeless ⇒ different wire format, and txpower loss degrades invariant-2 banding. | BLE spec v0, B1 go/no-go, the offline moat itself | ble-protocol | open |
| B11 | 2026-08-04 | qa-test | LIVE decision-34 violation, present in the tree NOW: Advertisement.kt:66 `public const val SERVICE_UUID16: Int = 0xFDA9` unguarded on the RELEASE path. caught by the new gate on its first real run. must be build-flavour-guarded or the release gate stays red — which is the gate working, not a gate bug. | release builds | ble-protocol | open |
| B9 | 2026-08-04 | ble-protocol | service UUID 0xFDA9 is PROVISIONAL, not SIG-allocated, MUST NOT SHIP. needs Bluetooth SIG Adopter membership + 16-bit UUID allocation. weeks of lead time, on the SHIPPING critical path, NOBODY ASSIGNED. | ship | founder | open |
| B10 | 2026-08-04 | ble-protocol | account_key provenance unresolved. PLAN §6.2 says server-issued, ADR-004 silent, BLE spec assumed device-generated. server-held ⇒ server can derive EVERY eid a user will ever broadcast. needs ADR + founder call. | key schedule, privacy claims | founder | open |
| B12 | 2026-08-04 | devops-tencent | HANDOFF-1: `devops/ci/gates/conformance_gate.sh` phase 2 invokes `./gradlew.bat` — a Windows-only entrypoint — while its own workflow header specifies a Linux runner. Cannot execute on the self-hosted Gitea runner. qa-test's carve-out, not devops-tencent's to edit. WORKAROUND IN PLACE: the GitHub `android` job is forced onto `windows-latest` (also the toolchain everything was verified green on, but 2x billed minutes on a private repo). FIX: make the wrapper call OS-aware (3-line reference impl: `gradle_wrapper()` in `devops/ci/runner/run-stage.sh`); then flip one line, `runs-on: windows-latest` -> `ubuntu-latest`, and the Gitea conformance stage starts working at the same time. `run-stage.sh` detects this exact condition and prints the reason rather than a bare "no such file"; the detection stops matching by itself once fixed. | conformance gate on Linux/Gitea; CI cost | qa-test | **CLOSED (fix side) 2026-08-04** — `qa_gradle_wrapper()` added to `devops/ci/gates/lib/common.sh`, same 3-line `uname`-branch rule as `run-stage.sh`'s `gradle_wrapper()` (reused the pattern, did not source their file). `conformance_gate.sh` phase 2 now calls it instead of hardcoding `./gradlew.bat`. Verified green on Windows (the only machine that can run it today): phase 1 + phase 2 PASS, 116 vectors live. Linux path reasoned through, not executed — see qa-test's handoff to devops-tencent for what remains unproven. **REMAINING HALF OF B12 is devops-tencent's**: flip `runs-on: windows-latest` -> `ubuntu-latest` in `.github/workflows/ci.yml`'s `android` job. |
| B8 | 2026-08-04 | android-kotlin | Android apps get NO control over RPA rotation. only lever = stop→start advertising; whether that forces a fresh MAC is CHIPSET-DEPENDENT. invariant 5 ("both or neither") may be unsatisfiable ⇒ device linkable across eid rotations, the exact attack inv5 exists to stop. needs a sniffer per OEM, not a docs read. | invariant 5, B1 go/no-go | ble-protocol + qa-test | open |

## STANDING RISKS (never "closed", always managed)
R1 CRIT · iOS bg BLE limits ⇒ Radar feels broken.
   mitigate: foreground-destination framing · state restoration · iOS↔iOS strong path ·
   honest copy. NEVER promise continuous bg discovery.
R2 CRIT · cold-start density. empty radar = dead product.
   mitigate: 1 district launch · venue partnerships · Discover carries product till density.
R3 CRIT · real-world safety incident.
   mitigate: mandatory verification · protocol-level block · blackout zones ·
   incident runbook + legal retained BEFORE launch.
R4 HIGH · battery drain ⇒ uninstall. mitigate: adaptive duty cycle + CI battery gate.
R5 MED (was HIGH) · codebase divergence. ADR-007 KMP removes the dangerous half — codec,
   key schedule, ratchet now have ONE impl. residual risk is UI + radio layer only.
   mitigate: conformance vectors (kept as regression net) + weekly parity review.
R12 MED · new, from ADR-007 · KMP interop tax: Swift↔Kotlin/Native debugging, build times,
   binary size, and iOS engineers needing Kotlin fluency. mitigate: keep shared API small and
   value-type-only across the boundary; no Kotlin coroutines Flow leaking raw into Swift.
R6 HIGH · store rejection (dating scrutiny). mitigate: early TestFlight, 18+, privacy labels.
R7 MED · self-host burden > 1 SRE. mitigate: containers+tofu, documented escape to managed.
R9 HIGH · calling abuse (exposure, harassment on video). mitigate: invited-not-rung ·
   1-tap in-call safety · instant end · blur+camera-off · report-driven enforcement,
   repeat call reports weighted heavily.
R10 MED · TURN egress cost spike. mitigate: monitored egress budget + alert · voice default ·
   800kbps video cap · aggressive P2P preference.
R11 HIGH · regulatory action on subscription practices. mitigate: zero dark patterns by
   construction · 2-tap cancel · renewal disclosure above fold · refund rate as a
   first-class metric. FTC enforces under ROSCA today; EU DFA draft Q3/Q4 2026.
R8 MED · E2EE vs moderation. mitigate: client-side report attaches plaintext WITH consent.
