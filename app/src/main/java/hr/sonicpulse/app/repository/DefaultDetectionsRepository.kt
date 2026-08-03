package hr.sonicpulse.app.repository

import hr.sonicpulse.app.data.datastore.InstallationIdRepository
import hr.sonicpulse.app.data.remote.DetectionApi
import hr.sonicpulse.app.data.remote.DetectionHistoryMapper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultDetectionsRepository @Inject constructor(
    private val detectionApi: DetectionApi,
    private val installationIdRepository: InstallationIdRepository
) : DetectionsRepository {

    override suspend fun getDetectionsPage(cursor: Long?, limit: Int): DetectionPage {
        val deviceId = installationIdRepository.getOrCreate()
        val response = detectionApi.getDetectionHistory(deviceId = deviceId, cursor = cursor, limit = limit)
        return DetectionPage(
            items = response.items.map(DetectionHistoryMapper::toDomain),
            nextCursor = response.nextCursor
        )
    }
}
