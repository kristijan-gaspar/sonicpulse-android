package hr.sonicpulse.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

class HotspotApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: HotspotApi

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
            .create(HotspotApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val fullItemJson = """
        {
          "id": "22222222-2222-2222-2222-222222222222",
          "latitude": 45.8,
          "longitude": 16.0,
          "radiusMeters": 120.5,
          "confidence": 84,
          "deviceCount": 3,
          "firstReceivedAtUtc": "2026-08-03T10:00:00Z",
          "lastReceivedAtUtc": "2026-08-03T10:00:12Z"
        }
    """.trimIndent()

    @Test
    fun `uses GET on the hotspots path`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        api.getHotspots(sinceHours = 24)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path?.startsWith("/api/hotspots") == true)
    }

    @Test
    fun `sinceHours 24 is sent exactly`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        api.getHotspots(sinceHours = 24)

        assertEquals("24", server.takeRequest().requestUrl?.queryParameter("sinceHours"))
    }

    @Test
    fun `sinceHours 72 is sent exactly`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        api.getHotspots(sinceHours = 72)

        assertEquals("72", server.takeRequest().requestUrl?.queryParameter("sinceHours"))
    }

    @Test
    fun `sinceHours 168 is sent exactly`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        api.getHotspots(sinceHours = 168)

        assertEquals("168", server.takeRequest().requestUrl?.queryParameter("sinceHours"))
    }

    @Test
    fun `the response is parsed as a bare array`() = runBlocking {
        server.enqueue(MockResponse().setBody("[$fullItemJson]"))

        val hotspots = api.getHotspots(sinceHours = 24)

        assertEquals(1, hotspots.size)
    }

    @Test
    fun `all fields deserialize`() = runBlocking {
        server.enqueue(MockResponse().setBody("[$fullItemJson]"))

        val hotspot = api.getHotspots(sinceHours = 24).single()

        assertEquals("22222222-2222-2222-2222-222222222222", hotspot.id)
        assertEquals(45.8, hotspot.latitude, 0.0)
        assertEquals(16.0, hotspot.longitude, 0.0)
        assertEquals(120.5, hotspot.radiusMeters, 0.0)
        assertEquals(84, hotspot.confidence)
        assertEquals(3, hotspot.deviceCount)
        assertEquals("2026-08-03T10:00:00Z", hotspot.firstReceivedAtUtc)
        assertEquals("2026-08-03T10:00:12Z", hotspot.lastReceivedAtUtc)
    }

    @Test
    fun `HTTP 401 propagates as a failure, never as a successful empty list`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val exception = assertThrows(HttpException::class.java) {
            runBlocking { api.getHotspots(sinceHours = 24) }
        }
        assertEquals(401, exception.code())
    }

    @Test
    fun `HTTP 500 propagates as a failure, never as a successful empty list`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val exception = assertThrows(HttpException::class.java) {
            runBlocking { api.getHotspots(sinceHours = 24) }
        }
        assertEquals(500, exception.code())
    }

    @Test
    fun `malformed JSON propagates as a failure, never as a successful empty list`() {
        server.enqueue(MockResponse().setBody("{ this is not a valid array"))

        assertThrows(Exception::class.java) {
            runBlocking { api.getHotspots(sinceHours = 24) }
        }
    }
}
