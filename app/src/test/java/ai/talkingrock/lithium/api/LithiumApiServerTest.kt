package ai.talkingrock.lithium.api

import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.db.TierCount
import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LithiumApiServer] routes.
 *
 * Uses Ktor's [testApplication] to run routes in-process without binding a real port.
 * [NotificationDao] and [AppBehaviorProfileDao] are mocked with MockK.
 * Pure-JVM — no Android context required.
 */
class LithiumApiServerTest {

    private lateinit var notificationDao: NotificationDao
    private lateinit var behaviorProfileDao: AppBehaviorProfileDao

    @Before
    fun setUp() {
        notificationDao = mockk()
        behaviorProfileDao = mockk()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun record(
        id: Long,
        pkg: String = "com.test.app",
        postedAtMs: Long = 1_000L,
        tier: Int = 2,
        classification: String? = null
    ) = NotificationRecord(
        id = id,
        packageName = pkg,
        postedAtMs = postedAtMs,
        tier = tier,
        aiClassification = classification
    )

    private fun ApplicationTestBuilder.setupApp() {
        application {
            install(ServerContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
            }
            routing {
                val server = LithiumApiServer(notificationDao, behaviorProfileDao)
                server.configureRoutesForTest(this)
            }
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @Test
    fun `GET api health returns 200 with status ok and uptimeMs non-negative`() = testApplication {
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HealthResponse>()
        assertEquals("ok", body.status)
        assertTrue("uptimeMs should be >= 0", body.uptimeMs >= 0L)
    }

    // ── GET /api/notifications ────────────────────────────────────────────────

    @Test
    fun `GET api notifications returns list of NotificationDto`() = testApplication {
        coEvery { notificationDao.getAllSince(any()) } returns listOf(record(1L), record(2L))
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/notifications")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<NotificationDto>>()
        assertEquals(2, body.size)
    }

    @Test
    fun `GET api notifications with since param passes correct sinceMs to DAO`() = testApplication {
        coEvery { notificationDao.getAllSince(5000L) } returns listOf(record(2L, postedAtMs = 6000L))
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/notifications?since=5000")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<NotificationDto>>()
        assertEquals(1, body.size)
        assertEquals(6000L, body[0].postedAtMs)
    }

    @Test
    fun `GET api notifications with tier param returns only matching tier rows`() = testApplication {
        coEvery { notificationDao.getAllSinceWithTiers(any(), listOf(1)) } returns listOf(
            record(1L, tier = 1)
        )
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/notifications?tier=1")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<NotificationDto>>()
        assertEquals(1, body.size)
        assertEquals(1, body[0].tier)
    }

    @Test
    fun `GET api notifications with multiple tier params passes both tiers to DAO`() = testApplication {
        coEvery { notificationDao.getAllSinceWithTiers(any(), listOf(1, 3)) } returns listOf(
            record(1L, tier = 1),
            record(2L, tier = 3)
        )
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/notifications?tier=1&tier=3")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<NotificationDto>>()
        assertEquals(2, body.size)
    }

    @Test
    fun `GET api notifications with limit param returns at most limit rows`() = testApplication {
        coEvery { notificationDao.getAllSince(any()) } returns (1L..20L).map { record(it) }
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/notifications?limit=5")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<NotificationDto>>()
        assertEquals(5, body.size)
    }

    @Test
    fun `GET api notifications unresolved returns unclassified rows`() = testApplication {
        coEvery { notificationDao.getUnclassified(any()) } returns listOf(
            record(1L, classification = null),
            record(2L, classification = null)
        )
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/notifications/unresolved")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<NotificationDto>>()
        assertEquals(2, body.size)
        assertTrue(body.all { it.aiClassification == null })
    }

    // ── GET /api/stats ────────────────────────────────────────────────────────

    @Test
    fun `GET api stats totalNotifications equals row count`() = testApplication {
        coEvery { notificationDao.count() } returns 42
        coEvery { notificationDao.countClassified() } returns 30
        coEvery { notificationDao.countDistinctClassifiedApps() } returns 5
        coEvery { notificationDao.getTierBreakdown() } returns listOf(
            TierCount(0, 10), TierCount(1, 15), TierCount(2, 12), TierCount(3, 5)
        )
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/stats")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<StatsResponse>()
        assertEquals(42, body.totalNotifications)
    }

    @Test
    fun `GET api stats tierBreakdown sums to totalNotifications`() = testApplication {
        val total = 40
        coEvery { notificationDao.count() } returns total
        coEvery { notificationDao.countClassified() } returns 30
        coEvery { notificationDao.countDistinctClassifiedApps() } returns 5
        coEvery { notificationDao.getTierBreakdown() } returns listOf(
            TierCount(0, 10), TierCount(1, 15), TierCount(2, 10), TierCount(3, 5)
        )
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/stats")
        val body = response.body<StatsResponse>()

        val tierSum = body.tierBreakdown.values.sum()
        assertEquals("tier breakdown must sum to total", total, tierSum)
    }

    @Test
    fun `GET api stats classifiedNotifications plus unclassified equals total`() = testApplication {
        coEvery { notificationDao.count() } returns 50
        coEvery { notificationDao.countClassified() } returns 35
        coEvery { notificationDao.countDistinctClassifiedApps() } returns 8
        coEvery { notificationDao.getTierBreakdown() } returns emptyList()
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/stats")
        val body = response.body<StatsResponse>()

        assertEquals(50, body.classifiedNotifications + body.unclassifiedNotifications)
    }

    @Test
    fun `GET api stats with no rows tierBreakdown is empty`() = testApplication {
        coEvery { notificationDao.count() } returns 0
        coEvery { notificationDao.countClassified() } returns 0
        coEvery { notificationDao.countDistinctClassifiedApps() } returns 0
        coEvery { notificationDao.getTierBreakdown() } returns emptyList()
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/stats")
        val body = response.body<StatsResponse>()

        assertTrue("tier breakdown is empty when no rows", body.tierBreakdown.isEmpty())
    }

    @Test
    fun `GET api stats noiseRatio is classified divided by total when total positive`() = testApplication {
        coEvery { notificationDao.count() } returns 100
        coEvery { notificationDao.countClassified() } returns 60
        coEvery { notificationDao.countDistinctClassifiedApps() } returns 5
        coEvery { notificationDao.getTierBreakdown() } returns emptyList()
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/stats")
        val body = response.body<StatsResponse>()

        assertEquals(0.6f, body.noiseRatio, 0.001f)
    }

    @Test
    fun `GET api stats noiseRatio is 0 when total is 0`() = testApplication {
        coEvery { notificationDao.count() } returns 0
        coEvery { notificationDao.countClassified() } returns 0
        coEvery { notificationDao.countDistinctClassifiedApps() } returns 0
        coEvery { notificationDao.getTierBreakdown() } returns emptyList()
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/stats")
        val body = response.body<StatsResponse>()

        assertEquals(0f, body.noiseRatio, 0.001f)
    }

    // ── GET /api/contacts ────────────────────────────────────────────────────

    @Test
    fun `GET api contacts returns per-app engagement sorted by totalNotifications desc`() = testApplication {
        coEvery { behaviorProfileDao.getAllProfiles() } returns listOf(
            AppBehaviorProfile(packageName = "com.a", channelId = "", totalReceived = 100, totalTapped = 10),
            AppBehaviorProfile(packageName = "com.b", channelId = "", totalReceived = 50, totalTapped = 5)
        )
        setupApp()
        val client = jsonClient()

        val response = client.get("/api/contacts")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<List<ContactEntry>>()
        assertEquals(2, body.size)
        assertEquals("com.a", body[0].packageName)  // higher totalNotifications first
        assertEquals("com.b", body[1].packageName)
    }

    // ── POST /api/classifications ─────────────────────────────────────────────

    @Test
    fun `POST api classifications calls updateClassification for each entry`() = testApplication {
        coEvery { notificationDao.updateClassification(any(), any(), any()) } returns Unit
        setupApp()
        val client = jsonClient()

        val updates = listOf(
            ClassificationUpdate(1L, "personal", 0.9f),
            ClassificationUpdate(2L, "promotional", 0.8f)
        )
        val response = client.post("/api/classifications") {
            contentType(ContentType.Application.Json)
            setBody(updates)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify(exactly = 1) { notificationDao.updateClassification(1L, "personal", 0.9f) }
        coVerify(exactly = 1) { notificationDao.updateClassification(2L, "promotional", 0.8f) }
    }

    @Test
    fun `POST api classifications returns AckResponse with correct updated count`() = testApplication {
        coEvery { notificationDao.updateClassification(any(), any(), any()) } returns Unit
        setupApp()
        val client = jsonClient()

        val updates = listOf(
            ClassificationUpdate(1L, "personal", 0.9f),
            ClassificationUpdate(2L, "promotional", 0.8f)
        )
        val response = client.post("/api/classifications") {
            contentType(ContentType.Application.Json)
            setBody(updates)
        }

        val body = response.body<AckResponse>()
        assertEquals(2, body.updated)
    }

    // ── POST /api/dismiss ────────────────────────────────────────────────────

    @Test
    fun `POST api dismiss calls updateRemoval with api_dismiss reason`() = testApplication {
        coEvery { notificationDao.updateRemoval(any(), any(), any()) } returns Unit
        setupApp()
        val client = jsonClient()

        val request = DismissRequest(ids = listOf(10L, 20L))
        val response = client.post("/api/dismiss") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { notificationDao.updateRemoval(10L, any(), "api_dismiss") }
        coVerify { notificationDao.updateRemoval(20L, any(), "api_dismiss") }
    }

    @Test
    fun `POST api dismiss returns AckResponse with correct updated count`() = testApplication {
        coEvery { notificationDao.updateRemoval(any(), any(), any()) } returns Unit
        setupApp()
        val client = jsonClient()

        val request = DismissRequest(ids = listOf(1L, 2L, 3L))
        val response = client.post("/api/dismiss") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        val body = response.body<AckResponse>()
        assertEquals(3, body.updated)
    }
}
