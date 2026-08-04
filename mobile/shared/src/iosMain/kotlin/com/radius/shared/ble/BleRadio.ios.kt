package com.radius.shared.ble

import com.radius.shared.protocol.AdvertiseRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

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
 * WHAT THIS CLASS DOES:
 *  - enforces the advertising-role gate (decision 35), in Kotlin, so Swift cannot forget it;
 *  - owns the 15-minute UTC epoch loop and the stop→re-derive→rebuild→start cycle
 *    (`KEY_SCHEDULE.md` §4.2), so the two platforms cannot rotate at different instants;
 *  - validates the frame length before anything reaches the radio;
 *  - forwards the resulting bytes to [BleRadioPort];
 *  - turns pushed [BleRadioListener] events into the `Flow`s that shared core consumes.
 *
 * !! UNVERIFIED, AND UNVERIFIABLE FROM THIS MACHINE !! Kotlin/Native iOS targets configure and
 * compile on macOS only (blocker B4), and the CI macOS job is what makes any statement about this
 * file true. Under android-first (decision 33) iOS is DEFERRED, so this file is kept structurally
 * correct and compiled by nobody. Treat every claim about it as owed, not paid.
 */
public actual class BleRadio(
    private val port: BleRadioPort,
) {

    private val radioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _availability = MutableStateFlow(RadioAvailability.UNKNOWN)
    public actual val availability: StateFlow<RadioAvailability> = _availability.asStateFlow()

    /** FAIL CLOSED, identically to Android. Decision 35 / `KEY_SCHEDULE.md` §9.3. */
    private val _advertiseRole = MutableStateFlow(AdvertiseRole.SCAN_ONLY)
    public actual val advertiseRole: StateFlow<AdvertiseRole> =
        _advertiseRole.asStateFlow()

    private val _advertiseState =
        MutableStateFlow(AdvertiseState(AdvertiseStatus.STOPPED, null, null, -1))
    public actual val advertiseState: StateFlow<AdvertiseState> = _advertiseState.asStateFlow()

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

    private var desiredAdvertise: AdvertiseRequest? = null
    private var epochJob: Job? = null
    private var epochBoundaryListener: EpochBoundaryListener? = null

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

    public actual fun setEpochBoundaryListener(listener: EpochBoundaryListener?) {
        epochBoundaryListener = listener
        syncEpochTicker()
    }

    public actual fun setAdvertiseRole(
        role: AdvertiseRole,
        source: AdvertiseRoleSource,
    ): BleOutcome {
        val previous = _advertiseRole.value
        _advertiseRole.value = role
        if (role == AdvertiseRole.SCAN_ONLY && previous != role) {
            // §9.3: the losing device stops IMMEDIATELY, not at the next boundary.
            stopAdvertising()
        }
        return BleOutcome.Ok
    }

    public actual fun startAdvertising(request: AdvertiseRequest): BleOutcome {
        // RULE 1. Same pure function the Android actual calls, so the two platforms cannot drift.
        (AdvertiseGuard.checkRole(_advertiseRole.value) as? BleOutcome.Rejected)
            ?.let { return rejected(it.reason, it.detail, AdvertiseStatus.STOPPED) }
        desiredAdvertise = request
        val outcome = applyAdvertising()
        syncEpochTicker()
        return outcome
    }

    public actual fun stopAdvertising(): BleOutcome {
        desiredAdvertise = null
        // Ticker is NOT stopped here — see syncEpochTicker. Going scan-only must not stop key
        // destruction.
        syncEpochTicker()
        _advertiseState.value = AdvertiseState(AdvertiseStatus.STOPPED, null, null, -1)
        return port.stopAdvertising()
    }

    public actual fun startScan(request: ScanRequest): BleOutcome = port.startScan(request)

    public actual fun stopScan(): BleOutcome = port.stopScan()

    /**
     * Delegates, and relies on the port to drop its listener reference — see the retain-cycle note
     * on [BleRadioPort]. Without that, this object leaks for the life of the process.
     */
    public actual fun shutdown() {
        desiredAdvertise = null
        epochJob?.cancel()
        epochJob = null
        // Ruling R-D applies to this listener too: it holds the key ring, and a leaked radio
        // pinning a ring for the process lifetime is the retention this handoff exists to end.
        epochBoundaryListener = null
        port.shutdown()
        radioScope.cancel()
    }

    // -----------------------------------------------------------------------------------------

    private fun applyAdvertising(): BleOutcome {
        val request = desiredAdvertise ?: return BleOutcome.Ok
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        val day = EpochClock.dayIndex(nowMillis)
        val epoch = EpochClock.epochIndex(nowMillis)

        val frame = request.payloadSource.frameForEpoch(day, epoch)

        // RULES 2 and 3.
        (AdvertiseGuard.checkFrame(frame, day, epoch) as? BleOutcome.Rejected)
            ?.let { return rejected(it.reason, it.detail, AdvertiseStatus.FAILED) }
        checkNotNull(frame)

        val outcome = port.startAdvertising(frame, request.serviceUuid16, request.duty)
        _advertiseState.value = when (outcome) {
            is BleOutcome.Ok -> AdvertiseState(AdvertiseStatus.STARTING, null, null, epoch)
            is BleOutcome.Rejected ->
                AdvertiseState(AdvertiseStatus.FAILED, outcome.reason, outcome.detail, epoch)
        }
        return outcome
    }

    /**
     * NO JITTER. `KEY_SCHEDULE.md` §3 — see the identical note on the Android actual.
     *
     * DEVICE-SCOPED, not advertising-scoped, for the same reason as Android: `pruneSupersededAt`
     * (§8.5.2) is owed by every device that holds a ring, and under decision 35 that is mostly
     * scan-only devices which never advertise at all. The ticker therefore runs while EITHER an
     * advertisement is wanted OR an epoch listener is registered.
     *
     * The iOS actual cannot key off "is scanning" the way Android does, because scanning here is
     * delegated straight to the Swift port and this class keeps no scan state. Registering an
     * [EpochBoundaryListener] is the signal instead — which is correct rather than a workaround:
     * the listener existing IS the statement that something holds a ring.
     */
    private fun syncEpochTicker() {
        val wanted = desiredAdvertise != null || epochBoundaryListener != null
        if (!wanted) {
            epochJob?.cancel()
            epochJob = null
            return
        }
        if (epochJob?.isActive == true) return

        epochJob = radioScope.launch {
            while (isActive) {
                val now = Clock.System.now().toEpochMilliseconds()
                delay(EpochClock.millisUntilNextBoundary(now) + BOUNDARY_SETTLE_MS)

                // Key destruction first, unconditionally. Same ordering and same reasoning as the
                // Android actual: the irreversible security obligation must not sit behind an
                // advertising restart that may be skipped or may fail.
                val listener = epochBoundaryListener
                if (listener != null) {
                    val nowAtBoundary = Clock.System.now().toEpochMilliseconds()
                    runCatching {
                        listener.onEpochBoundary(
                            EpochClock.dayIndex(nowAtBoundary),
                            EpochClock.epochIndex(nowAtBoundary),
                        )
                    }
                }

                if (desiredAdvertise == null) continue
                port.stopAdvertising()
                delay(ADVERTISE_RESTART_GAP_MS)
                if (desiredAdvertise == null) continue
                applyAdvertising()
            }
        }
    }

    private fun rejected(
        reason: BleOutcome.Reason,
        detail: String?,
        status: AdvertiseStatus,
    ): BleOutcome {
        _advertiseState.value = AdvertiseState(status, reason, detail, -1)
        return BleOutcome.Rejected(reason, detail)
    }

    private companion object {
        /**
         * Fixed and identical on every device, so the population still rotates at one instant.
         * Not jitter — `KEY_SCHEDULE.md` §3 forbids that. Kept numerically equal to the Android
         * actual's constant ON PURPOSE: the two platforms must rotate together, and a difference
         * here would put iOS and Android devices in separate anonymity sets at every boundary.
         */
        const val BOUNDARY_SETTLE_MS = 50L

        /**
         * UNMEASURED GUESS — pending sniffer data, and doubly unmeasured here because iOS is
         * DEFERRED (decision 33) so nobody is scheduled to measure it.
         *
         * Worse on this platform than on Android: `CoreBluetooth` neither exposes the local
         * address nor accepts a rotation instruction, so an iPhone cannot even DETECT a desync
         * locally, let alone fix one (`KEY_SCHEDULE.md` §4.2). This value is a hypothesis about
         * hardware nobody has pointed a sniffer at. Do not read it as tuned.
         */
        const val ADVERTISE_RESTART_GAP_MS = 250L
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
//    `SPEC.md` §5.2 answers this with Carrier B (GATT_PULL), which is DEFERRED with iOS and
//    therefore returns entirely unpriced — the Android-only spike exercises it not at all.
//
// 2. BACKGROUND ADVERTISING MOVES TO THE OVERFLOW AREA. A backgrounded iOS app's service UUIDs
//    are relocated to a special advertising area visible ONLY to another iOS device explicitly
//    scanning for that exact UUID. Android cannot see it at all. Backgrounded iOS→Android
//    discovery is not slow; it is absent. `SPEC.md` §5.4 Tier 3. Not fixable by us.
//
// CONSEQUENCE FOR THIS FILE: `port.startAdvertising(frame, …)` hands Swift 19 bytes that Swift
// currently has no legal way to transmit. That is not a defect in this signature — it is the
// finding above, arriving at the seam. When Carrier B lands, it lands as a port that writes those
// same 19 bytes into the `beacon_payload` characteristic instead of into an advertisement, and
// this file does not change. That is the §5.0-a decision working: the frame changes carrier, it
// does not degrade.
// ---------------------------------------------------------------------------------------------
