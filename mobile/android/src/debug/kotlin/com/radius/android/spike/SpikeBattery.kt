package com.radius.android.spike

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * BATTERY SAMPLING — go/no-go **P1** (`<4 %/hr scanning`, `<1 %/day idle`).
 *
 * ## The rule this file exists to enforce
 *
 * **A battery number without the radio state next to it is unattributable**, and an unattributable
 * number is how `SCAN_MODE_LOW_LATENCY` — a 100 % duty receiver against a contracted 30 % — survived
 * review once already (decision 44, and `PHASE0_GO_NO_GO.md` P1 says in as many words that any
 * figure taken before that fix is meaningless). So every row this class produces carries, in the
 * same row, what the radio was doing when it was taken: duty profile, scan mode actually in effect,
 * whether a scan was open, whether the transmitter was live, and the cumulative milliseconds of each
 * since the run began. The %/hr figure and its attribution cannot be separated afterwards because
 * they were never in separate places.
 *
 * ## Why the charge counter and not the percentage
 *
 * `BATTERY_PROPERTY_CAPACITY` is an integer percent. On a 5000 mAh handset one unit is ~50 mAh, so a
 * 30-minute run at the 4 %/hr budget moves it by 2 — and the reading is a step function, so the run
 * can plausibly show 1, 2 or 3 depending on where in the step it started and stopped. That is a
 * ±50 % error bar on the single number Phase 0 P1 turns on.
 *
 * `BATTERY_PROPERTY_CHARGE_COUNTER` is µAh remaining, and on most handsets it moves continuously.
 * BOTH are recorded, because on some devices the charge counter is unimplemented (returns 0 or
 * `Long.MIN_VALUE`) and on some it is quantised as coarsely as the percentage. Which one is
 * trustworthy is a per-model finding, and recording both is how it gets found rather than assumed.
 *
 * ## What is NOT trustworthy here, stated rather than discovered later
 *
 *  - **`BATTERY_PROPERTY_CURRENT_NOW` sign convention is OEM-dependent.** Some report discharge as
 *    negative, some as positive, and a well-known family of devices reports milliamps where the API
 *    documents microamps. It is recorded RAW and must not be summed or integrated without checking
 *    the device's convention against a known-charging and known-discharging sample first.
 *  - **This measures the WHOLE DEVICE.** It is not our app's drain. Without a paired
 *    [SpikeMode.BATTERY_BASELINE] run on the same handset, at the same screen state, in the same
 *    session, a %/hr figure is device drain, not Radar drain, and quoting it against the 4 %/hr
 *    budget overstates our cost by whatever the phone does on its own. The baseline mode exists for
 *    exactly this subtraction and the run header records whether one is owed.
 *  - **Screen state dominates everything.** An OLED panel at full brightness is an order of
 *    magnitude above a BLE scan. `screen_interactive` is on every row; a run with the screen on is
 *    not comparable to one with it off and the two must never be averaged.
 */
