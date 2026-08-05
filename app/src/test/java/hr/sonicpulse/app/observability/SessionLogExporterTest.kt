package hr.sonicpulse.app.observability

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers [SessionLogExporter.writeTo] — the actual byte-writing step — against a plain
 * [OutputStream]. The full [SessionLogExporter.write] (which resolves a real
 * `ContentResolver`/`Uri` pair via Android's Storage Access Framework) is not covered here: doing
 * so needs an Android runtime (e.g. Robolectric), which this project's unit tests don't have —
 * see PROGRESS.md/the session-logging report for this as a known limitation.
 */
class SessionLogExporterTest {

    @Test
    fun `writeTo writes the exact UTF-8 bytes of the given JSON`() {
        val stream = ByteArrayOutputStream()

        SessionLogExporter.writeTo(stream, """{"a":1}""")

        assertEquals("""{"a":1}""", stream.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun `writeTo closes the stream even on success`() {
        var closed = false
        val stream = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun close() {
                closed = true
            }
        }

        SessionLogExporter.writeTo(stream, "{}")

        assertEquals(true, closed)
    }

    @Test
    fun `a write failure propagates rather than being swallowed`() {
        val failingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk full")
            }
        }

        assertThrows(IOException::class.java) {
            SessionLogExporter.writeTo(failingStream, "{}")
        }
    }
}
