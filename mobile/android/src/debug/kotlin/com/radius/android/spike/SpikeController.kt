package com.radius.android.spike

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.radius.shared.ble.AdvertisePayloadSource
import com.radius.shared.ble.AdvertiseRequest
import com.radius.shared.ble.AdvertiseState
import com.radius.shared.ble.AdvertiseRoleSource
import com.radius.shared.ble.AndroidRadioEvent
import com.radius.shared.ble.AndroidSightingDiagnostic
import com.radius.shared.ble.BleOutcome
import com.radius.shared.ble.BleRadio
import com.radius.shared.ble.ScanRequest
import com.radius.shared.protocol.AdvertisementCodec
import com.radius.shared.protocol.AdvertiseRole
import com.radius.shared.protocol.BandingPipeline
import com.radius.shared.protocol.BleFrame
import com.radius.shared.protocol.BleFrameCodec
import com.radius.shared.protocol.KeyRing
import com.radius.shared.protocol.KeySchedule
import com.radius.shared.protocol.ProtocolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE PHASE 0 SPIKE HARNESS. Debug source set only. Not shipped, not shippable.
 *
 * ## What it is for
 *
 * Phase 0 is a go/no-go on the moat, and the deliverable is a MEASUREMENT, not a feature. This
 * class drives the real [BleRadio] with the real `mobile/shared/protocol/` codec and key schedule,
 * and writes down exactly what came back. It is deliberately more careful about RECORDING than
 * about anything else, because a spike that produces a pretty number nobody can audit has produced
 * nothing.
 *
 * ## The four rules it follows about data
 *
 *  1. **No dedup, no smoothing, no sampling.** Every advertisement is a row. Two identical rows
 *     4 ms apart stay two rows.
 *  2. **The radio's own lifecycle goes in the same file, on the same clock.** A gap with no
 *     explanation is a gap someone will explain wrongly — "the room was empty" and "our scan was
 *     throttled" and "the OEM killed us" look identical in a sightings-only log and are three
 *     different Phase 0 findings.
 *  3. **Our own losses are counted.** [BleRadio.diagnosticsDropped] and write failures are in the
 *     header and on the screen. The B8 question is an assertion about ABSENCE, so an uncounted
 *     drop invalidates the conclusion rather than degrading it.
 *  4. **Raw bytes are kept.** The full service data goes in the file as hex, so an off-device
 *     analysis can re-derive anything this class computed and disagree with it.
 *
 * ## What it does NOT do, and must not learn to
 *
 * It does not upload. It cannot: the app declares no `INTERNET` permission in any manifest, main
 * or debug. `40-contracts` **P1** forbids an observed `ephemeral_id` reaching a server by any
 * route including diagnostics, and the enforcement here is the absence of a socket rather than a
 * promise about intent.
 */
