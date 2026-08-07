package com.radius.android.spike

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

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
 *
 * ## Threading — THE LINE-INTEGRITY GUARANTEE IS A CONCURRENCY GUARANTEE
 *
 * This class is written from at least four threads: the diagnostics collector, the radio-event
 * collector, the battery sampler, the density-bucket loop, and whichever thread called `stop()`.
 * Every mutating method therefore takes [fileLock], and the counters are atomics.
 *
 * That is not tidiness, it is the file format. `BufferedWriter.appendLine` is `append(line)` then
 * `newLine()` — TWO acquisitions of the writer's own internal monitor — so two unsynchronised
 * threads interleave as `{"seq":41,…{"seq":42,…}\n}\n`. `events.jsonl` is documented above as
 * line-oriented precisely so that an OEM kill leaves a VALID file up to the last flush, and a torn
 * line breaks exactly that property: the analysis would find a corrupt record in a run whose
 * headline finding is "the OEM killed us here", i.e. in the run that matters most.
 *
 * `written`/`writeFailures`/`sinceFlush` are atomics for a sharper reason than lost increments. A
 * racy `write_failures` UNDER-reports, and under-reporting is the one direction that is not
 * survivable: the instrument would describe itself as cleaner than it was, and `integrityNote`
 * would print "no bridging observed" over a file with holes it does not know about.
 *
 * [fileLock] is a LEAF. Nothing inside this class calls back out, so it can be taken while holding
 * no other lock or none at all, and the controller never acquires it while holding its own — the
 * counter reads it does under its lock are atomic reads, which take nothing.
 */
internal class SpikeWriter(context: Context, val runId: String) {

    /**
     * Guards every file handle and every write. See the threading note in the class KDoc: this is
     * what makes one appended row exactly one line.
     */
    private val fileLock = Any()

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

    private var closed = false

    private val sinceFlush = AtomicLong(0L)
    private val writtenCount = AtomicLong(0L)
    private val writeFailureCount = AtomicLong(0L)
    private val postCloseWrites = AtomicLong(0L)

    val written: Long get() = writtenCount.get()

    /**
     * Rows this class was asked to write and could not. READ FROM OTHER THREADS WHILE THE RUN IS
     * LIVE — the battery row, the density bucket and `meta.json` all carry it — so it is an atomic
     * rather than a plain `Long`. An under-reported loss counter fails in the direction where the
     * capture claims to be cleaner than it is.
     */
    val writeFailures: Long get() = writeFailureCount.get()

    /** Exactly what a human types at a laptop. Shown on screen so nobody has to guess. */
    val adbPullCommand: String
        get() = "adb pull ${root.absolutePath.replace('\\', '/')} ./spike-$runId"

    val directory: String get() = root.absolutePath

