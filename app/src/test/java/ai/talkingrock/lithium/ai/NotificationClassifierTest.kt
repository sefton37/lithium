package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.NotificationRecord
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [NotificationClassifier] heuristic tier against synthetic notification profiles.
 *
 * These tests run without a device (Robolectric not needed — classifier is pure logic).
 * The ONNX and llama.cpp tiers are stubbed out; only the heuristic path is exercised.
 */
class NotificationClassifierTest {

    private lateinit var classifier: NotificationClassifier

    @Before
    fun setUp() {
        SyntheticNotifications.resetIds()
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

    // ── Ongoing / Background ─────────────────────────────────────────────────

    @Test
    fun `ongoing notifications classify as BACKGROUND`() = runTest {
        val record = notification("com.spotify.music", "Now Playing", "Song name", isOngoing = true)
        val result = classifier.classify(record)
        assertEquals("background", result.label)
        assertTrue(result.confidence >= 0.90f)
    }

    @Test
    fun `known background package classifies as BACKGROUND even if not ongoing`() = runTest {
        val record = notification("com.spotify.music", "Spotify", "Your playlist was updated", isOngoing = false)
        val result = classifier.classify(record)
        assertEquals("background", result.label)
    }

    @Test
    fun `navigation ongoing classifies as BACKGROUND`() = runTest {
        val record = notification("com.google.android.apps.maps", "Navigate", "Turn left in 200m", isOngoing = true)
        val result = classifier.classify(record)
        assertEquals("background", result.label)
    }

    // ── Contact / Personal ───────────────────────────────────────────────────

    @Test
    fun `notification from contact classifies as PERSONAL`() = runTest {
        val record = notification("com.whatsapp", "Mom", "Hey sweetie", isFromContact = true)
        val result = classifier.classify(record)
        assertEquals("personal", result.label)
        assertTrue(result.confidence >= 0.90f)
    }

    @Test
    fun `messaging app without contact still classifies as PERSONAL`() = runTest {
        val record = notification("com.whatsapp", "Unknown Number", "Hello?")
        val result = classifier.classify(record)
        assertEquals("personal", result.label)
    }

    // ── System ───────────────────────────────────────────────────────────────

    @Test
    fun `system UI notification classifies as SYSTEM`() = runTest {
        val record = notification("com.android.systemui", "System", "Battery at 15%")
        val result = classifier.classify(record)
        assertEquals("system", result.label)
    }

    @Test
    fun `Google Play Services classifies as SYSTEM`() = runTest {
        val record = notification("com.google.android.gms", "Google", "Checking for updates")
        val result = classifier.classify(record)
        assertEquals("system", result.label)
    }

    // ── Transactional ────────────────────────────────────────────────────────

    @Test
    fun `OTP code classifies as TRANSACTIONAL`() = runTest {
        val record = notification("com.azure.authenticator", "Verification", "Your code is 482910")
        val result = classifier.classify(record)
        assertEquals("transactional", result.label)
    }

    @Test
    fun `delivery notification classifies as TRANSACTIONAL`() = runTest {
        val record = notification("com.amazon.mShop.android.shopping", "Amazon", "Your order has been delivered")
        val result = classifier.classify(record)
        assertEquals("transactional", result.label)
    }

    @Test
    fun `payment notification classifies as TRANSACTIONAL`() = runTest {
        val record = notification("com.venmo", "Venmo", "Payment received from Alex Smith")
        val result = classifier.classify(record)
        assertEquals("transactional", result.label)
    }

    // ── Social Signal ────────────────────────────────────────────────────────

    @Test
    fun `instagram like classifies as SOCIAL_SIGNAL`() = runTest {
        val record = notification("com.instagram.android", "Instagram", "alex_smith liked your photo")
        val result = classifier.classify(record)
        assertEquals("social_signal", result.label)
    }

    @Test
    fun `reddit reply classifies as SOCIAL_SIGNAL`() = runTest {
        val record = notification("com.reddit.frontpage", "Reddit", "user123 replied to your comment")
        val result = classifier.classify(record)
        assertEquals("social_signal", result.label)
    }

    // ── Engagement Bait ──────────────────────────────────────────────────────

    @Test
    fun `algorithmic recommendation classifies as ENGAGEMENT_BAIT`() = runTest {
        val record = notification("com.instagram.android", "suggested_reels", "Suggested for you", channelId = "suggested_reels")
        val result = classifier.classify(record)
        assertEquals("engagement_bait", result.label)
    }

    @Test
    fun `trending content from social package classifies as ENGAGEMENT_BAIT`() = runTest {
        // TikTok's real package is com.zhiliaoapp.musically which doesn't contain "tiktok"
        // but the text contains algorithmic keywords ("trending near you")
        val record = notification("com.instagram.android", "Instagram", "See what's trending near you")
        val result = classifier.classify(record)
        assertEquals("engagement_bait", result.label)
    }

    // ── Promotional ──────────────────────────────────────────────────────────

    @Test
    fun `sale notification classifies as PROMOTIONAL`() = runTest {
        val record = notification("com.amazon.mShop.android.shopping", "Amazon", "Lightning Deal: 70% off today only")
        val result = classifier.classify(record)
        assertEquals("promotional", result.label)
    }

    @Test
    fun `ecommerce package classifies as PROMOTIONAL`() = runTest {
        val record = notification("com.temu", "Temu", "New items just for you")
        val result = classifier.classify(record)
        assertEquals("promotional", result.label)
    }

    // ── Profile-Level Aggregate Tests ────────────────────────────────────────

    @Test
    fun `heavy social media profile - majority classified correctly`() = runTest {
        val notifications = SyntheticNotifications.heavySocialMedia()
        val results = notifications.map { classifier.classify(it) }
        val counts = results.groupBy { it.label }.mapValues { it.value.size }

        // Spotify ongoing → background
        assertTrue("Expected background >= 90, got ${counts["background"] ?: 0}", (counts["background"] ?: 0) >= 90)
        // Instagram/TikTok/Reddit algorithmic → engagement_bait or social_signal
        val socialBait = (counts["engagement_bait"] ?: 0) + (counts["social_signal"] ?: 0)
        assertTrue("Expected social+bait >= 100, got $socialBait", socialBait >= 100)
        // WhatsApp/Signal from contacts → personal
        assertTrue("Expected personal >= 20, got ${counts["personal"] ?: 0}", (counts["personal"] ?: 0) >= 20)
        // Unknown should be minority
        val unknownPct = (counts["unknown"] ?: 0).toFloat() / results.size
        assertTrue("Expected unknown < 30%, got ${(unknownPct * 100).toInt()}%", unknownPct < 0.30f)
    }

    @Test
    fun `business user profile - messaging dominates`() = runTest {
        val notifications = SyntheticNotifications.businessUser()
        val results = notifications.map { classifier.classify(it) }
        val counts = results.groupBy { it.label }.mapValues { it.value.size }

        // Outlook/Slack/Teams → personal (messaging packages)
        assertTrue("Expected personal >= 200, got ${counts["personal"] ?: 0}", (counts["personal"] ?: 0) >= 200)
        // OTP → transactional
        assertTrue("Expected transactional >= 3, got ${counts["transactional"] ?: 0}", (counts["transactional"] ?: 0) >= 3)
        // Calendar → system
        assertTrue("Expected system >= 10, got ${counts["system"] ?: 0}", (counts["system"] ?: 0) >= 10)
    }

    @Test
    fun `media heavy profile - background dominates`() = runTest {
        val notifications = SyntheticNotifications.mediaHeavy()
        val results = notifications.map { classifier.classify(it) }
        val counts = results.groupBy { it.label }.mapValues { it.value.size }
        val total = results.size

        // 2050 ongoing → background should be overwhelming majority
        val backgroundPct = (counts["background"] ?: 0).toFloat() / total
        assertTrue("Expected background > 90%, got ${(backgroundPct * 100).toInt()}%", backgroundPct > 0.90f)
        // Real alerts underneath should still be classified
        val alertCount = total - (counts["background"] ?: 0)
        assertTrue("Expected 30+ real alerts, got $alertCount", alertCount >= 30)
    }

    @Test
    fun `spam victim profile - promotional dominates non-background`() = runTest {
        val notifications = SyntheticNotifications.spamVictim()
        val results = notifications.map { classifier.classify(it) }
        val counts = results.groupBy { it.label }.mapValues { it.value.size }

        val promotional = (counts["promotional"] ?: 0)
        val transactional = (counts["transactional"] ?: 0)
        assertTrue("Expected promotional >= 50, got $promotional", promotional >= 50)
        assertTrue("Expected transactional >= 5 (deliveries), got $transactional", transactional >= 5)
    }

    @Test
    fun `contact heavy profile - high contact ratio`() = runTest {
        val notifications = SyntheticNotifications.contactHeavy()
        val results = notifications.map { classifier.classify(it) }

        // Check isFromContact ratio on non-ongoing notifications
        val alerts = notifications.filter { !it.isOngoing }
        val contactAlerts = alerts.count { it.isFromContact }
        val contactPct = contactAlerts.toFloat() / alerts.size
        assertTrue("Expected contact ratio > 40%, got ${(contactPct * 100).toInt()}%", contactPct > 0.40f)

        // Personal classification should be high
        val counts = results.groupBy { it.label }.mapValues { it.value.size }
        assertTrue("Expected personal >= 100, got ${counts["personal"] ?: 0}", (counts["personal"] ?: 0) >= 100)
    }

    @Test
    fun `minimal user profile - small total, all classified`() = runTest {
        val notifications = SyntheticNotifications.minimalUser()
        val results = notifications.map { classifier.classify(it) }
        val counts = results.groupBy { it.label }.mapValues { it.value.size }
        val total = results.size

        assertTrue("Expected total ~30, got $total", total in 25..40)
        // Should have very few unknowns
        val unknownPct = (counts["unknown"] ?: 0).toFloat() / total
        assertTrue("Expected unknown < 20%, got ${(unknownPct * 100).toInt()}%", unknownPct < 0.20f)
    }

    @Test
    fun `gamer profile - game notifications not misclassified as personal`() = runTest {
        val notifications = SyntheticNotifications.gamer()
        val results = notifications.map { classifier.classify(it) }
        val counts = results.groupBy { it.label }.mapValues { it.value.size }

        // Discord → personal (messaging package)
        assertTrue("Expected personal >= 100 (Discord), got ${counts["personal"] ?: 0}", (counts["personal"] ?: 0) >= 100)
        // Spotify → background
        assertTrue("Expected background >= 70, got ${counts["background"] ?: 0}", (counts["background"] ?: 0) >= 70)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun notification(
        pkg: String,
        title: String,
        text: String,
        channelId: String = "default",
        isOngoing: Boolean = false,
        isFromContact: Boolean = false
    ) = NotificationRecord(
        id = 0,
        packageName = pkg,
        postedAtMs = System.currentTimeMillis(),
        title = title,
        text = text,
        channelId = channelId,
        isOngoing = isOngoing,
        isFromContact = isFromContact
    )
}
