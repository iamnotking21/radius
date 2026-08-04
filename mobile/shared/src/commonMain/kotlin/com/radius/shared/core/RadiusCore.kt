package com.radius.shared.core

import com.radius.shared.ble.BleRadio
import com.radius.shared.domain.discover.DiscoverFeed
import com.radius.shared.domain.radar.RadarController
import com.radius.shared.domain.radar.RadarNode
import com.radius.shared.domain.threads.ThreadsInbox
import kotlinx.coroutines.CoroutineScope

/**
 * THE SINGLE ENTRY POINT into the shared core, for both platforms.
 *
 * Android reaches this through exactly one Hilt `@Provides` (mobile/android/di/SharedModule.kt).
 * iOS constructs it directly in Swift. Neither client reaches past it into internals.
 *
 * DI RULES (ADR-007, non-negotiable):
 *  - CONSTRUCTOR INJECTION ONLY. Every collaborator arrives through this constructor.
 *  - NO Koin. NO Hilt. NO Dagger. NO service locator. NO DI framework of any kind in commonMain,
 *    ever. A DI framework here would become a third contract between the two clients and would
 *    have to be understood by iOS engineers reading Kotlin. It buys nothing a factory does not.
 *  - Hilt exists on the Android side only, for the Android UI graph, and stops at this boundary.
 *
 * THREADING: the core owns [scope]. Callbacks fire on the core's dispatchers, NOT the main thread.
 * Swift callers hop to `MainActor` themselves; Compose collects with `collectAsStateWithLifecycle`.
 */
public class RadiusCore internal constructor(
    public val config: RadiusConfig,
    public val discover: DiscoverFeed,
    public val radar: RadarController,
    public val threads: ThreadsInbox,
    private val scope: CoroutineScope,
) {

    /**
     * Swift-facing accessor for the radar node stream. Kotlin/Android should prefer
     * `radar.nodes()` directly; this exists so Swift never sees a raw `Flow` (risk R12).
     *
     * Every future Swift-facing stream gets a sibling of this method. That is the documented
     * wrapper convention — see [FlowAdapter].
     */
    public fun radarNodesAdapter(): FlowAdapter<List<RadarNode>> =
        FlowAdapter(radar.nodes(), scope)

    public companion object {
        /**
         * THE FACTORY. A plain function. This is all the "DI" this module will ever have.
         *
         * @param config immutable app configuration.
         * @param radio the platform radio, built by the platform (Android needs a `Context`,
         *   iOS does not) and injected here.
         * @param scope the scope the core runs on. The platform owns its lifetime and cancels it
         *   on teardown; the core never creates a `GlobalScope`.
         */
        public fun create(
            config: RadiusConfig,
            radio: BleRadio,
            scope: CoroutineScope,
        ): RadiusCore {
            // NOT IMPLEMENTED, ON PURPOSE, AND NOT FAKED.
            //
            // Wiring this up requires three things that do not exist yet, in this order:
            //   1. mobile/protocol/ — the BLE wire spec + conformance vectors (ble-protocol).
            //   2. mobile/shared/protocol/ — the single Kotlin codec + state machine that passes
            //      those vectors (ble-protocol). NOT ours to write. Do not stub it here.
            //   3. persistence (SQLDelight schema) and the transport decision recorded in
            //      20-state OPEN QUESTIONS.
            //
            // Returning a no-op core with empty flows would let the app "work", which is worse
            // than useless: it would let someone demo a radar that can never see anyone and call
            // Phase 0 green. It throws instead. Nothing in the Android shell injects it yet.
            TODO(
                "RadiusCore.create is not implemented. Blocked on mobile/shared/protocol/ " +
                    "(ble-protocol) and on the shared persistence/transport tasks. " +
                    "See .claude/memory/20-state.md NEXT 6, items 2 and 3."
            )
        }
    }
}
