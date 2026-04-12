package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.NotificationRecord
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ReportGenerator] using synthetic notification profiles.
 *
 * Verifies that the report text and JSON accurately represent the underlying data,
 * especially the separation of alerts from background and the contact/algorithmic ratio.
 */
class ReportGeneratorTest {

    private lateinit var generator: ReportGenerator
    private lateinit var classifier: NotificationClassifier

    @Before
    fun setUp() {
        SyntheticNotifications.resetIds()
        // AppLabelResolver requires a Context, so mock it for JVM tests.
        // Return the package name itself as the label — sufficient for report text assertions.
        val appLabelResolver = mockk<AppLabelResolver> {
            every { label(any()) } answers { firstArg<String>() }
        }
        generator = ReportGenerator(appLabelResolver)
        val aiEngine = mockk<AiEngine> {
            every { isModelLoaded() } returns false
            every { classify(any()) } returns null
        }
        val llamaEngine = mockk<LlamaEngine> {
            every { isModelLoaded() } returns false
            coEvery { classify(any(), any(), any(), any()) } returns null
        }
        classifier = NotificationClassifier(aiEngine, llamaEngine)
    }

    @Test
    fun `media heavy report filters background from alert count`() = runTest {
        val notifications = SyntheticNotifications.mediaHeavy()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject
        val text = json["text"]!!.jsonPrimitive.content

        val alertCount = json["alert_count"]!!.jsonPrimitive.int
        val bgCount = json["background_count"]!!.jsonPrimitive.int

        // 2050 ongoing should be background, ~50 real alerts
        assertTrue("Expected background > 2000, got $bgCount", bgCount > 2000)
        assertTrue("Expected alerts < 100, got $alertCount", alertCount < 100)
        assertTrue("Report should mention 'background updates were filtered'", text.contains("background updates were filtered"))
        assertFalse("Report should NOT lead with 2000+ count", text.startsWith("You received 2"))
    }

    @Test
    fun `heavy social media report surfaces engagement bait`() = runTest {
        val notifications = SyntheticNotifications.heavySocialMedia()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject
        val text = json["text"]!!.jsonPrimitive.content

        // Should mention social signal or engagement bait categories
        val hasEngagement = text.contains("engagement bait") || text.contains("social signal")
        assertTrue("Report should mention social/engagement categories", hasEngagement)
    }

    @Test
    fun `contact heavy report reflects high contact ratio`() = runTest {
        val notifications = SyntheticNotifications.contactHeavy()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject
        val text = json["text"]!!.jsonPrimitive.content
        val contactCount = json["contact_vs_algorithmic"]!!.jsonObject["contact"]!!.jsonPrimitive.int

        assertTrue("Expected contact count > 50, got $contactCount", contactCount > 50)
        assertTrue("Report should mention contacts", text.contains("contacts") || text.contains("people"))
    }

    @Test
    fun `spam victim report shows promotional dominance`() = runTest {
        val notifications = SyntheticNotifications.spamVictim()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject
        val text = json["text"]!!.jsonPrimitive.content

        assertTrue("Report should mention promotional", text.contains("promotional"))
    }

    @Test
    fun `minimal user report has low total`() = runTest {
        val notifications = SyntheticNotifications.minimalUser()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject

        val alertCount = json["alert_count"]!!.jsonPrimitive.int
        assertTrue("Expected alerts < 30, got $alertCount", alertCount < 30)
    }

    @Test
    fun `empty notification list produces quiet day report`() {
        val report = generator.generate(
            byCategory = emptyMap(),
            appStats = emptyList(),
            contactVsAlgo = Pair(0, 0)
        )
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject
        val text = json["text"]!!.jsonPrimitive.content

        assertTrue("Should mention quiet day", text.contains("quiet day"))
    }

    @Test
    fun `background-only apps excluded from top apps sentence`() = runTest {
        val notifications = SyntheticNotifications.mediaHeavy()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject
        val text = json["text"]!!.jsonPrimitive.content

        // Spotify is 1500 notifications but all background — should NOT appear as "busiest"
        assertFalse("Spotify should not be in busiest sources", text.contains("Spotify"))
    }

    @Test
    fun `report JSON contains all expected fields`() = runTest {
        val notifications = SyntheticNotifications.businessUser()
        val classified = classifyAll(notifications)
        val report = generateReport(classified, notifications)
        val json = Json.parseToJsonElement(report.summaryJson).jsonObject

        assertNotNull("Missing 'text'", json["text"])
        assertNotNull("Missing 'total'", json["total"])
        assertNotNull("Missing 'alert_count'", json["alert_count"])
        assertNotNull("Missing 'background_count'", json["background_count"])
        assertNotNull("Missing 'by_category'", json["by_category"])
        assertNotNull("Missing 'contact_vs_algorithmic'", json["contact_vs_algorithmic"])
        assertNotNull("Missing 'top_apps'", json["top_apps"])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun classifyAll(notifications: List<NotificationRecord>): List<NotificationRecord> {
        return notifications.map { record ->
            val result = classifier.classify(record)
            record.copy(aiClassification = result.label, aiConfidence = result.confidence)
        }
    }

    private fun generateReport(
        classified: List<NotificationRecord>,
        original: List<NotificationRecord>
    ): ai.talkingrock.lithium.data.model.Report {
        val byCategory = classified.groupBy { record ->
            if (record.aiClassification != null) {
                NotificationCategory.fromLabel(record.aiClassification!!)
            } else {
                NotificationCategory.UNKNOWN
            }
        }

        val appStats = classified
            .groupBy { it.packageName }
            .map { (pkg, records) ->
                AppStats(
                    packageName = pkg,
                    totalCount = records.size,
                    tappedCount = 0,
                    ignoredCount = records.size,
                    avgSessionDurationMs = 0L
                )
            }
            .sortedByDescending { it.totalCount }

        val nonBackground = classified.filter {
            val cat = it.aiClassification?.let { l -> NotificationCategory.fromLabel(l) }
            cat != NotificationCategory.BACKGROUND
        }
        val contactCount = nonBackground.count { it.isFromContact }
        val algoCount = nonBackground.count { record ->
            val cat = record.aiClassification?.let { NotificationCategory.fromLabel(it) }
            cat == NotificationCategory.ENGAGEMENT_BAIT || cat == NotificationCategory.SOCIAL_SIGNAL
        }

        return generator.generate(byCategory, appStats, Pair(contactCount, algoCount))
    }
}
