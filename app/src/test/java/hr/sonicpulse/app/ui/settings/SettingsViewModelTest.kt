package hr.sonicpulse.app.ui.settings

import android.Manifest
import hr.sonicpulse.app.BuildConfig
import hr.sonicpulse.app.data.datastore.FakeAppSettingsRepository
import hr.sonicpulse.app.data.datastore.FakeInstallationIdRepository
import hr.sonicpulse.app.domain.model.AppLanguage
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.ThemeMode
import hr.sonicpulse.app.ui.permissions.FakePermissionChecker
import hr.sonicpulse.app.ui.theme.FakeAppLanguageController
import java.io.IOException
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
import org.junit.Assert.assertNull
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

    /** [SettingsViewModel.uiState] is built via `stateIn(started = SharingStarted.Eagerly, ...)` —
     * with a [StandardTestDispatcher] (unlike real `Dispatchers.Main.immediate`), the eager
     * collector only actually runs once the dispatcher is advanced, so every caller here needs
     * the state to reflect the fakes' current values before its first assertion. */
    private fun TestScope.viewModel(
        settingsRepository: FakeAppSettingsRepository = FakeAppSettingsRepository(),
        installationIdRepository: FakeInstallationIdRepository = FakeInstallationIdRepository(fixedId = "fixed-id"),
        permissionChecker: FakePermissionChecker = FakePermissionChecker(),
        appLanguageController: FakeAppLanguageController = FakeAppLanguageController()
    ) = SettingsViewModel(
        settingsRepository,
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
    fun `exposes the existing installation id unchanged`() = runTest(testDispatcher) {
        val viewModel = viewModel(installationIdRepository = FakeInstallationIdRepository(fixedId = "abc-123"))

        advanceUntilIdle()

        assertEquals("abc-123", viewModel.uiState.value.installationId)
    }

    @Test
    fun `an IOException reading the installation id leaves it null instead of crashing the ViewModel`() =
        runTest(testDispatcher) {
            val failingRepository = FakeInstallationIdRepository().apply {
                throwOnGetOrCreate = IOException("disk unavailable")
            }

            val viewModel = viewModel(installationIdRepository = failingRepository)

            assertNull(viewModel.uiState.value.installationId)
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

    /**
     * Deliberately does NOT use the [viewModel] helper — it calls `advanceUntilIdle()` right after
     * construction, which would let `combine()`'s first real emission run and mask exactly the bug
     * this test exists to catch: `stateIn`'s own `initialValue` (read *before* any emission, on a
     * [StandardTestDispatcher] where the eager collector hasn't run yet) must already carry the
     * real effective language, not [SettingsUiState]'s own `AppLanguage.Croatian` default.
     */
    @Test
    fun `the immediate initial uiState value is English when the controller reports English`() = runTest(testDispatcher) {
        val languageController = FakeAppLanguageController(initial = AppLanguage.English)

        val viewModel = SettingsViewModel(
            FakeAppSettingsRepository(),
            FakeInstallationIdRepository(fixedId = "fixed-id"),
            FakePermissionChecker(),
            languageController
        )

        assertEquals(AppLanguage.English, viewModel.uiState.value.language)
        assertTrue(languageController.setLanguageCalls.isEmpty())
    }

    /** Same as above, mirrored for Croatian — proves both directions never show the other
     * language's default even momentarily (§2). */
    @Test
    fun `the immediate initial uiState value is Croatian when the controller reports Croatian`() = runTest(testDispatcher) {
        val languageController = FakeAppLanguageController(initial = AppLanguage.Croatian)

        val viewModel = SettingsViewModel(
            FakeAppSettingsRepository(),
            FakeInstallationIdRepository(fixedId = "fixed-id"),
            FakePermissionChecker(),
            languageController
        )

        assertEquals(AppLanguage.Croatian, viewModel.uiState.value.language)
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
