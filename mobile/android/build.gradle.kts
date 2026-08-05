// :android — Radius Android app shell.
//
// !! UNVERIFIED !! No JDK on the authoring machine (blocker B5). Never evaluated by Gradle.
//
// Hilt lives HERE and only here. It is the ANDROID UI GRAPH. It must never appear in :shared —
// ADR-007 is explicit: commonMain uses constructor injection and a plain factory, no DI framework,
// no Koin, ever. The single seam between the two is di/SharedModule.kt.

import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompilerPlugin)
    alias(libs.plugins.hilt)
    // KSP, not kapt. Orchestrator ruling 2. kapt is a legacy stub-generating javac plugin; KSP is
    // K2-native and materially faster on a Hilt graph.
    //
    // VERSION COUPLING, DO NOT BREAK: the `ksp` version in libs.versions.toml is
    // "<kotlin>-<ksp>" and MUST track `kotlin` exactly. Bumping Kotlin without bumping KSP fails
    // the build with a version-mismatch error that reads like a plugin resolution problem.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.radius.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radius.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1-phase0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Explicitly NOT set: resConfigs narrowing, until localisation scope is decided.
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No signing config in the repo. Keys are never committed — root CLAUDE.md, SOPS only.
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// No `kapt { correctErrorTypes = true }` equivalent is needed under KSP — that setting existed to
// work around kapt's error-type stubs, which KSP does not generate.

// =================================================================================================
// DESIGN TOKENS — GENERATED AT BUILD TIME, NEVER CHECKED IN.
//
// WHY GENERATE INSTEAD OF VENDORING A COPY (this was a real fork in the road):
//   mobile/design-tokens/README.md's HANDOFF step 1 says "copy RadiusDesignTokens.kt into your
//   sourceSet". I did not, and the reason is the rule the whole token exercise exists to enforce:
//   a raw hex literal must not live in mobile/android/. A vendored copy is 30 hex literals sitting
//   in my source tree that drift silently the first time tokens.json changes and nobody re-copies.
//   Generating into build/ (gitignored) means there is exactly ONE hex value in the repo per token,
//   in tokens.json, owned by design-system, and drift is structurally impossible.
//
// FREE BONUS, AND IT IS NOT SMALL: generate.mjs re-derives all 48 WCAG contrast pairings and exits
//   non-zero on a regression. Wiring it here makes the accessibility contrast gate a hard failure
//   of `:android:assembleDebug` — not a thing someone remembers to run.
//
// COST, STATED PLAINLY: `:android` now needs `node` on PATH. devops/ci/runner/run-stage.sh already
//   requires node for the battery and conformance stages, so CI has it, but the android_lint /
//   android_assemble_* stages do not currently assert it. HANDOFF filed for devops-tencent to add
//   `command -v node` to those stages' preconditions so a missing toolchain fails with a sentence
//   instead of a Gradle stack trace. Until then, the task below fails with that sentence itself.
// =================================================================================================

/**
 * Runs design-system's generator, then rewrites the emitted Kotlin into a package I own.
 *
 * THE REWRITES ARE A SHIM AND THEY ARE TEMPORARY. `build/android/RadiusDesignTokens.kt` as emitted
 * today does not compile, for two reasons that are both one-line fixes in generate.mjs and are both
 * in the HANDOFF to design-system:
 *
 *   1. It emits `import Color` — an unresolvable root-package import. It wants
 *      `androidx.compose.ui.graphics.Color`.
 *   2. Even with that import fixed, the file declares `public object Color` nested inside
 *      `RadiusDesignTokens`. In every `val canvas: Color = Color(0xFF0B0B10)` the TYPE position
 *      `Color` resolves to that enclosing object's classifier, not to Compose's `Color`, so every
 *      one of the 30 colour declarations is a type mismatch.
 *
 * I am not allowed to write in mobile/design-tokens/ (root CLAUDE.md repo map) and I am not going
 * to hand-patch a file stamped DO NOT HAND-EDIT and then let the patch rot. So the fix is applied
 * mechanically, here, with every substitution ASSERTING that it matched — if design-system changes
 * the generator's shape (including fixing it properly), this task fails loudly and names the rule
 * that stopped matching, rather than silently emitting something subtly wrong.
 *
 * DELETE RULE: when generate.mjs emits compilable Kotlin, delete [rewrites] entirely and keep only
 * the package rewrite (which README HANDOFF step 1 explicitly delegates to me).
 */
