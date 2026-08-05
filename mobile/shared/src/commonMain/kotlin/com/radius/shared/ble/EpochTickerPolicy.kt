package com.radius.shared.ble

/**
 * WHEN THE 15-MINUTE EPOCH TICKER RUNS. One implementation, called by BOTH `actual` radios.
 *
 * Same shape and same reasoning as [AdvertiseGuard]: a rule that both platforms must apply
 * identically, expressed once as a pure function so the two cannot drift, and unit-testable in
 * `commonMain` because it needs no radio to evaluate. Neither `actual` radio can be instantiated
 * without a platform, so anything left inside them is covered by nothing.
 *
 * ## The rule
 *
 * ```
 *   running AND (wants to advertise OR holds a ring)
 * ```
 *
 * The ticker has exactly two jobs and this predicate is the union of their triggers:
 *
 *  1. **The advertising restart** (`KEY_SCHEDULE.md` §4.2) — the only lever an app has on RPA
 *     rotation. Applies only while advertising, hence [advertising].
 *  2. **Announcing the boundary** so the ring owner can run `KeyRing.pruneSupersededAt`
 *     (§8.5.2). That obligation is a consequence of HOLDING KEYS, and the only thing either radio
 *     knows about who holds keys is whether anyone registered an [EpochBoundaryListener]. Hence
 *     [hasEpochListener].
 *
 * ## SCANNING IS NOT A TERM, AND ITS ABSENCE IS THE POINT
 *
 * The Android actual previously included `desiredScan != null`. It was wrong, and the reason is
 * worth keeping in front of the next reader rather than in a changelog:
 *
 *  - **Scanning is a reason for neither job.** It does not cause an advertising restart and it does
 *    not create keys. Leaving the term in invites the belief that the ticker exists because the
 *    radio is busy, which is the wrong mental model of a destruction obligation.
 *  - **Turning Radar off does not surrender the ring.** The device keeps it for `isOwnEphemeralId`
 *    and against a role transfer. A device that stops scanning and keeps its keys still owes §8.5.2
 *    a prune at every boundary — and under decision 35 the scan-only, non-advertising device is the
 *    MAJORITY case, so keying off radio activity would leave superseded keys alive on most of the
 *    fleet.
 *  - **The asymmetry ran against the user.** Under the old predicate an adapter-off event — a low
 *    signal, someone tapping Bluetooth in Quick Settings — kept the ticker alive, while GHOST MODE,
 *    the one-tap privacy control that is the highest-signal privacy assertion in the product,
 *    stopped it. A privacy control must never suspend a privacy obligation. Whatever the exposure
 *    number says, that is the wrong direction for a control to act in.
 *
 * The iOS actual arrived at `epochBoundaryListener != null` by necessity — it holds no scan state to
 * key off. That accident turned out to be the correct generalisation, and this function is now the
 * single statement of it.
 *
 * ## MECHANISM CONDITION — READ BEFORE MAKING THIS SURVIVE DOZE
 *
 * This predicate is affordable because the ticker is a coroutine `delay`. A `delay` CANNOT wake a
 * sleeping SoC: it piggybacks on a wake the device was taking anyway, so an idle handset pays
 * nothing for the ticker being "on". The battery objection to running it more often was rejected on
 * that mechanism, not waved away.
 *
 * IF ANYONE PROMOTES THE TICKER TO `AlarmManager.setExactAndAllowWhileIdle` (or an iOS equivalent)
 * TO SURVIVE DOZE, THE JUSTIFICATION IS VOID. That is 96 exact alarms per day, each one a forced
 * wake, against a CI-gated `<1 %/day idle` contract. The predicate would then have to be revisited
 * — not the alarm tuned. The change to watch for is in `syncEpochTickerLocked` in
 * `BleRadio.android.kt` and `syncEpochTicker` in `BleRadio.ios.kt`; this comment is the reason it
 * cannot be made quietly.
 *
 * ## NOT THE LONG-TERM HOME OF THE GUARANTEE
 *
 * Decision 78: when ring persistence lands, key destruction moves to prune-on-load and
 * prune-on-ring-update, owned by the ring store, because the radio's lifetime is not the ring's
 * lifetime — a process that never starts the radio still loads keys, and a process that is killed
 * mid-epoch never gets a boundary at all. The ticker then becomes a best-effort third driver rather
 * than the guarantee. Recorded here so the persistence implementer inherits it.
 */
internal object EpochTickerPolicy {

    /**
     * @param isShutdown the radio has been shut down. Nothing runs after that, unconditionally.
     * @param advertising the caller currently wants to advertise (`desiredAdvertise != null`).
     * @param hasEpochListener something registered an [EpochBoundaryListener], i.e. something on
     *   this device holds a key ring and owes §8.5.2 a prune at every boundary.
     */
    fun wanted(
        isShutdown: Boolean,
        advertising: Boolean,
        hasEpochListener: Boolean,
    ): Boolean = !isShutdown && (advertising || hasEpochListener)
}
