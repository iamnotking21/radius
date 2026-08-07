package com.radius.android.spike

/**
 * WHAT A HUMAN HAS TO DO, PER MODE — written for someone holding a bag of phones, not a BLE engineer.
 *
 * ## Why the procedure is in the app and not only in a document
 *
 * The person running this will be standing in a car park at 9pm with two handsets, a notepad and no
 * laptop. A procedure in a repository is a procedure they do not have. Every step below is rendered
 * on the spike screen for the currently-selected mode, so the instrument tells you how to use it.
 *
 * This object is the SINGLE source: the KDoc you are reading and the on-screen text are the same
 * strings. They cannot drift.
 *
 * ## The trust labels, and why every number carries one
 *
 * `SPEC.md` §5.0 and `KEY_SCHEDULE.md` §5.1 both turn on assertions about ABSENCE, and an instrument
 * that does not declare its own blind spots produces confident wrong answers. So every measurement
 * this harness emits is labelled with one of three levels, in the file header, on the screen, and
 * here:
 *
 *  - [Trust.STANDS_ALONE] — one handset, one run, no correction. Quote it.
 *  - [Trust.NEEDS_SECOND_DEVICE] — meaningless or biased without a paired run on another handset.
 *    A single-device value may still be shown, and it is shown, because a wrong-by-a-known-term
 *    number is still useful for "is the rig working" — but it is not the measurement.
 *  - [Trust.NEEDS_SNIFFER] — the phone physically cannot see the thing being asserted. Decision 69:
 *    "non-zero is real evidence of failure; zero is NOT evidence of success."
 */
internal object SpikeProcedure {

    enum class Trust {
        STANDS_ALONE,
        NEEDS_SECOND_DEVICE,
        NEEDS_SNIFFER,
        ;

        val label: String
            get() = when (this) {
                STANDS_ALONE -> "TRUSTWORTHY ALONE"
                NEEDS_SECOND_DEVICE -> "NEEDS A SECOND DEVICE TO INTERPRET"
                NEEDS_SNIFFER -> "NEEDS A SNIFFER — a zero here is NOT a pass"
            }
    }

    /** One measurement this harness produces, with what it answers and what it needs to be true. */
    class Measurement(
        val name: String,
        val answers: String,
        val trust: Trust,
        val caveat: String,
    )

    /**
     * THE TRUST TABLE. Every number the harness writes, and whether it can be read on its own.
     *
     * Deliberately ordered by how likely it is to be misquoted, most-likely first.
     */
    val MEASUREMENTS: List<Measurement> = listOf(
        Measurement(
            name = "Bridged addresses / bridged eids (B8)",
            answers = "PHASE0_GO_NO_GO P0 — does the MAC rotate with the ephemeral id",
            trust = Trust.NEEDS_SNIFFER,
            caveat = "Android never exposes the TxAdd bit, so a permanently FIXED public address " +
                "reads here as a properly rotating one. Decision 69: a non-zero value is real " +
                "evidence of FAILURE; a zero is NOT evidence of success. Also VOID entirely in " +
                "LATENCY PROBE mode, where our own cycling rotates the address inside an epoch.",
        ),
        Measurement(
            name = "Discovery latency p50 / p95",
            answers = "PHASE0_GO_NO_GO P2 — p50 <= 5s foreground",
            trust = Trust.NEEDS_SECOND_DEVICE,
            caveat = "The on-screen figure is biased by the clock offset between the two handsets " +
                "(unknown, could be seconds). Correct it either by the paired-sum estimator (both " +
                "devices' latency.csv, offset cancels exactly) or by the recorded network-time " +
                "offsets in the two meta.json files. A NEGATIVE minimum latency is direct proof of " +
                "skew of at least that size and is shown on the screen for that reason.",
        ),
        Measurement(
            name = "Battery %/hr",
            answers = "PHASE0_GO_NO_GO P1 — <4%/hr scanning, <1%/day idle",
            trust = Trust.NEEDS_SECOND_DEVICE,
            caveat = "This is WHOLE-DEVICE drain, not our drain. It needs a paired BATTERY " +
                "BASELINE run on the same handset, same screen state, same session, to subtract " +
                "the phone's own idle cost. It is VOID if maxCapture was on or the phone was " +
                "plugged in at any point.",
        ),
        Measurement(
            name = "Scan-on / advertise-on milliseconds",
            answers = "whether the platform actually did what we asked for the whole run",
            trust = Trust.STANDS_ALONE,
            caveat = "HOST-REQUESTED radio time, not receiver-on time — the controller's internal " +
                "duty cycling is invisible to an app. Multiply by nominal_scan_duty_pct for a " +
                "rough receiver estimate, and treat that product as nominal, not measured.",
        ),
        Measurement(
            name = "Acquisition rate, packets and distinct peers per bucket",
            answers = "SPEC 5.0 — acquisition success rate over a 10-minute window",
            trust = Trust.STANDS_ALONE,
            caveat = "Counts what THIS receiver heard. Expected-packet denominators use nominal " +
                "advertising intervals, so a yield percentage computed from them is nominal too. " +
                "Empty buckets are emitted; do not compute a rate over non-empty buckets only.",
        ),
        Measurement(
            name = "Concurrent peers",
            answers = "SPEC 5.0 — behaviour at 1 / 5 / 15 / 30 concurrent peers",
            trust = Trust.STANDS_ALONE,
            caveat = "Concurrency is not observable; this is 'heard from in the last " +
                "${SpikeTiming.PEER_LIVENESS_MS / 1000}s'. Change that window and the number " +
                "changes. Per-peer first/last timestamps are in density_peers.csv so any window " +
                "can be re-derived. Reaching 15 and 30 needs either 15-30 real handsets or the " +
                "dumb-advertiser dongles in PHASE0_SPIKE_MATRIX section 1 — and a dongle is a " +
                "RADIO-CAPACITY proxy, not an end-to-end density test, because it is not running " +
                "our app.",
        ),
        Measurement(
            name = "Diagnostics dropped / write failures",
            answers = "whether this capture has holes WE made",
            trust = Trust.STANDS_ALONE,
            caveat = "Non-zero means gaps in the file are ours, not the air's. Any conclusion of " +
                "the form 'X never happened' is VOID for a run with a non-zero value here.",
        ),
        Measurement(
            name = "Address type bits",
            answers = "KEY_SCHEDULE 4.3.2 one-packet screen",
            trust = Trust.NEEDS_SNIFFER,
            caveat = "Inferred from Android's string form of the address. The TxAdd bit is not " +
                "visible, so the catastrophic public-address case passes this screen.",
        ),
    )

