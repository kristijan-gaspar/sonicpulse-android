package hr.sonicpulse.app.ui.settings

import android.Manifest
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.data.datastore.FakeAppSettingsRepository
import hr.sonicpulse.app.data.datastore.FakeInstallationIdRepository
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.SessionDetection
import hr.sonicpulse.app.domain.model.SubmissionFailureReason
import hr.sonicpulse.app.domain.model.ThemeMode
import hr.sonicpulse.app.repository.FakeMonitoringStateRepository
import hr.sonicpulse.app.repository.SubmissionCounters
import hr.sonicpulse.app.ui.permissions.FakePermissionChecker
import hr.sonicpulse.app.ui.theme.FakeAppLanguageController
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun detection() = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = -10.0,
        peakTimeClient = Instant.EPOCH,
        location = LocationSnapshot.Valid(45.8, 16.0, 8.0f)
    )

    /** [SettingsViewModel.uiState] is built via `stateIn(started = SharingStarted.Eagerly, ...)` —
     * with a [StandardTestDispatcher] (unlike real `Dispatchers.Main.immediate`), the eager
     * collector only actually runs once the dispatcher is advanced, so every caller here needs
     * the state to reflect the fakes' current values before its first assertion. */
    private fun TestScope.viewModel(
        settingsRepository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        monitoringStateRepository: FakeMonitoringStateRepository = FakeMonitoringStateRepository(),
        installationIdRepository: FakeInstallationIdRepository = FakeInstallationIdRepository(fixedId = "fixed-id"),
        permissionChecker: FakePermissionChecker = FakePermissionChecker(),
        appLanguageController: FakeAppLanguageController = FakeAppLanguageController()
    ) = SettingsViewModel(
        settingsRepository,
        monitoringStateRepository,
        installationIdRepository,
        permissionChecker,
        appLanguageController
    ).also { advanceUntilIdle() }

    @Test
    fun `maps persisted settings into ui state`() = runTest(testDispatcher) {
        val settingsRepository = FakeAppSettingsRepository(AppSettings(themeMode = ThemeMode.Light))
        val languageController = FakeAppLanguageController(initial = AppLanguage.English)

        val state = viewModel(settingsRepository = settingsRepository, appLanguageController = languageController).uiState.value

        assertEquals(ThemeMode.Light, state.themeMode)
        assertEquals(AppLanguage.English, state.language)
    }

    @Test
    fun `maps granted microphone and precise location permissions`() = runTest(testDispatcher) {
        val checker = FakePermissionChecker()
        checker.setGranted(Manifest.permission.RECORD_AUDIO, true)
        checker.setGranted(Manifest.permission.ACCESS_FINE_LOCATION, true)

        val state = viewModel(permissionChecker = checker).uiState.value

        assertTrue(state.microphonePermissionGranted)
        assertTrue(state.preciseLocationPermissionGranted)
    }

    @Test
    fun `approximate-only location is not reported as precise permission granted`() = runTest(testDispatcher) {
        // Only COARSE granted, FINE not granted — mirrors the real ContextCompat check the
        // FakePermissionChecker stands in for; the two permissions are checked independently.
        val checker = FakePermissionChecker()
        checker.setGranted(Manifest.permission.ACCESS_COARSE_LOCATION, true)

        val state = viewModel(permissionChecker = checker).uiState.value

        assertFalse(state.preciseLocationPermissionGranted)
    }

    @Test
    fun `maps successful submission counter`() = runTest(testDispatcher) {
        val monitoringStateRepository = FakeMonitoringStateRepository()
        monitoringStateRepository.monitoringStarted()
        val target = detection()
        monitoringStateRepository.localDetectionOccurred(target)
        monitoringStateRepository.submissionSucceeded(target.localEventId)

        val state = viewModel(monitoringStateRepository = monitoringStateRepository).uiState.value

        assertEquals(1, state.successfulSubmissions)
    }

    @Test
    fun `maps the uncapped local detection count directly, not the capped retained list`() = runTest(testDispatcher) {
        val monitoringStateRepository = FakeMonitoringStateRepository()
        monitoringStateRepository.monitoringStarted()
        repeat(101) { monitoringStateRepository.localDetectionOccurred(detection()) }

        val state = viewModel(monitoringStateRepository = monitoringStateRepository).uiState.value

        assertEquals(101, state.localDetections)
        assertEquals(100, monitoringStateRepository.state.value.sessionDetections.size)
    }

    @Test
    fun `maps network error counter`() = runTest(testDispatcher) {
        val monitoringStateRepository = FakeMonitoringStateRepository()
        monitoringStateRepository.monitoringStarted()
        val target = detection()
        monitoringStateRepository.localDetectionOccurred(target)
        monitoringStateRepository.submissionFailed(target.localEventId, SubmissionFailureReason.NetworkError)

        val state = viewModel(monitoringStateRepository = monitoringStateRepository).uiState.value

        assertEquals(1, state.networkErrors)
    }

    @Test
    fun `maps combined location-drop count from no-location, stale and inaccurate reasons`() = runTest(testDispatcher) {
        val monitoringStateRepository = FakeMonitoringStateRepository()
        monitoringStateRepository.monitoringStarted()
        listOf(
            SubmissionFailureReason.NoLocation,
            SubmissionFailureReason.StaleLocation,
            SubmissionFailureReason.InaccurateLocation
        ).forEach { reason ->
            val target = detection()
            monitoringStateRepository.localDetectionOccurred(target)
            monitoringStateRepository.submissionFailed(target.localEventId, reason)
        }

        val state = viewModel(monitoringStateRepository = monitoringStateRepository).uiState.value

        assertEquals(3, state.droppedLocation)
    }

    @Test
    fun `maps the real permissionFailures diagnostic`() = runTest(testDispatcher) {
        val monitoringStateRepository = FakeMonitoringStateRepository()
        monitoringStateRepository.setState(monitoringStateRepository.state.value.copy(submissionCounters = SubmissionCounters(permissionFailures = 4)))

        val state = viewModel(monitoringStateRepository = monitoringStateRepository).uiState.value

        assertEquals(4, state.permissionFailures)
    }

    @Test
    fun `exposes the existing installation id unchanged`() = runTest(testDispatcher) {
        val viewModel = viewModel(installationIdRepository = FakeInstallationIdRepository(fixedId = "abc-123"))

        advanceUntilIdle()

        assertEquals("abc-123", viewModel.uiState.value.installationId)
    }

    @Test
    fun `exposes BuildConfig VERSION_NAME`() = runTest(testDispatcher) {
        val state = viewModel().uiState.value

        assertEquals(BuildConfig.VERSION_NAME, state.versionName)
    }

    @Test
    fun `setThemeMode calls the repository`() = runTest(testDispatcher) {
        val settingsRepository = FakeAppSettingsRepository()
        val viewModel = viewModel(settingsRepository = settingsRepository)

        viewModel.setThemeMode(ThemeMode.Light)
        advanceUntilIdle()

        assertEquals(listOf(ThemeMode.Light), settingsRepository.setThemeModeCalls)
    }

    @Test
    fun `changing language calls the correct AppLanguageController operation`() = runTest(testDispatcher) {
        val languageController = FakeAppLanguageController(initial = AppLanguage.Croatian)
        val viewModel = viewModel(appLanguageController = languageController)

        viewModel.setLanguage(AppLanguage.English)
        advanceUntilIdle()

        assertEquals(listOf(AppLanguage.English), languageController.setLanguageCalls)
        assertEquals(AppLanguage.English, viewModel.uiState.value.language)
    }

    @Test
    fun `selecting the already-active theme does not write to the repository`() = runTest(testDispatcher) {
        val settingsRepository = FakeAppSettingsRepository(AppSettings(themeMode = ThemeMode.Dark))
        val viewModel = viewModel(settingsRepository = settingsRepository)

        viewModel.setThemeMode(ThemeMode.Dark)
        advanceUntilIdle()

        assertTrue(settingsRepository.setThemeModeCalls.isEmpty())
    }

    @Test
    fun `selecting the currently effective language is a no-op`() = runTest(testDispatcher) {
        val languageController = FakeAppLanguageController(initial = AppLanguage.Croatian)
        val viewModel = viewModel(appLanguageController = languageController)

        viewModel.setLanguage(AppLanguage.Croatian)
        advanceUntilIdle()

        assertTrue(languageController.setLanguageCalls.isEmpty())
    }

    @Test
    fun `constructing the ViewModel only reads the current language, never writes it`() = runTest(testDispatcher) {
        val languageController = FakeAppLanguageController(initial = AppLanguage.English)

        val viewModel = viewModel(appLanguageController = languageController)

        assertEquals(AppLanguage.English, viewModel.uiState.value.language)
        assertTrue(languageController.setLanguageCalls.isEmpty())
    }

    @Test
    fun `refreshPermissionStatus re-reads permission status`() = runTest(testDispatcher) {
        val checker = FakePermissionChecker()
        val viewModel = viewModel(permissionChecker = checker)
        check(!viewModel.uiState.value.microphonePermissionGranted)

        checker.setGranted(Manifest.permission.RECORD_AUDIO, true)
        viewModel.refreshPermissionStatus()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.microphonePermissionGranted)
    }
}
