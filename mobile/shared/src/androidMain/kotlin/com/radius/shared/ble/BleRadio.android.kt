package com.radius.shared.ble

import android.Manifest
import com.radius.shared.protocol.AdvertiseRole
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android radio, on `android.bluetooth.le` DIRECTLY.
 *
 * NO Nordic library. NO RxAndroidBle. NO wrapper of any kind — ADR-001, reaffirmed by ADR-007.
 * The critical path stays ours because every OEM bug we will hit lives exactly here, and a wrapper
 * turns "we can fix it" into "we can file an issue".
 *
 * ## What this class is responsible for, exactly
 *
 *  1. Putting a 19-byte frame on air as Carrier A service data (`SPEC.md` §5.1), and taking it off.
 *  2. Scanning for our service UUID and republishing every match, unmodified.
 *  3. **Owning the epoch boundary.** Stop → re-derive → rebuild → start, every 15 minutes, UTC
 *     aligned (`KEY_SCHEDULE.md` §4.2). A full advertising restart is the only lever any Android
 *     app has on RPA rotation, and whether it works is chipset-dependent (B8).
 *  4. **Failing closed on the advertising role** (decision 35 / §9.3).
 *  5. Behaving correctly when the adapter goes off, permission is refused, or the device has no
 *     peripheral role.
 *
 * It does NOT decode the frame, band an RSSI, decide a duty profile, or know what an account is.
 * The single exception to "does not decode" is `flags` bit0 — see [AdvertiseGuard.connectable], which
 * reads one bit in order to avoid lying on air about its own PDU type.
 *
 * ## Verification status — READ BEFORE CITING ANYTHING FROM HERE
 *
 * This file COMPILES. That is all a desk can establish. Nothing below has been on a radio.
 * Specifically unproven and unprovable without hardware: whether a stop→start actually rotates the
 * RPA on any given chipset (B8, the top technical risk in the project), whether the scan modes
 * chosen here hold the <4 %/hr budget, whether the OEM power managers let a foreground service
 * keep a scan alive, and whether the adapter-recovery delay in [ADAPTER_SETTLE_MS] is long enough
 * on real Samsung/Xiaomi firmware. A green build is not a green radio.
 *
 * ## Threading
 *
 * Commands are synchronous and serialised on [lock]. Platform callbacks arrive on binder threads
 * and only touch `tryEmit`/`StateFlow.value`, both thread-safe. Timers (epoch rotation, conserve
 * duty cycling, adapter recovery) run on [radioScope], which the radio owns and which
 * [shutdown] cancels. The radio creating its own scope is deliberate: it is a platform object with
 * an explicit lifecycle, it must keep running when no UI is collecting, and pushing a scope
 * through Hilt for it would put a Radar-lifetime concern in the app graph for no gain. This is not
 * the `RadiusCore` scope rule from ADR-007 — that rule is about the core, and this is not
 * `GlobalScope`.
 *
 * @param appContext MUST be the application context. Holding an Activity here leaks it across the
 *   foreground-service lifetime, which for Radar is the whole session.
 */