    /**
     * WHY THERE IS NO "JUST GRANT IT FROM THE LAPTOP" SHORTCUT.
     *
     * Shown on the screen whenever permissions are missing, because the obvious reflex — grant them
     * over adb and skip the dialogs — fails on the exact vendor whose battery manager makes this
     * spike worth running.
     */
    const val GRANT_FROM_LAPTOP_NOTE: String =
        "ON XIAOMI / HyperOS THERE IS NO ADB SHORTCUT FOR THIS. `adb shell pm grant` is REFUSED " +
            "(SecurityException: neither user 2000 nor current process has " +
            "GRANT_RUNTIME_PERMISSIONS). CONFIRMED on a Redmi 15 5G, Android 16. Tap the button " +
            "above and answer the dialogs on the phone. There is no workaround."

    /**
     * WHAT ADB CAN AND CANNOT DO TO THE HANDSET DURING A RUN.
     *
     * Every line here is a result observed on a Redmi 15 5G (25057RN09G, Android 16 / API 36), not
     * prior knowledge. They are on the screen as well as in `docs/oem.md` because the failure mode
     * is a person losing a field session to a command that printed an exception to stderr and was
     * read as "nothing happened".
     */
    val HOST_NOTES: List<String> = listOf(
        "WORKS: adb shell svc bluetooth enable — returns Success and the radio comes on. This is " +
            "the reliable way to get Bluetooth up without touching the phone. CONFIRMED, Redmi 15 5G.",
        "REFUSED: adb shell pm grant — SecurityException, GRANT_RUNTIME_PERMISSIONS. Permissions " +
            "must be tapped on the device.",
        "REFUSED: adb shell input tap / swipe / keyevent — SecurityException, INJECT_EVENTS. " +
            "MIUI blocks input injection unless 'USB debugging (Security settings)' is on, and " +
            "that toggle needs a signed-in Xiaomi account. READ THIS CAREFULLY: `input` prints the " +
            "exception to STDERR and still exits 0, so a swipe that never happened looks exactly " +
            "like a swipe that did nothing. If a gesture appears to have no effect, run it again " +
            "and read stderr before concluding the SCREEN is broken.",
        "REFUSED: sendevent on /dev/input/* — SELinux denies shell write access to input_device " +
            "even though shell is in the `input` group and the node is crw-rw----. DAC allows it; " +
            "MAC does not.",
        "WORKS: /system/bin/uinput — registers a virtual touchscreen in the kernel and bypasses " +
            "both checks. This is the only scripted-gesture route on this handset.",
        "WORKS: adb shell uiautomator dump, adb exec-out screencap -p, adb shell am start, " +
            "adb pull. Enough to launch the harness, watch it and collect the files.",
    )

