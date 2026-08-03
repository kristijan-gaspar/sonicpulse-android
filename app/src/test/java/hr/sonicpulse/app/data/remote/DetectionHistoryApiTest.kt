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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

private const val DEVICE_ID = "11111111-1111-1111-1111-111111111111"

class DetectionHistoryApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DetectionApi

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

    private val fullItemJson = """
        {
          "id": "22222222-2222-2222-2222-222222222222",
          "sequenceNumber": 123,
          "deviceId": "$DEVICE_ID",
          "peakDbfs": -8.5,
          "latitude": 45.8,
          "longitude": 16.0,
          "gpsAccuracy": 8.0,
          "receivedAtUtc": "2026-08-03T10:00:00Z",
          "peakTimeClient": "2026-08-03T09:59:59Z",
          "hotspotId": "33333333-3333-3333-3333-333333333333"
        }
    """.trimIndent()

    @Test
    fun `uses GET on the device-history path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))

        api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path?.startsWith("/api/devices/$DEVICE_ID/detections") == true)
    }

    @Test
    fun `places the device id in the URL path`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))

        api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        val recorded = server.takeRequest()
        assertTrue(recorded.requestUrl?.encodedPath?.contains("/devices/$DEVICE_ID/detections") == true)
    }

    @Test
    fun `the initial request omits the cursor query parameter`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))

        api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        val recorded = server.takeRequest()
        assertNull(recorded.requestUrl?.queryParameter("cursor"))
    }

    @Test
    fun `a subsequent request sends the exact cursor value`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))

        api.getDetectionHistory(deviceId = DEVICE_ID, cursor = 123L, limit = 50)

        val recorded = server.takeRequest()
        assertEquals("123", recorded.requestUrl?.queryParameter("cursor"))
    }

    @Test
    fun `the limit query parameter is sent correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))

        api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 42)

        val recorded = server.takeRequest()
        assertEquals("42", recorded.requestUrl?.queryParameter("limit"))
    }

    @Test
    fun `deserializes a complete JSON response`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[$fullItemJson],"nextCursor":123}"""))

        val page = api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        val item = page.items.single()
        assertEquals("22222222-2222-2222-2222-222222222222", item.id)
        assertEquals(123L, item.sequenceNumber)
        assertEquals(DEVICE_ID, item.deviceId)
        assertEquals(-8.5, item.peakDbfs, 0.0)
        assertEquals(45.8, item.latitude, 0.0)
        assertEquals(16.0, item.longitude, 0.0)
        assertEquals(8.0, item.gpsAccuracy, 0.0)
        assertEquals("2026-08-03T10:00:00Z", item.receivedAtUtc)
        assertEquals("2026-08-03T09:59:59Z", item.peakTimeClient)
        assertEquals("33333333-3333-3333-3333-333333333333", item.hotspotId)
    }

    @Test
    fun `nextCursor deserializes as a bare number`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":123}"""))

        val page = api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        assertEquals(123L, page.nextCursor)
    }

    @Test
    fun `nextCursor null deserializes correctly`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"nextCursor":null}"""))

        val page = api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        assertNull(page.nextCursor)
    }

    @Test
    fun `peakTimeClient null and hotspotId null deserialize correctly`() = runBlocking {
        val itemJson = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "sequenceNumber": 1,
              "deviceId": "$DEVICE_ID",
              "peakDbfs": -8.5,
              "latitude": 45.8,
              "longitude": 16.0,
              "gpsAccuracy": 8.0,
              "receivedAtUtc": "2026-08-03T10:00:00Z",
              "peakTimeClient": null,
              "hotspotId": null
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("""{"items":[$itemJson],"nextCursor":null}"""))

        val page = api.getDetectionHistory(deviceId = DEVICE_ID, cursor = null, limit = 50)

        val item = page.items.single()
        assertNull(item.peakTimeClient)
        assertNull(item.hotspotId)
    }
}