internal class SpikeBatteryReader(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager? =
        appContext.getSystemService(BatteryManager::class.java)
    private val powerManager: PowerManager? =
        appContext.getSystemService(PowerManager::class.java)

    /**
     * Capabilities, read once at run start and written into `meta.json`.
     *
     * A run on a handset whose charge counter returns nothing is not a broken run — it is a run
     * whose battery figures have a ±1 % quantisation floor, and the person reading the CSV six weeks
     * later needs to know which kind they are holding.
     */
    fun capabilities(): Map<String, String> {
        val sample = read()
        return linkedMapOf(
            "battery_charge_counter_supported" to (sample.chargeCounterUah != UNSUPPORTED).toString(),
            "battery_current_now_supported" to (sample.currentNowUa != UNSUPPORTED_INT).toString(),
            "battery_energy_counter_supported" to (sample.energyCounterNwh != UNSUPPORTED).toString(),
            "battery_scale" to sample.scale.toString(),
            "battery_current_now_sign_convention" to "UNVERIFIED — OEM-dependent, raw value only",
            "battery_start_level_pct" to sample.levelPct.toString(),
            "battery_start_charge_counter_uah" to sample.chargeCounterUah.toString(),
        )
    }

    fun read(): SpikeBatterySample {
        // Sticky broadcast. Passing a null receiver registers nothing and returns the last value the
        // system published, so there is no receiver to leak and no lifecycle to get wrong. The
        // exported flag is supplied on T+ anyway — it is ignored for a null receiver, and supplying
        // it costs nothing next to arguing about whether it is ignored.
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(null, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(null, filter)
            }

        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val voltage = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temperature = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

        return SpikeBatterySample(
            wallUtcMs = System.currentTimeMillis(),
            // THE DENOMINATOR. Taken in the same breath as the wall clock and recorded beside it,
            // never derived from it. See the KDoc on SpikeBatterySample.elapsedRealtimeMs.
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
            levelPct = longProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toInt()
                .takeIf { it in 0..100 }
                ?: if (level >= 0 && scale > 0) level * 100 / scale else -1,
            rawLevel = level,
            scale = scale,
            chargeCounterUah = longProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            currentNowUa = longProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toIntOrUnsupported(),
            energyCounterNwh = longProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER),
            voltageMv = voltage,
            temperatureDeciC = temperature,
            pluggedRaw = plugged,
            statusRaw = status,
            healthRaw = health,
            screenInteractive = powerManager?.isInteractive == true,
            powerSaveMode = powerManager?.isPowerSaveMode == true,
            deviceIdleMode = powerManager?.isDeviceIdleMode == true,
        )
    }

    private fun longProperty(id: Int): Long {
        val bm = batteryManager ?: return UNSUPPORTED
        return try {
            val v = bm.getLongProperty(id)
            // Android returns Long.MIN_VALUE for an unsupported property on most builds, and 0 on
            // some. 0 is indistinguishable from a genuinely flat battery for the charge counter, so
            // it is NOT normalised away here — it is written through and the analysis sees a column
            // of zeros, which is unmistakable. Only the sentinel is normalised.
            if (v == Long.MIN_VALUE) UNSUPPORTED else v
        } catch (error: Exception) {
            UNSUPPORTED
        }
    }

    private fun Long.toIntOrUnsupported(): Int =
        if (this == UNSUPPORTED || this > Int.MAX_VALUE || this < Int.MIN_VALUE) {
            UNSUPPORTED_INT
        } else {
            toInt()
        }

    companion object {
        const val UNSUPPORTED: Long = Long.MIN_VALUE
        const val UNSUPPORTED_INT: Int = Int.MIN_VALUE
    }
}

/**
 * One battery reading. Plain value type; every field is recorded raw and nothing is normalised,
 * averaged or smoothed, for the same reason the sighting rows are not.
 */
internal data class SpikeBatterySample(
    val wallUtcMs: Long,
    /**
     * `SystemClock.elapsedRealtime()` AT THE SAME INSTANT AS [wallUtcMs], and the ONLY clock any
     * %/hr figure may be divided by.
     *
     * `SpikeDutyLedger` already integrates radio time on this clock and states why in its class
     * KDoc: wall clock jumps when the platform re-syncs NTP, and a jump in the middle of a 90-minute
     * capture silently rewrites the denominator of every %/hr figure in the run. The drain estimator
     * used to divide by `wallUtcMs` deltas, which meant the numerator (charge) and the denominator
     * (time) were measured against two different notions of time, one of which is not monotonic.
     *
     * It is recorded in the CSV as well as used on-device BECAUSE THE ERROR IS OTHERWISE
     * UNRECOVERABLE IN ANALYSIS. A sample carrying only a wall clock gives an off-device reader no
     * way to notice a re-sync, let alone correct for it: the row simply says the run was four
     * minutes shorter than it was. With both clocks in the file, `wall_utc_ms` minus
     * `elapsed_realtime_ms` is constant across a clean run and steps exactly where the platform
     * re-synced — so the anomaly is visible without being asked about.
     *
     * It also keeps counting through deep sleep, which is exactly what an idle-drain measurement
     * needs and what `uptimeMillis` would not give.
     */
    val elapsedRealtimeMs: Long,
    val levelPct: Int,
    val rawLevel: Int,
    val scale: Int,
    val chargeCounterUah: Long,
    val currentNowUa: Int,
    val energyCounterNwh: Long,
    val voltageMv: Int,
    val temperatureDeciC: Int,
    val pluggedRaw: Int,
    val statusRaw: Int,
    val healthRaw: Int,
    val screenInteractive: Boolean,
    val powerSaveMode: Boolean,
    val deviceIdleMode: Boolean,
) {
    val plugged: Boolean get() = pluggedRaw > 0

    val pluggedName: String
        get() = when (pluggedRaw) {
            0 -> "UNPLUGGED"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "OTHER($pluggedRaw)"
        }

    val statusName: String
        get() = when (statusRaw) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
            else -> "UNKNOWN($statusRaw)"
        }
}

