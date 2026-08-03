package hr.sonicpulse.app.data.datastore

/**
 * Whether this app installation has ever requested a given runtime permission before — Android
 * itself does not expose this. Without it, `shouldShowRequestPermissionRationale() == false` is
 * ambiguous: it's true both before the permission was ever requested and after it has been
 * permanently denied, and those two cases must be handled very differently.
 */
interface PermissionRequestHistory {
    suspend fun hasRequestedBefore(permission: String): Boolean
    suspend fun markRequested(permission: String)
}
