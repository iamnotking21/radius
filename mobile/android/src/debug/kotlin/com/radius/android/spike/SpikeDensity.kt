package com.radius.android.spike

/**
 * ACQUISITION RATE and PEER DENSITY — `SPEC.md` §5.0's required measurements:
 * *"acquisition success rate over a 10-minute window"* and *"behaviour at 1, 5, 15 and 30 concurrent
 * peers"*.
 *
 * ## What is counted, and what "distinct peer" means
 *
 * A peer is keyed by, in order of preference:
 *
 *  1. **resolved slot** — stable across both epoch rotation and RPA rotation, because it is derived
 *     from the key schedule. Only available for peers whose slot is in `resolveSlots`, i.e. other
 *     handsets in the same field session running this harness.
 *  2. **`ephemeral_id`** — stable for one 15-minute epoch, and DIFFERENT for the same physical
 *     device in the next one. A run spanning an epoch boundary will therefore appear to gain new
 *     peers at the boundary. That is not a bug and it is not noise: it is the protocol working, and
 *     it is why the `epoch` column exists on every row.
 *  3. **advertising address** — for a Carrier B candidate (our UUID, no service data), which is all
 *     there is. It rotates with the RPA, which is the entire point of the RPA, so an address-keyed
 *     peer count is an OVERCOUNT over any window longer than a rotation.
 *
 * **All three counts are recorded separately per bucket**, precisely so nobody has to trust one
 * definition. If `distinct_slots` says 4 and `distinct_addresses` says 37 over the same hour, the
 * difference is the rotation rate, not a discrepancy.
 *
 * ## "Concurrent" is a choice, not an observation
 *
 * There is no way to observe how many peers are concurrently present. There is only "how many were
 * heard from in the last N seconds", and N moves the answer. [SpikeTiming.PEER_LIVENESS_MS] fixes N
 * for the live readout and the `concurrent_peers` column; the per-peer first/last timestamps in
 * `density_peers.csv` let any other N be re-derived off-device. This is stated on the screen too,
 * because "concurrent peers: 14" is exactly the kind of number that gets quoted without its window.
 *
 * ## Empty buckets are data
 *
 * A bucket with zero packets is emitted, always, from a timer rather than from the arrival of the
 * next packet. A sightings-driven accumulator silently omits the dead minutes, and the dead minutes
 * are the measurement: `SPEC.md` §5.0 asks for an acquisition SUCCESS RATE, and a success rate
 * computed only over buckets in which something succeeded is 100 % by construction.
 *
 * ## The losses are counted here too
 *
 * Each bucket carries the delta of `diagnostics_dropped` and `write_failures` over that bucket, and
 * the milliseconds within the bucket during which a scan was actually open. A low-density bucket
 * that coincides with a closed scan window or a run of dropped diagnostics is OUR hole, not a quiet
 * room, and B8/§5.0 are assertions about absence — an uncounted drop voids the conclusion rather
 * than degrading it.
 */
internal class DensityAccumulator {

    private var bucketIndex: Long = Long.MIN_VALUE

    // ---- within the current bucket
    private var packets = 0L
    private var decodeFailures = 0L
    private var carrierB = 0L
    private val peers = LinkedHashMap<String, PeerBucketStat>()
    private val addressesThisBucket = LinkedHashSet<String>()
    private val eidsThisBucket = LinkedHashSet<String>()
    private val slotsThisBucket = LinkedHashSet<Int>()

    // ---- spanning buckets, for the liveness window
    private val lastSeenByPeer = LinkedHashMap<String, Long>()

    var peakConcurrentPeers = 0
        private set

    /** Distinct peers ever seen in this run, by the preferred key. Cumulative, never reset per bucket. */
    private val everSeen = LinkedHashSet<String>()

    val distinctPeersEver: Int get() = everSeen.size

    fun reset() {
        bucketIndex = Long.MIN_VALUE
        clearBucket()
        lastSeenByPeer.clear()
        everSeen.clear()
        peakConcurrentPeers = 0
    }

    /**
     * Number of peers heard from within the last [SpikeTiming.PEER_LIVENESS_MS]. See the class note:
     * this is a windowed count, not an observation of concurrency.
     */
    fun concurrentPeers(nowMs: Long): Int =
        lastSeenByPeer.count { nowMs - it.value <= SpikeTiming.PEER_LIVENESS_MS }

    /**
     * Fold one sighting in.
     *
     * @return buckets completed by this sighting's arrival, oldest first. Usually empty.
     */
    fun onSighting(
        nowMs: Long,
        peerKey: String,
        advertiserAddress: String,
        eidHex: String,
        resolvedSlot: Int?,
        decodeFailed: Boolean,
        isCarrierBCandidate: Boolean,
        droppedTotal: Long,
        writeFailuresTotal: Long,
        scanOnMsTotal: Long,
    ): List<DensityBucket> {
        val completed = advanceTo(nowMs, droppedTotal, writeFailuresTotal, scanOnMsTotal)

        packets++
        if (decodeFailed) decodeFailures++
        if (isCarrierBCandidate) carrierB++
        addressesThisBucket.add(advertiserAddress)
        if (eidHex.isNotEmpty()) eidsThisBucket.add(eidHex)
        if (resolvedSlot != null) slotsThisBucket.add(resolvedSlot)

        val stat = peers.getOrPut(peerKey) {
            PeerBucketStat(peerKey = peerKey, resolvedSlot = resolvedSlot, firstSeenMs = nowMs)
        }
        stat.record(nowMs)

        everSeen.add(peerKey)
        lastSeenByPeer[peerKey] = nowMs
        val concurrent = concurrentPeers(nowMs)
        if (concurrent > peakConcurrentPeers) peakConcurrentPeers = concurrent

        return completed
    }

