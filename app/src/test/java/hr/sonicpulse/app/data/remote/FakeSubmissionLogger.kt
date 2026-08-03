package hr.sonicpulse.app.data.remote

class FakeSubmissionLogger : SubmissionLogger {
    val events = mutableListOf<String>()

    override fun networkError() {
        events += "networkError"
    }

    override fun localStorageError() {
        events += "localStorageError"
    }

    override fun badRequest() {
        events += "badRequest"
    }

    override fun unauthorized() {
        events += "unauthorized"
    }

    override fun rateLimited(retryAfterSeconds: Long?) {
        events += "rateLimited($retryAfterSeconds)"
    }

    override fun clientError(httpCode: Int) {
        events += "clientError($httpCode)"
    }

    override fun serverError(httpCode: Int) {
        events += "serverError($httpCode)"
    }

    override fun unexpectedHttpStatus(httpCode: Int) {
        events += "unexpectedHttpStatus($httpCode)"
    }
}
