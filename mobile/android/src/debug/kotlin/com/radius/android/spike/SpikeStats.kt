package com.radius.android.spike

/**
 * The live readout. What a person standing in a car park needs to see to know whether the run is
 * working, without waiting to pull the file.
 *
 * SNAPSHOT VALUE TYPE. Recomputed and republished by [SpikeController]; nothing here is mutated in
 * place, so the UI cannot read a half-updated state.
 *
 * @property uniqueAdvertiserAddresses THE HEADLINE COUNTER, and the raw material for B8. Every
 *   distinct advertising address this device has heard carrying our service UUID. Against a known
 *   number of physical handsets, its growth rate IS the observed RPA rotation period.
 * @property bridgedAddresses addresses observed with MORE THAN ONE ephemeral id.
 * @property bridgedEids ephemeral ids observed from MORE THAN ONE address.
 *
 * The two `bridged*` counters together are the `KEY_SCHEDULE.md` §4.3.1 bijection test, computed
 * live from what this phone's own receiver saw. **BOTH MUST BE ZERO.** A single non-zero value is
 * the §4.1 bridging attack and there is no "mostly passes".
 *
 * READ THE LIMIT BEFORE READING THE NUMBER. This is a SCREEN, not the measurement:
 *
 *  - A phone's scanner hops channels and misses packets, so zero here is "no overlap observed",
 *    not "no overlap". §5.1 is explicit that a missed packet and an absent packet are
 *    indistinguishable, which is precisely why three sniffer dongles exist.
 *  - It cannot see the address TYPE reliably (`SPEC.md`/`KEY_SCHEDULE.md` §4.3.2 needs the TxAdd
 *    bit, which Android never exposes), so the catastrophic public-address case can pass here.
 *  - It only sees devices running our build, so it says nothing about the general population.
 *
 * A NON-ZERO value is real evidence of failure. A ZERO value is not evidence of success. Those two
 * statements are not symmetric and the asymmetry is the whole reason §5.3 exists.
 */
internal data class SpikeStats(
    val running: Boolean = false,
    val runId: String = "-",
    val elapsedMillis: Long = 0L,
    val directory: String = "-",
    val adbCommand: String = "-",

    val sightings: Long = 0L,
    val productStreamSightings: Long = 0L,
    val carrierBObservations: Long = 0L,

    val uniqueAdvertiserAddresses: Int = 0,
    val uniqueEphemeralIds: Int = 0,
    val bridgedAddresses: Int = 0,
    val bridgedEids: Int = 0,

    val resolvedBySlot: Map<Int, Long> = emptyMap(),
    val selfEidDrops: Long = 0L,
    val decodeErrors: Map<String, Long> = emptyMap(),
    val bandCounts: Map<String, Long> = emptyMap(),
    val addressTypeCounts: Map<String, Long> = emptyMap(),

    val scanFailures: Long = 0L,
    val scanThrottles: Long = 0L,
    val scanStarts: Long = 0L,
    val epochRotations: Long = 0L,
    val adapterEvents: Long = 0L,

    val advertiseStatus: String = "STOPPED",
    val advertiseDetail: String = "",
    val advertiseRole: String = "SCAN_ONLY",
    val radioAvailability: String = "UNKNOWN",
    val peripheralRoleSupported: Boolean = false,

    val diagnosticsDropped: Long = 0L,
    val writeFailures: Long = 0L,
    val lastEventLine: String = "",
) {
    /**
     * The one-line verdict on whether the capture is usable at all. Deliberately worded so that a
     * degraded run is not silently treated as a clean one.
     */
    val integrityNote: String
        get() = when {
            diagnosticsDropped > 0L || writeFailures > 0L ->
                "DEGRADED — $diagnosticsDropped dropped, $writeFailures write failures. " +
                    "Gaps in this file are OURS. Treat absence claims as void."
            bridgedAddresses > 0 || bridgedEids > 0 ->
                "BRIDGING OBSERVED — $bridgedAddresses addr, $bridgedEids eid. " +
                    "Invariant 5 fails on this hardware. Confirm on a sniffer, then §4.3.4."
            sightings == 0L -> "NO SIGHTINGS YET"
            else -> "no bridging observed so far — NOT a pass, see §5.1"
        }
}
