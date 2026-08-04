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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        w.open(
            config = cfg,
            radioNotes = mapOf(
                "availability" to radio.availability.value.name,
                "peripheral_role_supported" to radio.peripheralRoleSupported.toString(),
                "multiple_advertisement_supported" to radio.multipleAdvertisementSupported.toString(),
                "service_uuid16" to SERVICE_UUID_HEX,
                "service_uuid16_status" to "PROVISIONAL, NOT SIG-ALLOCATED, MUST NOT SHIP (SPEC §4.1)",
                "frame_bytes" to BleFrameCodec.FRAME_LENGTH_BYTES.toString(),
                "carrier_a_bytes" to AdvertisementCodec.CARRIER_A_LENGTH_BYTES.toString(),
            ),
        )
        writer = w
        startedAtMillis = System.currentTimeMillis()

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
            val adv = radio.startAdvertising(
                AdvertiseRequest(
                    payloadSource = spikePayloadSource(cfg),
                    serviceUuid16 = SERVICE_UUID_HEX,
                    duty = cfg.duty,
                ),
            )
            record(EventKind.CONTROL, "startAdvertising -> $adv")
        } else {
            // Belt and braces on top of the radio's own default. Decision 35 is the control that
            // stops two devices broadcasting one identity, so it gets two independent closed doors.
            radio.setAdvertiseRole(AdvertiseRole.SCAN_ONLY, AdvertiseRoleSource.DEBUG_SPIKE_HARNESS)
            record(EventKind.CONTROL, "scan-only: no advertise role requested")
        }

        publish(running = true)
    }

    fun stop() {
        val w = writer
        radio.stopAdvertising()
        radio.setAdvertiseRole(AdvertiseRole.SCAN_ONLY, AdvertiseRoleSource.DEBUG_SPIKE_HARNESS)
        radio.stopScan()
        radio.setEpochBoundaryListener(null)
        radio.setDiagnosticsEnabled(false, "spike stopped")
        radio.setScanModeOverride(null, "spike stopped")

        jobs.forEach { it.cancel() }
        jobs = mutableListOf()

        if (w != null) {
            record(EventKind.CONTROL, "run stopped")
            w.closeMeta(
                mapOf(
                    "ended_utc" to SpikeWriter.isoUtc(System.currentTimeMillis()),
                    "duration_ms" to (System.currentTimeMillis() - startedAtMillis).toString(),
                    "sightings" to sightingCount.toString(),
                    "unique_advertiser_addresses" to addrToEids.size.toString(),
                    "unique_ephemeral_ids" to eidToAddrs.size.toString(),
                    "bridged_addresses" to bridgedAddresses().toString(),
                    "bridged_eids" to bridgedEids().toString(),
                    "diagnostics_dropped" to radio.diagnosticsDropped.toString(),
                    "write_failures" to w.writeFailures.toString(),
                    "integrity" to _stats.value.integrityNote,
                ),
            )
            w.close()
        }
        writer = null
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
                AndroidRadioEvent.Kind.SCAN_FAILED -> scanFailures++
                AndroidRadioEvent.Kind.SCAN_THROTTLED -> scanThrottles++
                AndroidRadioEvent.Kind.SCAN_STARTED -> scanStarts++
                AndroidRadioEvent.Kind.EPOCH_ROTATED -> epochRotations++
                AndroidRadioEvent.Kind.ADAPTER_STATE -> adapterEvents++
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
