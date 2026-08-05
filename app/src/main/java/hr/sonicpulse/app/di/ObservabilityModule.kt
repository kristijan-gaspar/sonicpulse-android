package hr.sonicpulse.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.observability.DetectionSessionLogger
import hr.sonicpulse.app.observability.JsonDetectionSessionLogger
import hr.sonicpulse.app.observability.NoOpDetectionSessionLogger
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The single place `BuildConfig.ENABLE_SESSION_LOGGING` is checked for the session-logging
 * feature — every other caller (in particular [hr.sonicpulse.app.service.MonitoringService] and
 * [hr.sonicpulse.app.ui.monitoring.MonitoringViewModel]) only ever sees [DetectionSessionLogger],
 * never the flag itself. A `@Provides` method (not `@Binds`) because the concrete
 * implementation genuinely depends on a runtime condition, not just an interface/impl pairing.
 *
 * Both candidates are requested as `Provider<T>`, not the concrete type directly: Dagger only
 * constructs a `Provider`'s value when `.get()` is actually called, so in a release build (flag
 * false) [JsonDetectionSessionLogger] — which this module never calls `.get()` on — is never
 * instantiated, even though both providers are injected into this method. If either
 * implementation later gains its own dependencies, Dagger (not this module) constructs it.
 */
@Module
@InstallIn(SingletonComponent::class)
object ObservabilityModule {

    @Provides
    @Singleton
    fun provideDetectionSessionLogger(
        jsonLoggerProvider: Provider<JsonDetectionSessionLogger>,
        noOpLoggerProvider: Provider<NoOpDetectionSessionLogger>
    ): DetectionSessionLogger =
        if (BuildConfig.ENABLE_SESSION_LOGGING) {
            jsonLoggerProvider.get()
        } else {
            noOpLoggerProvider.get()
        }
}
