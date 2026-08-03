package hr.sonicpulse.app.domain.model

import hr.sonicpulse.app.data.location.LocationSnapshot
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A normal unit test cannot prove that invalid Kotlin code fails to compile — this file's proof
 * is that it compiles at all: [SessionDetection.location] is declared as [LocationSnapshot.Valid],
 * so assigning it to a `LocationSnapshot.Valid`-typed local (no cast) only type-checks if the
 * property really is statically that type, not the broader [LocationSnapshot] sealed interface.
 */
class SessionDetectionTest {

    @Test
    fun `SessionDetection location is statically typed as LocationSnapshot Valid`() {
        val valid = LocationSnapshot.Valid(45.0, 15.0, 10.0f)
        val detection = SessionDetection(
            localEventId = UUID.randomUUID(),
            peakDbfs = -10.0,
            peakTimeClient = Instant.EPOCH,
            location = valid
        )

        val location: LocationSnapshot.Valid = detection.location

        assertEquals(valid, location)
    }
}
