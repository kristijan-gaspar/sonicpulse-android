package hr.sonicpulse.app.data.remote

import android.util.Log
import javax.inject.Inject

/** Same redaction rules in release and debug builds — only status codes and parsed delays are logged. */
class AndroidSubmissionLogger @Inject constructor() : SubmissionLogger {

    override fun networkError() {
        Log.w(TAG, "Detection submission failed due to a network error")
    }

    override fun localStorageError() {
        Log.w(TAG, "Detection submission failed: could not read the installation id")
    }

    override fun badRequest() {
        Log.e(TAG, "Detection submission rejected as malformed (400)")
    }

    override fun unauthorized() {
        Log.w(TAG, "Detection submission rejected: server reports an authentication/configuration error")
    }

    override fun rateLimited(retryAfterSeconds: Long?) {
        Log.i(TAG, "Detection submission rate-limited (retryAfterSeconds=$retryAfterSeconds)")
    }

    override fun clientError(httpCode: Int) {
        Log.w(TAG, "Detection submission failed with a client error ($httpCode)")
    }

    override fun serverError(httpCode: Int) {
        Log.w(TAG, "Detection submission failed with a server error ($httpCode)")
    }

    override fun unexpectedHttpStatus(httpCode: Int) {
        Log.w(TAG, "Detection submission received an unexpected HTTP status ($httpCode)")
    }

    private companion object {
        const val TAG = "DetectionSubmitter"
    }
}
