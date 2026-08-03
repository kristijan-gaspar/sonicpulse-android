package hr.sonicpulse.app.data.datastore

class FakePermissionRequestHistory : PermissionRequestHistory {
    private val requested = mutableSetOf<String>()

    override suspend fun hasRequestedBefore(permission: String): Boolean = permission in requested

    override suspend fun markRequested(permission: String) {
        requested += permission
    }
}