    /**
     * Open every file for the run, or open none of them.
     *
     * THROWS, DELIBERATELY, AND THE CALLER MUST NOT ENTER A RUN IF IT DOES. External storage can be
     * unmounted, the directory can be unwritable on a locked-down OEM build, and the disk can be
     * full — all three are real, and a harness that half-opened its files and then scanned for
     * ninety minutes would produce an unreadable artefact from a genuine capture. On failure the
     * partially-opened handles are closed here so the throw does not also leak file descriptors,
     * and [SpikeController.start] turns the exception into a visible refusal rather than a crash
     * through `SpikeForegroundService.onStartCommand` — which by then has already called
     * `startForeground`, so an escaping throw kills the instrument with a notification on screen.
     */
    fun open(config: SpikeConfig, radioNotes: Map<String, String>) {
        synchronized(fileLock) {
            check(!closed) { "SpikeWriter for run $runId has already been closed" }
            try {
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
            } catch (error: Throwable) {
                closeHandlesLocked()
                throw error
            }
            Log.i(TAG, "spike run $runId -> ${root.absolutePath}")
        }
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
        //
        // EVERY FLAG IN THIS BLOCK IS NAMED `..._permitted_by_mode`, AND THAT NAMING IS THE FIX FOR
        // A REAL DEFECT FOUND BY READING A REAL CAPTURE OFF A REDMI 15 5G.
        //
        // They were `battery_figures_valid`, `bijection_valid` and `latency_figures_present`. On run
        // 20260807-054448 the header said `"battery_figures_valid": "true"` while every row of the
        // same run's `battery.csv` said `valid_for_drain=false`, because the handset was plugged in.
        // Neither statement was wrong: the flag meant "CAPTURE mode permits a battery figure", the
        // column meant "THIS sample was taken on battery and counts". But they read as a direct
        // contradiction, and the one three lines from the top of the file is the one a person reads
        // first — so a reader opening `meta.json` alone would conclude the battery numbers from a
        // charging run were good. Exactly backwards, in the one direction that is not survivable.
        //
        // These fields are written at OPEN and can therefore only describe the CONFIGURATION. The
        // verdict about the DATA cannot exist yet — it is derived from the rows and appended by
        // `closeMeta` at the end of the run, as `battery_samples_valid_for_drain`,
        // `bijection_screen_evidence` and `latency_figures_observed`. That split is load-bearing in
        // the other direction too: a run killed mid-flight by an OEM battery manager never reaches
        // `closeMeta`, and its header must not be left asserting a verdict about rows nobody counted.
        fields["mode"] = config.mode.name
        fields["mode_note"] = SpikeProcedure.headline(config.mode, config.maxCapture)
        fields["max_capture"] = config.maxCapture.toString()
        fields["mode_flags_note"] = MODE_FLAGS_NOTE
        fields["battery_figures_permitted_by_mode"] = config.batteryFiguresPermittedByMode.toString()
        fields["bijection_permitted_by_mode"] = config.mode.bijectionValid.toString()
        fields["latency_figures_permitted_by_mode"] =
            (config.mode == SpikeMode.LATENCY_PROBE).toString()
        // Timing constants, verbatim, because every one of them is an UNMEASURED GUESS and a run is
        // only reproducible against the values it actually used.
        SpikeTiming.describeForMeta().forEach { (k, v) -> fields[k] = v }
        radioNotes.forEach { (k, v) -> fields["radio_$k"] = v }
        metaFile.writeText(fields.entries.joinToString(",\n  ", "{\n  ", "\n}\n") {
            "${jsonString(it.key)}: ${jsonString(it.value)}"
        })
    }

    /** Update the header at the end of a run with the counters that only exist afterwards. */
    fun closeMeta(summary: Map<String, String>) = synchronized(fileLock) {
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
        synchronized(fileLock) {
            val target = events ?: return refuseLocked("events.jsonl")
            try {
                target.appendLine(line)
                writtenCount.incrementAndGet()
                maybeFlushLocked()
            } catch (io: IOException) {
                writeFailureCount.incrementAndGet()
            }
        }
    }

