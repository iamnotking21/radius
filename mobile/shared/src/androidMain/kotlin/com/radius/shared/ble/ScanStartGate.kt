package com.radius.shared.ble

/**
 * ANDROID'S UNDOCUMENTED SCAN-START THROTTLE, COUNTED ON OUR SIDE.
 *
 * Since Android 7.0, `AppScanStats` in the system Bluetooth process refuses an app that calls
 * `BluetoothLeScanner.startScan` more than **5 times in 30 seconds**. The refusal is the worst
 * possible shape: the call returns normally, `onScanFailed` is often NOT delivered, and the app
 * simply stops receiving results for the rest of the window. It looks exactly like "there is
 * nobody nearby", which for this product is indistinguishable from the feature being broken.
 *
 * Every mechanism in the radio wants to restart a scan: the duty cycler, adapter-off recovery, the
 * epoch loop, the foreground/background transition, and a user toggling Radar. Any two of them
 * firing together can spend the budget. So the budget is accounted centrally, here, and
 * `startScan` returns [BleOutcome.Reason.THROTTLED] with the wait in milliseconds rather than
 * walking into the trap and reporting success.
 *
 * DESIGN NOTES:
 *  - The window is a rolling 30 s, not a fixed bucket. A fixed bucket allows 10 starts across a
 *    boundary, which is the failure this exists to prevent.
 *  - [budget] defaults to 4, one below the platform's 5. The system counter and ours cannot be
 *    perfectly in step (the platform counts across process restarts and we do not), so we leave
 *    one start of slack. Spending the last one is how you get an invisible radio.
 *  - Pure, clock-injected, no Android types. It is unit-tested on the JVM, and that test is
 *    meaningful — unlike a JVM test of anything that touches the radio.
 *
 * NOT THREAD-SAFE by itself. The radio calls it under its own lock.
 */
internal class ScanStartGate(
    private val budget: Int = 4,
    private val windowMillis: Long = 30_000L,
) {

    private val starts = ArrayDeque<Long>()

    /**
     * @return 0 if a start is allowed now and it has been recorded, otherwise the number of
     *   milliseconds to wait before trying again. A non-zero return records nothing.
     */
    fun tryStart(nowMillis: Long): Long {
        evict(nowMillis)
        if (starts.size < budget) {
            starts.addLast(nowMillis)
            return 0L
        }
        val oldest = starts.first()
        return (oldest + windowMillis - nowMillis).coerceAtLeast(1L)
    }

    /**
     * Starts still counted against the budget. Recorded in the spike export: a run that spends its
     * scan budget has gaps that are OURS, not the radio's, and an analysis that cannot tell the
     * difference will blame the wrong thing.
     */
    fun startsInWindow(nowMillis: Long): Int {
        evict(nowMillis)
        return starts.size
    }

    private fun evict(nowMillis: Long) {
        while (starts.isNotEmpty() && nowMillis - starts.first() >= windowMillis) {
            starts.removeFirst()
        }
    }
}
