package com.radius.shared.ble

import android.Manifest
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Android radio, on `android.bluetooth.le` DIRECTLY.
 *
 * NO Nordic library. NO RxAndroidBle. NO wrapper of any kind — ADR-001, reaffirmed by ADR-007.
 * The critical path stays ours because every OEM bug we will hit lives exactly here, and a wrapper
 * turns "we can fix it" into "we can file an issue".
 *
 * !! UNVERIFIED, AND NOT FUNCTIONALLY COMPLETE !!
 * This has never been compiled (no JDK, blocker B5) and never been run on hardware. Advertising
 * and scanning are wired; the pieces marked TODO below are genuinely missing, not "probably fine".
 * A BLE claim is only true when it comes off two real handsets (Samsung + Pixel minimum).
 *
 * @param appContext MUST be the application context. Holding an Activity here leaks it across the
 *   foreground-service lifetime, which for Radar is the whole session.
 */
public actual class BleRadio(
    private val appContext: Context,
) {

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _availability = MutableStateFlow(RadioAvailability.UNKNOWN)
    public actual val availability: StateFlow<RadioAvailability> = _availability.asStateFlow()

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

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var scanServiceUuid: ParcelUuid? = null
    private var isShutdown = false

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                refreshAvailability()
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
    // advertise
    // -----------------------------------------------------------------------------------------

    public actual fun startAdvertising(request: AdvertiseRequest): BleOutcome {
        if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)

        val advertiser = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
            ?: return BleOutcome.Rejected(BleOutcome.Reason.ADAPTER_OFF, "no advertiser")

        if (adapter?.isMultipleAdvertisementSupported != true) {
            // Some budget devices can scan but cannot advertise at all. Radar is half-dead on
            // them: they can see others, others cannot see them. The UI must say so honestly
            // rather than silently pretending. Track the model list in mobile/android/docs/oem.md.
            return BleOutcome.Rejected(BleOutcome.Reason.UNSUPPORTED, "no peripheral role")
        }

        if (!hasAdvertisePermission()) {
            return BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, null)
        }

        // LEGACY ADVERTISEMENT BUDGET — 31 bytes, and we are close to the edge:
        //   flags AD                 3
        //   16-bit service UUID AD   4   (1 len + 1 type + 2 uuid)
        //   service data AD          4 + payload
        // With the v0 payload of 19 bytes that totals 30. ONE byte of headroom.
        // Consequences: setIncludeDeviceName MUST stay false (it would overflow, and a device name
        // is a stable identifier — safety invariant 4 forbids it outright), and setIncludeTxPower
        // stays false because tx power already travels inside our payload.
        if (request.payload.size > MAX_SERVICE_DATA_BYTES) {
            return BleOutcome.Rejected(
                BleOutcome.Reason.PAYLOAD_TOO_LARGE,
                "${request.payload.size}B > ${MAX_SERVICE_DATA_BYTES}B",
            )
        }

        stopAdvertising()

        val parcelUuid = parcelUuidFrom16(request.serviceUuid16)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(request.duty.toAdvertiseMode())
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            // NOT connectable. 40-contracts: GATT is opened ONLY after a mutual wave. A
            // permanently connectable advertiser lets any stranger open a connection to us and
            // costs battery for nothing. The handshake path will flip to a short connectable
            // window when a session is actually agreed.
            // TODO(radar-handshake): connectable window on mutual wave, then straight back to false.
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(parcelUuid)
            .addServiceData(parcelUuid, request.payload)
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: ${advertiseErrorName(errorCode)}")
                // TODO(radar): surface this upward. A silent advertise failure is the single
                //  nastiest Radar bug — the user sees others, nobody sees them, and nothing warns.
            }
        }

        return try {
            advertiser.startAdvertising(settings, data, callback)
            advertiseCallback = callback
            BleOutcome.Ok
        } catch (security: SecurityException) {
            // Belt and braces: several OEMs throw here even when the permission check passed,
            // typically when the adapter is mid-restart.
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        } catch (illegal: IllegalStateException) {
            BleOutcome.Rejected(BleOutcome.Reason.PLATFORM_ERROR, illegal.message)
        }
    }

    public actual fun stopAdvertising(): BleOutcome {
        val callback = advertiseCallback ?: return BleOutcome.Ok
        advertiseCallback = null
        return try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            BleOutcome.Ok
        } catch (security: SecurityException) {
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        }
    }

    // -----------------------------------------------------------------------------------------
    // scan
    // -----------------------------------------------------------------------------------------

    public actual fun startScan(request: ScanRequest): BleOutcome {
        if (isShutdown) return BleOutcome.Rejected(BleOutcome.Reason.NOT_RUNNING, null)

        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return BleOutcome.Rejected(BleOutcome.Reason.ADAPTER_OFF, "no scanner")

        if (!hasScanPermission()) {
            return BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, null)
        }

        stopScan()

        val parcelUuid = parcelUuidFrom16(request.serviceUuid16)
        scanServiceUuid = parcelUuid

        // ALWAYS FILTERED. An unfiltered scan is a battery catastrophe, it picks up every fridge
        // and headset in the building, and on Android it is also the pattern that gets an app
        // flagged for location inference. Filtering on service DATA (not just the UUID) keeps the
        // hardware filter engaged, which is what actually saves the battery.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceData(parcelUuid, byteArrayOf(), byteArrayOf())
                .build(),
        )

        val settings = ScanSettings.Builder()
            .setScanMode(request.duty.toScanMode())
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            // Legacy PDUs on purpose. setLegacy(false) drops peers on several chipsets and gains
            // us nothing at a 19-byte payload. Revisit only with hardware evidence.
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emit(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::emit)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "scan failed: ${scanErrorName(errorCode)}")
                if (errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    // Classic symptom of the undocumented 5-scans-per-30-seconds throttle. The
                    // duty controller must back off rather than retry-loop.
                    // TODO(radar): exponential backoff + surface to the duty controller.
                }
            }
        }

        return try {
            scanner.startScan(filters, settings, callback)
            scanCallback = callback
            BleOutcome.Ok
        } catch (security: SecurityException) {
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        } catch (illegal: IllegalStateException) {
            BleOutcome.Rejected(BleOutcome.Reason.PLATFORM_ERROR, illegal.message)
        }
    }

    public actual fun stopScan(): BleOutcome {
        val callback = scanCallback ?: return BleOutcome.Ok
        scanCallback = null
        scanServiceUuid = null
        return try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
            BleOutcome.Ok
        } catch (security: SecurityException) {
            BleOutcome.Rejected(BleOutcome.Reason.PERMISSION_DENIED, security.message)
        }
    }

    public actual fun shutdown() {
        isShutdown = true
        stopScan()
        stopAdvertising()
        runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
    }

    // -----------------------------------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------------------------------

    private fun emit(result: ScanResult) {
        val uuid = scanServiceUuid ?: return
        val payload = result.scanRecord?.getServiceData(uuid) ?: return

        _sightings.tryEmit(
            RawSighting(
                payload = payload,
                rssiDbm = result.rssi,
                observedAtEpochMs = epochMillisOf(result),
            ),
        )
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

        /** 31-byte legacy PDU minus flags (3) and the 16-bit service UUID AD (4) minus header (4). */
        private const val MAX_SERVICE_DATA_BYTES = 20

        fun parcelUuidFrom16(hex16: String): ParcelUuid =
            ParcelUuid(UUID.fromString(uuid128From16(hex16)))

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
 * Duty → Android advertise mode.
 *
 * Android exposes three coarse modes, not an interval in milliseconds:
 *   LOW_LATENCY ≈ 100ms · BALANCED ≈ 250ms · LOW_POWER ≈ 1000ms
 * Our contract is 250ms foreground / 1000ms background, so FOREGROUND maps to BALANCED, not to
 * LOW_LATENCY. LOW_LATENCY would burn roughly 2.5x the advertising energy for latency the product
 * does not need, and it is the first thing to check if the <4%/hr battery gate fails.
 */
private fun DutyProfile.toAdvertiseMode(): Int = when (this) {
    DutyProfile.FOREGROUND -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
    DutyProfile.BACKGROUND -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
    DutyProfile.CONSERVE -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
}

/**
 * Duty → Android scan mode.
 *
 * The 30% scan duty cycle in root CLAUDE.md is NOT expressible through `ScanSettings`. Android's
 * scan modes have fixed, undocumented, per-OEM windows. Real duty cycling has to be done above
 * this layer by starting and stopping the scan on a timer — and that must respect the
 * 5-starts-per-30-seconds throttle or Android silently stops delivering results.
 * TODO(radar): duty controller owns start/stop timing; this layer only obeys.
 */
private fun DutyProfile.toScanMode(): Int = when (this) {
    DutyProfile.FOREGROUND -> ScanSettings.SCAN_MODE_LOW_LATENCY
    DutyProfile.BACKGROUND -> ScanSettings.SCAN_MODE_BALANCED
    DutyProfile.CONSERVE -> ScanSettings.SCAN_MODE_LOW_POWER
}

// ---------------------------------------------------------------------------------------------
// STILL MISSING. Listed so nobody mistakes this file for finished:
//
//  * RPA / MAC rotation in step with ephemeral_id (safety invariant 5 — "both or neither").
//    Android gives apps NO direct control over resolvable private address rotation; the
//    controller owns it, typically on a ~15 minute timer that we cannot read or set. The only
//    lever is stop → start advertising, and whether that actually forces a new RPA is
//    CHIPSET-DEPENDENT. This must be measured on real hardware per OEM with a sniffer before
//    anyone claims invariant 5 holds on Android. If it cannot be forced, that is a finding for
//    the Phase 0 go/no-go memo, not something to paper over.
//  * Adaptive duty: stationary detection, <20% battery, no peer seen for 10 minutes.
//  * Scan restart throttle handling (5 per 30s) and backoff.
//  * GATT: opened only post-mutual-wave, ~180B chunks, ACKed, MTU ≥185.
//  * OEM battery-killer workarounds — Samsung, Xiaomi, Huawei. See mobile/android/docs/oem.md.
// ---------------------------------------------------------------------------------------------