@Singleton
internal class SpikeController @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val radio: BleRadio,
) {

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val _stats = MutableStateFlow(SpikeStats())
    val stats: StateFlow<SpikeStats> = _stats.asStateFlow()

    private val _config = MutableStateFlow(SpikeConfig())
    val config: StateFlow<SpikeConfig> = _config.asStateFlow()

    private var writer: SpikeWriter? = null
    private var jobs: MutableList<Job> = mutableListOf()
    private var startedAtMillis = 0L
    private var seq = 0L
    private var lastPublishUptimeMs = 0L

    // --- accumulators. Only touched from [scope], which is single-writer per stream via `mutex`.
    private val addrToEids = LinkedHashMap<String, MutableSet<String>>()
    private val eidToAddrs = LinkedHashMap<String, MutableSet<String>>()
    private val resolvedBySlot = LinkedHashMap<Int, Long>()
    private val decodeErrors = LinkedHashMap<String, Long>()
    private val bandCounts = LinkedHashMap<String, Long>()
    private val addressTypeCounts = LinkedHashMap<String, Long>()
    private val pipelines = LinkedHashMap<String, BandingPipeline>()
    private val peerDisplaySeeds = LinkedHashMap<String, ByteArray>()

    private var sightingCount = 0L
    private var productStreamCount = 0L
    private var carrierBCount = 0L
    private var selfEidDrops = 0L
    private var scanFailures = 0L
    private var scanThrottles = 0L
    private var scanStarts = 0L
    private var epochRotations = 0L
    private var adapterEvents = 0L
    private var lastEventLine = ""

    // --- the three Phase 0 measurements this class was extended to record. Each one lives in its
    // own file with its own argument about what it can and cannot claim; this class only drives
    // them and writes their rows down.
    private val batteryReader = SpikeBatteryReader(context)
    private val drain = BatteryDrainEstimator()
    private val latency = LatencyTracker()
    private val densityAccumulator = DensityAccumulator()
    private val duty = SpikeDutyLedger()

    private var batterySamples = 0L
    private var latencyRowsWritten = 0L
    private var densityBucketsWritten = 0L
    private var concurrentPeersNow = 0
    private var emitStartLagMs = -1L

    /**
     * Held so the latency probe can stop and restart the SAME advertisement each cycle without
     * rebuilding the payload source. The source itself is stateless by construction
     * (`AdvertisePayloadSource` takes the epoch as a parameter), so restarting cannot resurrect a
     * stale frame — that is the whole reason the port takes a supplier rather than bytes.
     */
    private var advertiseRequest: AdvertiseRequest? = null

    private var startClock: SpikeClockReference? = null

    private val lock = Any()

    /** Per-run, never persisted, never transmitted. `BANDING.md` §6.2. */
    private val sessionSalt = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private lateinit var ownRing: KeyRing

    /**
     * Precomputed `eidHex -> slot` for the current epoch and its immediate neighbours.
     *
     * `KEY_SCHEDULE.md` §6.1 requires resolution to be a PRECOMPUTED TABLE LOOKUP, not a per
     * -observation scan, and §8.4 requires the ring to be evaluated PER EPOCH and never cached
     * across a boundary. The table is therefore rebuilt whenever the epoch index changes and is
     * keyed by the epoch it was built for, so a stale one is detectable rather than silently used.
     * That is the same bug shape the shared-API contract calls out: caching a resolution across a
     * seam yields exactly one wrong epoch per rotation and passes every test that does not
     * straddle it.
     */
    private var resolutionTable: Map<String, Int> = emptyMap()
    private var resolutionTableEpoch: Long = Long.MIN_VALUE

    // -------------------------------------------------------------------------------------------

    fun updateConfig(config: SpikeConfig) {
        if (_stats.value.running) {
            // A run whose parameters changed halfway through is two measurements someone will
            // average. Restart instead, into a new file.
            stop()
        }
        _config.value = config
    }

    fun start() {
        synchronized(lock) {
            if (_stats.value.running) return
            resetAccumulators()
        }

        val cfg = _config.value
        ownRing = SpikeKeys.ringForSlot(cfg.deviceSlot)

        val runId = SpikeWriter.newRunId()
        val w = SpikeWriter(context, runId)

        // THE CLOCK REFERENCE, TAKEN BEFORE ANYTHING ELSE. Two handsets, two clocks, and every
        // cross-device number in this run is wrong by their difference until somebody subtracts it.
        // Recorded at start and again at stop so the DRIFT over the run is visible, which is the
        // error bar on that subtraction. See SpikeLatency.kt METHOD 3.
        val clockAtStart = SpikeClockReference.capture()
        startClock = clockAtStart

        val notes = LinkedHashMap<String, String>()
        notes["availability"] = radio.availability.value.name
        notes["peripheral_role_supported"] = radio.peripheralRoleSupported.toString()
        notes["multiple_advertisement_supported"] = radio.multipleAdvertisementSupported.toString()
        notes["service_uuid16"] = SERVICE_UUID_HEX
        notes["service_uuid16_status"] = "PROVISIONAL, NOT SIG-ALLOCATED, MUST NOT SHIP (SPEC §4.1)"
        notes["frame_bytes"] = BleFrameCodec.FRAME_LENGTH_BYTES.toString()
        notes["carrier_a_bytes"] = AdvertisementCodec.CARRIER_A_LENGTH_BYTES.toString()
        notes["scan_mode_effective"] =
            SpikeTiming.scanModeName(SpikeTiming.effectiveScanMode(cfg))
        notes["scan_duty_nominal_pct"] =
            SpikeTiming.nominalScanDutyPct(SpikeTiming.effectiveScanMode(cfg)).toString()
        notes["scan_duty_caveat"] =
            "NOMINAL AOSP value. Several OEMs ship their own windows. Host-level scan_on_ms is " +
                "REQUESTED radio time, not receiver-on time — see SpikeDutyLedger.kt."
        clockAtStart.describe("clock_start").forEach { (k, v) -> notes[k] = v }
        batteryReader.capabilities().forEach { (k, v) -> notes[k] = v }

        w.open(config = cfg, radioNotes = notes)
        writer = w
        startedAtMillis = System.currentTimeMillis()
        duty.start()

        record(
            EventKind.CONTROL,
            "run ${runId} mode=${cfg.mode} — ${SpikeProcedure.headline(cfg.mode, cfg.maxCapture)}",
        )

        if (!cfg.mode.usesRadio) {
            // BATTERY_BASELINE. The radio is never started: no scan, no advertisement, no
            // diagnostics stream. The battery sampler and the foreground service run, and nothing
            // is on the air. That is the whole experiment — see SpikeMode.BATTERY_BASELINE.
            record(
                EventKind.CONTROL,
                "BATTERY BASELINE — radio deliberately NOT started. Zero sightings is the correct " +
                    "result for this mode, not a failure.",
            )
            jobs += scope.launch { batterySamplerLoop() }
            publish(running = true)
            return
        }

        radio.setDiagnosticsEnabled(true, "phase-0 spike harness, run $runId")
        radio.setScanModeOverride(cfg.scanModeOverride, "spike maxCapture=${cfg.maxCapture}")

        // §8.5.2 KEY DESTRUCTION. `pruneSupersededAt` is a CALLER OBLIGATION — the library cannot
        // self-trigger, so a platform that never calls it retains every key the device has ever
        // held and ADR-008 M4's rotation bounds nothing. The radio owns the only UTC-boundary
        // timer, so it announces the boundary and we, holding the ring, act on it.
        //
        // Called on EVERY boundary, not only on seams: it is idempotent and cheap when there is
        // nothing to do, and detecting seams here would be a second copy of the ring logic in the
        // wrong module — the exact bug shape §8.4 spends a page on.
        //
        // In THIS harness the ring has one entry and can never have a seam, so this will destroy
        // nothing and return 0 forever. Wired anyway, and the count recorded: an unexercised code
        // path that is never called is indistinguishable from one that does not exist, and the
        // point of the spike is to run the real arrangement rather than a demonstration of it.
        radio.setEpochBoundaryListener { day, epoch ->
            val destroyed = ownRing.pruneSupersededAt(day, epoch)
            record(
                EventKind.CONTROL,
                "epoch boundary day=$day epoch=$epoch pruneSupersededAt destroyed=$destroyed " +
                    "ring=$ownRing",
                toLogcat = false,
            )
        }

        jobs += scope.launch { radio.androidDiagnostics.collect(::onDiagnostic) }
        jobs += scope.launch { radio.androidEvents.collect(::onRadioEvent) }
        jobs += scope.launch {
            // Counted, not recorded. The product stream carries the same service data; if the two
            // counts diverge that is a radio bug, and it is cheaper to notice it here than in the
            // field notes six weeks later.
            radio.sightings.collect { synchronized(lock) { productStreamCount++ } }
        }
        jobs += scope.launch { radio.advertiseState.collect(::onAdvertiseState) }

        val scan = radio.startScan(ScanRequest(serviceUuid16 = SERVICE_UUID_HEX, duty = cfg.duty))
        record(EventKind.CONTROL, "startScan -> $scan")

        if (cfg.advertise) {
            // THE ONLY NON-PRODUCTION GRANT IN THE CODEBASE. Greppable by
            // `AdvertiseRoleSource.DEBUG_SPIKE_HARNESS`, which is the point of the enum. The real
            // grant arrives with key issuance (KEY_SCHEDULE.md §2.2), which needs a server that
            // does not exist yet (B3).
            radio.setAdvertiseRole(AdvertiseRole.ADVERTISE, AdvertiseRoleSource.DEBUG_SPIKE_HARNESS)
            val request = AdvertiseRequest(
                payloadSource = spikePayloadSource(cfg),
                serviceUuid16 = SERVICE_UUID_HEX,
                duty = cfg.duty,
            )
            advertiseRequest = request

            if (cfg.mode == SpikeMode.LATENCY_PROBE) {
                // DO NOT start advertising here. The probe owns the transmitter from now on and
                // starts it at the next UTC-aligned cycle boundary, so that the scanner's notion of
                // "the peer started transmitting at T" is derived from an absolute schedule rather
                // than from when somebody pressed a button. See SpikeLatency.kt METHOD 1.
                record(
                    EventKind.CONTROL,
                    "latency probe owns the transmitter: cycle=${SpikeTiming.LATENCY_CYCLE_MS}ms " +
                        "on=${SpikeTiming.LATENCY_ON_MS}ms, UTC-aligned, no operator action",
                )
                jobs += scope.launch { latencyEmitterLoop() }
            } else {
                val adv = radio.startAdvertising(request)
                record(EventKind.CONTROL, "startAdvertising -> $adv")
            }
        } else {
            // Belt and braces on top of the radio's own default. Decision 35 is the control that
            // stops two devices broadcasting one identity, so it gets two independent closed doors.
            radio.setAdvertiseRole(AdvertiseRole.SCAN_ONLY, AdvertiseRoleSource.DEBUG_SPIKE_HARNESS)
            record(EventKind.CONTROL, "scan-only: no advertise role requested")
            if (cfg.mode == SpikeMode.LATENCY_PROBE) {
                record(
                    EventKind.CONTROL,
                    "LATENCY PROBE, SCAN SIDE ONLY. The paired-sum estimator that cancels clock " +
                        "skew exactly needs BOTH handsets advertising. This run yields " +
                        "one-directional, skew-biased numbers only.",
                )
            }
        }

        jobs += scope.launch { batterySamplerLoop() }
        jobs += scope.launch { densityBucketLoop() }

        publish(running = true)
    }

    // -------------------------------------------------------------------------------------------
    // measurement loops
    // -------------------------------------------------------------------------------------------

    /**
     * go/no-go **P1**. One battery row per [SpikeTiming.BATTERY_SAMPLE_MS], each carrying the radio
     * state at that instant. See `SpikeBattery.kt` for why the two are never separable afterwards.
     *
     * The FIRST sample is taken immediately, before any delay: a run that dies after four minutes
     * still needs a t=0 point, or its one surviving sample is a level with nothing to subtract from.
     */
    private suspend fun batterySamplerLoop() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            sampleBattery()
            delay(SpikeTiming.BATTERY_SAMPLE_MS)
        }
    }

    private fun sampleBattery() {
        val w = writer ?: return
        val cfg = _config.value
        val sample = batteryReader.read()
        val n = synchronized(lock) { ++batterySamples }
        drain.add(sample)

        val scanMode = SpikeTiming.effectiveScanMode(cfg)
        val validForDrain = cfg.batteryFiguresValid && !sample.plugged

        w.appendBatteryCsv(
            listOf(
                n.toString(),
                sample.wallUtcMs.toString(),
                SpikeWriter.isoUtc(sample.wallUtcMs),
                (sample.wallUtcMs - startedAtMillis).toString(),
                sample.levelPct.toString(),
                sample.rawLevel.toString(),
                sample.scale.toString(),
                sample.chargeCounterUah.toString(),
                sample.currentNowUa.toString(),
                sample.energyCounterNwh.toString(),
                sample.voltageMv.toString(),
                sample.temperatureDeciC.toString(),
                sample.pluggedName,
                sample.pluggedRaw.toString(),
                sample.statusName,
                sample.healthRaw.toString(),
                sample.screenInteractive.toString(),
                sample.powerSaveMode.toString(),
                sample.deviceIdleMode.toString(),
                cfg.mode.name,
                cfg.duty.name,
                SpikeTiming.scanModeName(scanMode),
                SpikeTiming.nominalScanDutyPct(scanMode).toString(),
                cfg.maxCapture.toString(),
                duty.scanning.toString(),
                duty.advertising.toString(),
                radio.advertiseState.value.status.name,
                duty.scanOnMs().toString(),
                duty.advertiseOnMs().toString(),
                duty.scanOpenTransitions.toString(),
                sightingCount.toString(),
                densityAccumulator.distinctPeersEver.toString(),
                concurrentPeersNow.toString(),
                radio.diagnosticsDropped.toString(),
                w.writeFailures.toString(),
                validForDrain.toString(),
            ).joinToString(",") { csvCell(it) },
        )
        publish(running = _stats.value.running)
    }

    /**
     * `SPEC.md` §5.0. Closes and writes each acquisition-rate bucket on a timer, so that a bucket in
     * which NOTHING was heard is still written. A sightings-driven flush would omit the dead minutes,
     * and the dead minutes are the measurement.
     */
    private suspend fun densityBucketLoop() {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            val nextBoundary = (Math.floorDiv(now, SpikeTiming.DENSITY_BUCKET_MS) + 1) *
                SpikeTiming.DENSITY_BUCKET_MS
            delay((nextBoundary - now).coerceAtLeast(1L) + BUCKET_SETTLE_MS)
            val w = writer ?: return
            val completed = synchronized(lock) {
                densityAccumulator.advanceTo(
                    System.currentTimeMillis(),
                    radio.diagnosticsDropped,
                    w.writeFailures,
                    duty.scanOnMs(),
                )
            }
            completed.forEach(::writeDensityBucket)
            synchronized(lock) {
                concurrentPeersNow = densityAccumulator.concurrentPeers(System.currentTimeMillis())
            }
            publish(running = _stats.value.running)
        }
    }

    /**
     * go/no-go **P2**, emitter side. Turns the transmitter on and off on the UTC-aligned cycle.
     *
     * Every transition is written to `latency.csv` with the lag from the nominal cycle boundary,
     * measured on THIS device's clock only. That single-clock number is the transmit-side component
     * of discovery latency, and being able to subtract it is what separates "our software took 900 ms
     * to get on air" from "the air took 900 ms", which are different findings with different fixes.
     */
    private suspend fun latencyEmitterLoop() {
        // AT MOST ONE START PER CYCLE. Without this, a loop iteration that wakes shortly before the
        // ON→OFF transition (delay overshoot, a Doze wake, a slow `startAdvertising`) starts the
        // transmitter a second time inside the same ON window and writes an EMIT_STARTED row with a
        // ~19 900 ms lag. That row is indistinguishable from a genuinely catastrophic transmit
        // delay, and it would be entirely manufactured by the instrument.
        var lastStartedCycle = Long.MIN_VALUE
        var lastStoppedCycle = Long.MIN_VALUE
        while (kotlin.coroutines.coroutineContext.isActive) {
            val now = System.currentTimeMillis()
            if (LatencyCycle.inOnWindow(now)) {
                val cycleStart = LatencyCycle.cycleStartMs(now)
                val cycle = LatencyCycle.cycleIndex(now)
                val request = advertiseRequest.takeIf { cycle != lastStartedCycle }
                if (request != null) {
                    lastStartedCycle = cycle
                    val outcome = radio.startAdvertising(request)
                    val at = System.currentTimeMillis()
                    val lag = at - cycleStart
                    synchronized(lock) { emitStartLagMs = lag }
                    writeLatencyRow(
                        event = "EMIT_STARTED",
                        cycleStartMs = cycleStart,
                        atMs = at,
                        peerKey = "SELF",
                        resolvedSlot = _config.value.deviceSlot.toString(),
                        latencyMs = lag,
                    )
                    record(
                        EventKind.CONTROL,
                        "latency cycle ON: startAdvertising -> $outcome lag=${lag}ms",
                        toLogcat = false,
                    )
                }
                delay(LatencyCycle.millisUntilNextTransition(System.currentTimeMillis())
                    .coerceAtLeast(1L))
            } else {
                // Same one-per-cycle guard as the ON branch. `stopAdvertising` is idempotent at the
                // radio, but the ROW is not — a repeat would put two EMIT_STOPPED entries in one
                // cycle and make the OFF window look like it was entered twice.
                val cycle = LatencyCycle.cycleIndex(now)
                if (cycle != lastStoppedCycle) {
                    lastStoppedCycle = cycle
                    val cycleStart = LatencyCycle.cycleStartMs(now)
                    radio.stopAdvertising()
                    writeLatencyRow(
                        event = "EMIT_STOPPED",
                        cycleStartMs = cycleStart,
                        atMs = System.currentTimeMillis(),
                        peerKey = "SELF",
                        resolvedSlot = _config.value.deviceSlot.toString(),
                        latencyMs = System.currentTimeMillis() - cycleStart,
                    )
                }
                delay(LatencyCycle.millisUntilNextTransition(System.currentTimeMillis())
                    .coerceAtLeast(1L))
            }
        }
    }

    fun stop() {
        val w = writer
        radio.stopAdvertising()
        radio.setAdvertiseRole(AdvertiseRole.SCAN_ONLY, AdvertiseRoleSource.DEBUG_SPIKE_HARNESS)
        radio.stopScan()
        radio.setEpochBoundaryListener(null)
        radio.setDiagnosticsEnabled(false, "spike stopped")
        radio.setScanModeOverride(null, "spike stopped")

        // CLOSE THE LEDGER HERE, SYNCHRONOUSLY, rather than waiting for the SCAN_STOPPED event to
        // arrive through the flow. The event collector is about to be cancelled and the emission is
        // asynchronous, so relying on it is a race whose losing side silently leaves the final scan
        // interval open — inflating scan_on_ms by however long the process happens to survive.
        synchronized(lock) {
            duty.onScanClosed()
            duty.onAdvertiseStopped()
        }

        jobs.forEach { it.cancel() }
        jobs = mutableListOf()

        if (w != null) {
            // One last battery point at the moment of stopping, so the run's endpoints are the run's
            // endpoints and not "whenever the 60-second timer last happened to fire".
            runCatching { sampleBattery() }

            // Flush whatever bucket the run died in. A partial bucket is labelled by its own
            // start/end timestamps and the analysis can see it is short; DISCARDING it would delete
            // the minutes immediately before a stop, which for an OEM-kill run is the interesting part.
            val trailing = synchronized(lock) {
                densityAccumulator.advanceTo(
                    System.currentTimeMillis() + SpikeTiming.DENSITY_BUCKET_MS,
                    radio.diagnosticsDropped,
                    w.writeFailures,
                    duty.scanOnMs(),
                )
            }
            trailing.forEach(::writeDensityBucket)

            val cfg = _config.value
            val endClock = SpikeClockReference.capture()
            val summary = LinkedHashMap<String, String>()
            summary["ended_utc"] = SpikeWriter.isoUtc(System.currentTimeMillis())
            summary["duration_ms"] = (System.currentTimeMillis() - startedAtMillis).toString()
            summary["sightings"] = sightingCount.toString()
            summary["unique_advertiser_addresses"] = addrToEids.size.toString()
            summary["unique_ephemeral_ids"] = eidToAddrs.size.toString()
            summary["bridged_addresses"] = bridgedAddresses().toString()
            summary["bridged_eids"] = bridgedEids().toString()
            summary["diagnostics_dropped"] = radio.diagnosticsDropped.toString()
            summary["write_failures"] = w.writeFailures.toString()
            summary["integrity"] = _stats.value.integrityNote

            // ---- P1 battery ----
            summary["battery_samples"] = batterySamples.toString()
            summary["battery_level_delta_pct"] = drain.levelDeltaPct.toString()
            summary["battery_charge_delta_uah"] = drain.chargeDeltaUah.toString()
            summary["battery_ever_plugged"] = drain.everPlugged.toString()
            summary["battery_screen_on_samples"] = drain.screenOnSamples.toString()
            summary["battery_invalid_reason"] = drain.invalidReason(cfg.maxCapture, cfg.mode)
            summary["scan_on_ms_total"] = duty.scanOnMs().toString()
            summary["advertise_on_ms_total"] = duty.advertiseOnMs().toString()
            summary["scan_on_pct_of_run"] = duty.scanOnPct().toString()
            summary["scan_open_transitions"] = duty.scanOpenTransitions.toString()
            summary["battery_attribution_note"] =
                "scan_on_ms is HOST-REQUESTED radio time, not receiver-on time. Whole-device " +
                    "drain, not app drain: subtract a paired BATTERY_BASELINE run on this handset."

            // ---- P2 latency ----
            summary["latency_rows"] = latencyRowsWritten.toString()
            summary["latency_samples"] = latency.totalSamples.toString()
            summary["latency_min_ms"] =
                if (latency.totalSamples == 0L) "" else latency.minLatencyMs.toString()
            summary["latency_max_ms"] =
                if (latency.totalSamples == 0L) "" else latency.maxLatencyMs.toString()
            summary["latency_after_on_window"] = latency.afterOnWindow.toString()
            summary["latency_missed_peer_cycles"] = latency.missedCycles.toString()
            summary["latency_correction_required"] =
                "YES — every latency_ms is uncorrected for the clock offset between handsets. " +
                    "Use the paired-sum estimator over BOTH devices' latency.csv, or subtract the " +
                    "two clock_*_network_offset_ms values. SpikeLatency.kt."
            endClock.describe("clock_end").forEach { (k, v) -> summary[k] = v }
            startClock?.let { start ->
                summary["clock_drift_over_run_ms"] =
                    if (start.networkTimeAvailable && endClock.networkTimeAvailable) {
                        (endClock.networkOffsetMs - start.networkOffsetMs).toString()
                    } else {
                        "UNKNOWN — network time unavailable at one or both ends of the run"
                    }
            }

            // ---- SPEC §5.0 density ----
            summary["density_buckets"] = densityBucketsWritten.toString()
            summary["distinct_peers_total"] = densityAccumulator.distinctPeersEver.toString()
            summary["peak_concurrent_peers"] = densityAccumulator.peakConcurrentPeers.toString()
            summary["peak_concurrent_window_ms"] = SpikeTiming.PEER_LIVENESS_MS.toString()

            record(EventKind.CONTROL, "run stopped")
            w.closeMeta(summary)
            w.close()
        }
        writer = null
        advertiseRequest = null
        publish(running = false)
    }

    fun flush() {
        writer?.flush()
    }

    // -------------------------------------------------------------------------------------------
    // advertising payload
    // -------------------------------------------------------------------------------------------

    /**
     * The per-epoch frame supplier handed to the radio.
     *
     * NOTE THE SHAPE: the radio calls this at every 15-minute boundary with the epoch it wants.
     * Nothing here caches, and nothing here can — the function has no state to cache in. That is
     * the port-level expression of `KEY_SCHEDULE.md` §8.4, and it is why `AdvertiseRequest` carries
     * a source rather than bytes.
     */
    private fun spikePayloadSource(cfg: SpikeConfig): AdvertisePayloadSource =
        AdvertisePayloadSource { dayIndex, epochIndex ->
            when (val eid = KeySchedule.ephemeralId(ownRing, dayIndex, epochIndex)) {
                is ProtocolResult.Failure -> {
                    record(EventKind.CONTROL, "no frame for ($dayIndex,$epochIndex): ${eid.error}")
                    null // E_NO_ACTIVE_KEY -> silence. Never the previous epoch's identity.
                }

                is ProtocolResult.Success -> {
                    val frame = BleFrame(
                        version = BleFrameCodec.PROTOCOL_VERSION,
                        ephemeralId = eid.value,
                        txPowerCalDbm = BleFrameCodec.clampTxPowerCal(cfg.txPowerCalDbm),
                        connectable = cfg.connectable,
                    )
                    when (val encoded = BleFrameCodec.encode(frame)) {
                        is ProtocolResult.Failure -> {
                            record(EventKind.CONTROL, "encode failed: ${encoded.error}")
                            null
                        }

                        is ProtocolResult.Success -> {
                            // FILE ONLY, NEVER LOGCAT. Knowing which eid WE emitted is genuinely
                            // useful for the analysis — it is how you exclude your own device from
                            // the peer set — but logcat is read by other apps on some builds, is
                            // captured wholesale by bug-report tooling, and is exactly the "lands
                            // in a crash report" path that R-C makes RawSighting.toString redact.
                            // The capture file is a deliberate artefact behind a cable; logcat is
                            // ambient. They are not the same place.
                            record(
                                EventKind.CONTROL,
                                "frame built for day=$dayIndex epoch=$epochIndex " +
                                    "eid=${eid.value.toHex()}",
                                toLogcat = false,
                            )
                            encoded.value
                        }
                    }
                }
            }
        }

    // -------------------------------------------------------------------------------------------
    // ingest
    // -------------------------------------------------------------------------------------------

    private fun onDiagnostic(d: AndroidSightingDiagnostic) {
        val w = writer ?: return
        val dayIndex = KeySchedule.dayIndex(d.observedAtEpochMs / 1000L)
        val epochIndex = KeySchedule.epochIndex(d.observedAtEpochMs / 1000L)
        rebuildResolutionTableIfNeeded(dayIndex, epochIndex)

        val serviceData = d.serviceDataOrNull
        var carrier = "B_GATT_PULL_CANDIDATE"
        var decodeError = ""
        var eidHex = ""
        var version = ""
        var txCal = ""
        var flagConnectable = ""
        var resolvedSlot = ""
        var selfEid = ""
        var band = ""
        var confidence = ""
        var displayMetres = ""

        if (serviceData != null) {
            carrier = "A_SERVICE_DATA"
            when (val decoded = BleFrameCodec.decode(serviceData)) {
                is ProtocolResult.Failure -> {
                    // KEPT, NOT DROPPED. SPEC §3.6 says a rejected frame must not reach the UI or
                    // create a peer — and it does not; nothing here is a peer. But a spike that
                    // silently discarded malformed frames would be unable to tell "the encoder is
                    // wrong" from "nobody was there", which are the two most likely Phase 0
                    // outcomes. The error code is recorded and the raw bytes are kept.
                    decodeError = decoded.error.name
                    bump(decodeErrors, decoded.error.name)
                }

                is ProtocolResult.Success -> {
                    val frame = decoded.value
                    val eid = frame.ephemeralId()
                    eidHex = eid.toHex()
                    version = frame.version.toString()
                    txCal = frame.txPowerCalDbm.toString()
                    flagConnectable = frame.connectable.toString()

                    if (KeySchedule.isOwnEphemeralId(ownRing, dayIndex, epochIndex, eid)) {
                        // §9.6 E_SELF_EID. Recorded and flagged rather than deleted: a non-zero
                        // count means either a duplicate-broadcast bug or somebody replaying us,
                        // and §9.6 says both are worth knowing about.
                        selfEid = "true"
                        synchronized(lock) { selfEidDrops++ }
                    }

                    // THE §8.5.2 SEAM CONSEQUENCE, AND IT IS NOT AN ERROR STATE.
                    //
                    // The accepted window is {e-1, e, e+1}, and at a rotation seam `e-1` belongs to
                    // the key `pruneSupersededAt` has just destroyed. So for exactly one epoch after
                    // a rotation this device cannot re-derive its own previous-epoch eid, and
                    // `isOwnEphemeralId` above returns FALSE for a reflected or relayed copy of it.
                    // Such a frame falls through to ordinary resolution.
                    //
                    // That is deliberate and upheld: §9.6 is defence in depth against a rare event,
                    // §8.5.2 addresses a permanent exposure, and holding the superseded key 15
                    // minutes longer would mean still holding it during every single rotation —
                    // the one moment it is most worth not having.
                    //
                    // WHAT THIS CODE THEREFORE DOES NOT DO, on purpose: no error, no anomaly log,
                    // no retry, no second resolution attempt against a wider window. It is handled
                    // as an ordinary frame that happens not to resolve, which is the whole point.
                    //
                    // NOTE FOR ANYONE READING selfEidDrops: the counter is KNOWN-INCOMPLETE for one
                    // epoch after each seam, by construction. A zero is not proof that nothing was
                    // reflected. In this harness our own slot is deliberately in the resolution
                    // candidate set (so a device can confirm what it is emitting), which is NOT
                    // production behaviour — a real device does not carry its own account as a
                    // candidate. The `self_eid` and `resolved_slot` columns keep the two cases
                    // distinguishable off-device; nothing here depends on either behaviour.

                    val slot = resolutionTable[eidHex]
                    if (slot != null) {
                        resolvedSlot = slot.toString()
                        synchronized(lock) { bump(resolvedBySlot, slot) }
                    }

                    val reading = pipelineFor(slot?.toString() ?: eidHex, frame.txPowerCalDbm)
                        .update(d.rssiDbm)
                    band = reading.band.name
                    confidence = reading.confidence.name
                    displayMetres = reading.displayMetres?.toString() ?: ""
                    bump(bandCounts, reading.band.name)

                    synchronized(lock) {
                        addrToEids.getOrPut(d.advertiserAddress) { LinkedHashSet() }.add(eidHex)
                        eidToAddrs.getOrPut(eidHex) { LinkedHashSet() }.add(d.advertiserAddress)
                    }
                }
            }
        } else {
            synchronized(lock) { carrierBCount++ }
        }

        synchronized(lock) {
            sightingCount++
            addrToEids.getOrPut(d.advertiserAddress) { LinkedHashSet() }
            bump(addressTypeCounts, addressTypeName(d.addressTypeBits))
        }

        // ---- SPEC §5.0 acquisition rate / peer density -------------------------------------------
        // Fed from the SAME record that produced the sighting row, in the same pass, so the density
        // file and the sightings file cannot disagree about what arrived.
        val slot = resolvedSlot.toIntOrNull()
        val peerKey = peerKeyFor(slot, eidHex, d.advertiserAddress)
        val completedBuckets = synchronized(lock) {
            val buckets = densityAccumulator.onSighting(
                nowMs = d.observedAtEpochMs,
                peerKey = peerKey,
                advertiserAddress = d.advertiserAddress,
                eidHex = eidHex,
                resolvedSlot = slot,
                decodeFailed = decodeError.isNotEmpty(),
                isCarrierBCandidate = serviceData == null,
                droppedTotal = radio.diagnosticsDropped,
                writeFailuresTotal = w.writeFailures,
                scanOnMsTotal = duty.scanOnMs(),
            )
            concurrentPeersNow = densityAccumulator.concurrentPeers(d.observedAtEpochMs)
            buckets
        }
        completedBuckets.forEach(::writeDensityBucket)

        // ---- go/no-go P2 discovery latency ------------------------------------------------------
        // Only in LATENCY_PROBE mode: outside it the peer's transmitter is not on a known schedule,
        // so "time since the cycle boundary" measures nothing. Self-sightings are excluded — a
        // device hearing its own reflection would record a latency of approximately zero and drag
        // the percentile down, which is the most flattering possible way to be wrong.
        if (_config.value.mode == SpikeMode.LATENCY_PROBE && selfEid != "true" &&
            decodeError.isEmpty() && eidHex.isNotEmpty()
        ) {
            val sample = synchronized(lock) { latency.onSighting(peerKey, d.observedAtEpochMs) }
            if (sample != null) {
                writeLatencyRow(
                    event = "SCAN_FIRST_SEEN",
                    cycleStartMs = sample.cycleStartUtcMs,
                    atMs = sample.firstSeenUtcMs,
                    peerKey = sample.peerKey,
                    resolvedSlot = resolvedSlot,
                    latencyMs = sample.latencyMs,
                )
            }
        }

        val n = nextSeq()
        val elapsed = d.observedAtEpochMs - startedAtMillis

        w.appendSightingCsv(
            listOf(
                n.toString(),
                d.observedAtEpochMs.toString(),
                SpikeWriter.isoUtc(d.observedAtEpochMs),
                elapsed.toString(),
                d.advertiserAddress,
                d.addressTypeBits.toString(),
                d.rssiDbm.toString(),
                carrier,
                decodeError,
                eidHex,
                version,
                txCal,
                flagConnectable,
                resolvedSlot,
                selfEid,
                band,
                confidence,
                displayMetres,
                d.isConnectable.toString(),
                d.isLegacy.toString(),
                d.dataStatus.toString(),
                d.primaryPhy.toString(),
                d.secondaryPhy.toString(),
                d.advertisingSid.toString(),
                d.txPowerAdv.toString(),
                d.timestampNanos.toString(),
                dayIndex.toString(),
                epochIndex.toString(),
                serviceData?.toHex() ?: "",
            ).joinToString(",") { csvCell(it) },
        )

        w.appendEventJson(
            buildString {
                append('{')
                append("\"seq\":").append(n)
                append(",\"t\":").append(d.observedAtEpochMs)
                append(",\"iso\":").append(SpikeWriter.jsonString(SpikeWriter.isoUtc(d.observedAtEpochMs)))
                append(",\"type\":\"sighting\"")
                append(",\"addr\":").append(SpikeWriter.jsonString(d.advertiserAddress))
                append(",\"addr_type_bits\":").append(d.addressTypeBits)
                append(",\"rssi_dbm\":").append(d.rssiDbm)
                append(",\"carrier\":").append(SpikeWriter.jsonString(carrier))
                append(",\"decode_error\":").append(SpikeWriter.jsonString(decodeError))
                append(",\"eid\":").append(SpikeWriter.jsonString(eidHex))
                append(",\"tx_power_cal_dbm\":").append(SpikeWriter.jsonString(txCal))
                append(",\"resolved_slot\":").append(SpikeWriter.jsonString(resolvedSlot))
                append(",\"self_eid\":").append(selfEid == "true")
                append(",\"band\":").append(SpikeWriter.jsonString(band))
                append(",\"confidence\":").append(SpikeWriter.jsonString(confidence))
                append(",\"pdu_connectable\":").append(d.isConnectable)
                append(",\"pdu_legacy\":").append(d.isLegacy)
                append(",\"primary_phy\":").append(d.primaryPhy)
                append(",\"data_status\":").append(d.dataStatus)
                append(",\"timestamp_nanos\":").append(d.timestampNanos)
                append(",\"day\":").append(dayIndex)
                append(",\"epoch\":").append(epochIndex)
                append(",\"service_data\":").append(SpikeWriter.jsonString(serviceData?.toHex() ?: ""))
                append('}')
            },
        )

        // THROTTLED, AND THIS IS A CORRECTNESS FIX RATHER THAN A PERFORMANCE ONE. `publish` copies
        // six maps and allocates a whole SpikeStats. Doing that per packet — which under max
        // capture in a busy room is hundreds per second — makes this collector the slow consumer,
        // and a slow consumer on a SUSPEND-policy SharedFlow means `tryEmit` starts returning
        // false and RECORDS GET DROPPED. The instrument would degrade its own capture in order to
        // animate a counter. The FILE is written on every packet, unthrottled; only the screen
        // waits.
        val now = SystemClock.uptimeMillis()
        if (now - lastPublishUptimeMs >= PUBLISH_INTERVAL_MS) {
            lastPublishUptimeMs = now
            publish(running = true)
        }
    }

    private fun onRadioEvent(e: AndroidRadioEvent) {
        synchronized(lock) {
            when (e.kind) {
                // THE RADIO-ON-TIME LEDGER. This is what makes the battery figure attributable:
                // without it, a run the OEM killed after twelve minutes reports an excellent %/hr
                // for a phone that spent the other seventy-eight doing nothing. SpikeDutyLedger.kt.
                AndroidRadioEvent.Kind.SCAN_STARTED -> { scanStarts++; duty.onScanOpened() }
                AndroidRadioEvent.Kind.SCAN_STOPPED -> duty.onScanClosed()
                // A failed scan is a CLOSED scan. Counting it as open would credit the radio with
                // time it did not spend receiving, in the exact direction that flatters us.
                AndroidRadioEvent.Kind.SCAN_FAILED -> { scanFailures++; duty.onScanClosed() }
                AndroidRadioEvent.Kind.SCAN_THROTTLED -> scanThrottles++
                AndroidRadioEvent.Kind.ADVERTISE_STARTED -> duty.onAdvertiseLive()
                AndroidRadioEvent.Kind.ADVERTISE_STOPPED,
                AndroidRadioEvent.Kind.ADVERTISE_FAILED -> duty.onAdvertiseStopped()
                AndroidRadioEvent.Kind.EPOCH_ROTATED -> epochRotations++
                AndroidRadioEvent.Kind.ADAPTER_STATE -> {
                    adapterEvents++
                    // The adapter going away takes both the scan and the advertisement with it,
                    // and the radio does not always emit a STOPPED for each on the way out.
                    if (e.detail == "OFF" || e.detail == "TURNING_OFF") {
                        duty.onScanClosed()
                        duty.onAdvertiseStopped()
                    }
                }
                else -> Unit
            }
            lastEventLine = "${e.kind} ${e.detail}"
        }
        record(EventKind.RADIO, "${e.kind}: ${e.detail}", atMillis = e.atEpochMs)
        publish(running = _stats.value.running)
    }

    private fun onAdvertiseState(state: AdvertiseState) {
        record(
            EventKind.RADIO,
            "advertiseState ${state.status} ${state.reason ?: ""} ${state.detail ?: ""} " +
                "epoch=${state.epochIndex}",
        )
        publish(running = _stats.value.running)
    }

    // -------------------------------------------------------------------------------------------

    private fun rebuildResolutionTableIfNeeded(dayIndex: Long, epochIndex: Int) {
        val absolute = dayIndex * KeySchedule.EPOCHS_PER_DAY + epochIndex
        if (absolute == resolutionTableEpoch) return

        val table = LinkedHashMap<String, Int>()
        // ±1 epoch, matching KeySchedule.ACCEPTED_EPOCH_SKEW. §6.3 is explicit that the acceptance
        // window IS the replay window, one for one — widening it here to "make discovery more
        // reliable" would widen the replay window by the same amount, which is a protocol decision
        // and not the harness's to take.
        for (slot in _config.value.resolveSlots) {
            val ring = SpikeKeys.ringForSlot(slot)
            for (delta in -KeySchedule.ACCEPTED_EPOCH_SKEW..KeySchedule.ACCEPTED_EPOCH_SKEW) {
                val (d, e) = stepEpoch(dayIndex, epochIndex, delta)
                when (val eid = KeySchedule.ephemeralId(ring, d, e)) {
                    is ProtocolResult.Success -> table[eid.value.toHex()] = slot
                    is ProtocolResult.Failure -> Unit
                }
            }
        }
        resolutionTable = table
        resolutionTableEpoch = absolute
        record(
            EventKind.CONTROL,
            "resolution table rebuilt for day=$dayIndex epoch=$epochIndex entries=${table.size}",
        )
    }

    private fun stepEpoch(day: Long, epoch: Int, delta: Int): Pair<Long, Int> {
        var d = day
        var e = epoch + delta
        while (e < 0) {
            e += KeySchedule.EPOCHS_PER_DAY
            d -= 1
        }
        while (e >= KeySchedule.EPOCHS_PER_DAY) {
            e -= KeySchedule.EPOCHS_PER_DAY
            d += 1
        }
        return d to e
    }

    private fun pipelineFor(peerKey: String, txPowerCalDbm: Int): BandingPipeline =
        synchronized(lock) {
            pipelines.getOrPut(peerKey) {
                // `localPeerId` is supposed to be the resolved LOCAL account handle. There is no
                // account resolution in a spike, so this is a per-run random stand-in. CONSEQUENCE,
                // stated so nobody quotes the wrong column: `display_metres` in the capture is
                // MEANINGLESS. `band` and `confidence` are real — they depend only on RSSI and
                // tx_power_cal — and they are what the run is for.
                val seed = peerDisplaySeeds.getOrPut(peerKey) {
                    ByteArray(16).also { SecureRandom().nextBytes(it) }
                }
                BandingPipeline(
                    txPowerCalDbm = txPowerCalDbm,
                    sessionSalt = sessionSalt,
                    localPeerId = seed,
                )
            }
        }

    private fun record(
        kind: EventKind,
        detail: String,
        atMillis: Long = System.currentTimeMillis(),
        toLogcat: Boolean = true,
    ) {
        val w = writer ?: return
        val n = nextSeq()
        w.appendEventJson(
            "{\"seq\":$n,\"t\":$atMillis," +
                "\"iso\":${SpikeWriter.jsonString(SpikeWriter.isoUtc(atMillis))}," +
                "\"type\":${SpikeWriter.jsonString(kind.name.lowercase())}," +
                "\"detail\":${SpikeWriter.jsonString(detail)}}",
        )
        if (toLogcat && kind == EventKind.CONTROL) Log.i(TAG, detail)
    }

    private fun writeLatencyRow(
        event: String,
        cycleStartMs: Long,
        atMs: Long,
        peerKey: String,
        resolvedSlot: String,
        latencyMs: Long,
    ) {
        val w = writer ?: return
        val cfg = _config.value
        val seconds = atMs / 1000L
        synchronized(lock) { latencyRowsWritten++ }
        w.appendLatencyCsv(
            listOf(
                event,
                LatencyCycle.cycleIndex(cycleStartMs).toString(),
                LatencyCycle.epochCycleIndex(cycleStartMs).toString(),
                cycleStartMs.toString(),
                peerKey,
                resolvedSlot,
                atMs.toString(),
                latencyMs.toString(),
                (latencyMs > SpikeTiming.LATENCY_ON_MS).toString(),
                // A packet cannot arrive before it was sent. A negative value is therefore
                // model-free proof that this device's clock is behind the emitter's by at least
                // this much — SpikeLatency.kt METHOD 4, the error bar that costs nothing.
                (latencyMs < 0L).toString(),
                cfg.duty.name,
                SpikeTiming.scanModeName(SpikeTiming.effectiveScanMode(cfg)),
                KeySchedule.dayIndex(seconds).toString(),
                KeySchedule.epochIndex(seconds).toString(),
            ).joinToString(",") { csvCell(it) },
        )
    }

    private fun writeDensityBucket(bucket: DensityBucket) {
        val w = writer ?: return
        val cfg = _config.value
        val scanMode = SpikeTiming.effectiveScanMode(cfg)
        val advInterval = SpikeTiming.nominalAdvertiseIntervalMs(cfg.duty)
        // NOMINAL, and named NOMINAL in the column header. It is the packet count a peer WOULD emit
        // in this bucket at the documented advertising interval, before any consideration of how
        // much of the bucket our receiver was actually listening for. Dividing observed by this
        // gives a yield that is only as good as the constant, and the constant is an AOSP document,
        // not a measurement of the handset in the room.
        val expectedPerPeer = if (advInterval > 0) SpikeTiming.DENSITY_BUCKET_MS / advInterval else 0
        synchronized(lock) { densityBucketsWritten++ }

        w.appendDensityCsv(
            listOf(
                bucket.index.toString(),
                bucket.startUtcMs.toString(),
                SpikeWriter.isoUtc(bucket.startUtcMs),
                bucket.endUtcMs.toString(),
                SpikeTiming.DENSITY_BUCKET_MS.toString(),
                bucket.packets.toString(),
                bucket.decodeFailures.toString(),
                bucket.carrierBCandidates.toString(),
                bucket.distinctPeers.toString(),
                bucket.distinctAddresses.toString(),
                bucket.distinctEids.toString(),
                bucket.distinctSlots.toString(),
                bucket.concurrentPeersAtEnd.toString(),
                SpikeTiming.PEER_LIVENESS_MS.toString(),
                bucket.scanOnMsInBucket.toString(),
                SpikeTiming.nominalScanDutyPct(scanMode).toString(),
                advInterval.toString(),
                expectedPerPeer.toString(),
                bucket.diagnosticsDroppedInBucket.toString(),
                bucket.writeFailuresInBucket.toString(),
                cfg.mode.name,
                cfg.duty.name,
                SpikeTiming.scanModeName(scanMode),
                cfg.maxCapture.toString(),
            ).joinToString(",") { csvCell(it) },
        )

        bucket.peers.forEach { peer ->
            w.appendDensityPeerCsv(
                listOf(
                    bucket.index.toString(),
                    bucket.startUtcMs.toString(),
                    peer.peerKey,
                    peer.resolvedSlot?.toString() ?: "",
                    peer.packets.toString(),
                    peer.firstSeenMs.toString(),
                    peer.lastSeenMs.toString(),
                    (peer.lastSeenMs - peer.firstSeenMs).toString(),
                    peer.maxGapMs.toString(),
                ).joinToString(",") { csvCell(it) },
            )
        }
    }

    /**
     * How a peer is identified for counting purposes, most stable key first.
     *
     * Slot survives both epoch rotation and RPA rotation because it comes from the key schedule.
     * An eid survives one epoch. An address survives until the controller rotates it. All three are
     * ALSO counted separately per bucket, so the difference between the counts is visible rather
     * than resolved silently in favour of whichever one this function happened to pick.
     */
    private fun peerKeyFor(slot: Int?, eidHex: String, address: String): String = when {
        slot != null -> "slot:$slot"
        eidHex.isNotEmpty() -> "eid:$eidHex"
        else -> "addr:$address"
    }

    private fun nextSeq(): Long = synchronized(lock) { ++seq }

    private fun publish(running: Boolean) {
        val w = writer
        synchronized(lock) {
            _stats.value = SpikeStats(
                running = running,
                runId = w?.runId ?: "-",
                elapsedMillis = if (startedAtMillis == 0L) 0L else
                    System.currentTimeMillis() - startedAtMillis,
                directory = w?.directory ?: "-",
                adbCommand = w?.adbPullCommand ?: "-",
                sightings = sightingCount,
                productStreamSightings = productStreamCount,
                carrierBObservations = carrierBCount,
                uniqueAdvertiserAddresses = addrToEids.size,
                uniqueEphemeralIds = eidToAddrs.size,
                bridgedAddresses = bridgedAddresses(),
                bridgedEids = bridgedEids(),
                resolvedBySlot = LinkedHashMap(resolvedBySlot),
                selfEidDrops = selfEidDrops,
                decodeErrors = LinkedHashMap(decodeErrors),
                bandCounts = LinkedHashMap(bandCounts),
                addressTypeCounts = LinkedHashMap(addressTypeCounts),
                scanFailures = scanFailures,
                scanThrottles = scanThrottles,
                scanStarts = scanStarts,
                epochRotations = epochRotations,
                adapterEvents = adapterEvents,
                advertiseStatus = radio.advertiseState.value.status.name,
                advertiseDetail = radio.advertiseState.value.let {
                    listOfNotNull(it.reason?.name, it.detail).joinToString(" ")
                },
                advertiseRole = radio.advertiseRole.value.name,
                radioAvailability = radio.availability.value.name,
                peripheralRoleSupported = radio.peripheralRoleSupported,
                diagnosticsDropped = radio.diagnosticsDropped,
                writeFailures = w?.writeFailures ?: 0L,
                lastEventLine = lastEventLine,

                mode = _config.value.mode,
                modeHeadline = SpikeProcedure.headline(
                    _config.value.mode,
                    _config.value.maxCapture,
                ),

                batterySamples = batterySamples,
                batteryLevelPct = drain.latestSample?.levelPct ?: -1,
                batteryLevelDeltaPct = drain.levelDeltaPct,
                batteryChargeDeltaUah = drain.chargeDeltaUah,
                batteryTemperatureDeciC = drain.latestSample?.temperatureDeciC ?: -1,
                batteryPlugged = drain.latestSample?.plugged == true,
                batteryEverPlugged = drain.everPlugged,
                batteryScreenInteractive = drain.latestSample?.screenInteractive == true,
                batteryDeviceIdle = drain.latestSample?.deviceIdleMode == true,
                batteryPowerSave = drain.latestSample?.powerSaveMode == true,
                percentPerHourFromLevel = drain.percentPerHourFromLevel(),
                percentPerHourFromCharge = drain.percentPerHourFromCharge(),
                batteryInvalidReason = drain.invalidReason(
                    _config.value.maxCapture,
                    _config.value.mode,
                ),

                scanOnMs = duty.scanOnMs(),
                advertiseOnMs = duty.advertiseOnMs(),
                scanOnPct = duty.scanOnPct(),
                scanOpenTransitions = duty.scanOpenTransitions,
                nominalScanDutyPct = SpikeTiming.nominalScanDutyPct(
                    SpikeTiming.effectiveScanMode(_config.value),
                ),
                scanModeName = SpikeTiming.scanModeName(
                    SpikeTiming.effectiveScanMode(_config.value),
                ),

                latencySamples = latency.totalSamples,
                latencyP50Ms = latency.percentileMs(50),
                latencyP95Ms = latency.percentileMs(95),
                latencyMinMs = if (latency.totalSamples == 0L) null else latency.minLatencyMs,
                latencyMaxMs = if (latency.totalSamples == 0L) null else latency.maxLatencyMs,
                latencyAfterOnWindow = latency.afterOnWindow,
                latencyMissedPeerCycles = latency.missedCycles,
                emitStartLagMs = emitStartLagMs,
                networkTimeAvailable = startClock?.networkTimeAvailable == true,
                networkTimeSource = startClock?.source ?: "",

                densityBuckets = densityBucketsWritten,
                distinctPeersTotal = densityAccumulator.distinctPeersEver,
                concurrentPeers = concurrentPeersNow,
                peakConcurrentPeers = densityAccumulator.peakConcurrentPeers,
            )
        }
    }

    private fun bridgedAddresses(): Int = addrToEids.count { it.value.size > 1 }

    private fun bridgedEids(): Int = eidToAddrs.count { it.value.size > 1 }

    private fun resetAccumulators() {
        addrToEids.clear(); eidToAddrs.clear(); resolvedBySlot.clear()
        decodeErrors.clear(); bandCounts.clear(); addressTypeCounts.clear()
        pipelines.clear(); peerDisplaySeeds.clear()
        sightingCount = 0L; productStreamCount = 0L; carrierBCount = 0L; selfEidDrops = 0L
        scanFailures = 0L; scanThrottles = 0L; scanStarts = 0L
        epochRotations = 0L; adapterEvents = 0L; seq = 0L
        resolutionTable = emptyMap(); resolutionTableEpoch = Long.MIN_VALUE
        lastEventLine = ""
        drain.reset(); latency.reset(); densityAccumulator.reset()
        batterySamples = 0L; latencyRowsWritten = 0L; densityBucketsWritten = 0L
        concurrentPeersNow = 0; emitStartLagMs = -1L
        startClock = null; advertiseRequest = null
    }

    private fun <K> bump(map: MutableMap<K, Long>, key: K) {
        map[key] = (map[key] ?: 0L) + 1L
    }

    private enum class EventKind { CONTROL, RADIO }

    companion object {
        private const val TAG = "RadiusSpike"

        /** Screen refresh cap. The file is never throttled; only the UI snapshot is. */
        private const val PUBLISH_INTERVAL_MS = 250L

        /**
         * UNMEASURED GUESS — see `SpikeTiming.kt` for the block this belongs to.
         *
         * Delay past a density-bucket boundary before closing the bucket, so a sighting whose
         * `observedAtEpochMs` was back-dated by the `timestampNanos` conversion still lands in the
         * bucket it belongs to rather than in the one after. Deliberately larger than any plausible
         * scan-callback delivery delay and far smaller than a bucket.
         */
        private const val BUCKET_SETTLE_MS = 250L

        /**
         * `SPEC.md` §4.1. 0xFDA9 is PROVISIONAL and MUST NOT SHIP — SIG Adopter registration and a
         * member UUID allocation are weeks of lead time and are on the critical path for shipping,
         * not for the spike. Taken from ble-protocol's constant rather than typed out, so that
         * replacing it is one edit in one place.
         */
        val SERVICE_UUID_HEX: String =
            AdvertisementCodec.SERVICE_UUID16.toString(16).uppercase().padStart(4, '0')

        fun addressTypeName(bits: Int): String = when (bits) {
            0b01 -> "resolvable_private_or_public"
            0b00 -> "non_resolvable_private_or_public"
            0b11 -> "static_random"
            0b10 -> "reserved"
            else -> "unknown"
        }

        fun csvCell(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
    }
}