public actual class BleRadio(
    private val appContext: Context,
) {

    private val lock = Any()

    private val radioScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _availability = MutableStateFlow(RadioAvailability.UNKNOWN)
    public actual val availability: StateFlow<RadioAvailability> = _availability.asStateFlow()

    /**
     * FAIL CLOSED. Decision 35 / `KEY_SCHEDULE.md` §9.3. The initial value is [AdvertiseRole
     * .SCAN_ONLY] and there is no constructor parameter, config field or build flag that can make
     * it anything else. A device becomes discoverable only by an explicit
     * [setAdvertiseRole] call, never by an omission.
     */
    private val _advertiseRole = MutableStateFlow(AdvertiseRole.SCAN_ONLY)
    public actual val advertiseRole: StateFlow<AdvertiseRole> =
        _advertiseRole.asStateFlow()

    private val _advertiseState = MutableStateFlow(STOPPED_STATE)
    public actual val advertiseState: StateFlow<AdvertiseState> = _advertiseState.asStateFlow()

    /**
     * A hot [MutableSharedFlow], not `callbackFlow`.
     *
     * Reason: scanning has an imperative lifecycle here — [startScan] and [stopScan] are separate
     * public commands driven by the foreground service and the duty controller. `callbackFlow`
     * would tie the scan to subscriber count, so the radio would stop the moment the UI stopped
     * collecting, which is precisely wrong for a background radar.
     *
     * DROP_OLDEST: in a crowded room this emits faster than anything downstream can band and
     * persist. Dropping the oldest sighting is correct — it is superseded information, and the
     * peer will advertise again within a second. Suspending the scan callback thread is not an
     * option; on several OEMs a slow callback stalls the whole BLE stack.
     */
    private val _sightings = MutableSharedFlow<RawSighting>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public actual val sightings: Flow<RawSighting> = _sightings.asSharedFlow()

    // -----------------------------------------------------------------------------------------
    // PHASE 0 SPIKE INSTRUMENTATION — androidMain only, OFF by default.
    // See AndroidRadioDiagnostics.kt for the full argument about why this is not a hole in the
    // RSSI rule, and for the deletion obligation.
    // -----------------------------------------------------------------------------------------

    private val _androidDiagnostics = MutableSharedFlow<AndroidSightingDiagnostic>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Raw per-advertisement diagnostics, INCLUDING the peer's advertising address and unfiltered
     * RSSI. Emits nothing unless [setDiagnosticsEnabled] has been called with `true`.
     *
     * `BufferOverflow.SUSPEND` here, deliberately opposite to [sightings]. The product stream may
     * drop — a superseded sighting is worthless. A MEASUREMENT stream must not drop silently,
     * because a dropped record is indistinguishable from a packet that was never received, and the
     * whole B8 question is an assertion about absence. If the harness cannot keep up, the radio
     * back-pressures and the log will show it, which is the honest failure.
     *
     * ADDRESS-TYPE CAVEAT, and it matters: Android reports a peer address as a STRING and does not
     * expose the TxAdd bit, so this cannot distinguish a public address from a random one whose
     * top bits happen to match. The in-app screen is therefore a HINT, not the `KEY_SCHEDULE.md`
     * §4.3.2 test. §4.3.2 needs a sniffer, and no amount of on-device cleverness substitutes.
     */
    public val androidDiagnostics: SharedFlow<AndroidSightingDiagnostic> =
        _androidDiagnostics.asSharedFlow()

    private val _androidEvents = MutableSharedFlow<AndroidRadioEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * The radio's own lifecycle, on the same clock as [androidDiagnostics]. Always emits, even
     * with diagnostics disabled — these carry no peer data at all, and a gap in the sighting log
     * is uninterpretable without them.
     */
    public val androidEvents: SharedFlow<AndroidRadioEvent> = _androidEvents.asSharedFlow()

    @Volatile
    private var diagnosticsEnabled: Boolean = false

    private val diagnosticsDroppedCount = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Diagnostic records the radio produced and could not hand over, because the harness was not
     * draining fast enough. MUST be reported alongside any capture — see the note at the emit
     * site. A non-zero value means the capture has holes we made, not holes the radio saw.
     */
    public val diagnosticsDropped: Long
        get() = diagnosticsDroppedCount.get()

    @Volatile
    private var scanModeOverride: Int? = null

    /**
     * Whether this handset has the peripheral role at all. Some budget devices scan happily and
     * cannot advertise; Radar is half-dead on them ("see but not be seen"), and per
     * `KEY_SCHEDULE.md` §4.3.6 the user is entitled to be told so in plain language rather than
     * left wondering why nobody ever waves. Record the models in `mobile/android/docs/oem.md`.
     */
    public val peripheralRoleSupported: Boolean
        get() = adapter?.bluetoothLeAdvertiser != null

    /**
     * `BluetoothAdapter.isMultipleAdvertisementSupported`. RECORDED, NOT USED AS A GATE.
     *
     * The previous draft of this file gated advertising on it, which was a bug: it answers "can
     * this controller run several advertising sets at once", not "can this device advertise". The
     * correct capability check is [peripheralRoleSupported]. Kept because it is useful per-OEM
     * data for the spike table.
     */
    public val multipleAdvertisementSupported: Boolean
        get() = adapter?.isMultipleAdvertisementSupported == true

    // -----------------------------------------------------------------------------------------
    // desired state — what the caller asked for, as opposed to what the radio is doing.
    // The distinction is the whole of adapter-off recovery: an adapter cycle destroys the platform
    // callbacks but MUST NOT destroy the user's intent, or Radar silently dies when someone
    // toggles Bluetooth in Quick Settings.
    // -----------------------------------------------------------------------------------------

    private var desiredScan: ScanRequest? = null
    private var desiredAdvertise: AdvertiseRequest? = null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var scanServiceUuid: ParcelUuid? = null
    private var liveAdvertiseEpoch: Long = -1L
    private var isShutdown = false

    private val scanStartGate = ScanStartGate()

    private var epochBoundaryListener: EpochBoundaryListener? = null
    private var epochJob: Job? = null
    private var dutyJob: Job? = null
    private var recoveryJob: Job? = null

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            event(AndroidRadioEvent.Kind.ADAPTER_STATE, adapterStateName(state))
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onAdapterLost()
                BluetoothAdapter.STATE_ON -> onAdapterReturned()
                else -> refreshAvailability()
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(adapterStateReceiver, filter)
        }
        refreshAvailability()
    }

    // -----------------------------------------------------------------------------------------
    // spike controls
    // -----------------------------------------------------------------------------------------

    /**
     * Turn the Phase 0 measurement stream on. OFF by default, on every build type.
     *
     * @param reason recorded in the event log and in logcat. It exists so that a capture file can
     *   always answer "who turned this on and why", and so the call site is greppable.
     */
    public fun setDiagnosticsEnabled(enabled: Boolean, reason: String) {
        diagnosticsEnabled = enabled
        Log.i(TAG, "diagnostics=$enabled reason=$reason")
        event(AndroidRadioEvent.Kind.DUTY, "diagnostics=$enabled reason=$reason")
    }

    /**
     * PHASE 0 ONLY. Force a specific `ScanSettings` scan mode, overriding [DutyProfile].
     *
     * The spike has two incompatible jobs and they need different radios:
     *
     *  - **Measurement runs** (B8 co-rotation screening, discovery-latency percentiles) want the
     *    highest possible capture yield. `KEY_SCHEDULE.md` §5.3 C5 requires ≥60 % of expected
     *    packets or the capture is VOID, and a 25 %-duty scan cannot deliver that.
     *  - **Battery runs** must use the duty the product will actually ship, or the number is
     *    fiction.
     *
     * Conflating the two is how a spike produces a battery figure from a max-capture run and calls
     * the budget met. So the override is explicit, it is recorded in the export header, and any
     * %/hr figure taken while it is non-null is INVALID BY CONSTRUCTION.
     *
     * @param scanMode a `ScanSettings.SCAN_MODE_*` constant, or `null` to return to [DutyProfile]
     *   control. Takes effect on the next scan start.
     */
    public fun setScanModeOverride(scanMode: Int?, reason: String): BleOutcome {
        if (scanMode != null && scanMode !in ALLOWED_SCAN_MODES) {
            return BleOutcome.Rejected(BleOutcome.Reason.PLATFORM_ERROR, "bad scan mode $scanMode")
        }
        scanModeOverride = scanMode
        event(AndroidRadioEvent.Kind.DUTY, "scanModeOverride=$scanMode reason=$reason")
        return BleOutcome.Ok
    }

    // -----------------------------------------------------------------------------------------
    // advertise
    // -----------------------------------------------------------------------------------------

    public actual fun setEpochBoundaryListener(listener: EpochBoundaryListener?) {
        synchronized(lock) { epochBoundaryListener = listener }
    }

    public actual fun setAdvertiseRole(
        role: AdvertiseRole,
        source: AdvertiseRoleSource,
    ): BleOutcome {
        synchronized(lock) {
            if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)
            val previous = _advertiseRole.value
            _advertiseRole.value = role
            Log.i(TAG, "advertiseRole $previous -> $role (source=$source)")
            event(AndroidRadioEvent.Kind.DUTY, "role=$role source=$source")

            if (role == AdvertiseRole.SCAN_ONLY && previous != role) {
                // Intent is cleared, not just the transmitter. Leaving `desiredAdvertise` set would
                // make every subsequent epoch boundary re-attempt an advertisement that the role
                // gate then refuses — a retry loop against our own fail-closed control, filling the
                // event log with ROLE_SCAN_ONLY rejections that look like a bug. A revoked role
                // means we are not trying to advertise, full stop.
                desiredAdvertise = null

                // §9.3 ORDERING IS NORMATIVE: the losing device stops IMMEDIATELY, the gaining
                // device starts at the next epoch boundary. Deferring this stop to the boundary
                // would let two devices emit the same ephemeral id from two places for up to
                // fifteen minutes, which is precisely the §9.2 hazard the rule exists to prevent.
                stopAdvertisingLocked("role revoked to SCAN_ONLY")
            }
            return BleOutcome.Ok
        }
    }

    public actual fun startAdvertising(request: AdvertiseRequest): BleOutcome {
        synchronized(lock) {
            if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)

            if (_advertiseRole.value != AdvertiseRole.ADVERTISE) {
                // The default answer. Not an error — a device with no ADVERTISE role is a correctly
                // configured scan-only device (decision 35).
                val outcome = BleOutcome.Rejected(BleOutcome.Reason.ROLE_SCAN_ONLY, null)
                publishAdvertiseState(AdvertiseStatus.STOPPED, outcome)
                return outcome
            }

            desiredAdvertise = request
            val outcome = applyAdvertisingLocked(System.currentTimeMillis())

            // The epoch ticker runs whether or not the first attempt succeeded: an advertise
            // failure now (adapter settling, another app holding the advertiser) must not disable
            // rotation forever. It retries at the boundary, which is the only cadence that is safe
            // anyway.
            syncEpochTickerLocked()
            return outcome
        }
    }

    public actual fun stopAdvertising(): BleOutcome {
        synchronized(lock) {
            desiredAdvertise = null
            return stopAdvertisingLocked("caller")
        }
    }

    /** Caller must hold [lock]. */
    @SuppressLint("MissingPermission")
    private fun stopAdvertisingLocked(why: String): BleOutcome {
        // NOT an unconditional epoch-ticker cancel any more. Stopping the transmitter must not
        // stop key destruction: a device that goes scan-only — which is the ordinary state under
        // decision 35, and exactly what a role transfer produces — still holds a ring and still
        // owes §8.5.2 a prune at every boundary. syncEpochTickerLocked keeps the ticker alive as
        // long as anything is running.
        syncEpochTickerLocked()
        val callback = advertiseCallback
        advertiseCallback = null
        liveAdvertiseEpoch = -1L
        publishAdvertiseState(AdvertiseStatus.STOPPED, null)
        if (callback == null) return BleOutcome.Ok
        event(AndroidRadioEvent.Kind.ADVERTISE_STOPPED, why)
        return try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            BleOutcome.Ok
        } catch (security: SecurityException) {
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        } catch (illegal: IllegalStateException) {
            // Adapter torn down under us. The advertising set is gone regardless, which is the
            // outcome we wanted, so this is not an error worth propagating.
            BleOutcome.Ok
        }
    }

    /** Caller must hold [lock]. */
    @SuppressLint("MissingPermission")
    private fun applyAdvertisingLocked(nowMillis: Long): BleOutcome {
        val request = desiredAdvertise ?: return BleOutcome.Ok

        // RULE 1, shared with iOS, tested in commonTest. Checked BEFORE the payload source is
        // touched: a SCAN_ONLY device must not even derive an ephemeral id it may not broadcast.
        (AdvertiseGuard.checkRole(_advertiseRole.value) as? BleOutcome.Rejected)
            ?.let { return reject(it.reason, it.detail) }

        val advertiser = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
            ?: return reject(BleOutcome.Reason.ADAPTER_OFF, "no advertiser")

        if (!hasAdvertisePermission()) {
            event(AndroidRadioEvent.Kind.PERMISSION, "BLUETOOTH_ADVERTISE missing")
            return reject(BleOutcome.Reason.PERMISSION_DENIED, null)
        }

        val dayIndex = EpochClock.dayIndex(nowMillis)
        val epochIndex = EpochClock.epochIndex(nowMillis)

        // THE KEY RING, AS THE RADIO SEES IT (40-contracts CONSISTENCY GAP 3). The frame is pulled
        // for THIS epoch, every time. There is no field on this class holding a payload, so there
        // is nothing to accidentally carry across a boundary.
        val frame = request.payloadSource.frameForEpoch(dayIndex, epochIndex)

        // RULES 2 and 3, shared with iOS, tested in commonTest.
        (AdvertiseGuard.checkFrame(frame, dayIndex, epochIndex) as? BleOutcome.Rejected)
            ?.let { return reject(it.reason, it.detail) }
        checkNotNull(frame)

        // LEGACY ADVERTISEMENT BUDGET — `SPEC.md` §5.1, re-derived here because getting it wrong
        // produces ADVERTISE_FAILED_DATA_TOO_LARGE at runtime on some chipsets and a silently
        // truncated PDU on others:
        //   flags AD                 3
        //   16-bit service UUID AD   4   (1 len + 1 type + 2 uuid)
        //   service data AD          4 + 19
        //   ---------------------------
        //                           30 of 31. One byte spare, deliberately left spare.
        // setIncludeDeviceName MUST stay false — it would overflow, AND a device name is a stable
        // identifier, which safety invariant 4 forbids outright. setIncludeTxPowerLevel stays
        // false because tx power already travels inside the frame (`SPEC.md` §3.2).

        stopAdvertisingPlatformLocked("rebuild")

        val parcelUuid = parcelUuidFrom16(request.serviceUuid16)

        // SINGLE SOURCE OF TRUTH FOR CONNECTABILITY — see AdvertiseGuard.connectable.
        val connectable = AdvertiseGuard.connectable(frame)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(request.duty.toAdvertiseMode())
            // COUPLED TO calibration/tx_power_classes.json. `tx_power_cal` inside the frame is the
            // RSSI a receiver measures at 1 m FROM THIS SETTING. Change the level here and every
            // calibration value becomes wrong, and every band with it. Do not "tune" this without
            // regenerating the calibration table on the rig.
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(connectable)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(parcelUuid)
            .addServiceData(parcelUuid, frame)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                publishAdvertiseState(AdvertiseStatus.ADVERTISING, null)
                event(
                    AndroidRadioEvent.Kind.ADVERTISE_STARTED,
                    "epoch=$epochIndex connectable=$connectable mode=${request.duty}",
                )
            }

            override fun onStartFailure(errorCode: Int) {
                // ARRIVES AFTER startAdvertising() RETURNED Ok. This is why advertiseState exists:
                // a caller that treats the return value as proof of transmission ships a phone
                // that can see everyone and be seen by nobody, with no error anywhere.
                val name = advertiseErrorName(errorCode)
                Log.w(TAG, "advertise failed: $name")
                publishAdvertiseState(
                    AdvertiseStatus.FAILED,
                    BleOutcome.Rejected(BleOutcome.Reason.PLATFORM_ERROR, name),
                )
                event(AndroidRadioEvent.Kind.ADVERTISE_FAILED, name)
            }
        }

        return try {
            advertiser.startAdvertising(settings, data, callback)
            advertiseCallback = callback
            liveAdvertiseEpoch = EpochClock.absoluteEpoch(nowMillis)
            publishAdvertiseState(AdvertiseStatus.STARTING, null)
            BleOutcome.Ok
        } catch (security: SecurityException) {
            // Belt and braces: several OEMs throw here even when the permission check passed,
            // typically when the adapter is mid-restart.
            reject(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        } catch (illegal: IllegalStateException) {
            reject(BleOutcome.Reason.PLATFORM_ERROR, illegal.message)
        }
    }

    /** Caller must hold [lock]. Drops the platform advertising set without touching intent. */
    @SuppressLint("MissingPermission")
    private fun stopAdvertisingPlatformLocked(why: String) {
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        liveAdvertiseEpoch = -1L
        event(AndroidRadioEvent.Kind.ADVERTISE_STOPPED, why)
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
    }

    /**
     * THE EPOCH TICKER. `KEY_SCHEDULE.md` §4.2 (RPA lever) and §8.5.2 (key destruction).
     *
     * DEVICE-SCOPED, NOT ADVERTISING-SCOPED, and that distinction is a security property rather
     * than tidiness. It runs whenever the radio is doing anything at all — scanning, advertising,
     * or waiting for the adapter to come back. Two obligations hang off a boundary and only one of
     * them is about advertising:
     *
     *  - the advertising restart, which obviously only applies while advertising;
     *  - `KeyRing.pruneSupersededAt` via [EpochBoundaryListener], which applies to EVERY device
     *    that holds a ring. Under decision 35 that is overwhelmingly scan-only devices, so a
     *    ticker that only ran while advertising would leave the superseded keys alive on almost
     *    the entire fleet. See the long note on [EpochBoundaryListener].
     *
     * It also keeps running while the adapter is off. Destruction is not radio work, and a device
     * sitting with Bluetooth disabled has no business retaining keys it was required to destroy.
     *
     * NO JITTER, EVER. §3: rotation is synchronised to the global UTC boundary because staggering
     * shrinks the anonymity set at a boundary from the whole population to one, and makes every
     * rotation individually linkable. [BOUNDARY_SETTLE_MS] is a fixed constant identical on every
     * device, so the population still rotates together; it exists only so the clock has definitely
     * passed the boundary before we ask for the new epoch's frame.
     *
     * Caller must hold [lock].
     */
    private fun syncEpochTickerLocked() {
        val wanted = !isShutdown && (desiredScan != null || desiredAdvertise != null)
        if (!wanted) {
            epochJob?.cancel()
            epochJob = null
            return
        }
        if (epochJob?.isActive == true) return

        epochJob = radioScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(EpochClock.millisUntilNextBoundary(now) + BOUNDARY_SETTLE_MS)
                onEpochBoundary()
            }
        }
    }

    private suspend fun onEpochBoundary() {
        val now = System.currentTimeMillis()
        val day = EpochClock.dayIndex(now)
        val epoch = EpochClock.epochIndex(now)

        // ---- 1. KEY DESTRUCTION FIRST, UNCONDITIONALLY, AND OUTSIDE THE LOCK ----
        //
        // First, because it is the irreversible security obligation and must not be gated behind
        // an advertising restart that may be skipped (scan-only device), may fail (adapter off), or
        // may return early. Unconditionally, for the same reason. Outside the lock, because the
        // listener is somebody else's code and calling out to arbitrary code while holding the
        // radio's monitor is how a radio deadlocks.
        //
        // Not wrapped in a retry and not treated as failable: `pruneSupersededAt` no-ops rather
        // than throwing, by design. `runCatching` only guards against a listener that violates its
        // MUST-NOT-THROW contract, and a throwing listener must not stop the advertising restart.
        val listener = synchronized(lock) { if (isShutdown) return else epochBoundaryListener }
        if (listener != null) {
            runCatching { listener.onEpochBoundary(day, epoch) }
                .onFailure { Log.w(TAG, "epoch boundary listener threw; continuing", it) }
        }

        // ---- 2. ADVERTISING RESTART, only if we are advertising ----
        synchronized(lock) {
            if (isShutdown || desiredAdvertise == null) return
            stopAdvertisingPlatformLocked("epoch boundary")
        }

        // A gap between stop and start. The controller frees the advertising set asynchronously,
        // and on several chipsets an immediate re-start reuses the same set — and, we suspect, the
        // same RPA. That suspicion is exactly B8 and it is UNVERIFIED: this delay may help, may do
        // nothing, and its correct value is a sniffer measurement, not a guess. It is a named
        // constant so the rig can vary it.
        delay(ADVERTISE_RESTART_GAP_MS)

        synchronized(lock) {
            if (isShutdown || desiredAdvertise == null) return
            applyAdvertisingLocked(System.currentTimeMillis())
            event(AndroidRadioEvent.Kind.EPOCH_ROTATED, "day=$day epoch=$epoch")
        }
    }

    // -----------------------------------------------------------------------------------------
    // scan
    // -----------------------------------------------------------------------------------------

    public actual fun startScan(request: ScanRequest): BleOutcome {
        synchronized(lock) {
            if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)
            desiredScan = request
            // A scan-only device is the decision-35 majority case and still owes §8.5.2 a prune at
            // every boundary. Starting a scan is enough to make the device "running".
            syncEpochTickerLocked()
            dutyJob?.cancel()
            dutyJob = null

            return if (request.duty == DutyProfile.CONSERVE) {
                // Deepest cut short of stopping: host-driven on/off on top of the controller's own
                // LOW_POWER duty. Everywhere else we let the controller do the duty cycling,
                // because host-driven start/stop costs wakeups and spends the scan-start budget —
                // but CONSERVE is the profile where a long genuine OFF period is the whole point.
                startConserveDutyLoopLocked()
                BleOutcome.Ok
            } else {
                applyScanLocked(System.currentTimeMillis())
            }
        }
    }

    public actual fun stopScan(): BleOutcome {
        synchronized(lock) {
            desiredScan = null
            dutyJob?.cancel()
            dutyJob = null
            syncEpochTickerLocked()
            return stopScanPlatformLocked("caller")
        }
    }

    /** Caller must hold [lock]. */
    @SuppressLint("MissingPermission")
    private fun applyScanLocked(nowMillis: Long): BleOutcome {
        val request = desiredScan ?: return BleOutcome.Ok

        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return BleOutcome.Rejected(BleOutcome.Reason.ADAPTER_OFF, "no scanner")

        if (!hasScanPermission()) {
            event(AndroidRadioEvent.Kind.PERMISSION, "BLUETOOTH_SCAN missing")
            return BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, null)
        }

        // Restarting an already-running scan costs a start from the platform budget for nothing.
        stopScanPlatformLocked("restart")

        val waitMillis = scanStartGate.tryStart(nowMillis)
        if (waitMillis > 0L) {
            event(AndroidRadioEvent.Kind.SCAN_THROTTLED, "wait=${waitMillis}ms")
            scheduleScanRetryLocked(waitMillis)
            return BleOutcome.Rejected(BleOutcome.Reason.THROTTLED, "$waitMillis")
        }

        val parcelUuid = parcelUuidFrom16(request.serviceUuid16)
        scanServiceUuid = parcelUuid

        // ALWAYS FILTERED. An unfiltered scan is a battery catastrophe, picks up every fridge and
        // headset in the building, and — decisively — Android 8.1+ DELIVERS NOTHING from an
        // unfiltered scan while the screen is off, which is most of Radar's life.
        //
        // Filter on the SERVICE UUID, not on service data. The previous draft filtered on service
        // data with an empty mask; that matches, but service-data filters are the ones OEM
        // controllers are least likely to offload, and a filter that falls back to software
        // filtering does not survive a screen-off scan — the exact case we need. A UUID filter is
        // the well-trodden offloadable path.
        //
        // Consequence, and it is a feature: a Carrier B peer (`SPEC.md` §5.2 — our UUID, NO service
        // data) still reaches us. It produces no RawSighting, but it does produce a diagnostic
        // record, so the §5.0 matrix can tell "an iOS-shaped peer was here" apart from "nothing was
        // here". A service-data filter would have hidden it completely.
        val filters = listOf(ScanFilter.Builder().setServiceUuid(parcelUuid).build())

        val settings = ScanSettings.Builder()
            .setScanMode(scanModeOverride ?: request.duty.toScanMode())
            // ALL_MATCHES, never FIRST_MATCH. FIRST_MATCH deduplicates in the controller, and the
            // spike is explicitly forbidden from deduplicating: a per-packet stream is the raw
            // material for the co-rotation and latency questions.
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            // No batching. Batching is a real battery lever and it is on the list to measure, but
            // it costs per-packet timestamps, and Phase 0 needs those more than it needs the
            // milliamps. Revisit with rig data, not with an opinion.
            .setReportDelay(0L)
            // Legacy PDUs on purpose. `SPEC.md` §6: extended advertising MUST NOT be used at our
            // device floor. setLegacy(false) also drops peers on several chipsets and gains us
            // nothing at 19 bytes.
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emit)
            }

            override fun onScanFailed(errorCode: Int) {
                val name = scanErrorName(errorCode)
                Log.w(TAG, "scan failed: $name")
                event(AndroidRadioEvent.Kind.SCAN_FAILED, name)
                if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    // The classic symptom of the 5-starts-per-30s throttle, and of a stack that has
                    // wedged. Back off hard; retrying immediately is how an app gets itself
                    // permanently muted for the rest of the window.
                    synchronized(lock) {
                        scanCallback = null
                        scheduleScanRetryLocked(REGISTRATION_BACKOFF_MS)
                    }
                }
            }
        }

        return try {
            scanner.startScan(filters, settings, callback)
            scanCallback = callback
            event(
                AndroidRadioEvent.Kind.SCAN_STARTED,
                "duty=${request.duty} mode=${scanModeOverride ?: request.duty.toScanMode()} " +
                    "startsInWindow=${scanStartGate.startsInWindow(nowMillis)}",
            )
            BleOutcome.Ok
        } catch (security: SecurityException) {
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        } catch (illegal: IllegalStateException) {
            BleOutcome.Rejected(BleOutcome.Reason.PLATFORM_ERROR, illegal.message)
        }
    }

    /** Caller must hold [lock]. */
    @SuppressLint("MissingPermission")
    private fun stopScanPlatformLocked(why: String): BleOutcome {
        val callback = scanCallback ?: return BleOutcome.Ok
        scanCallback = null
        scanServiceUuid = null
        event(AndroidRadioEvent.Kind.SCAN_STOPPED, why)
        return try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
            BleOutcome.Ok
        } catch (security: SecurityException) {
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        } catch (illegal: IllegalStateException) {
            BleOutcome.Ok
        }
    }

    /** Caller must hold [lock]. */
    private fun scheduleScanRetryLocked(delayMillis: Long) {
        dutyJob?.cancel()
        dutyJob = radioScope.launch {
            delay(delayMillis)
            synchronized(lock) {
                if (isShutdown) return@synchronized
                val request = desiredScan ?: return@synchronized
                if (request.duty == DutyProfile.CONSERVE) {
                    startConserveDutyLoopLocked()
                } else {
                    applyScanLocked(System.currentTimeMillis())
                }
            }
        }
    }

    /** Caller must hold [lock]. */
    private fun startConserveDutyLoopLocked() {
        dutyJob?.cancel()
        dutyJob = radioScope.launch {
            while (isActive) {
                synchronized(lock) {
                    if (isShutdown) return@launch
                    applyScanLocked(System.currentTimeMillis())
                }
                event(AndroidRadioEvent.Kind.DUTY, "conserve window open ${CONSERVE_ON_MS}ms")
                delay(CONSERVE_ON_MS)
                synchronized(lock) { stopScanPlatformLocked("conserve window closed") }
                event(AndroidRadioEvent.Kind.DUTY, "conserve window shut ${CONSERVE_OFF_MS}ms")
                delay(CONSERVE_OFF_MS)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // adapter lifecycle
    // -----------------------------------------------------------------------------------------

    /**
     * The user toggled Bluetooth off, or the stack crashed and is restarting (which happens on
     * several OEMs under aggressive power management). Every platform callback we hold is now
     * dead; calling `stopScan`/`stopAdvertising` with them either throws or no-ops.
     *
     * DESIRED STATE IS NOT CLEARED. Radar must come back by itself when the adapter does, without
     * the user having to work out that they need to toggle it in the app too.
     */
    private fun onAdapterLost() {
        synchronized(lock) {
            scanCallback = null
            scanServiceUuid = null
            advertiseCallback = null
            liveAdvertiseEpoch = -1L
            dutyJob?.cancel(); dutyJob = null
            // THE EPOCH TICKER IS DELIBERATELY NOT CANCELLED HERE. Key destruction (§8.5.2) is not
            // radio work, and a device sitting with Bluetooth switched off has no business
            // retaining superseded keys it was already required to destroy. The boundary handler
            // skips the advertising half by itself when there is nothing to advertise.
            publishAdvertiseState(
                AdvertiseStatus.FAILED,
                BleOutcome.Rejected(BleOutcome.Reason.ADAPTER_OFF, null),
            )
        }
        refreshAvailability()
    }

    private fun onAdapterReturned() {
        refreshAvailability()
        synchronized(lock) {
            if (isShutdown) return
            recoveryJob?.cancel()
            recoveryJob = radioScope.launch {
                // ADAPTER_SETTLE_MS, and it is not superstition. On a large fraction of handsets
                // ACTION_STATE_CHANGED/STATE_ON arrives before the LE scanner and advertiser are
                // actually usable: getBluetoothLeScanner() returns null, or startScan fails with
                // INTERNAL_ERROR, for roughly a second afterwards. Retrying immediately burns a
                // scan-start from the 5-per-30s budget and fails anyway. The correct value is
                // per-OEM and is a hardware measurement — see mobile/android/docs/oem.md.
                delay(ADAPTER_SETTLE_MS)
                synchronized(lock) {
                    if (isShutdown) return@synchronized
                    val now = System.currentTimeMillis()
                    if (desiredAdvertise != null) {
                        applyAdvertisingLocked(now)
                    }
                    syncEpochTickerLocked()
                    val scan = desiredScan
                    if (scan != null) {
                        if (scan.duty == DutyProfile.CONSERVE) {
                            startConserveDutyLoopLocked()
                        } else {
                            applyScanLocked(now)
                        }
                    }
                }
            }
        }
    }

    public actual fun shutdown() {
        synchronized(lock) {
            isShutdown = true
            desiredScan = null
            desiredAdvertise = null
            epochJob?.cancel(); epochJob = null
            dutyJob?.cancel(); dutyJob = null
            recoveryJob?.cancel(); recoveryJob = null
            stopScanPlatformLocked("shutdown")
            stopAdvertisingPlatformLocked("shutdown")
            publishAdvertiseState(AdvertiseStatus.STOPPED, null)
            // Ruling R-D: shutdown MUST drop listener references. Applies to this one too — the
            // epoch listener holds the key ring, and a leaked radio pinning a ring for the life of
            // the process is the retention this whole handoff exists to end.
            epochBoundaryListener = null
        }
        runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
        radioScope.cancel()
    }

    // -----------------------------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------------------------

    private fun emit(result: ScanResult) {
        val uuid = scanServiceUuid ?: return
        val record = result.scanRecord
        val serviceData = record?.getServiceData(uuid)

        // Defensive: a few OEMs deliver unfiltered results when they fall back to software
        // filtering. Anything that is neither carrying our service data nor listing our UUID is
        // not ours and must not appear anywhere, not even in the measurement stream.
        if (serviceData == null && record?.serviceUuids?.contains(uuid) != true) return

        val observedAt = epochMillisOf(result)

        if (serviceData != null) {
            _sightings.tryEmit(
                RawSighting(
                    payload = serviceData,
                    rssiDbm = result.rssi,
                    observedAtEpochMs = observedAt,
                ),
            )
        }

        if (!diagnosticsEnabled) return

        val address = result.device?.address ?: return
        val accepted = _androidDiagnostics.tryEmit(
            AndroidSightingDiagnostic(
                serviceDataOrNull = serviceData,
                rssiDbm = result.rssi,
                advertiserAddress = address,
                addressTypeBits = addressTypeBitsOf(address),
                observedAtEpochMs = observedAt,
                timestampNanos = result.timestampNanos,
                txPowerAdv = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.txPower
                } else {
                    TX_POWER_NOT_PRESENT
                },
                isLegacy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.isLegacy else true,
                isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.isConnectable
                } else {
                    true
                },
                dataStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.dataStatus
                } else {
                    0
                },
                primaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) result.primaryPhy else 1,
                secondaryPhy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.secondaryPhy
                } else {
                    0
                },
                advertisingSid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.advertisingSid
                } else {
                    ScanResult.SID_NOT_PRESENT
                },
                periodicAdvertisingInterval = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    result.periodicAdvertisingInterval
                } else {
                    0
                },
            ),
        )

        // A MEASUREMENT STREAM MUST COUNT ITS OWN LOSSES. `tryEmit` on a SUSPEND-policy
        // SharedFlow returns false rather than blocking the BLE callback thread — which is right,
        // because a slow callback stalls the whole stack on several OEMs, but it means a record
        // can vanish. A vanished record is indistinguishable from a packet that never arrived, and
        // the B8 question is an assertion about ABSENCE. So the loss is counted and surfaced,
        // every time, and the harness prints it in the run header. An uncounted drop would turn a
        // capture from evidence into an anecdote.
        if (!accepted) {
            val n = diagnosticsDroppedCount.incrementAndGet()
            if (n == 1L || n % 100L == 0L) {
                event(AndroidRadioEvent.Kind.DIAGNOSTIC_DROPPED, "total=$n")
            }
        }
    }

    /**
     * `ScanResult.timestampNanos` is elapsed-realtime since boot, not wall clock. Converting it
     * properly matters: batched results can be several seconds old, and using
     * `currentTimeMillis()` at callback time would make a stale sighting look fresh and keep a
     * departed peer glued to the radar.
     */
    private fun epochMillisOf(result: ScanResult): Long {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - result.timestampNanos) / 1_000_000L
        return System.currentTimeMillis() - ageMillis
    }

    private fun refreshAvailability() {
        val current = adapter
        _availability.value = when {
            current == null -> RadioAvailability.UNSUPPORTED
            !appContext.packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> RadioAvailability.UNSUPPORTED
            !hasScanPermission() -> RadioAvailability.PERMISSION_DENIED
            !current.isEnabled -> RadioAvailability.ADAPTER_OFF
            else -> RadioAvailability.READY
        }
    }

    private fun reject(reason: BleOutcome.Reason, detail: String?): BleOutcome {
        val outcome = BleOutcome.Rejected(reason, detail)
        publishAdvertiseState(AdvertiseStatus.FAILED, outcome)
        return outcome
    }

    private fun publishAdvertiseState(status: AdvertiseStatus, outcome: BleOutcome?) {
        val rejected = outcome as? BleOutcome.Rejected
        _advertiseState.value = AdvertiseState(
            status = status,
            reason = rejected?.reason,
            detail = rejected?.detail,
            epochIndex = if (liveAdvertiseEpoch < 0) {
                -1
            } else {
                (liveAdvertiseEpoch % EpochClock.EPOCHS_PER_DAY).toInt()
            },
        )
    }

    private fun event(kind: AndroidRadioEvent.Kind, detail: String) {
        _androidEvents.tryEmit(
            AndroidRadioEvent(kind, System.currentTimeMillis(), detail),
        )
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // API 29-30: there is no BLUETOOTH_SCAN, and a BLE scan without ACCESS_FINE_LOCATION
            // returns zero results *silently* — no exception, no callback, just nothing. This is
            // the only reason FINE_LOCATION is in the manifest, and it is capped at API 30.
            granted(Manifest.permission.BLUETOOTH_ADMIN) &&
                granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            granted(Manifest.permission.BLUETOOTH_ADMIN)
        }

    private fun granted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        private const val TAG = "RadiusBleRadio"

        /** `SPEC.md` §3: "exactly 19 bytes. Not 18, not 20." */
        const val FRAME_BYTES = 19

        /** 31-byte legacy PDU minus flags AD (3), 16-bit UUID AD (4) and service-data header (4). */
        const val MAX_SERVICE_DATA_BYTES = 20

        // =====================================================================================
        // UNMEASURED GUESSES. EVERY TIMING CONSTANT BELOW IS A NUMBER SOMEBODY MADE UP AT A DESK.
        //
        // None has been observed on a radio. They are not tuned, not derived from a datasheet,
        // and not defaults recommended by anyone. They are starting points chosen to be plausible
        // and to be VARIED BY THE RIG — which is why they are named constants in one block rather
        // than literals scattered through the file.
        //
        // DO NOT read a value here as evidence that it is correct, and do not cite one in a
        // report. When a sniffer or a Battery Historian trace produces a real number, replace the
        // value AND delete it from this block, so that the set of things still being guessed is
        // always visible at a glance. A constant that has moved out of this block has been
        // measured; a constant still in it has not.
        //
        // THIS BLOCK HAS A SECOND HALF, IN ANOTHER FILE, AND YOU NEED BOTH. The Phase 0 harness
        // constants (battery sample period, latency probe cycle/ON window, density bucket, peer
        // liveness window, nominal duty percentages) live in
        // `mobile/android/src/debug/kotlin/com/radius/android/spike/SpikeTiming.kt`, under the same
        // banner and the same rule. They are separate ONLY because those constants do not ship and
        // these do. Two places to look, stated here rather than discovered by someone who thought
        // this block was the whole set.
        // =====================================================================================

        /**
         * Delay past the UTC boundary before asking for the new epoch's frame.
         *
         * NOT A GUESS IN THE SAME SENSE, and the distinction matters: this is fixed and IDENTICAL
         * ON EVERY DEVICE, so the whole population still rotates at one instant. It is not jitter
         * (`KEY_SCHEDULE.md` §3 forbids that outright — staggering shrinks the boundary anonymity
         * set to one). Only its magnitude is arbitrary.
         */
        const val BOUNDARY_SETTLE_MS = 50L

        /**
         * UNMEASURED GUESS — pending sniffer data. Gap between stop and start when rotating.
         *
         * The hypothesis is that some controllers reuse the advertising set, and its RPA, if
         * restarted immediately. This delay may prevent that, may do nothing, or may need to be
         * far longer. B8 (`KEY_SCHEDULE.md` §4.3) decides it, and nothing else can — the app
         * cannot read its own advertising address, so this value is unfalsifiable from inside the
         * process. Expect the rig to sweep it.
         */
        const val ADVERTISE_RESTART_GAP_MS = 250L

        /**
         * UNMEASURED GUESS — per OEM. How long after `STATE_ON` the LE scanner is actually usable.
         *
         * On many handsets `getBluetoothLeScanner()` returns null, or `startScan` fails with
         * `INTERNAL_ERROR`, for roughly a second after the broadcast arrives. "Roughly a second"
         * is folklore, not a measurement; too short wastes a scan-start from the 5-per-30s budget
         * and fails anyway, too long is dead air after every Bluetooth toggle. See
         * `mobile/android/docs/oem.md`.
         */
        const val ADAPTER_SETTLE_MS = 1_500L

        /**
         * UNMEASURED GUESS. Back-off after `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`, chosen
         * as "just over the 30 s throttle window". Whether the platform's counter is actually
         * clear by then is unobserved — we cannot read it.
         */
        const val REGISTRATION_BACKOFF_MS = 35_000L

        /**
         * UNMEASURED GUESSES. CONSERVE host duty cycle: one scan start per 2 minutes, chosen to
         * sit well inside the 5-per-30s budget. Whether a 20 s window every 2 minutes still finds
         * peers often enough to be worth the radio time is a field question, and whether it
         * actually saves the battery it is supposed to save is a Battery Historian question.
         */
        const val CONSERVE_ON_MS = 20_000L
        const val CONSERVE_OFF_MS = 100_000L

        const val TX_POWER_NOT_PRESENT = 127

        val STOPPED_STATE = AdvertiseState(AdvertiseStatus.STOPPED, null, null, -1)

        val ALLOWED_SCAN_MODES = intArrayOf(
            ScanSettings.SCAN_MODE_OPPORTUNISTIC,
            ScanSettings.SCAN_MODE_LOW_POWER,
            ScanSettings.SCAN_MODE_BALANCED,
            ScanSettings.SCAN_MODE_LOW_LATENCY,
        )

        fun parcelUuidFrom16(hex16: String): ParcelUuid =
            ParcelUuid(UUID.fromString(uuid128From16(hex16)))

        fun adapterStateName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }

        fun advertiseErrorName(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            else -> "UNKNOWN($code)"
        }

        fun scanErrorName(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            else -> "UNKNOWN($code)"
        }
    }
}

