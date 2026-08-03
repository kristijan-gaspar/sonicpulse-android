package hr.sonicpulse.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit

class DetectionApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DetectionApi

    private val requestDto = DetectionRequestDto(
        deviceId = "11111111-1111-1111-1111-111111111111",
        peakDbfs = -12.5,
        latitude = 45.8,
        longitude = 16.0,
        gpsAccuracy = 8.0,
        peakTimeClient = "2026-08-03T10:00:00Z"
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val client = OkHttpClient.Builder()
            .addInterceptor(ApiKeyInterceptor(apiKey = "test-api-key"))
            .build()

        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DetectionApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds X-Api-Key header to every request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))

        api.submitDetection(requestDto)

        val recorded = server.takeRequest()
        assertEquals("test-api-key", recorded.getHeader("X-Api-Key"))
    }

    @Test
    fun `sends detection fields as JSON body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))

        api.submitDetection(requestDto)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"deviceId\":\"11111111-1111-1111-1111-111111111111\""))
        assertTrue(body.contains("\"peakDbfs\":-12.5"))
        assertTrue(body.contains("\"peakTimeClient\":\"2026-08-03T10:00:00Z\""))
    }

    @Test
    fun `201 response is successful with code 201`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))

        val response = api.submitDetection(requestDto)

        assertTrue(response.isSuccessful)
        assertEquals(201, response.code())
    }

    @Test
    fun `429 response exposes Retry-After header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "30"))

        val response = api.submitDetection(requestDto)

        assertEquals(429, response.code())
        assertEquals("30", response.headers()["Retry-After"])
    }

    @Test
    fun `401 response has no Retry-After header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val response = api.submitDetection(requestDto)

        assertEquals(401, response.code())
        assertNull(response.headers()["Retry-After"])
    }
}
