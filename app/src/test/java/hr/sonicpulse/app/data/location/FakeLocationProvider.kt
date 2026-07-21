package hr.sonicpulse.app.data.location

class FakeLocationProvider : LocationProvider {

    override var currentSnapshot: LocationSnapshot = LocationSnapshot.NoFixYet

    var permissionLevelValue: LocationPermissionLevel = LocationPermissionLevel.FINE
    var locationServicesEnabledValue: Boolean = true

    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set

    private var pendingOnResult: ((LocationStartResult) -> Unit)? = null

    override fun permissionLevel(): LocationPermissionLevel = permissionLevelValue

    override fun areLocationServicesEnabled(): Boolean = locationServicesEnabledValue

    override fun start(onResult: (LocationStartResult) -> Unit) {
        startCallCount++
        pendingOnResult = onResult
    }

    override fun stop() {
        stopCallCount++
        val onResult = pendingOnResult
        pendingOnResult = null
        onResult?.invoke(LocationStartResult.Cancelled)
    }

    /** Test helper: resolve the most recent pending start() call. */
    fun completeStart(result: LocationStartResult) {
        val onResult = pendingOnResult
        pendingOnResult = null
        onResult?.invoke(result)
    }
}
