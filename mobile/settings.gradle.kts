// Radius mobile — Gradle settings
//
// Layout (ADR-007, Kotlin Multiplatform shared core + native UI):
//   :shared    KMP core. commonMain + androidMain + iosMain. owner: android-kotlin
//              (mobile/shared/protocol/ carved out to ble-protocol)
//   :android   Compose app shell. owner: android-kotlin
//
// mobile/ios/ is NOT a Gradle module. It is an Xcode project (Tuist) that consumes the
// XCFramework produced by :shared. Owner: ios-swift.
//
// !! Kotlin/Native iOS targets require macOS. On Windows/Linux the :shared iOS targets
//    will not configure. Blocker B4.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "radius-mobile"

include(":shared")
include(":android")
