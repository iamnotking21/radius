package com.radius.android.spike

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The capture file. Append-only, on-device, `adb pull` and nothing else.
 *
 * ## Egress, stated precisely because P1 is a named prohibition
 *
 * `40-contracts` **P1**: a raw, filtered, hashed or truncated `ephemeral_id` must NEVER reach a
 * server on any endpoint, including diagnostics. This class is where that rule is most obviously
 * at risk, so the containment is structural rather than promised:
 *
 *  - **The app declares no `INTERNET` permission.** Not in `src/main/AndroidManifest.xml`, not in
 *    the debug manifest. The spike build cannot open a socket. Adding `INTERNET` to make some
 *    future thing work would be the single most important line in that diff.
 *  - This file lives in `src/debug/`. It is absent from a release APK.
 *  - The destination is app-specific external storage, which is removed on uninstall and readable
 *    by `adb` without root — deliberately, so pulling the data is a person's decision at a cable.
 *
 * ## Format, and why there are two of them
 *
 * `events.jsonl` is the RECORD: one JSON object per line, every sighting AND every radio lifecycle
 * event, in arrival order, with a sequence number. Line-oriented so a run killed by an OEM battery
 * manager still leaves a valid file up to the last flush.
 *
 * `sightings.csv` is the CONVENIENCE: the same sighting rows, flat, for a spreadsheet or pandas.
 * It is written from the same data in the same pass, so the two cannot disagree.
 *
 * `meta.json` is the HEADER: device, OS build, radio capabilities, config, the two clock references
 * that make a cross-device timestamp comparison auditable, every timing constant the run used, and
 * the honesty flags (mode, max-capture on/off, diagnostics dropped). A capture without its
 * parameters is uninterpretable, and a capture whose parameters are only knowable by reading the
 * source at the commit it was built from is a capture nobody can re-derive in six weeks.
 *
 * `battery.csv` is go/no-go **P1**: one row per sample, each carrying the radio state at that
 * instant. See `SpikeBattery.kt` for why the two are never in separate files.
 *
 * `latency.csv` is go/no-go **P2**: one row per first-sighting-per-cycle, plus the emitter's own
 * `EMIT_STARTED` rows so the transmit-side lag can be subtracted on a single clock. See
 * `SpikeLatency.kt` for the clock-skew argument — it is the load-bearing part of this measurement.
 *
 * `density.csv` / `density_peers.csv` are `SPEC.md` §5.0: acquisition rate and concurrent-peer
 * behaviour, bucketed, with empty buckets emitted because a success rate computed only over buckets
 * where something succeeded is 100 % by construction.
 *
 * ## What is NOT done to the data
 *
 * No dedup. No smoothing. No sampling. No rounding of RSSI. No merging of adjacent identical rows.
 * Sequence numbers are contiguous, so a gap in wall-clock time is visibly a gap and not a deletion.
 * If the radio saw the same advertiser twice in 4 ms, there are two rows. The analysis needs to see
 * what the radio saw, including the parts that look like noise, because "looks like noise" is a
 * conclusion and this file is evidence.
 */
internal class SpikeWriter(context: Context, val runId: String) {

