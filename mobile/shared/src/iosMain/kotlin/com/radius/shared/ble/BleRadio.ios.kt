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
import platform.Foundation.NSRecursiveLock

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

    /**
     * THE RADIO'S MONITOR. Everything below this comment is mutable state read and written from
     * threads that have nothing to do with each other, and until this lock existed NONE of it was
     * synchronised while the Android actual synchronised ALL of it.
     *
     * WHO RACES WITH WHOM. The public methods are called from whatever thread shared core or the
     * Swift layer happens to be on — a CoreBluetooth delegate queue, the main queue, a Ktor
     * continuation. The epoch ticker reads the same fields from a `Dispatchers.Default` worker.
     * Two concrete bugs this closes, both from the same read-modify-write shape:
     *
     *  1. TWO TICKERS, ONE EPOCH. `syncEpochTicker` tested `epochJob?.isActive != true` and then
     *     assigned. Two callers interleaving there both see "no ticker", both launch one, and the
     *     boundary is announced TWICE per epoch with one job leaked for the life of the radio. A
     *     duplicate `pruneSupersededAt` is harmless; a duplicate advertising restart at a boundary
     *     is a second RPA rotation inside one epoch, which is the B8 shape we are trying to
     *     MEASURE, arriving as an artefact of our own code.
     *  2. A LISTENER INVOKED AFTER RELEASE. The ticker read `epochBoundaryListener` with no
     *     shutdown re-check while `shutdown()` nulled it unsynchronised. Ruling R-D says a caller
     *     that releases the listener has released the KEY RING; the loop could still call into it.
     *     Android has `synchronized(lock) { if (isShutdown) return else epochBoundaryListener }`
     *     for exactly this, and iOS now has the same expression.
     *
     * WHY RECURSIVE. `synchronized` on the JVM is reentrant, and the Android actual leans on that:
     * `setAdvertiseRole` → `stopAdvertisingLocked` → `syncEpochTickerLocked` is three frames deep
     * in one critical section. A plain `NSLock` would deadlock on that path instead of racing, and
     * a deadlock in a file nobody compiles is a worse trade than the few nanoseconds recursion
     * costs. Same `*Locked` naming discipline as Android, for the same reason: the suffix IS the
     * contract.
     *
     * PORT CALLS HAPPEN UNDER THIS LOCK, deliberately, exactly as the Android actual calls
     * `BluetoothLeAdvertiser` under its monitor. Radio operations are ORDER-SENSITIVE — a stop and
     * a start that interleave leave the transmitter running the previous epoch's frame — and the
     * lock is what makes the ordering total. The consequence for the Swift side is a REQUIREMENT:
     * a [BleRadioPort] method MUST NOT synchronously call back into this object. Pushing sightings
     * through [BleRadioListener] is fine (it only touches thread-safe flows); a `dispatch_sync`
     * back into `startAdvertising` is not.
     *
     * THE EPOCH LISTENER IS CALLED OUTSIDE IT, equally deliberately. That one is arbitrary caller
     * code holding a key ring, and calling out to arbitrary code while holding a radio's monitor is
     * how a radio deadlocks. It is read under the lock and invoked after it is dropped.
     */
    private val stateLock = NSRecursiveLock()

    private var desiredAdvertise: AdvertiseRequest? = null
    private var epochJob: Job? = null
    private var epochBoundaryListener: EpochBoundaryListener? = null

    /**
     * Set once by [shutdown] and never cleared. Feeds [EpochTickerPolicy.wanted] so the ticker
     * predicate is the same expression on both platforms, and gates listener registration so a
     * post-shutdown caller cannot re-pin a key ring on a dead radio (ruling R-D).
     *
     * Read and written ONLY under [stateLock]. Not `@Volatile` instead of locked: visibility was
     * never the whole problem — every use of it is one half of a check-then-act with another field.
     */
    private var isShutdown = false

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
        stateLock.withLock {
            // Refused after shutdown, not merely ineffective: the reference holds the KEY RING, and
            // pinning it on a dead radio for the life of the process is exactly the retention
            // ruling R-D made shutdown() null it for. Identical to the Android actual.
            if (isShutdown) return
            epochBoundaryListener = listener
            // Re-evaluated HERE because registering a listener is one of the two inputs to
            // EpochTickerPolicy. A predicate whose correctness depends on the caller's call order
            // is not a predicate.
            syncEpochTickerLocked()
        }
    }

    public actual fun setAdvertiseRole(
        role: AdvertiseRole,
        source: AdvertiseRoleSource,
    ): BleOutcome {
        stateLock.withLock {
            // Android returns NOT_RUNNING here and iOS silently accepted the call, which meant a
            // dead radio still published a widened role through `advertiseRole` for anything
            // observing that flow. Fail closed, on both platforms, with the same reason code.
            if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)
            val previous = _advertiseRole.value
            _advertiseRole.value = role
            if (role == AdvertiseRole.SCAN_ONLY && previous != role) {
                // Intent is cleared, not just the transmitter — otherwise every subsequent epoch
                // boundary re-attempts an advertisement the role gate then refuses. Mirrors the
                // Android actual line for line.
                desiredAdvertise = null
                // §9.3: the losing device stops IMMEDIATELY, not at the next boundary.
                stopAdvertisingLocked()
            }
            return BleOutcome.Ok
        }
    }

    public actual fun startAdvertising(request: AdvertiseRequest): BleOutcome {
        stateLock.withLock {
            if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)

            // RULE 1. Same pure function the Android actual calls, so the two platforms cannot
            // drift. Checked before the payload source is touched: a SCAN_ONLY device must not even
            // derive an ephemeral id it may not broadcast.
            (AdvertiseGuard.checkRole(_advertiseRole.value) as? BleOutcome.Rejected)
                ?.let { return rejected(it.reason, it.detail, AdvertiseStatus.STOPPED) }

            desiredAdvertise = request
            val outcome = applyAdvertisingLocked()
            // The ticker runs whether or not the first attempt succeeded: a failure now must not
            // disable rotation forever. It retries at the boundary.
            syncEpochTickerLocked()
            return outcome
        }
    }

    public actual fun stopAdvertising(): BleOutcome {
        stateLock.withLock {
            desiredAdvertise = null
            return stopAdvertisingLocked()
        }
    }

    /** Caller must hold [stateLock]. */
    private fun stopAdvertisingLocked(): BleOutcome {
        // Ticker is NOT stopped here — see syncEpochTickerLocked. Going scan-only must not stop key
        // destruction.
        syncEpochTickerLocked()
        _advertiseState.value = AdvertiseState(AdvertiseStatus.STOPPED, null, null, -1)
        return port.stopAdvertising()
    }

    public actual fun startScan(request: ScanRequest): BleOutcome {
        stateLock.withLock {
            // Same fail-closed answer Android gives. This class keeps no scan state — the port owns
            // it — so the lock is held only for the shutdown check and to keep the ordering of port
            // calls total.
            if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)
            return port.startScan(request)
        }
    }

    public actual fun stopScan(): BleOutcome = stateLock.withLock { port.stopScan() }

    /**
     * Delegates, and relies on the port to drop its listener reference — see the retain-cycle note
     * on [BleRadioPort]. Without that, this object leaks for the life of the process.
     */
    public actual fun shutdown() {
        stateLock.withLock {
            isShutdown = true
            desiredAdvertise = null
            epochJob?.cancel()
            epochJob = null
            // Ruling R-D applies to this listener too: it holds the key ring, and a leaked radio
            // pinning a ring for the process lifetime is the retention this handoff exists to end.
            epochBoundaryListener = null
            _advertiseState.value = AdvertiseState(AdvertiseStatus.STOPPED, null, null, -1)
        }
        // OUTSIDE the lock, like Android's unregisterReceiver/radioScope.cancel(): `port.shutdown()`
        // is Swift code that may block on its own queue, and cancelling the scope can resume
        // continuations. Neither needs the monitor, and holding it across them widens the window in
        // which a Swift queue could deadlock against us.
        port.shutdown()
        radioScope.cancel()
    }

    // -----------------------------------------------------------------------------------------

    /** Caller must hold [stateLock]. */
    private fun applyAdvertisingLocked(): BleOutcome {
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
     * RING-SCOPED, not radio-scoped: `pruneSupersededAt` (§8.5.2) is owed by every device that
     * holds a ring, and under decision 35 that is mostly scan-only devices which never advertise at
     * all. The ticker therefore runs while EITHER an advertisement is wanted OR an epoch listener
     * is registered.
     *
     * This actual could never key off "is scanning" the way Android used to, because scanning here
     * is delegated straight to the Swift port and this class keeps no scan state. That constraint
     * turned out to be the CORRECT generalisation rather than a workaround — the listener existing
     * IS the statement that something holds a ring — and Android has now been corrected to match.
     * The predicate itself lives in [EpochTickerPolicy], in `commonMain`, so the agreement is
     * structural instead of a comment in two files claiming the same thing. THE PREDICATE WAS ONLY
     * HALF THE DRIFT: sharing the expression settled WHEN the ticker should run, and left HOW the
     * state behind it is read entirely unsynchronised on this side. Reading it under [stateLock] —
     * so that the test-then-launch below is one indivisible step — is the other half.
     *
     * Caller must hold [stateLock].
     */
    private fun syncEpochTickerLocked() {
        val wanted = EpochTickerPolicy.wanted(
            isShutdown = isShutdown,
            advertising = desiredAdvertise != null,
            hasEpochListener = epochBoundaryListener != null,
        )
        if (!wanted) {
            epochJob?.cancel()
            epochJob = null
            return
        }
        // TEST AND ASSIGN IN THE SAME CRITICAL SECTION. Unlocked, two callers both saw "no ticker"
        // and both launched one: two announcements per boundary and a leaked job.
        if (epochJob?.isActive == true) return

        epochJob = radioScope.launch {
            while (isActive) {
                val now = Clock.System.now().toEpochMilliseconds()
                delay(EpochClock.millisUntilNextBoundary(now) + BOUNDARY_SETTLE_MS)
                onEpochBoundary()
            }
        }
    }

    /**
     * Extracted so this side has the same shape as `BleRadio.android.kt`'s `onEpochBoundary`, down
     * to which statements are inside the monitor and which are not. The two files being read
     * side by side is the only review that ever catches radio drift, and that is much harder when
     * one platform is a flat loop body and the other is a named function.
     */
    private suspend fun onEpochBoundary() {
        val now = Clock.System.now().toEpochMilliseconds()
        val day = EpochClock.dayIndex(now)
        val epoch = EpochClock.epochIndex(now)

        // ---- 1. KEY DESTRUCTION FIRST, UNCONDITIONALLY, AND OUTSIDE THE LOCK ----
        //
        // First and unconditionally, because it is the irreversible security obligation and must
        // not sit behind an advertising restart that may be skipped or may fail. Outside the lock,
        // because the listener is somebody else's code and calling out to arbitrary code while
        // holding the radio's monitor is how a radio deadlocks.
        //
        // THE SHUTDOWN RE-CHECK IS PART OF THE SAME READ, not a separate `if`. Ruling R-D: a caller
        // that released this listener released the key ring with it, and reading a stale non-null
        // reference here would call into a ring its owner believes it has dropped.
        val boundaryListener = stateLock.withLock {
            if (isShutdown) return else epochBoundaryListener
        }
        if (boundaryListener != null) {
            runCatching { boundaryListener.onEpochBoundary(day, epoch) }
        }

        // ---- 2. ADVERTISING RESTART, only if we are advertising ----
        stateLock.withLock {
            if (isShutdown || desiredAdvertise == null) return
            port.stopAdvertising()
        }

        // The gap is NOT held under the lock: it is a suspension, and a monitor held across a
        // suspension can be released by a different thread than took it. Both sides of it re-read
        // the state, which is why the re-check below is not redundant with the one above.
        delay(ADVERTISE_RESTART_GAP_MS)

        stateLock.withLock {
            if (isShutdown || desiredAdvertise == null) return
            applyAdvertisingLocked()
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

/**
 * `synchronized` for Kotlin/Native, in the twelve lines it actually takes.
 *
 * There is no `kotlin.synchronized` on Native and no atomics dependency in `:shared` — adding one
 * is an ORCHESTRATION §8 escalation, and `NSRecursiveLock` is already in the Foundation interop
 * every Apple target links. `inline` matters for more than speed: it is what lets a `return` inside
 * the block leave the ENCLOSING function while `finally` still unlocks, which is the shape the
 * Android actual uses throughout (`synchronized(lock) { if (isShutdown) return … }`) and therefore
 * the shape this file has to be able to use if the two are ever to be compared line by line.
 *
 * MUST NOT be held across a suspension point. The lock belongs to a THREAD; a coroutine can resume
 * on a different one, and `unlock()` from a thread that did not `lock()` is undefined behaviour on
 * a recursive mutex. Every use in this file locks, reads or writes plain fields, and unlocks.
 */
private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
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
