package hr.sonicpulse.app.data.datastore

/**
 * A UUID identifying this app installation (sent as the API's `deviceId` field, plan §4/branch 6)
 * — not a verified physical device identity. Generated once on first launch, persisted, and
 * regenerated only on reinstall or app-data-clear.
 */
interface InstallationIdRepository {
    suspend fun getOrCreate(): String
}