/**
 * Turns a series of [SpikeBatterySample]s into a drain estimate — **for the live screen only.**
 *
 * The FILE is the measurement. This exists so that a person standing in a car park can tell a run
 * that is working from a run that is not, without waiting an hour and pulling a CSV. Everything it
 * computes is recomputable, and should be recomputed, off-device from `battery.csv`.
 *
 * It refuses to produce a number in three cases, and the refusal is the point:
 *
 *  - before [SpikeTiming.BATTERY_MIN_PROJECTION_MS] has elapsed — a slope through four minutes of a
 *    four-hour curve is noise with a decimal point on it;
 *  - if the device was plugged in at ANY point in the run — charging inverts the sign of the thing
 *    being measured, and a partially-charged run cannot be repaired by discarding rows;
 *  - if `maxCapture` was on — `SCAN_MODE_LOW_LATENCY` is not the shipping duty and the resulting
 *    figure is fiction (`SpikeConfig.maxCapture`, decision 44).
 */
internal class BatteryDrainEstimator {

    private var first: SpikeBatterySample? = null
    private var latest: SpikeBatterySample? = null
    var samples: Long = 0L
        private set
    var everPlugged: Boolean = false
        private set
    var screenOnSamples: Long = 0L
        private set

    fun add(sample: SpikeBatterySample) {
        if (first == null) first = sample
        latest = sample
        samples++
        if (sample.plugged) everPlugged = true
        if (sample.screenInteractive) screenOnSamples++
    }

    fun reset() {
        first = null
        latest = null
        samples = 0L
        everPlugged = false
        screenOnSamples = 0L
    }

    val latestSample: SpikeBatterySample? get() = latest

    /**
     * MONOTONIC, and this is the denominator of every %/hr figure this class produces.
     *
     * NOT `wallUtcMs` deltas. An NTP re-sync mid-capture moves the wall clock by an arbitrary amount
     * in an arbitrary direction, and dividing a charge delta by a wall-clock delta silently rewrites
     * the rate for the whole run — a 4 %/hr result becomes 4.4 %/hr, or 3.6 %/hr, with nothing
     * anywhere indicating that anything happened. `SpikeDutyLedger` makes the same argument for the
     * radio-time numerator; the two have to be on the same clock or the ratio is not a ratio.
     *
     * `coerceAtLeast(0)` is a belt-and-braces assertion rather than a fix: `elapsedRealtime` cannot
     * go backwards, so a negative here would mean the samples arrived out of order, and a zero
     * denominator is caught by the [SpikeTiming.BATTERY_MIN_PROJECTION_MS] guard above every use.
     */
    val elapsedMs: Long
        get() {
            val a = first ?: return 0L
            val b = latest ?: return 0L
            return (b.elapsedRealtimeMs - a.elapsedRealtimeMs).coerceAtLeast(0L)
        }

    /**
     * How far this run's wall clock moved RELATIVE to the monotonic clock, milliseconds.
     *
     * Zero on a clean run. Non-zero means the platform re-synced (or a user set the clock) mid
     * capture, which is a fact about the run that the analysis needs — every cross-device latency
     * number in `latency.csv` is on the wall clock, and a step in it lands inside the measurement.
     * Written into the run summary; never silently corrected for.
     */
    val wallClockStepMs: Long
        get() {
            val a = first ?: return 0L
            val b = latest ?: return 0L
            return (b.wallUtcMs - a.wallUtcMs) - (b.elapsedRealtimeMs - a.elapsedRealtimeMs)
        }

