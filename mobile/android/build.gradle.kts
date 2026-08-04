// :android — Radius Android app shell.
//
// !! UNVERIFIED !! No JDK on the authoring machine (blocker B5). Never evaluated by Gradle.
//
// Hilt lives HERE and only here. It is the ANDROID UI GRAPH. It must never appear in :shared —
// ADR-007 is explicit: commonMain uses constructor injection and a plain factory, no DI framework,
// no Koin, ever. The single seam between the two is di/SharedModule.kt.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
