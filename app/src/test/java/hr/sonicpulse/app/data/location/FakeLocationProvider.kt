package hr.sonicpulse.app.data.location

class FakeLocationProvider : LocationProvider {

    override var currentSnapshot: LocationSnapshot = LocationSnapshot.NoFixYet

    var permissionLevelValue: LocationPermissionLevel = LocationPermissionLevel.FINE

    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set

    override fun permissionLevel(): LocationPermissionLevel = permissionLevelValue

    override fun start() {
        startCallCount++
    }

    override fun stop() {
        stopCallCount++
    }
}