/**
 * Expands an assigned 16-bit Bluetooth SIG UUID into its full 128-bit form.
 *
 * Kept as a pure string function, separate from [ParcelUuid], precisely so it can be unit-tested
 * on the JVM — `android.os.ParcelUuid` from the android.jar stub throws "Stub!" outside an
 * instrumentation run, and this expansion is exactly the kind of thing that is silently wrong.
 *
 * Base UUID: `00000000-0000-1000-8000-00805F9B34FB`, with the 16-bit value in bytes 2-3.
 */
internal fun uuid128From16(hex16: String): String {
    require(hex16.length == 4 && hex16.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
        "expected a 4-hex-digit 16-bit UUID, got '$hex16'"
    }
    return "0000${hex16.uppercase()}-0000-1000-8000-00805F9B34FB"
}

/**
 * The `KEY_SCHEDULE.md` §4.3.2 address-type HINT, from Android's string form of a peer address.
 *
 * Returns the top two bits of the most significant address octet: `0b01` resolvable-private,
 * `0b00` non-resolvable-private, `0b11` static-random.
 *
 * **THIS IS NOT THE §4.3.2 TEST AND MUST NOT BE REPORTED AS ONE.** §4.3.2 also requires the TxAdd
 * bit, which says whether the address is public at all, and Android does not expose it — a public
 * address whose OUI begins `0x40`-`0x7F` is indistinguishable here from a resolvable private
 * address. The catastrophic case is therefore the one this function cannot see. It is a cheap
 * pre-screen that can produce false passes, which is why §5.1 buys three sniffer dongles.
 */