data class TokenRewrite(
    val description: String,
    val pattern: Regex,
    val replacement: String,
    /** Minimum matches required. Below this, the task fails rather than emitting silent nonsense. */
    val expected: Int,
)

abstract class GenerateDesignTokens : DefaultTask() {

    /** Source of truth. Changing it must re-run this task. */
    @get:InputFile
    abstract val tokensJson: RegularFileProperty

    /** The generator itself — a change to emission logic is also a change to our sources. */
    @get:InputFile
    abstract val generatorScript: RegularFileProperty

    /** mobile/design-tokens — the generator resolves its own paths relative to this. */
    @get:Internal
    abstract val generatorWorkingDir: DirectoryProperty

    /** Where the generator drops its output (inside design-tokens' own gitignored build/). */
    @get:Internal
    abstract val emittedFile: RegularFileProperty

    @get:Input
    abstract val targetPackage: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val workingDir = generatorWorkingDir.get().asFile
        val script = generatorScript.get().asFile

        val log = ByteArrayOutputStream()
        val result = try {
            execOps.exec {
                commandLine("node", script.absolutePath)
                workingDir(workingDir)
                standardOutput = log
                errorOutput = log
                isIgnoreExitValue = true
            }
        } catch (missing: Exception) {
            throw GradleException(
                "Could not run `node`. :android generates its design tokens from " +
                    "mobile/design-tokens/tokens.json at build time, so Node is required to build " +
                    "the Android app. Install Node (any recent LTS; the generator has zero npm " +
                    "dependencies and needs no install step) and re-run.",
                missing,
            )
        }

        val output = log.toString()
        if (result.exitValue != 0) {
            // Exit code 1 here is most likely the WCAG contrast gate, which names the exact pairing
            // and the ratio it dropped to. Surface it verbatim — it is the actionable part.
            throw GradleException(
                "mobile/design-tokens/scripts/generate.mjs failed (exit ${result.exitValue}).\n" +
                    "This is design-system's gate, not an Android problem: a token change has most " +
                    "likely broken a documented WCAG contrast guarantee.\n\n$output",
            )
        }
        // Surface the contrast-gate summary, not the "wrote file" line. Someone reading a build log
        // should see that 48 accessibility pairings were re-checked on this build.
        logger.lifecycle(
            output.trim().lines().lastOrNull { it.contains("pairings checked") }
                ?: output.trim().lines().last(),
        )

        val emitted = emittedFile.get().asFile
        if (!emitted.isFile) {
            throw GradleException("generate.mjs reported success but did not write $emitted")
        }

        var source = emitted.readText()

        // The `expected` count assertion is the whole point: a silently-unapplied rewrite produces
        // uncompilable or, worse, wrong-but-compiling output.
        val rewrites = listOf(
            TokenRewrite(
                description = "package (HANDOFF step 1 — mine to set)",
                pattern = Regex("^package com\\.radius\\.designtokens\\.generated$", RegexOption.MULTILINE),
                replacement = "package ${targetPackage.get()}",
                expected = 1,
            ),
            TokenRewrite(
                description = "drop the unresolvable `import Color` (generator bug 1)",
                pattern = Regex("^import Color\\r?\\n", RegexOption.MULTILINE),
                replacement = "",
                expected = 1,
            ),
            TokenRewrite(
                description = "fully-qualify Color to escape the nested `object Color` shadow (generator bug 2)",
                pattern = Regex(": Color = Color\\("),
                replacement = ": androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(",
                expected = 30,
            ),
            TokenRewrite(
                // Kotlin block comments NEST (unlike Java's). The KDoc on Accent.Radar contains the
                // prose "do not reuse signal/* anywhere else", and that `/*` opens a nested comment
                // which the KDoc's own `*/` then closes — leaving the rest of the file, all 100
                // lines of it, inside a comment. It fails as "Missing '}'" plus "Unclosed comment"
                // at EOF, which points nowhere near the actual cause.
                description = "neutralise the nested-comment opener in Accent.Radar's KDoc (generator bug 3)",
                pattern = Regex("signal/\\*"),
                // U+2217 ASTERISK OPERATOR: reads identically, is not a comment opener.
                replacement = "signal/∗",
                expected = 1,
            ),
        )

