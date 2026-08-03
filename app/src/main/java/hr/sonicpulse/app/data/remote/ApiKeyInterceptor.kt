package hr.sonicpulse.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the shared API key to every backend request (§4 — accepted, documented limitation). */
class ApiKeyInterceptor(private val apiKey: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .addHeader(HEADER_NAME, apiKey)
            .build()
        return chain.proceed(request)
    }

    private companion object {
        const val HEADER_NAME = "X-Api-Key"
    }
}
