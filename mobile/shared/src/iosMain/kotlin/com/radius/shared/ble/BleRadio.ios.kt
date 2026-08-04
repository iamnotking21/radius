package com.radius.shared.ble

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS radio adapter. **CONTAINS NO COREBLUETOOTH.** Deliberately.
 *
 * Orchestrator ruling, decision row 21: the iOS radio is a SWIFT PORT injected into Kotlin, not
 * Kotlin/Native calling CoreBluetooth. This file was rewritten to that shape; the previous version
 * did the opposite and was wrong.
 *
 * The argument, recorded so it is not relitigated: the radio is the moat, and it gets written
 * where the tooling is best. Swift gets Instruments Energy Log — which we will need to hit the
 * CI-gated <4%/hr scanning budget — a real debugger, and native `CBCentralManager` state
 * restoration. Kotlin/Native objc-interop delegates would have been the hardest-to-debug code in
 * the product, in the one place we cannot afford mystery. It also puts iOS state-restoration
 * ownership with ios-swift, where the platform knowledge is.
 *
 * ALL THIS CLASS DOES:
 *  - forwards five commands to [BleRadioPort], unchanged;
 *  - turns pushed [BleRadioListener] events into the `Flow`s that shared core consumes.
 *
 * WHAT THIS CHANGES ABOUT VERIFIABILITY: this file no longer contains a single unverified
 * CoreBluetooth symbol. Every delegate signature and enum name I could not check without a Mac has
 * moved to Swift, where ios-swift owns it and can compile it. This file is still UNVERIFIED — no
 * JDK (B5), and Kotlin/Native iOS targets need macOS (B4) — but it is now plain Kotlin with no
 * platform API surface, so the residual risk in it is close to zero.
 *
 * WHAT THIS CHANGES ABOUT THE RADIO'S CAPABILITIES: nothing. See the note at the foot of the file.
 */
public actual class BleRadio(
    private val port: BleRadioPort,
) {

    private val _availability = MutableStateFlow(RadioAvailability.UNKNOWN)
    public actual val availability: StateFlow<RadioAvailability> = _availability.asStateFlow()

    /**
     * Same buffering policy as the Android actual, for the same reason: in a crowded room the
     * radio emits faster than banding and persistence can consume, and dropping a superseded
     * sighting is correct — the peer re-advertises within a second. Blocking the caller is not an
     * option, because the caller is a CoreBluetooth delegate queue.
     */
    private val _sightings = MutableSharedFlow<RawSighting>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public actual val sightings: Flow<RawSighting> = _sightings.asSharedFlow()

    /**
     * `tryEmit` and `MutableStateFlow.value` are both thread-safe, which is what makes the
     * "may be called from any queue" clause in [BleRadioPort] safe to promise Swift.
     */
    private val listener = object : BleRadioListener {
        override fun onSighting(sighting: RawSighting) {
            _sightings.tryEmit(sighting)
        }

        override fun onAvailabilityChanged(availability: RadioAvailability) {
            _availability.value = availability
        }
    }

    init {
        port.attach(listener)
    }

    public actual fun startAdvertising(request: AdvertiseRequest): BleOutcome =
        port.startAdvertising(request)

    public actual fun stopAdvertising(): BleOutcome = port.stopAdvertising()

    public actual fun startScan(request: ScanRequest): BleOutcome = port.startScan(request)

    public actual fun stopScan(): BleOutcome = port.stopScan()

    /**
     * Delegates, and relies on the port to drop its listener reference — see the retain-cycle note
     * on [BleRadioPort]. Without that, this object leaks for the life of the process.
     */
    public actual fun shutdown() {
        port.shutdown()
    }
}

// ---------------------------------------------------------------------------------------------
// PHASE 0 FINDING — STILL STANDS. Blocker B7. Moving the radio to Swift changed WHO WRITES THE
// CODE, not WHAT THE RADIO CAN EMIT. Do not let the reassignment be mistaken for a resolution.
//
// 1. iOS CANNOT ADVERTISE SERVICE DATA. `CBPeripheralManager.startAdvertising(_:)` honours
//    exactly two keys — CBAdvertisementDataLocalNameKey and CBAdvertisementDataServiceUUIDsKey.
//    Everything else, service data and manufacturer data included, is silently discarded. The v0
//    wire payload [ver:1][ephemeral_id:16][txpower:1][flags:1] therefore cannot leave an iPhone as
//    specced, in the foreground or the background. A Swift implementation hits the identical wall.
//
//    Known workaround: carry the 16-byte ephemeral_id AS a 128-bit service UUID and scan for it.
//    It fits exactly, and leaves ver, txpower and flags with nowhere to live. It is a WIRE SPEC
//    change and therefore ble-protocol's call under contract-first law, not a fix for this file.
//
// 2. BACKGROUND ADVERTISING MOVES TO THE OVERFLOW AREA. A backgrounded iOS app's service UUIDs
//    are relocated to a special advertising area visible ONLY to another iOS device explicitly
//    scanning for that exact UUID. Android cannot see it at all. Backgrounded iOS→Android
//    discovery is not slow; it is absent. Standing risk R1, and the most likely source of a NO-GO.
//    Measure it on hardware. Do not argue about it.
//
// Reached independently by ios-swift and by me from opposite sides of the boundary, which is why
// the orchestrator is treating it as fact rather than hypothesis.
// ---------------------------------------------------------------------------------------------
