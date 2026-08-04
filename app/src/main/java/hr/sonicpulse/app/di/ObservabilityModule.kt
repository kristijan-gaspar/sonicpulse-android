package hr.sonicpulse.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.observability.DetectionSessionLogger
import hr.sonicpulse.app.observability.JsonDetectionSessionLogger
import hr.sonicpulse.app.observability.NoOpDetectionSessionLogger
import javax.inject.Singleton

/**
 * The single place `BuildConfig.ENABLE_SESSION_LOGGING` is checked for the session-logging
 * feature — every other caller (in particular [hr.sonicpulse.app.service.MonitoringService] and
 * [hr.sonicpulse.app.ui.monitoring.MonitoringViewModel]) only ever sees [DetectionSessionLogger],
 * never the flag itself. A `@Provides` method (not `@Binds`) because the concrete
 * implementation genuinely depends on a runtime condition, not just an interface/impl pairing.
 */
@Module
@InstallIn(SingletonComponent::class)
object ObservabilityModule {

    @Provides
    @Singleton
    fun provideDetectionSessionLogger(): DetectionSessionLogger =
        if (BuildConfig.ENABLE_SESSION_LOGGING) {
            JsonDetectionSessionLogger()
        } else {
            NoOpDetectionSessionLogger()
        }
}