    /**
     * Step-by-step, per mode. Short lines: this gets read on a phone screen at 200 % font scale.
     */
    fun steps(mode: SpikeMode): List<String> = when (mode) {
        SpikeMode.CAPTURE -> listOf(
            "1. Give each handset a DIFFERENT slot. Put every other handset's slot in resolve.",
            "2. Exactly ONE handset per identity turns Advertise on. Two on one slot is the " +
                "decision-35 twin case and fills the log with E_SELF_EID.",
            "3. For a KEY_SCHEDULE 5.3 capture: Max capture ON, run >= 90 minutes, spanning >= 6 " +
                "epoch boundaries. Battery figures from that run are VOID and the header says so.",
            "4. Leave it alone. Do not open other Bluetooth apps; do not toggle Bluetooth unless " +
                "the Bluetooth-restart condition is the one you are capturing.",
            "5. Stop the run from the notification, or with the Stop button pinned at the bottom " +
                "of this screen — it is there at every scroll position, on purpose. Then pull the " +
                "folder with the adb line in the EXPORT section.",
        )

        SpikeMode.LATENCY_PROBE -> listOf(
            "1. TWO handsets. Different slots. Each one's resolve list contains the other's slot.",
            "2. Turn Advertise ON on BOTH. Symmetry is not optional: it is what cancels the clock " +
                "offset between the two phones exactly (paired-sum estimator, SpikeLatency.kt).",
            "3. Max capture OFF. Latency measured at 100% scan duty is not the latency the product " +
                "will have, and the whole point of P2 is the shipping duty.",
            "4. Before starting, on BOTH phones: Settings > System > Date & time > automatic time ON, " +
                "and let each phone see a network once so it holds an NTP sample. The screen shows " +
                "NETWORK TIME: AVAILABLE when it does. Without it you lose the independent error bar.",
            "4b. ON ANDROID 12 OR OLDER THAT ERROR BAR DOES NOT EXIST — the API is Android 13+. " +
                "The screen will say so. On those handsets step 2 is not optional: the paired-sum " +
                "estimator over two advertising phones is the ONLY skew control you have.",
            "5. Start both runs. They do NOT need to start together — the cycle schedule is absolute " +
                "UTC, so both phones fall into step on their own. No countdown, no tapping.",
            "6. Run at least 30 minutes. Each cycle yields at most one sample per peer, so 30 " +
                "minutes is ~30 samples per direction; p95 wants more than that. An hour is better.",
            "7. Watch MIN LATENCY on the screen. If it is negative, the clocks are skewed by at " +
                "least that much and the raw p50 is wrong by at least that much.",
            "8. Pull BOTH folders. A single phone's latency.csv cannot compute the corrected number.",
        )

        SpikeMode.BATTERY_BASELINE -> listOf(
            "1. Run this FIRST, then the scanning run, back to back on the SAME handset.",
            "2. Unplug the phone. A run with any plugged sample is void and cannot be repaired by " +
                "deleting rows.",
            "3. Decide screen on or screen off and use the same choice for both runs. An OLED at " +
                "full brightness dwarfs a BLE scan; the two runs are only comparable at the same " +
                "screen state.",
            "4. On a laptop, before starting: adb shell dumpsys batterystats --reset",
            "5. Run at least 60 minutes. The projection is suppressed below " +
                "${SpikeTiming.BATTERY_MIN_PROJECTION_MS / 60_000} minutes on purpose.",
            "6. After stopping: adb bugreport battery-baseline.zip, and load it into Battery " +
                "Historian. Attach it to the PR — battery numbers without a Historian trace are " +
                "not accepted (root CLAUDE.md).",
            "7. Then switch to CAPTURE mode, same handset, same screen state, and repeat. The " +
                "difference between the two runs is Radar's cost. Either run on its own is not.",
        )
    }

    /** One line, shown at the top of the screen, naming what the current run can and cannot claim. */
    fun headline(mode: SpikeMode, maxCapture: Boolean): String = buildString {
        append(mode.label)
        append(" — ")
        when (mode) {
            SpikeMode.CAPTURE ->
                append("bijection screen VALID (subject to the sniffer caveat). ")
            SpikeMode.LATENCY_PROBE ->
                append("bijection screen VOID: our own cycling rotates the address inside an epoch. ")
            SpikeMode.BATTERY_BASELINE ->
                append("radio never started. No sightings expected; zero is the correct result. ")
        }
        if (maxCapture) {
            append("BATTERY FIGURES VOID (maxCapture = 100% duty).")
        } else {
            append("Battery figures valid if unplugged and paired with a baseline run.")
        }
    }
}
