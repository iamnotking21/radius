package com.radius.android.spike

import android.bluetooth.le.ScanSettings
import com.radius.shared.ble.DutyProfile

/**
 * One spike run's settings. Immutable; changing anything restarts the run and starts a new file,
 * because a capture whose parameters changed halfway through is not one measurement, it is two
 * measurements someone will average.
 *
 * @property deviceSlot this handset's public test root ([SpikeKeys]). Every handset in a session
 *   MUST have a different slot. Two handsets on the same slot are the decision-35 twin case, and
 *   they will fill the log with `E_SELF_EID` — which is a useful thing to demonstrate deliberately
 *   and a very confusing thing to hit by accident.
 * @property resolveSlots slots this device will try to resolve. Include the other handsets'.
 * @property advertise ask for the transmitter. Defaults FALSE, and the radio defaults to
 *   `SCAN_ONLY` underneath it as well — two independent fail-closed defaults, because this is the
 *   control that keeps two devices from broadcasting the same identity from two places.
 * @property connectable value of `flags` bit0 (`SPEC.md` §3.3), which also sets the link-layer PDU
 *   type. FIELD-CONFIGURABLE ON PURPOSE: whether a controller rotates its address may differ
 *   between `ADV_IND` and `ADV_NONCONN_IND`, and if it does, B8 has a different answer per mode
 *   and we need to know that before we pick one.
 * @property txPowerCalDbm the calibrated 1 m RSSI claimed on air. Clamped to the seven legal
 *   values by `BleFrameCodec.clampTxPowerCal`. UNMEASURED for every real handset — the
 *   `calibration/` table is a placeholder, and filling it is spike item 4.
 * @property maxCapture use `SCAN_MODE_LOW_LATENCY` instead of the duty profile.
 *   **ANY BATTERY NUMBER TAKEN WITH THIS ON IS INVALID.** It exists because the §5.3 C5 capture
 *   yield (≥60 % of expected packets) is unreachable at the contracted 25 % duty, so a
 *   co-rotation capture and a battery capture cannot be the same run. Recorded in the run header
 *   so the two can never be confused after the fact.
 */
internal data class SpikeConfig(
    val deviceSlot: Int = 1,
    val resolveSlots: Set<Int> = setOf(1, 2, 3),
    val advertise: Boolean = false,
    val connectable: Boolean = false,
    val txPowerCalDbm: Int = -60,
    val duty: DutyProfile = DutyProfile.FOREGROUND,
    val maxCapture: Boolean = false,
) {
    val scanModeOverride: Int?
        get() = if (maxCapture) ScanSettings.SCAN_MODE_LOW_LATENCY else null

    fun describe(): String =
        "slot=$deviceSlot resolve=${resolveSlots.sorted()} advertise=$advertise " +
            "connectable=$connectable txCal=${txPowerCalDbm}dBm duty=$duty maxCapture=$maxCapture"
}
