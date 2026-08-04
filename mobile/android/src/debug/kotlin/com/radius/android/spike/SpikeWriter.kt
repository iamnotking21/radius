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
 * `meta.json` is the HEADER: device, OS build, radio capabilities, config, and the honesty flags
 * (max-capture on/off, diagnostics dropped). A capture without its parameters is uninterpretable.
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

    private var events: BufferedWriter? = null
    private var sightings: BufferedWriter? = null

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
        // THE HONESTY FLAG. A battery figure from a max-capture run is fiction; recording the flag
        // in the header is what stops someone quoting one six weeks from now.
        fields["max_capture"] = config.maxCapture.toString()
        fields["battery_figures_valid"] = (!config.maxCapture).toString()
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
    }

    fun close() {
        flush()
        runCatching { events?.close() }
        runCatching { sightings?.close() }
        events = null
        sightings = null
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
