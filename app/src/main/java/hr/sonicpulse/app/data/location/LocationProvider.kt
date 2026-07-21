package hr.sonicpulse.app.data.location

interface LocationProvider {
    val currentSnapshot: LocationSnapshot

    fun permissionLevel(): LocationPermissionLevel

    /** Idempotent: a no-op if already started. Clears any fix from a previous session. */
    fun start()

    /** Idempotent: a no-op if not currently started. Always removes the location callback. */
    fun stop()
}
