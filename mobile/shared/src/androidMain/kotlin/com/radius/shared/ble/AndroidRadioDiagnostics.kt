package com.radius.shared.ble

/**
 * PHASE 0 SPIKE INSTRUMENTATION. ANDROID SOURCE SET ONLY. OFF BY DEFAULT.
 *
 * ## Why this exists at all, given the RSSI rule
 *
 * `40-contracts` and `BANDING.md` §6.3 say raw RSSI must not cross the `mobile/shared/protocol/`
 * boundary, must not reach the server, and must not appear in logs. That rule is about the
 * PRODUCT PATH and it is not weakened here: [RawSighting] remains the only thing the product
 * consumes, `PeerReading` will still have no RSSI accessor, and nothing in this file is reachable
 * from `RadiusCore`, from the UI, or from any code path that can transmit.
 *
 * But Phase 0 is a MEASUREMENT, and the measurement is of the radio. You cannot answer "does the
 * band map to reality", "what is the discovery latency", or B8 ("does the advertiser address
 * co-rotate with the ephemeral id") from a stream that has already discarded RSSI and the peer
 * address. An instrument that smooths its input is not an instrument.
 *
 * So the carve-out is made explicitly, narrowly, and with the containment stated:
 *
 *  1. **It is off unless switched on**, per radio instance, by
 *     [BleRadio.setDiagnosticsEnabled], which takes a reason and logs it.
 *  2. **Its only consumer lives in `mobile/android/src/debug/`**, a debug source set that is
 *     physically absent from a release APK — not gated by a flag, absent.
 *  3. **The app holds no `INTERNET` permission.** Check `mobile/android/src/main/AndroidManifest.xml`:
 *     there is no `android.permission.INTERNET`, and the debug manifest does not add one. The
 *     spike build is structurally incapable of uploading anything, which is the enforcement of
 *     `40-contracts` **P1** — no observed ephemeral id reaches a server, ever, including "just for
 *     diagnostics". Export is `adb pull` and nothing else.
 *  4. **It is androidMain-only.** It is not in `commonMain`, so it is not in the gated shared API,
 *     not in the XCFramework, and unreachable from iOS.
 *
 * **DELETION IS PART OF THE DESIGN.** This type and [BleRadio.setDiagnosticsEnabled] are Phase 0
 * scaffolding. They must be deleted, or moved behind a debug-only source set of their own, before
 * anything ships. That is a release-checklist item, not a TODO.
 *
 * ## What is recorded, and why each field
 *
 * Everything the platform hands us about one advertisement, unmodified. No dedup, no smoothing, no
 * averaging — the analysis depends on seeing what the radio saw, gaps and anomalies included.
 *
 * @property serviceDataOrNull the Radius service data as received, or `null` when our service UUID
 *   was present with NO service data. That second case is not noise: it is what a Carrier B
 *   (`SPEC.md` §5.2) peer looks like from an Android scanner, and the §5.0 matrix needs it
 *   distinguishable from "not seen".
 * @property rssiDbm raw, unfiltered, as reported.
 * @property advertiserAddress the peer's advertising address as the platform reports it. For B8
 *   this is the entire point: paired with the ephemeral id inside [serviceDataOrNull] it is the
 *   `(AdvA, eid)` tuple whose bijection `KEY_SCHEDULE.md` §4.3.1 asks about. **Epoch-scoped, never
 *   a database key, never persisted beyond the spike file** (`KEY_SCHEDULE.md` §4.2).
 * @property addressTypeBits top two bits of the most significant address byte — the §4.3.2
 *   one-packet screen. `0b01` resolvable-private (acceptable), `0b00` non-resolvable-private
 *   (acceptable), `0b11` static-random (FAIL), public (CATASTROPHIC). Android reports a string, so
 *   the address-type bits are inferred from it and the TxAdd bit is NOT visible — see the caveat
 *   in `BleRadio.androidDiagnostics`.
 * @property timestampNanos `ScanResult.timestampNanos`, elapsed-realtime since boot, exactly as
 *   given. Recorded raw ALONGSIDE the derived wall clock because batched results can be seconds
 *   old and the conversion is the kind of thing that is silently wrong.
 * @property txPowerAdv `ScanResult.getTxPower()`, the TX power in the advertisement's own AD
 *   structure, `Int.MIN_VALUE`-shaped sentinel when absent. We do not advertise it (it is in our
 *   frame instead), so a non-sentinel value here means we are looking at somebody else's device.
 * @property isLegacy false ⇒ extended advertising PDU. `SPEC.md` §6 says extended advertising MUST
 *   NOT be used; observing it tells us a peer is misconfigured.
 * @property isConnectable the LINK-LAYER connectability of the received PDU. Cross-check it
 *   against `flags` bit0 inside the frame: a disagreement means the transmitter's advertised
 *   claim and its actual PDU type have desynced (`SPEC.md` §3.3).
 * @property dataStatus `ScanResult.getDataStatus()` — truncated advertisements show up here.
 * @property primaryPhy / @property secondaryPhy PHY the PDU arrived on. Coded PHY on a legacy
 *   advertisement is an OEM anomaly worth seeing.
 * @property advertisingSid extended-advertising set id, `SID_NOT_PRESENT` for legacy.
 * @property periodicAdvertisingInterval 0 when absent.
 */
