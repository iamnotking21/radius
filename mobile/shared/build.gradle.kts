// :shared — Radius KMP core (ADR-007).
//
// !! UNVERIFIED !! There is no JDK on the authoring machine (blocker B5). This file has never
// been evaluated by Gradle. Nothing here may be cited as "building" until `./gradlew :shared:build`
// runs green on a machine with JDK 17+.
//
// PUBLIC API OF THIS MODULE IS A CONTRACT (.claude/ORCHESTRATION.md §3, ADR-007).
// Two consumers: mobile/android (Compose) and mobile/ios (SwiftUI, via XCFramework).
// It does not change unilaterally. explicitApi() below is deliberate — it makes every widening
// of the contract a visible, reviewable diff instead of an accident.
//
// mobile/shared/protocol/ is NOT ours. It belongs to ble-protocol and is absent on purpose.
// Do not create it here.

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

// ---------------------------------------------------------------------------------------------
// BLOCKER B4 WORKAROUND — READ THIS BEFORE YOU COPY IT.
//
// Kotlin/Native iOS targets can only be *configured and compiled on macOS*. On Windows/Linux the
// iosArm64 / iosSimulatorArm64 targets cannot produce a framework at all. This guard exists so
// that `:android` remains buildable on the founder's Windows machine during Phase 0.
//
// THIS IS NOT A SUBSTITUTE FOR A MAC. It does not make iOS build on Windows. It only prevents an
// Android-only build from failing at configuration time. The moment the shared core carries real
// logic, an iOS build on real Apple hardware is the ONLY thing that proves the two clients share
// one implementation — which is the entire point of ADR-007. A Windows-green build proves nothing
// about iOS. See 60-blockers B4. Mac is mandatory infrastructure, not a preference.
//
// CI MUST run the macOS job. If the macOS runner is ever "temporarily disabled", the iOS half of
// this module is unverified and any parity claim is false.
// ---------------------------------------------------------------------------------------------
val hostOs: String = providers.systemProperty("os.name").getOrElse("")
val isMacHost: Boolean = hostOs.startsWith("Mac")

if (!isMacHost) {
    logger.warn(
        "\n[:shared] iOS targets SKIPPED — host is '$hostOs', not macOS (blocker B4).\n" +
            "[:shared] The RadiusShared XCFramework was NOT built. iOS is UNVERIFIED.\n" +
            "[:shared] Do not report parity between platforms from this build.\n"
    )
}

kotlin {
    // Contract module: every public declaration must state its visibility and return type.
    explicitApi()

    compilerOptions {
        // expect/actual *classes* are still flagged Beta by the compiler. We use them for the
        // radio layer on purpose (ADR-007: radio is written twice behind expect/actual).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    if (isMacHost) {
        // Static framework: ADR-007 wants one binary artefact Xcode links directly. Static avoids
        // the dynamic-framework embed/sign dance and starts faster; the size cost is accepted.
        val xcf = XCFramework("RadiusShared")
        listOf(
            iosArm64(),
            iosSimulatorArm64(),
            // iosX64 intentionally absent — only add it if an Intel Mac joins the fleet (10-stack).
        ).forEach { iosTarget ->
            iosTarget.binaries.framework {
                baseName = "RadiusShared"
                isStatic = true
                xcf.add(this)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.core)
            // Persistence is shared/'s job (ADR-007). SQLDelight only — no ORM, no Room, no GRDB.
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // NOTE: no HTTP client here yet. The catalog marks wire+ktor "provisional pending
            // founder sign-off" (a new dependency = ORCHESTRATION §8 escalation). The Connect-RPC
            // client lands only after that decision. Do not add it to unblock yourself.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotest.assertions)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.sqldelight.driver.android)
            // Local DB is encrypted at rest (mobile/CLAUDE.md). Declared now so the dependency is
            // visible in review; the driver wiring itself is not written yet.
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
        }

        androidUnitTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            // Robolectric is deliberately absent: BLE on a JVM/Robolectric harness is worthless
            // (root CLAUDE.md "simulator BLE test = worthless"). Radio is tested on real hardware.
        }

        if (isMacHost) {
            iosMain.dependencies {
                implementation(libs.sqldelight.driver.native)
            }
            iosTest.dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.radius.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("RadiusDatabase") {
            packageName.set("com.radius.shared.db")
            // Schema source: src/commonMain/sqldelight/**.sq — currently empty by design.
            // Tables land with the persistence task, not with this scaffold.
            // Raw SQL only. If anyone proposes an ORM here, point them at root CLAUDE.md BANNED.
        }
    }
}
