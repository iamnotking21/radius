// :android — Radius Android app shell.
//
// Evaluated and built by Gradle on JDK 21 (B5 unblocked). :android:assembleDebug,
// :android:testDebugUnitTest and :android:lintDebug all pass from a cold build/ dir.
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
// FREE BONUS, AND IT IS NOT SMALL: generate.mjs re-derives all 51 WCAG contrast pairings and exits
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
 * Runs design-system's generator, then sets the emitted file's package to one I own.
 *
 * HISTORY, KEPT SHORT BECAUSE IT ENDED WELL: this task once carried three extra rewrites patching
 * generate.mjs's output so it would compile at all — an unresolvable `import Color`, a nested
 * `object Color` that shadowed Compose's `Color` in all 30 type positions, and a block-comment
 * opener sitting in KDoc prose, which opened a NESTED comment and swallowed the back half of the
 * file. Each rewrite asserted its own match count, so the moment design-system fixed the generator
 * (alias `ComposeColor`, plus a `docSafe()` sanitiser on every doc comment) this task went RED on
 * good news and named the rewrite to delete. All three are now deleted. Do not re-add one: a shim
 * that comes back is just a patch with extra steps. If the emitted Kotlin stops compiling, that is
 * a generator bug and it goes back to design-system.
 *
 * AND DO NOT WRITE A LITERAL BLOCK-COMMENT OPENER IN THIS KDOC. I did, in the sentence above,
 * while describing that exact bug — design-system hit the identical trap writing its own note
 * about it. Kotlin nests block comments, so it silently commented out the rest of THIS file:
 * dependencies{} included. Gradle did not report a syntax error; it reported
 * "Hilt plugin applied but no hilt-android dependency found", because from its point of view the
 * dependencies block genuinely was not there. Spell it out in words instead.
 *
 * What remains is not a workaround. mobile/design-tokens/README.md's HANDOFF step 1 explicitly
 * delegates package placement to the consumer, and it still asserts, for the same reason as before:
 * if the generator's package line changes shape, I want a sentence naming it, not 30 unresolved
 * references pointing nowhere near the cause.
 */
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

        val emittedSource = emitted.readText()

        // HANDOFF step 1: the generator emits a placeholder package and the consumer places the
        // file. Asserted, not assumed — an unapplied rewrite compiles to nothing useful.
        val packageLine = Regex("^package com\\.radius\\.designtokens\\.generated$", RegexOption.MULTILINE)
        val matches = packageLine.findAll(emittedSource).count()
        if (matches != 1) {
            throw GradleException(
                "Expected exactly one `package com.radius.designtokens.generated` line in the " +
                    "generated tokens, found $matches.\n" +
                    "generate.mjs's output shape changed. mobile/design-tokens/README.md's HANDOFF " +
                    "step 1 delegates package placement to the consumer, so :android rewrites that " +
                    "line; it cannot if the line is not there. This is a design-system change — " +
                    "raise it, do not patch around it here.",
            )
        }
        val source = packageLine.replace(
            emittedSource,
            Regex.escapeReplacement("package ${targetPackage.get()}"),
        )

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