    fun appendSightingCsv(line: String) {
        synchronized(fileLock) {
            val target = sightings ?: return refuseLocked("sightings.csv")
            try {
                target.appendLine(line)
                maybeFlushLocked()
            } catch (io: IOException) {
                writeFailureCount.incrementAndGet()
            }
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
    fun appendBatteryCsv(line: String) = appendImmediate(line, "battery.csv") { battery }

    fun appendLatencyCsv(line: String) = appendImmediate(line, "latency.csv") { latency }

    fun appendDensityCsv(line: String) = appendImmediate(line, "density.csv") { density }

    fun appendDensityPeerCsv(line: String) =
        appendImmediate(line, "density_peers.csv") { densityPeers }

    private inline fun appendImmediate(
        line: String,
        name: String,
        target: () -> BufferedWriter?,
    ) {
        synchronized(fileLock) {
            val writer = target() ?: return refuseLocked(name)
            try {
                writer.appendLine(line)
                writer.flush()
            } catch (io: IOException) {
                writeFailureCount.incrementAndGet()
            }
        }
    }

    /**
     * A row arrived for a file that is not open. Caller must hold [fileLock].
     *
     * COUNTED AS A WRITE FAILURE, not ignored. Before this existed the handles were nulled by
     * [close] and every subsequent `target?.appendLine` was a SILENT no-op that still incremented
     * `written` — so the last rows of a run vanished and the file's own loss counter said zero. The
     * ordering fix is in `SpikeController.stop` (cancel AND JOIN the collectors before closing);
     * this is the backstop that makes the residual case audible instead of invisible.
     */
    private fun refuseLocked(name: String) {
        writeFailureCount.incrementAndGet()
        val n = postCloseWrites.incrementAndGet()
        if (n == 1L || n % 50L == 0L) {
            Log.w(TAG, "WRITE AFTER CLOSE ($name) — row LOST, counted as a write failure. total=$n")
        }
    }

    /** Caller must hold [fileLock]. */
    private fun maybeFlushLocked() {
        // Flush often. An OEM power manager killing the process is one of the things being
        // measured, and a 30-second write buffer would delete the evidence of the event that
        // matters most.
        if (sinceFlush.incrementAndGet() >= FLUSH_EVERY) {
            sinceFlush.set(0L)
            flushLocked()
        }
    }

    fun flush() {
        synchronized(fileLock) { flushLocked() }
    }

    /** Caller must hold [fileLock]. */
    private fun flushLocked() {
        runCatching { events?.flush() }
        runCatching { sightings?.flush() }
        runCatching { battery?.flush() }
        runCatching { latency?.flush() }
        runCatching { density?.flush() }
        runCatching { densityPeers?.flush() }
    }

    fun close() {
        synchronized(fileLock) {
            closed = true
            flushLocked()
            closeHandlesLocked()
        }
    }

    /** Caller must hold [fileLock]. Idempotent. */
    private fun closeHandlesLocked() {
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

        /**
         * Written into every `meta.json` immediately BEFORE the three capability flags, so that a
         * person scrolling from the top hits the disclaimer before the booleans rather than after.
         *
         * It names the three verdict keys explicitly, because the failure this prevents is someone
         * reading a capability flag as a verdict — and the cure for that is not a subtler word, it
         * is telling them which key actually answers their question.
         */
        const val MODE_FLAGS_NOTE: String =
            "The three ..._permitted_by_mode flags below describe THE MODE, not this run's data. " +
                "They are written when the run STARTS, before any row exists, so they cannot be " +
                "verdicts. What actually happened is computed from the rows at the end of the run: " +
                "battery_samples_valid_for_drain, bijection_screen_evidence, " +
                "latency_figures_observed. IF THOSE THREE KEYS ARE ABSENT the run never reached a " +
                "clean stop (OEM kill, crash, battery) and NO verdict was computed for it — read " +
                "the CSVs directly and trust nothing in this header about their contents."

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
         *
         * `elapsed_realtime_ms` IS THE DENOMINATOR. Divide by differences of that column, never by
         * differences of `wall_utc_ms`: the wall clock is rewritten by an NTP re-sync and the
         * monotonic clock is not. `wall_utc_ms − elapsed_realtime_ms` is constant across a clean run
         * and steps exactly where the platform re-synced, which is how a re-sync becomes visible
         * instead of becoming a wrong answer. See `SpikeBatterySample.elapsedRealtimeMs`.
         */
        val BATTERY_CSV_HEADER: String = listOf(
            "sample", "wall_utc_ms", "iso_utc", "elapsed_ms", "elapsed_realtime_ms",
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

        /**
         * A run id that is unique even when two runs start in the same second.
         *
         * IT USED TO BE SECOND-RESOLUTION, and the run id IS the directory name. Two starts inside
         * one second — a double tap, or a service restart racing the screen — produced the same
         * path, and the second run's `bufferedWriter()` TRUNCATED the first run's files. The
         * operator would have been left with one directory holding the tail of one capture and the
         * head of another, with a `meta.json` describing whichever wrote last. Milliseconds plus 24
         * bits of `SecureRandom` make that collision unreachable; the timestamp prefix stays first
         * so the directory listing still sorts chronologically.
         */
        fun newRunId(): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss.SSS", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(System.currentTimeMillis())
            val entropy = ByteArray(3).also { SecureRandom().nextBytes(it) }.toHex()
            return "$stamp-$entropy"
        }

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
