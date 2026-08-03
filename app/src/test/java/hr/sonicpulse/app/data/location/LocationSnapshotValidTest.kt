package hr.sonicpulse.app.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationSnapshotValidTest {

    @Test
    fun `rejects latitude above 90`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 90.1, longitude = 0.0, accuracyMeters = 1.0f)
        }
    }

    @Test
    fun `rejects latitude below -90`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = -90.1, longitude = 0.0, accuracyMeters = 1.0f)
        }
    }

    @Test
    fun `rejects longitude above 180`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 0.0, longitude = 180.1, accuracyMeters = 1.0f)
        }
    }

    @Test
    fun `rejects longitude below -180`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 0.0, longitude = -180.1, accuracyMeters = 1.0f)
        }
    }

    @Test
    fun `rejects a non-finite latitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = Double.NaN, longitude = 0.0, accuracyMeters = 1.0f)
        }
    }

    @Test
    fun `rejects a non-finite longitude`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 0.0, longitude = Double.POSITIVE_INFINITY, accuracyMeters = 1.0f)
        }
    }

    @Test
    fun `rejects zero accuracy`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 0.0, longitude = 0.0, accuracyMeters = 0.0f)
        }
    }

    @Test
    fun `rejects negative accuracy`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 0.0, longitude = 0.0, accuracyMeters = -1.0f)
        }
    }

    @Test
    fun `rejects a non-finite accuracy`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot.Valid(latitude = 0.0, longitude = 0.0, accuracyMeters = Float.NaN)
        }
    }

    @Test
    fun `accepts boundary latitude, longitude and a small strictly-positive accuracy`() {
        val snapshot = LocationSnapshot.Valid(latitude = 90.0, longitude = -180.0, accuracyMeters = 0.1f)

        assertEquals(90.0, snapshot.latitude, 0.0)
        assertEquals(-180.0, snapshot.longitude, 0.0)
        assertEquals(0.1f, snapshot.accuracyMeters, 0.0f)
    }
}