    private val root: File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "spike/$runId")

    private val eventsFile = File(root, "events.jsonl")
    private val sightingsFile = File(root, "sightings.csv")
    private val metaFile = File(root, "meta.json")
    private val batteryFile = File(root, "battery.csv")
    private val latencyFile = File(root, "latency.csv")
    private val densityFile = File(root, "density.csv")
    private val densityPeersFile = File(root, "density_peers.csv")

    private var events: BufferedWriter? = null
    private var sightings: BufferedWriter? = null
    private var battery: BufferedWriter? = null
    private var latency: BufferedWriter? = null
    private var density: BufferedWriter? = null
    private var densityPeers: BufferedWriter? = null

    private var sinceFlush = 0
    var written: Long = 0L
        private set
    var writeFailures: Long = 0L
        private set

    /** Exactly what a human types at a laptop. Shown on screen so nobody has to guess. */
    val adbPullCommand: String
        get() = "adb pull ${root.absolutePath.replace('\\', '/')} ./spike-$runId"

    val directory: String get() = root.absolutePath

    fun open(config: SpikeConfig, radioNotes: Map<String, String>) {
        root.mkdirs()
        events = eventsFile.bufferedWriter()
        sightings = sightingsFile.bufferedWriter()
        sightings?.appendLine(CSV_HEADER)
        battery = batteryFile.bufferedWriter()
        battery?.appendLine(BATTERY_CSV_HEADER)
        latency = latencyFile.bufferedWriter()
        latency?.appendLine(LATENCY_CSV_HEADER)
        density = densityFile.bufferedWriter()
        density?.appendLine(DENSITY_CSV_HEADER)
        densityPeers = densityPeersFile.bufferedWriter()
        densityPeers?.appendLine(DENSITY_PEERS_CSV_HEADER)
        writeMeta(config, radioNotes)
        Log.i(TAG, "spike run $runId -> ${root.absolutePath}")
    }

    private fun writeMeta(config: SpikeConfig, radioNotes: Map<String, String>) {
        val fields = LinkedHashMap<String, String>()
        fields["run_id"] = runId
        fields["started_utc"] = isoUtc(System.currentTimeMillis())
        fields["schema"] = SCHEMA
        fields["device_manufacturer"] = Build.MANUFACTURER
        fields["device_model"] = Build.MODEL
        fields["device_board"] = Build.BOARD
        fields["device_hardware"] = Build.HARDWARE
        fields["os_release"] = Build.VERSION.RELEASE
        fields["os_sdk_int"] = Build.VERSION.SDK_INT.toString()
        fields["os_build_id"] = Build.DISPLAY
        // KEY_SCHEDULE.md §4.3.3 wants (model, OS build) as the row key and a re-test after any
        // OTA touching the Bluetooth stack. The fingerprint is what makes "same model, different
        // firmware" distinguishable in the results, which §4.3.4 INCONSISTENT depends on.
        fields["os_fingerprint"] = Build.FINGERPRINT
        fields["config"] = config.describe()
        // THE HONESTY FLAGS. A battery figure from a max-capture run is fiction, and a bijection
        // verdict from a latency-probe run is self-inflicted; recording the flags in the header is
        // what stops someone quoting either six weeks from now.
        fields["mode"] = config.mode.name
        fields["mode_note"] = SpikeProcedure.headline(config.mode, config.maxCapture)
        fields["max_capture"] = config.maxCapture.toString()
        fields["battery_figures_valid"] = config.batteryFiguresValid.toString()
        fields["bijection_valid"] = config.mode.bijectionValid.toString()
        fields["latency_figures_present"] = (config.mode == SpikeMode.LATENCY_PROBE).toString()
        // Timing constants, verbatim, because every one of them is an UNMEASURED GUESS and a run is
        // only reproducible against the values it actually used.
        SpikeTiming.describeForMeta().forEach { (k, v) -> fields[k] = v }
        radioNotes.forEach { (k, v) -> fields["radio_$k"] = v }
        metaFile.writeText(fields.entries.joinToString(",\n  ", "{\n  ", "\n}\n") {
            "${jsonString(it.key)}: ${jsonString(it.value)}"
        })
    }

    /** Update the header at the end of a run with the counters that only exist afterwards. */
    fun closeMeta(summary: Map<String, String>) {
        try {
            val existing = metaFile.readText().trimEnd().removeSuffix("}").trimEnd().removeSuffix(",")
            val extra = summary.entries.joinToString(",\n  ") {
                "${jsonString(it.key)}: ${jsonString(it.value)}"
            }
            metaFile.writeText("$existing,\n  $extra\n}\n")
        } catch (io: IOException) {
            Log.w(TAG, "could not finalise meta.json", io)
        }
    }

    fun appendEventJson(line: String) {
        try {
            events?.appendLine(line)
            written++
            maybeFlush()
        } catch (io: IOException) {
            writeFailures++
        }
    }

    fun appendSightingCsv(line: String) {
        try {
            sightings?.appendLine(line)
            maybeFlush()
        } catch (io: IOException) {
            writeFailures++
        }
    }

    /**
     * Battery, latency and density rows are flushed IMMEDIATELY rather than on the shared counter.
     *
     * They are low-rate (one per minute at most) so the cost is nothing, and they are exactly the
     * rows most likely to be lost to the thing being measured: an OEM battery manager killing the
     * process is a Phase 0 FINDING, and losing the last battery sample before the kill deletes the
     * evidence of the moment that matters most.
     */
    fun appendBatteryCsv(line: String) = appendImmediate(battery, line)

    fun appendLatencyCsv(line: String) = appendImmediate(latency, line)

    fun appendDensityCsv(line: String) = appendImmediate(density, line)

    fun appendDensityPeerCsv(line: String) = appendImmediate(densityPeers, line)

    private fun appendImmediate(target: BufferedWriter?, line: String) {
        try {
            target?.appendLine(line)
            target?.flush()
        } catch (io: IOException) {
            writeFailures++
        }
    }

    private fun maybeFlush() {
        // Flush often. An OEM power manager killing the process is one of the things being
        // measured, and a 30-second write buffer would delete the evidence of the event that
        // matters most.
        if (++sinceFlush >= FLUSH_EVERY) {
            sinceFlush = 0
            flush()
        }
    }

    fun flush() {
        runCatching { events?.flush() }
        runCatching { sightings?.flush() }
        runCatching { battery?.flush() }
        runCatching { latency?.flush() }
        runCatching { density?.flush() }
        runCatching { densityPeers?.flush() }
    }

    fun close() {
        flush()
        runCatching { events?.close() }
        runCatching { sightings?.close() }
        runCatching { battery?.close() }
        runCatching { latency?.close() }
        runCatching { density?.close() }
        runCatching { densityPeers?.close() }
        events = null
        sightings = null
        battery = null
        latency = null
        density = null
        densityPeers = null
    }

    companion object {
        private const val TAG = "SpikeWriter"
        private const val FLUSH_EVERY = 25
        const val SCHEMA = "radius-spike/1"

        val CSV_HEADER: String = listOf(
            "seq", "wall_utc_ms", "iso_utc", "elapsed_ms",
            "advertiser_addr", "addr_type_bits", "rssi_dbm",
            "carrier", "decode_error",
            "eid_hex", "version", "tx_power_cal_dbm", "flags_connectable",
            "resolved_slot", "self_eid",
            "band", "confidence", "display_metres",
            "pdu_connectable", "pdu_legacy", "data_status",
            "primary_phy", "secondary_phy", "adv_sid", "tx_power_adv",
            "timestamp_nanos", "day_index", "epoch_index",
            "service_data_hex",
        ).joinToString(",")

        /**
         * go/no-go **P1**. The radio-state columns are NOT decoration and must not be dropped to
         * make the file tidier: a %/hr figure without `scan_on_ms_cum`, `scan_mode` and
         * `max_capture` beside it is unattributable, and an unattributable number is how a
         * 100 %-duty scan mode survived review once already (decision 44).
         *
         * `valid_for_drain` is the composite: false means this row must not contribute to a %/hr
         * figure at all. `screen_interactive` deliberately does NOT clear it — a screen-on run is a
         * legitimate measurement of a different thing, and silently voiding it would hide that
         * choice instead of recording it.
         */
        val BATTERY_CSV_HEADER: String = listOf(
            "sample", "wall_utc_ms", "iso_utc", "elapsed_ms",
            "level_pct", "raw_level", "scale",
            "charge_counter_uah", "current_now_ua_RAW_SIGN_UNVERIFIED", "energy_counter_nwh",
            "voltage_mv", "temperature_deci_c",
            "plugged", "plugged_raw", "status", "health",
            "screen_interactive", "power_save_mode", "device_idle_mode",
            "mode", "duty_profile", "scan_mode", "nominal_scan_duty_pct", "max_capture",
            "scanning", "advertising", "advertise_status",
            "scan_on_ms_cum", "advertise_on_ms_cum", "scan_open_transitions",
            "sightings_cum", "distinct_peers_cum", "concurrent_peers",
            "diagnostics_dropped_cum", "write_failures_cum",
            "valid_for_drain",
        ).joinToString(",")

        /**
         * go/no-go **P2**. `event` is `SCAN_FIRST_SEEN` (this device saw a peer) or `EMIT_STARTED` /
         * `EMIT_STOPPED` (this device's own transmitter, on this device's own clock — the only
         * single-clock term in the whole measurement, and the one that lets transmit-side lag be
         * subtracted honestly).
         *
         * `latency_ms` is UNCORRECTED for clock offset between handsets. A negative value is direct
         * proof of skew. Read `SpikeLatency.kt` before quoting a percentile of this column.
         */
        val LATENCY_CSV_HEADER: String = listOf(
            "event", "cycle_index", "epoch_cycle_index", "cycle_start_utc_ms",
            "peer_key", "resolved_slot", "at_utc_ms",
            "latency_ms_UNCORRECTED_FOR_CLOCK_SKEW",
            "after_on_window", "negative_proves_skew",
            "duty_profile", "scan_mode", "day_index", "epoch_index",
        ).joinToString(",")

        /**
         * `SPEC.md` §5.0. One row per bucket, INCLUDING buckets in which nothing was heard.
         *
         * `scan_on_ms_in_bucket` and the two loss deltas are what separate "the room was empty" from
         * "our scan was shut" and from "we dropped the records" — three completely different Phase 0
         * findings that look identical in a packet count.
         */
        val DENSITY_CSV_HEADER: String = listOf(
            "bucket", "start_utc_ms", "iso_utc", "end_utc_ms", "bucket_ms",
            "packets", "decode_failures", "carrier_b_candidates",
            "distinct_peers", "distinct_addresses", "distinct_eids", "distinct_slots",
            "concurrent_peers_at_end", "peer_liveness_window_ms",
            "scan_on_ms_in_bucket", "nominal_scan_duty_pct",
            "nominal_adv_interval_ms", "expected_packets_per_peer_NOMINAL",
            "diagnostics_dropped_in_bucket", "write_failures_in_bucket",
            "mode", "duty_profile", "scan_mode", "max_capture",
        ).joinToString(",")

        /** Per-peer detail within a bucket. `max_gap_ms` separates a steady stream from one burst. */
        val DENSITY_PEERS_CSV_HEADER: String = listOf(
            "bucket", "start_utc_ms", "peer_key", "resolved_slot",
            "packets", "first_seen_utc_ms", "last_seen_utc_ms", "span_ms", "max_gap_ms",
        ).joinToString(",")

        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun isoUtc(millis: Long): String = synchronized(ISO) { ISO.format(millis) }

        fun newRunId(): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(System.currentTimeMillis())

        fun jsonString(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

/** Lower-case hex. Used ONLY for the on-device capture file — never for a log line, never on air. */
internal fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

private val HEX = "0123456789abcdef".toCharArray()
