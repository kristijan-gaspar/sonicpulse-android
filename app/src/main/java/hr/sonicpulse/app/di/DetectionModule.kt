package hr.sonicpulse.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.detection.AdaptiveDetectionProcessorFactory
import hr.sonicpulse.app.detection.DetectionProcessorFactory
import javax.inject.Singleton

/**
 * Binds [DetectionProcessorFactory] to the V2 adaptive implementation. A `@Provides` method
 * (not `@Binds`): [AdaptiveDetectionProcessorFactory]'s constructor takes an optional
 * `AdaptiveEngineConfig` with a default value, which Dagger cannot resolve via plain
 * constructor injection. The factory itself is safe to share as a singleton (its config is
 * immutable) — every monitoring session still gets its own fresh
 * [hr.sonicpulse.app.detection.DetectionProcessor] from [DetectionProcessorFactory.create].
 */
@Module
@InstallIn(SingletonComponent::class)
object DetectionModule {

    @Provides
    @Singleton
    fun provideDetectionProcessorFactory(): DetectionProcessorFactory = AdaptiveDetectionProcessorFactory()
}
