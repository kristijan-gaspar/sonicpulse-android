package hr.sonicpulse.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.data.location.DefaultLocationProvider
import hr.sonicpulse.app.data.location.LocationProvider
import hr.sonicpulse.app.repository.DefaultMonitoringStateRepository
import hr.sonicpulse.app.repository.MonitoringStateRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindMonitoringStateRepository(
        impl: DefaultMonitoringStateRepository
    ): MonitoringStateRepository

    @Binds
    abstract fun bindLocationProvider(
        impl: DefaultLocationProvider
    ): LocationProvider
}
