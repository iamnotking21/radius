package com.radius.android.di

import android.content.Context
import com.radius.shared.ble.BleRadio
import com.radius.shared.core.RadiusConfig
import com.radius.shared.core.RadiusCore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * THE ONLY SEAM BETWEEN HILT AND THE SHARED CORE.
 *
 * ADR-007 is explicit: `:shared` uses constructor injection and a plain factory. No Koin, no
 * Dagger, no DI framework of any kind in commonMain, ever — a DI framework there would become a
 * third contract between the two clients and iOS engineers would have to debug it in Kotlin.
 *
 * Hilt is the ANDROID UI GRAPH and it stops at this file. If a `@Inject` annotation ever appears
 * inside `mobile/shared/`, that is an architecture violation, not a refactor.
 *
 * The iOS equivalent of this file is roughly ten lines of Swift calling the same factory. That
 * symmetry is the test: if Android needs something here that Swift cannot express as a plain
 * constructor call, the shared API is wrong.
 *
 * !! UNVERIFIED !! Never compiled (blocker B5).
 */
@Module
@InstallIn(SingletonComponent::class)
object SharedModule {

    /**
     * The platform radio. Android's half of the `expect`/`actual` pair.
     *
     * Application context, never an Activity: the radio outlives every Activity by design, since
     * it keeps running under the foreground service.
     */
    @Provides
    @Singleton
    fun provideBleRadio(@ApplicationContext context: Context): BleRadio = BleRadio(context)

    /**
     * Scope owned by the app process, cancelled only when the process dies. `SupervisorJob` so one
     * failed collector cannot take down the whole core.
     */
    @Provides
    @Singleton
    @CoreScope
    fun provideCoreScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideRadiusConfig(): RadiusConfig = RadiusConfig(
        // TODO(config): read from BuildConfig / a signed remote-config-free constant once the API
        //  domain exists. Placeholder value; there is no backend deployed (blocker B3).
        apiBaseUrl = "https://api.invalid.radius.local",
        // EMPTY PIN LIST IS A DEBUG-ONLY STATE. mobile/CLAUDE.md requires the API domain to be
        // certificate-pinned. Shipping a release with an empty pin set must fail CI, not warn.
        // TODO(security): real SPKI pins + a backup pin, sourced from devops-tencent.
        certificatePinsBase64 = emptyList(),
        channel = RadiusConfig.Channel.DEBUG,
    )

    /**
     * THE single entry point into the shared core.
     *
     * WARNING — `RadiusCore.create()` currently throws `NotImplementedError` on purpose. It is
     * blocked on `mobile/shared/protocol/` (ble-protocol) and the persistence/transport tasks.
     *
     * That is safe today only because NOTHING injects `RadiusCore` yet: Hilt providers are lazy,
     * so this method is never called. The Radar service injects [BleRadio] directly instead.
     * The first screen that injects `RadiusCore` will crash instantly and loudly, which is the
     * intended behaviour — a no-op core that let the app "work" would let someone demo a radar
     * that can never find anyone and call Phase 0 green.
     */
    @Provides
    @Singleton
    fun provideRadiusCore(
        config: RadiusConfig,
        radio: BleRadio,
        @CoreScope scope: CoroutineScope,
    ): RadiusCore = RadiusCore.create(config, radio, scope)
}

/** Distinguishes the core's long-lived scope from any other `CoroutineScope` in the graph. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoreScope
