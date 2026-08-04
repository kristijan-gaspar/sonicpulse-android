package hr.sonicpulse.app.service.notification

class FakeDetectionVibrator : DetectionVibrator {
    var vibrationCount = 0
        private set

    override fun vibrateOnce() {
        vibrationCount++
    }
}