    val levelDeltaPct: Int
        get() {
            val a = first ?: return 0
            val b = latest ?: return 0
            return b.levelPct - a.levelPct
        }

    val chargeDeltaUah: Long
        get() {
            val a = first ?: return 0L
            val b = latest ?: return 0L
            if (a.chargeCounterUah == SpikeBatteryReader.UNSUPPORTED ||
                b.chargeCounterUah == SpikeBatteryReader.UNSUPPORTED
            ) {
                return 0L
            }
            return b.chargeCounterUah - a.chargeCounterUah
        }

    /**
     * Estimated full-charge capacity, µAh, back-derived from the first sample's counter and percent.
     *
     * DELIBERATELY CRUDE AND LABELLED SO. Android exposes no design-capacity API to a normal app.
     * `counter / (pct/100)` assumes the fuel gauge's percent and its µAh agree, which is exactly the
     * assumption a bad fuel gauge breaks. It is used ONLY to render a %/hr figure on the screen; the
     * CSV carries µAh and the analysis should use the handset's real datasheet capacity.
     */
    val estimatedFullChargeUah: Long
        get() {
            val a = first ?: return 0L
            if (a.chargeCounterUah <= 0L || a.levelPct <= 0) return 0L
            return a.chargeCounterUah * 100L / a.levelPct
        }

    /** Percent per hour from the coarse integer level. Quantised to whole percent. */
    fun percentPerHourFromLevel(): Double? {
        val ms = elapsedMs
        if (ms < SpikeTiming.BATTERY_MIN_PROJECTION_MS) return null
        return -levelDeltaPct.toDouble() * 3_600_000.0 / ms.toDouble()
    }

    /** Percent per hour from the µAh counter, against [estimatedFullChargeUah]. */
    fun percentPerHourFromCharge(): Double? {
        val ms = elapsedMs
        if (ms < SpikeTiming.BATTERY_MIN_PROJECTION_MS) return null
        val full = estimatedFullChargeUah
        if (full <= 0L) return null
        val delta = chargeDeltaUah
        if (delta == 0L) return null
        return -delta.toDouble() * 100.0 / full.toDouble() * 3_600_000.0 / ms.toDouble()
    }

    /**
     * Why a figure must not be quoted, or empty if it may be — with the caveat that "may be quoted"
     * still means "against a paired baseline run", which this class cannot check for.
     */
    fun invalidReason(maxCapture: Boolean, mode: SpikeMode): String = when {
        maxCapture ->
            "VOID — maxCapture (SCAN_MODE_LOW_LATENCY, 100% duty) is not the shipping duty. " +
                "Decision 44 / PHASE0_GO_NO_GO P1."
        everPlugged ->
            "VOID — device was plugged in during this run. Charging inverts the measurement and " +
                "discarding rows does not repair it. Re-run on battery."
        // BEFORE the too-short check, on purpose. "This run is a baseline" is a permanent fact
        // about what the file IS and never stops being true; "too short" stops being true after ten
        // minutes. Showing TOO SHORT first would mean a fresh baseline run describes itself as a
        // measurement-in-progress rather than as the subtrahend, which is the misreading this whole
        // string exists to prevent. Nothing is lost: the projections are null under the too-short
        // condition regardless, so no number is on screen to be misquoted.
        mode == SpikeMode.BATTERY_BASELINE ->
            "BASELINE — this is the device's own idle drain with our radio OFF. It is the " +
                "SUBTRAHEND, not the answer. Pair it with a scanning run on the same handset."
        elapsedMs < SpikeTiming.BATTERY_MIN_PROJECTION_MS ->
            "TOO SHORT — no projection before " +
                "${SpikeTiming.BATTERY_MIN_PROJECTION_MS / 60_000} min elapsed."
        else -> ""
    }
}
