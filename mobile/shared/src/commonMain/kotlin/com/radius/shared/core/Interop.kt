package com.radius.shared.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * THE DOCUMENTED FLOW WRAPPER.
 *
 * Why this file exists: risk R12 in `.claude/memory/60-blockers.md` says
 * "no Kotlin coroutines Flow leaking raw into Swift".
 *
 * A `kotlinx.coroutines.Flow` does not survive the Kotlin/Native → Objective-C export in any usable
 * shape. Swift sees an opaque protocol with a `suspend` collect it cannot call. Every KMP project
 * that skips this step ends up with a hand-rolled, subtly different wrapper on the Swift side —
 * which is exactly the duplication ADR-007 was adopted to remove.
 *
 * RULE FOR THIS MODULE:
 *  - `Flow<T>` may appear on any declaration consumed by **Android/Kotlin**.
 *  - Anything intended for **Swift** must be reachable through a [FlowAdapter] or a plain value
 *    type. If you are about to expose a raw `Flow` on a type Swift touches, stop and wrap it.
 *
 * Interop caveats, known and accepted:
 *  - Kotlin default arguments are NOT exported to Objective-C. Swift must pass all three lambdas
 *    to [subscribe]. That is why none of them have defaults.
 *  - `T` is constrained to `Any` because Objective-C lightweight generics cannot express nullable
 *    type arguments.
 *  - [RadiusCancellable.cancel] is idempotent. Swift `deinit` may call it after completion.
 */
public class FlowAdapter<T : Any> internal constructor(
    private val flow: Flow<T>,
    private val scope: CoroutineScope,
) {
    /**
     * Collects the underlying flow on the core's scope and forwards each element to [onEach].
     *
     * Callbacks are invoked on whatever dispatcher the core's scope uses. Swift callers MUST NOT
     * assume the main thread and MUST hop to `MainActor` before touching UI.
     */
    public fun subscribe(
        onEach: (T) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: () -> Unit,
    ): RadiusCancellable {
        val job = scope.launch {
            try {
                flow.collect { value -> onEach(value) }
                onComplete()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onError(error)
            }
        }
        return JobCancellable(job)
    }
}

/**
 * Opaque subscription handle. Value-type-only surface; safe to hold from Swift.
 *
 * NAMED `RadiusCancellable`, NOT `Cancellable`, ON PURPOSE. Kotlin/Native exports this into the
 * Swift namespace, where a bare `Cancellable` collides with `Combine.Cancellable` and forces every
 * call site in the iOS app to fully qualify it. Raised by ios-swift as assumption 7 in
 * mobile/ios/README.md before this API existed; adopted here. Do not "tidy" the prefix away.
 */
public interface RadiusCancellable {
    /** Idempotent. Safe to call more than once and safe to call after completion. */
    public fun cancel()
}

private class JobCancellable(private val job: kotlinx.coroutines.Job) : RadiusCancellable {
    override fun cancel() {
        job.cancel()
    }
}
