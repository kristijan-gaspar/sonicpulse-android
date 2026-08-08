package hr.sonicpulse.app.service

import hr.sonicpulse.app.data.location.LocationPermissionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringStartupGateTest {

    private fun gate(
        hasRecordAudioPermission: () -> Boolean = { true },
        locationPermissionLevel: () -> LocationPermissionLevel = {
            LocationPermissionLevel.FINE
        },
        areLocationServicesEnabled: () -> Boolean = { true },
        startForeground: () -> ForegroundStartOutcome = {
            ForegroundStartOutcome.Started
        },
        enableLocationForegroundType: () -> ForegroundStartOutcome = {
            ForegroundStartOutcome.Started
        }
    ) = MonitoringStartupGate(
        hasRecordAudioPermission = hasRecordAudioPermission,
        locationPermissionLevel = locationPermissionLevel,
        areLocationServicesEnabled = areLocationServicesEnabled,
        startForeground = startForeground,
        enableLocationForegroundType = enableLocationForegroundType
    )

    @Test
    fun `missing RECORD_AUDIO denies startup without checking anything else`() {
        var locationChecked = false
        var servicesChecked = false
        var startForegroundCalled = false
        var locationForegroundCalled = false

        val result = gate(
            hasRecordAudioPermission = { false },
            locationPermissionLevel = {
                locationChecked = true
                LocationPermissionLevel.FINE
            },
            areLocationServicesEnabled = {
                servicesChecked = true
                true
            },
            startForeground = {
                startForegroundCalled = true
                ForegroundStartOutcome.Started
            },
            enableLocationForegroundType = {
                locationForegroundCalled = true
                ForegroundStartOutcome.Started
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.MicrophonePermissionDenied
            ),
            result
        )
        assertFalse(locationChecked)
        assertFalse(servicesChecked)
        assertFalse(startForegroundCalled)
        assertFalse(locationForegroundCalled)
    }

    @Test
    fun `missing location permission denies startup after microphone promotion`() {
        var servicesChecked = false
        var microphonePromotionCalled = false
        var locationPromotionCalled = false

        val result = gate(
            locationPermissionLevel = {
                LocationPermissionLevel.NONE
            },
            areLocationServicesEnabled = {
                servicesChecked = true
                true
            },
            startForeground = {
                microphonePromotionCalled = true
                ForegroundStartOutcome.Started
            },
            enableLocationForegroundType = {
                locationPromotionCalled = true
                ForegroundStartOutcome.Started
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.LocationPermissionDenied
            ),
            result
        )
        assertTrue(microphonePromotionCalled)
        assertFalse(servicesChecked)
        assertFalse(locationPromotionCalled)
    }

    @Test
    fun `disabled location services deny startup after microphone promotion`() {
        var microphonePromotionCalled = false
        var locationPromotionCalled = false

        val result = gate(
            areLocationServicesEnabled = { false },
            startForeground = {
                microphonePromotionCalled = true
                ForegroundStartOutcome.Started
            },
            enableLocationForegroundType = {
                locationPromotionCalled = true
                ForegroundStartOutcome.Started
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.LocationServicesDisabled
            ),
            result
        )
        assertTrue(microphonePromotionCalled)
        assertFalse(locationPromotionCalled)
    }

    @Test
    fun `foreground promotion failure is reported as ForegroundStartFailed`() {
        val cause = IllegalStateException(
            "not allowed to start foreground service"
        )

        val result = gate(
            startForeground = {
                ForegroundStartOutcome.Failed(cause)
            }
        ).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)

        val failure =
            (result as MonitoringStartupResult.Failed).failure

        assertTrue(
            failure is MonitoringStartupFailure.ForegroundStartFailed
        )
        assertSame(
            cause,
            (failure as MonitoringStartupFailure.ForegroundStartFailed).cause
        )
    }

    @Test
    fun `SecurityException during microphone promotion is attributed to revoked microphone permission`() {
        var microphoneCheckCount = 0
        val cause = SecurityException("record audio")

        val result = gate(
            hasRecordAudioPermission = {
                microphoneCheckCount++
                microphoneCheckCount == 1
            },
            startForeground = {
                ForegroundStartOutcome.PermissionDenied(cause)
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.MicrophonePermissionDenied
            ),
            result
        )
    }

    @Test
    fun `SecurityException during location foreground promotion is attributed to revoked location permission`() {
        var locationCheckCount = 0
        val cause = SecurityException("location")

        val result = gate(
            locationPermissionLevel = {
                locationCheckCount++

                if (locationCheckCount == 1) {
                    LocationPermissionLevel.FINE
                } else {
                    LocationPermissionLevel.NONE
                }
            },
            enableLocationForegroundType = {
                ForegroundStartOutcome.PermissionDenied(cause)
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.LocationPermissionDenied
            ),
            result
        )
        assertEquals(2, locationCheckCount)
    }

    @Test
    fun `SecurityException during microphone-only promotion is never attributed to missing location permission`() {
        val cause = SecurityException("record audio")

        val result = gate(
            locationPermissionLevel = { LocationPermissionLevel.NONE },
            startForeground = { ForegroundStartOutcome.PermissionDenied(cause) }
        ).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)

        val failure = (result as MonitoringStartupResult.Failed).failure

        assertTrue(failure is MonitoringStartupFailure.ForegroundStartFailed)
        assertSame(cause, (failure as MonitoringStartupFailure.ForegroundStartFailed).cause)
    }

    @Test
    fun `SecurityException during microphone+location promotion is still attributed to revoked microphone permission`() {
        var checkCount = 0
        val cause = SecurityException("record audio revoked mid-flight")

        val result = gate(
            hasRecordAudioPermission = {
                checkCount++
                checkCount <= 2
            },
            enableLocationForegroundType = { ForegroundStartOutcome.PermissionDenied(cause) }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.MicrophonePermissionDenied
            ),
            result
        )
    }

    @Test
    fun `unattributed SecurityException is reported as ForegroundStartFailed`() {
        val cause = SecurityException("unexplained")

        val result = gate(
            startForeground = {
                ForegroundStartOutcome.PermissionDenied(cause)
            }
        ).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)

        val failure =
            (result as MonitoringStartupResult.Failed).failure

        assertTrue(
            failure is MonitoringStartupFailure.ForegroundStartFailed
        )
        assertSame(
            cause,
            (failure as MonitoringStartupFailure.ForegroundStartFailed).cause
        )
    }

    @Test
    fun `location foreground promotion failure is reported as ForegroundStartFailed`() {
        val cause = IllegalStateException(
            "location foreground promotion failed"
        )

        val result = gate(
            enableLocationForegroundType = {
                ForegroundStartOutcome.Failed(cause)
            }
        ).attemptStartup()

        assertTrue(result is MonitoringStartupResult.Failed)

        val failure =
            (result as MonitoringStartupResult.Failed).failure

        assertTrue(
            failure is MonitoringStartupFailure.ForegroundStartFailed
        )
        assertSame(
            cause,
            (failure as MonitoringStartupFailure.ForegroundStartFailed).cause
        )
    }

    @Test
    fun `RECORD_AUDIO revoked after microphone promotion denies startup`() {
        var checkCount = 0

        val result = gate(
            hasRecordAudioPermission = {
                checkCount++
                checkCount == 1
            }
        ).attemptStartup()

        assertEquals(
            MonitoringStartupResult.Failed(
                MonitoringStartupFailure.MicrophonePermissionDenied
            ),
            result
        )
    }

    @Test
    fun `all checks and both foreground promotions succeeding allow startup`() {
        val result = gate().attemptStartup()

        assertEquals(
            MonitoringStartupResult.Proceed,
            result
        )
    }
}
