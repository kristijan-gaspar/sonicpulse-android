package hr.sonicpulse.app.data.remote

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DetectionHistoryMapperTest {

    private fun dto(
        id: String = "22222222-2222-2222-2222-222222222222",
        sequenceNumber: Long = 123L,
        deviceId: String = "11111111-1111-1111-1111-111111111111",
        receivedAtUtc: String = "2026-08-03T10:00:00Z",
        peakTimeClient: String? = "2026-08-03T09:59:59Z",
        hotspotId: String? = "33333333-3333-3333-3333-333333333333"
    ) = DetectionHistoryItemDto(
        id = id,
        sequenceNumber = sequenceNumber,
        deviceId = deviceId,
        peakDbfs = -8.5,
        latitude = 45.8,
        longitude = 16.0,
        gpsAccuracy = 8.0,
        receivedAtUtc = receivedAtUtc,
        peakTimeClient = peakTimeClient,
        hotspotId = hotspotId
    )

    @Test
    fun `maps every field to its expected UUID and Instant value`() {
        val detection = DetectionHistoryMapper.toDomain(dto())

        assertEquals(UUID.fromString("22222222-2222-2222-2222-222222222222"), detection.id)
        assertEquals(123L, detection.sequenceNumber)
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), detection.deviceId)
        assertEquals(-8.5, detection.peakDbfs, 0.0)
        assertEquals(45.8, detection.latitude, 0.0)
        assertEquals(16.0, detection.longitude, 0.0)
        assertEquals(8.0, detection.gpsAccuracy, 0.0)
        assertEquals(Instant.parse("2026-08-03T10:00:00Z"), detection.receivedAtUtc)
        assertEquals(Instant.parse("2026-08-03T09:59:59Z"), detection.peakTimeClient)
        assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), detection.hotspotId)
    }

    @Test
    fun `a null peakTimeClient and null hotspotId map to null`() {
        val detection = DetectionHistoryMapper.toDomain(dto(peakTimeClient = null, hotspotId = null))

        assertNull(detection.peakTimeClient)
        assertNull(detection.hotspotId)
    }

    @Test
    fun `a malformed id throws instead of being silently accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionHistoryMapper.toDomain(dto(id = "not-a-uuid"))
        }
    }

    @Test
    fun `a malformed deviceId throws instead of being silently accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionHistoryMapper.toDomain(dto(deviceId = "not-a-uuid"))
        }
    }

    @Test
    fun `a malformed hotspotId throws instead of being silently accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            DetectionHistoryMapper.toDomain(dto(hotspotId = "not-a-uuid"))
        }
    }

    @Test
    fun `a malformed receivedAtUtc timestamp throws instead of being silently accepted`() {
        assertThrows(DateTimeParseException::class.java) {
            DetectionHistoryMapper.toDomain(dto(receivedAtUtc = "not-a-timestamp"))
        }
    }

    @Test
    fun `a malformed peakTimeClient timestamp throws instead of being silently accepted`() {
        assertThrows(DateTimeParseException::class.java) {
            DetectionHistoryMapper.toDomain(dto(peakTimeClient = "not-a-timestamp"))
        }
    }
}