internal fun addressTypeBitsOf(address: String): Int =
    address.take(2).toIntOrNull(16)?.let { it ushr 6 } ?: -1

/**
 * Duty → Android advertise mode.
 *
 * Android exposes three coarse modes, not an interval in milliseconds:
 *   LOW_LATENCY ≈ 100 ms · BALANCED ≈ 250 ms · LOW_POWER ≈ 1000 ms
 * Our contract (`SPEC.md` §6) is 250 ms foreground / 1000 ms background, so FOREGROUND maps to
 * BALANCED, not to LOW_LATENCY. LOW_LATENCY would burn roughly 2.5x the advertising energy for
 * latency the product does not need, and it is the first thing to check if the <4 %/hr battery
 * gate fails.
 */
private fun DutyProfile.toAdvertiseMode(): Int = when (this) {
    DutyProfile.FOREGROUND -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
    DutyProfile.BACKGROUND -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
    DutyProfile.CONSERVE -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
}

/**
 * Duty → Android scan mode. **FOREGROUND IS BALANCED, NOT LOW_LATENCY, AND THAT IS A BATTERY
 * DECISION, NOT A TYPO.**
 *
 * AOSP's `ScanManager` implements scan duty cycling in the controller, with these window/interval
 * pairs:
 *
 * ```
 *   SCAN_MODE_LOW_POWER      512 ms / 5120 ms   = 10 %
 *   SCAN_MODE_BALANCED      1024 ms / 4096 ms   = 25 %
 *   SCAN_MODE_LOW_LATENCY   4096 ms / 4096 ms   = 100 %
 * ```
 *
 * Root `CLAUDE.md` specifies a **30 % scan duty**. BALANCED at 25 % is the nearest thing Android
 * offers; LOW_LATENCY is a CONTINUOUS receiver, four times the contracted duty, and there is no
 * credible path from a 100 %-duty receiver to <4 %/hr. Choosing it for FOREGROUND — as the first
 * draft of this file did — would have been designing something already known to blow the budget.
 *
 * The corollary, which matters for the spike: the `KEY_SCHEDULE.md` §5.3 C5 capture-yield
 * criterion (≥60 % of expected packets) is NOT reachable at 25 % duty. Measurement runs must use
 * `setScanModeOverride(SCAN_MODE_LOW_LATENCY, …)`, and any battery figure taken during such a run
 * is invalid. That is why the override is a separate, recorded, explicitly-named call rather than
 * a `DutyProfile` value.
 *
 * Windows are also OEM-modified in practice — several vendors ship their own values — so the real
 * duty must be measured per device, not read off this table.
 */