    /**
     * Close and emit any bucket whose wall-clock window has ended, EVEN IF NOTHING WAS SEEN IN IT.
     * Driven from a timer. See "Empty buckets are data".
     */
    fun advanceTo(
        nowMs: Long,
        droppedTotal: Long,
        writeFailuresTotal: Long,
        scanOnMsTotal: Long,
    ): List<DensityBucket> {
        val target = Math.floorDiv(nowMs, SpikeTiming.DENSITY_BUCKET_MS)
        if (bucketIndex == Long.MIN_VALUE) {
            bucketIndex = target
            markBucketBaseline(droppedTotal, writeFailuresTotal, scanOnMsTotal)
            return emptyList()
        }
        if (target <= bucketIndex) return emptyList()

        // CATCH-UP ATTRIBUTION, STATED BECAUSE IT IS A SMALL LIE AND SHOULD BE A KNOWN ONE. When
        // several buckets close at once — the timer did not fire because the process was frozen, or
        // this is the flush at stop() — the loss and scan-time deltas accumulated across the whole
        // gap are attributed to the FIRST bucket of the run, because that is the only bucket we
        // know they cannot have happened before. The empty buckets after it therefore read as
        // zero-loss, which is not knowable. A multi-bucket catch-up is itself visible in the file
        // (consecutive buckets with identical zero counts and no scan time), so the reader can see
        // when this applies.
        val out = ArrayList<DensityBucket>()
        while (bucketIndex < target) {
            out += closeBucket(droppedTotal, writeFailuresTotal, scanOnMsTotal)
            bucketIndex++
            clearBucket()
            markBucketBaseline(droppedTotal, writeFailuresTotal, scanOnMsTotal)
        }
        return out
    }

    private var baseDropped = 0L
    private var baseWriteFailures = 0L
    private var baseScanOnMs = 0L

    private fun markBucketBaseline(dropped: Long, writeFailures: Long, scanOnMs: Long) {
        baseDropped = dropped
        baseWriteFailures = writeFailures
        baseScanOnMs = scanOnMs
    }

    private fun closeBucket(dropped: Long, writeFailures: Long, scanOnMs: Long): DensityBucket {
        val startMs = bucketIndex * SpikeTiming.DENSITY_BUCKET_MS
        val endMs = startMs + SpikeTiming.DENSITY_BUCKET_MS
        return DensityBucket(
            index = bucketIndex,
            startUtcMs = startMs,
            endUtcMs = endMs,
            packets = packets,
            decodeFailures = decodeFailures,
            carrierBCandidates = carrierB,
            distinctPeers = peers.size,
            distinctAddresses = addressesThisBucket.size,
            distinctEids = eidsThisBucket.size,
            distinctSlots = slotsThisBucket.size,
            concurrentPeersAtEnd = concurrentPeers(endMs),
            diagnosticsDroppedInBucket = (dropped - baseDropped).coerceAtLeast(0L),
            writeFailuresInBucket = (writeFailures - baseWriteFailures).coerceAtLeast(0L),
            scanOnMsInBucket = (scanOnMs - baseScanOnMs).coerceAtLeast(0L),
            peers = peers.values.toList(),
        )
    }

    private fun clearBucket() {
        packets = 0L
        decodeFailures = 0L
        carrierB = 0L
        peers.clear()
        addressesThisBucket.clear()
        eidsThisBucket.clear()
        slotsThisBucket.clear()
    }
}

/**
 * Per-peer behaviour within one bucket. [maxGapMs] is what separates "a steady 25 %-duty stream" from
 * "one burst at second 3 and then silence", which have identical packet counts and completely
 * different product meanings.
 */
internal class PeerBucketStat(
    val peerKey: String,
    val resolvedSlot: Int?,
    val firstSeenMs: Long,
) {
    var packets: Long = 0L
        private set
    var lastSeenMs: Long = firstSeenMs
        private set
    var maxGapMs: Long = 0L
        private set

    fun record(nowMs: Long) {
        if (packets > 0L) {
            val gap = nowMs - lastSeenMs
            if (gap > maxGapMs) maxGapMs = gap
        }
        packets++
        lastSeenMs = nowMs
    }
}

/** One completed acquisition-rate bucket. Written to `density.csv`; peers to `density_peers.csv`. */
internal class DensityBucket(
    val index: Long,
    val startUtcMs: Long,
    val endUtcMs: Long,
    val packets: Long,
    val decodeFailures: Long,
    val carrierBCandidates: Long,
    val distinctPeers: Int,
    val distinctAddresses: Int,
    val distinctEids: Int,
    val distinctSlots: Int,
    val concurrentPeersAtEnd: Int,
    val diagnosticsDroppedInBucket: Long,
    val writeFailuresInBucket: Long,
    val scanOnMsInBucket: Long,
    val peers: List<PeerBucketStat>,
)