public class AndroidSightingDiagnostic(
    public val serviceDataOrNull: ByteArray?,
    public val rssiDbm: Int,
    public val advertiserAddress: String,
    public val addressTypeBits: Int,
    public val observedAtEpochMs: Long,
    public val timestampNanos: Long,
    public val txPowerAdv: Int,
    public val isLegacy: Boolean,
    public val isConnectable: Boolean,
    public val dataStatus: Int,
    public val primaryPhy: Int,
    public val secondaryPhy: Int,
    public val advertisingSid: Int,
    public val periodicAdvertisingInterval: Int,
) {
    /**
     * Redacted for the same reason [RawSighting.toString] is. The struct is written to a file by
     * the harness field by field, deliberately; it must not slip into logcat by interpolation.
     */
    override fun toString(): String =
        "AndroidSightingDiagnostic(<redacted ${serviceDataOrNull?.size ?: 0}B>, at=$observedAtEpochMs)"
}

/**
 * Non-sighting things that happened to the radio, in the same stream and on the same clock as the
 * sightings.
 *
 * THIS IS THE HALF PEOPLE FORGET, AND IT IS THE HALF THAT MAKES A CAPTURE INTERPRETABLE. A 90
 * minute log with a four-minute gap is ambiguous: was the room empty, did the scan get throttled,
 * did the adapter cycle, did the OEM's power manager kill us? Those are four completely different
 * Phase 0 findings and they look identical in a sightings-only log. Recording the radio's own
 * lifecycle in the same file removes the ambiguity, which is the difference between evidence and
 * an anecdote.
 */
public class AndroidRadioEvent(
    public val kind: Kind,
    public val atEpochMs: Long,
    public val detail: String,
) {
    public enum class Kind {
        SCAN_STARTED,
        SCAN_STOPPED,
        /** `ScanCallback.onScanFailed`. Includes the decoded error name. */
        SCAN_FAILED,
        /** Our own [ScanStartGate] deferred a start. The gap that follows is ours. */
        SCAN_THROTTLED,
        ADVERTISE_STARTED,
        ADVERTISE_STOPPED,
        /** `AdvertiseCallback.onStartFailure`, which arrives AFTER `startAdvertising` returned. */
        ADVERTISE_FAILED,
        /** The 15-minute UTC boundary fired and the advertising set was rebuilt. §4.2. */
        EPOCH_ROTATED,
        /** Bluetooth adapter changed state under us. */
        ADAPTER_STATE,
        /** Runtime permission check failed at command time. */
        PERMISSION,
        /** Duty profile changed, or the duty cycler turned the scan on/off. */
        DUTY,

        /**
         * The radio produced a diagnostic record and the consumer did not take it in time. A hole
         * WE made. Reported so that a gap in the capture is never silently attributed to the air.
         */
        DIAGNOSTIC_DROPPED,
    }

    override fun toString(): String = "AndroidRadioEvent($kind, $atEpochMs, $detail)"
}
