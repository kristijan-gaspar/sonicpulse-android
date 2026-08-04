package hr.sonicpulse.app.service.notification

import hr.sonicpulse.app.data.datastore.FakeAppSettingsRepository
import hr.sonicpulse.app.data.location.LocationSnapshot
import hr.sonicpulse.app.domain.model.AppSettings
import hr.sonicpulse.app.domain.model.SessionDetection
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionAlertCoordinatorTest {

    private fun detection() = SessionDetection(
        localEventId = UUID.randomUUID(),
        peakDbfs = -10.0,
        peakTimeClient = Instant.EPOCH,
        location = LocationSnapshot.Valid(45.8, 16.0, 8.0f)
    )

    private fun coordinator(
        settings: AppSettings,
        notifier: FakeDetectionNotifier = FakeDetectionNotifier(),
        vibrator: FakeDetectionVibrator = FakeDetectionVibrator()
    ) = Triple(
        DetectionAlertCoordinator(FakeAppSettingsRepository(settings), notifier, vibrator),
        notifier,
        vibrator
    )

    @Test
    fun `notification disabled means no detection notification`() = runTest {
        val (coordinator, notifier, _) = coordinator(AppSettings(detectionNotificationEnabled = false))

        coordinator.onLocalDetection(detection())

        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `notification enabled posts exactly one notification for one local detection`() = runTest {
        val (coordinator, notifier, _) = coordinator(AppSettings(detectionNotificationEnabled = true))
        val target = detection()

        coordinator.onLocalDetection(target)

        assertEquals(listOf(target), notifier.notified)
    }

    @Test
    fun `duplicate observation of the same detection does not duplicate the notification`() = runTest {
        val (coordinator, notifier, _) = coordinator(AppSettings(detectionNotificationEnabled = true))
        val target = detection()

        coordinator.onLocalDetection(target)
        coordinator.onLocalDetection(target)

        assertEquals(1, notifier.notified.size)
    }

    @Test
    fun `vibration disabled means no vibration`() = runTest {
        val (coordinator, _, vibrator) = coordinator(AppSettings(detectionVibrationEnabled = false))

        coordinator.onLocalDetection(detection())

        assertEquals(0, vibrator.vibrationCount)
    }

    @Test
    fun `vibration enabled vibrates exactly once for one local detection`() = runTest {
        val (coordinator, _, vibrator) = coordinator(AppSettings(detectionVibrationEnabled = true))

        coordinator.onLocalDetection(detection())

        assertEquals(1, vibrator.vibrationCount)
    }

    @Test
    fun `duplicate observation of the same detection does not duplicate the vibration`() = runTest {
        val (coordinator, _, vibrator) = coordinator(AppSettings(detectionVibrationEnabled = true))
        val target = detection()

        coordinator.onLocalDetection(target)
        coordinator.onLocalDetection(target)

        assertEquals(1, vibrator.vibrationCount)
    }

    @Test
    fun `a different detection after the first is alerted independently`() = runTest {
        val (coordinator, notifier, vibrator) = coordinator(
            AppSettings(detectionNotificationEnabled = true, detectionVibrationEnabled = true)
        )

        coordinator.onLocalDetection(detection())
        coordinator.onLocalDetection(detection())

        assertEquals(2, notifier.notified.size)
        assertEquals(2, vibrator.vibrationCount)
    }

    @Test
    fun `both disabled means neither notification nor vibration`() = runTest {
        val (coordinator, notifier, vibrator) = coordinator(
            AppSettings(detectionNotificationEnabled = false, detectionVibrationEnabled = false)
        )

        coordinator.onLocalDetection(detection())

        assertTrue(notifier.notified.isEmpty())
        assertEquals(0, vibrator.vibrationCount)
    }

    @Test
    fun `settings changes are respected without restarting monitoring`() = runTest {
        val settingsRepository = FakeAppSettingsRepository(AppSettings(detectionNotificationEnabled = false))
        val notifier = FakeDetectionNotifier()
        val coordinator = DetectionAlertCoordinator(settingsRepository, notifier, FakeDetectionVibrator())

        coordinator.onLocalDetection(detection())
        assertTrue(notifier.notified.isEmpty())

        // The same coordinator instance, as MonitoringService keeps for the whole session — a
        // later Settings-screen change must be picked up on the very next detection.
        settingsRepository.setDetectionNotificationEnabled(true)
        coordinator.onLocalDetection(detection())

        assertEquals(1, notifier.notified.size)
    }
}
