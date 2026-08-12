package hr.sonicpulse.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.detection.AdaptiveDetectionProcessorFactory
import hr.sonicpulse.app.detection.DetectionProcessorFactory
import hr.sonicpulse.app.observability.DetectionSessionLogger
import hr.sonicpulse.engine.adaptive.AdaptiveEngineConfig
import javax.inject.Singleton

/**
 * Provides the single [AdaptiveEngineConfig] used across the app's V2 wiring — shared by
 * [DetectionProcessorFactory] (via [AdaptiveDetectionProcessorFactory]) and
 * [hr.sonicpulse.app.observability.JsonDetectionSessionLogger] (via constructor injection),
 * so the exported session log's config snapshot always describes the exact config every
 * session's processor was actually built from.
 *
 * `@Provides` (not `@Binds`) for [DetectionProcessorFactory]: [AdaptiveDetectionProcessorFactory]'s
 * constructor takes an optional `AdaptiveEngineConfig` with a default value, which Dagger
 * cannot resolve via plain constructor injection alone. The factory itself is safe to share as
 * a singleton (its config is immutable) — every monitoring session still gets its own fresh
 * [hr.sonicpulse.app.detection.DetectionProcessor] from [DetectionProcessorFactory.create].
 *
 * [DetectionSessionLogger] is requested here purely as a parameter, not selected or branched
 * on — `di/ObservabilityModule` remains the single place `BuildConfig.ENABLE_SESSION_LOGGING`
 * is checked; this module only wires whichever implementation Dagger resolves for it into
 * every V2 processor via `LoggingAdaptiveDetectionProcessor`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DetectionModule {

    @Provides
    @Singleton
    fun provideAdaptiveEngineConfig(): AdaptiveEngineConfig = AdaptiveEngineConfig()

    @Provides
    @Singleton
    fun provideDetectionProcessorFactory(
        config: AdaptiveEngineConfig,
        detectionSessionLogger: DetectionSessionLogger
    ): DetectionProcessorFactory =
        AdaptiveDetectionProcessorFactory(config, detectionSessionLogger)
}
