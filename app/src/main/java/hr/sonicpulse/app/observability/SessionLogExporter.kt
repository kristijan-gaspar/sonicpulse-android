package hr.sonicpulse.app.observability

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The only Storage-Access-Framework-aware piece of the session-log feature — deliberately
 * separate from [JsonDetectionSessionLogger] (which only ever produces a [String]) and from the
 * `ActivityResultContracts.CreateDocument` launcher itself (which must live in the Composable
 * that owns it). Takes an already-resolved [Uri] and already-serialized JSON; does not know
 * where either came from. */
object SessionLogExporter {

    /** Writes [json] to [uri]. Runs on [Dispatchers.IO] regardless of the caller's own context —
     * never touches the audio capture thread, and the caller (a user-triggered export action)
     * is never on it either. */
    suspend fun write(contentResolver: ContentResolver, uri: Uri, json: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = contentResolver.openOutputStream(uri)
                    ?: throw IOException("openOutputStream returned null for $uri")
                writeTo(stream, json)
            }
        }

    /** The actual byte-writing step, factored out of [write] so it's unit-testable against a
     * plain [OutputStream] — resolving a real [ContentResolver]/[Uri] pair requires an Android
     * runtime this project's unit tests don't have. Closes [stream] in every case. */
    internal fun writeTo(stream: OutputStream, json: String) {
        stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    }
}
