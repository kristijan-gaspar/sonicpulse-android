package hr.sonicpulse.app.data.location

interface LocationProvider {
    val currentSnapshot: LocationSnapshot

    fun permissionLevel(): LocationPermissionLevel

    fun areLocationServicesEnabled(): Boolean

    /**
     * Idempotent: a no-op if already started or an attempt is already pending. [onResult] is
     * invoked exactly once for every accepted start attempt — synchronously for a permission or
     * location-services check that fails up front, or asynchronously once the underlying
     * location request resolves (or is cancelled by a concurrent [stop]).
     */
    fun start(onResult: (LocationStartResult) -> Unit)

    /**
     * Idempotent: a no-op if not currently started. Always removes the location callback. If a
     * start attempt was still pending, its [onResult] is invoked with [LocationStartResult.Cancelled].
     */
    fun stop()
}
