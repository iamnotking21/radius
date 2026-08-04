package com.radius.shared.ble

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * iOS-side placeholder.
 *
 * !! NEVER COMPILED, NEVER RUN !! Kotlin/Native iOS targets build on macOS only (blocker B4).
 *
 * There is deliberately almost nothing here. A simulator cannot do BLE — root CLAUDE.md is explicit
 * that a simulator BLE result is invalid and must never be reported as a pass. The iOS radio is
 * proven on a physical iPhone alongside a physical Android handset on radio day, or not at all.
 *
 * What SHOULD eventually live in this source set: the shared-core conformance-vector run
 * (mobile/protocol/vectors/*.json) executed on the Kotlin/Native target, proving the iOS build of
 * the codec is byte-identical to the JVM build. That is the regression net ADR-007 keeps.
 */
class IosRadioContractTest {

    @Test
    fun placeholder_until_conformance_vectors_exist() {
        // mobile/protocol/ does not exist yet (ble-protocol owns it, NEXT-6 item 2).
        assertTrue(true, "replace with the conformance vector run")
    }
}
