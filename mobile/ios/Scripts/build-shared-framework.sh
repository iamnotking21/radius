#!/usr/bin/env bash
#
# Radius iOS — build the Kotlin shared core and stage its XCFramework for Xcode.
#
# !! UNVERIFIED !!  Written on Windows. Never executed. No Mac, no JDK (blockers B4, B5).
#
# Called two ways:
#   1. By the "Build RadiusShared (Gradle)" pre-build phase in Project.swift, on EVERY build.
#   2. By hand, once, before the very first `tuist generate` — because Tuist evaluates the
#      manifest (and therefore resolves the .xcframework path) before any build phase runs.
#
# Why a pre-build phase at all: without it Xcode links whatever RadiusShared.xcframework
# happens to be on disk. A silently stale shared core means the BLE codec, key schedule and
# ratchet running on the device are not the ones in the repo. That is a security bug that
# looks like nothing until it isn't. Fail loudly instead.

set -euo pipefail

FRAMEWORK_NAME="RadiusShared"

IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MOBILE_DIR="$(cd "${IOS_DIR}/.." && pwd)"
DEST_DIR="${IOS_DIR}/Frameworks"
DEST="${DEST_DIR}/${FRAMEWORK_NAME}.xcframework"

log()  { echo "note: [shared] $*"; }
fail() { echo "error: [shared] $*" >&2; exit 1; }

# --- 0. platform gate -------------------------------------------------------------------
# ADR-007: Kotlin/Native iOS targets compile on macOS ONLY. There is no Windows or Linux
# path. This is blocker B4 and it is not a configuration problem to be worked around.
if [ "$(uname -s)" != "Darwin" ]; then
    fail "iOS builds require macOS. Kotlin/Native iOS targets do not build on $(uname -s). Blocker B4."
fi

# --- 1. toolchain gate ------------------------------------------------------------------
# Xcode run-script phases get a minimal environment: no shell profile, often no JAVA_HOME,
# and a PATH that does not include Homebrew. Diagnose that here rather than letting Gradle
# emit something unreadable three hundred lines later.
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
    fail "no java on PATH and JAVA_HOME unset. Xcode run-script phases do not read your shell profile. Set JAVA_HOME in the scheme's environment, or in ~/.gradle/gradle.properties via org.gradle.java.home."
fi

GRADLEW="${MOBILE_DIR}/gradlew"
[ -x "${GRADLEW}" ] || fail "missing or non-executable Gradle wrapper at ${GRADLEW}. The wrapper has not been generated yet (mobile/ has no gradle/wrapper/ — blocker B5)."

# --- 2. map Xcode configuration -> Gradle variant ---------------------------------------
# CONFIGURATION is set by Xcode; default to Debug when invoked by hand.
case "${CONFIGURATION:-Debug}" in
    Release*|release*) VARIANT="release"; VARIANT_TASK="Release" ;;
    *)                 VARIANT="debug";   VARIANT_TASK="Debug"   ;;
esac

# ASSUMPTION — the shared module does not exist yet, so this task name is a proposal.
# The Kotlin Multiplatform `XCFramework()` helper generates
#   assemble<BaseName><Debug|Release>XCFramework
# only when the framework baseName is "RadiusShared" and the XCFramework holder is wired in
# mobile/shared/build.gradle.kts. If android-kotlin names it differently, this breaks here,
# loudly, which is the intended behaviour. See "Assumptions" in README.md.
GRADLE_TASK=":shared:assemble${FRAMEWORK_NAME}${VARIANT_TASK}XCFramework"

# --- 3. build ---------------------------------------------------------------------------
log "gradle ${GRADLE_TASK}"
"${GRADLEW}" --project-dir "${MOBILE_DIR}" "${GRADLE_TASK}"

# ASSUMPTION — default XCFramework output location for KMP.
SRC="${MOBILE_DIR}/shared/build/XCFrameworks/${VARIANT}/${FRAMEWORK_NAME}.xcframework"
[ -d "${SRC}" ] || fail "gradle reported success but produced no framework at ${SRC}. Either the task name or the output path assumption is wrong — do NOT 'fix' this by linking an older copy."

# --- 4. stage -----------------------------------------------------------------------------
# Replace wholesale. Never merge into an existing bundle: a leftover slice from a previous
# architecture or variant is exactly the stale-link failure this script exists to prevent.
mkdir -p "${DEST_DIR}"
rm -rf "${DEST}"
cp -R "${SRC}" "${DEST}"

log "staged ${VARIANT} ${FRAMEWORK_NAME}.xcframework -> ${DEST}"
