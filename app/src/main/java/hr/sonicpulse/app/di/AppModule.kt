package hr.sonicpulse.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.sonicpulse.app.data.datastore.AppSettingsRepository
import hr.sonicpulse.app.data.datastore.DefaultAppSettingsRepository
import hr.sonicpulse.app.data.datastore.DefaultInstallationIdRepository
import hr.sonicpulse.app.data.datastore.DefaultPermissionRequestHistory
import hr.sonicpulse.app.data.datastore.InstallationIdRepository
import hr.sonicpulse.app.data.datastore.PermissionRequestHistory
import hr.sonicpulse.app.data.location.DefaultLocationProvider
import hr.sonicpulse.app.data.location.LocationProvider
import hr.sonicpulse.app.data.remote.AndroidSubmissionLogger
import hr.sonicpulse.app.data.remote.SubmissionLogger
import hr.sonicpulse.app.repository.DefaultDetectionsRepository
import hr.sonicpulse.app.repository.DefaultHotspotsRepository
import hr.sonicpulse.app.repository.DefaultMonitoringStateRepository
import hr.sonicpulse.app.repository.DetectionsRepository
import hr.sonicpulse.app.repository.HotspotsRepository
import hr.sonicpulse.app.repository.MonitoringStateRepository
import hr.sonicpulse.app.ui.permissions.AndroidPermissionChecker
import hr.sonicpulse.app.ui.permissions.PermissionChecker
import hr.sonicpulse.app.ui.theme.AppLanguageController
import hr.sonicpulse.app.ui.theme.DefaultAppLanguageController

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindMonitoringStateRepository(
        impl: DefaultMonitoringStateRepository
    ): MonitoringStateRepository

    @Binds
    abstract fun bindDetectionsRepository(
        impl: DefaultDetectionsRepository
    ): DetectionsRepository

    @Binds
    abstract fun bindHotspotsRepository(
        impl: DefaultHotspotsRepository
    ): HotspotsRepository

    @Binds
    abstract fun bindLocationProvider(
        impl: DefaultLocationProvider
    ): LocationProvider

    @Binds
    abstract fun bindInstallationIdRepository(
        impl: DefaultInstallationIdRepository
    ): InstallationIdRepository

    @Binds
    abstract fun bindSubmissionLogger(
        impl: AndroidSubmissionLogger
    ): SubmissionLogger

    @Binds
    abstract fun bindPermissionRequestHistory(
        impl: DefaultPermissionRequestHistory
    ): PermissionRequestHistory

    @Binds
    abstract fun bindAppSettingsRepository(
        impl: DefaultAppSettingsRepository
    ): AppSettingsRepository

    @Binds
    abstract fun bindPermissionChecker(
        impl: AndroidPermissionChecker
    ): PermissionChecker

    @Binds
    abstract fun bindAppLanguageController(
        impl: DefaultAppLanguageController
    ): AppLanguageController
}
