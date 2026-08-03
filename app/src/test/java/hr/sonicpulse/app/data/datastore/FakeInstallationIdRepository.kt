package hr.sonicpulse.app.data.datastore

import java.util.UUID

class FakeInstallationIdRepository(
    private val fixedId: String = UUID.randomUUID().toString()
) : InstallationIdRepository {
    var throwOnGetOrCreate: Throwable? = null

    override suspend fun getOrCreate(): String {
        throwOnGetOrCreate?.let { throw it }
        return fixedId
    }
}