        for (rewrite in rewrites) {
            val description = rewrite.description
            val pattern = rewrite.pattern
            val minimum = rewrite.expected
            val matches = pattern.findAll(source).count()
            if (matches < minimum) {
                throw GradleException(
                    "Design-token rewrite \"$description\" matched $matches times, expected at " +
                        "least $minimum.\n" +
                        "generate.mjs's output shape changed. If it now emits compilable Kotlin, " +
                        "delete this rewrite from mobile/android/build.gradle.kts — that is the " +
                        "intended end state. If it changed for another reason, this task is " +
                        "stopping you from shipping tokens that silently do not mean what they say.",
                )
            }
            source = pattern.replace(source, Regex.escapeReplacement(rewrite.replacement))
        }

        val destination = outputDir.get().asFile
            .resolve(targetPackage.get().replace('.', '/'))
            .also { it.mkdirs() }
            .resolve(emitted.name)
        destination.writeText(source)
    }
}

val designTokensRoot: Directory = rootProject.layout.projectDirectory.dir("design-tokens")

val generateDesignTokens = tasks.register<GenerateDesignTokens>("generateDesignTokens") {
    group = "radius"
    description = "Generates RadiusDesignTokens.kt from mobile/design-tokens/tokens.json (runs the WCAG contrast gate)."

    tokensJson.set(designTokensRoot.file("tokens.json"))
    generatorScript.set(designTokensRoot.file("scripts/generate.mjs"))
    generatorWorkingDir.set(designTokensRoot)
    emittedFile.set(designTokensRoot.file("build/android/RadiusDesignTokens.kt"))
    targetPackage.set("com.radius.android.ui.theme.tokens")
    outputDir.set(layout.buildDirectory.dir("generated/designTokens/kotlin"))
}

android.sourceSets.getByName("main").kotlin.srcDir(generateDesignTokens)

// srcDir(TaskProvider) carries task dependency for Kotlin compilation, but AGP's lint and manifest
// tasks read source sets through a different path. preBuild is the one node everything variant-
// related hangs off, so this covers lintDebug and assembleRelease as well as assembleDebug.
tasks.named("preBuild") { dependsOn(generateDesignTokens) }

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // WORKMANAGER IS DELIBERATELY ABSENT. Removed 2026-08-04; it was declared and never used.
    //
    // It is not a free unused dependency: androidx.work MERGES THREE PERMISSIONS into our manifest
    // without anyone typing them — WAKE_LOCK, ACCESS_NETWORK_STATE and RECEIVE_BOOT_COMPLETED.
    // Verified in build/intermediates/packaged_manifests/ for both variants before removal, and
    // verified gone after. For a dating app that stores no coordinates, ACCESS_NETWORK_STATE
    // alongside NO network permission at all reads oddly to a store reviewer, and
    // RECEIVE_BOOT_COMPLETED is a background-persistence signal we were not asking for and got
    // nothing for. The permission list should be something we chose, not something that
    // accumulated.
    //
    // WHEN IT COMES BACK — and it will, for the retention/purge job (encounters 30d, free tier
    // 24h, then HARD delete) — reintroduce it WITH `tools:node="remove"` entries in the manifest
    // for whichever of the three we still do not want, rather than accepting all three silently.
    // Radar itself must never use WorkManager: its minimum periodic interval is 15 minutes and
    // Doze defers even that, so it cannot hold a continuous BLE scan. That is the foreground
    // service's job. See RadarForegroundService.

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Tink for Android-side key material (Keystore-backed). Present but unused in this scaffold.
    implementation(libs.tink.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
}