private fun DutyProfile.toScanMode(): Int = when (this) {
    DutyProfile.FOREGROUND -> ScanSettings.SCAN_MODE_BALANCED
    DutyProfile.BACKGROUND -> ScanSettings.SCAN_MODE_LOW_POWER
    DutyProfile.CONSERVE -> ScanSettings.SCAN_MODE_LOW_POWER
}

// ---------------------------------------------------------------------------------------------
// STILL MISSING. Listed so nobody mistakes this file for finished:
//
//  * PROOF that a stop→start actually rotates the RPA (safety invariant 5, B8). The loop is
//    implemented; whether the controller honours it is chipset firmware and is UNVERIFIED on every
//    device. `KEY_SCHEDULE.md` §5 is the measurement, it needs three sniffer dongles, and it is
//    the first thing the rig should run. If it fails broadly, this file is not the fix.
//  * Adaptive duty INPUTS: stationary detection, <20 % battery, no peer seen for 10 minutes. The
//    radio obeys a DutyProfile; nothing yet decides which one.
//  * GATT: opened only post-mutual-wave, ~180B chunks, ACKed, MTU ≥185, NO BONDING, all
//    characteristics declared with no security permissions (`SPEC.md` §7.3 — PR checklist item).
//  * Carrier B (`SPEC.md` §5.2). Deferred with iOS; the scanner currently records that it saw a
//    UUID-only advertiser and does nothing about it.
//  * Blackout zones: advertising suppressed entirely, scanning continues (`SPEC.md` §9).
//  * OEM battery-killer workarounds — Samsung, Xiaomi, Huawei. See mobile/android/docs/oem.md.
// ---------------------------------------------------------------------------------------------
