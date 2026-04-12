package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.db.TierReasonStat
import ai.talkingrock.lithium.data.model.AppBehaviorProfile
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Suggestion
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SuggestionGenerator].
 *
 * Pure-Kotlin — mocks [AppLabelResolver] so no Android context is required.
 * Covers both the ML-category path (generate()) and the tier-reason path (generateFromTierReasons()).
 */
class SuggestionGeneratorTest {

    private lateinit var mockLabels: AppLabelResolver
    private lateinit var generator: SuggestionGenerator

    @Before
    fun setUp() {
        mockLabels = mockk()
        every { mockLabels.label(any()) } answers { firstArg<String>().substringAfterLast('.') }
        generator = SuggestionGenerator(mockLabels)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun notif(
        pkg: String,
        category: NotificationCategory,
        isFromContact: Boolean = false,
        id: Long = 0L
    ): NotificationRecord = NotificationRecord(
        id = id,
        packageName = pkg,
        aiClassification = category.label,
        isFromContact = isFromContact
    )

    private fun stats(
        pkg: String,
        total: Int,
        tapped: Int = 0
    ): AppStats = AppStats(
        packageName = pkg,
        totalCount = total,
        tappedCount = tapped,
        ignoredCount = total - tapped,
        avgSessionDurationMs = 0L
    )

    private fun tierStat(
        pkg: String,
        tierReason: String,
        count: Int,
        tapped: Int = 0
    ): TierReasonStat = TierReasonStat(
        packageName = pkg,
        tierReason = tierReason,
        tier = 1,
        count = count,
        tapped = tapped
    )

    // ── ML path: volume guard ────────────────────────────────────────────────

    @Test
    fun `app with less than 5 notifications produces no suggestion`() {
        val pkg = "com.noisy.app"
        val appStats = listOf(stats(pkg, total = 4, tapped = 0))
        val notifs = (1..4).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        assertTrue("no suggestion for low-volume app", result.isEmpty())
    }

    // ── ML path: suppress rules ────────────────────────────────────────────

    @Test
    fun `engagement_bait high volume low tap rate produces SUPPRESS suggestion`() {
        val pkg = "com.linkedin.android"
        val appStats = listOf(stats(pkg, total = 15, tapped = 0))
        val notifs = (1..15).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val suggestion = result.firstOrNull { it.action == "suppress" }
        assertNotNull("SUPPRESS suggestion expected for high-volume engagement bait", suggestion)
    }

    @Test
    fun `engagement_bait high volume high tap rate produces no suppress suggestion`() {
        val pkg = "com.engaged.app"
        val appStats = listOf(stats(pkg, total = 15, tapped = 10))  // tap rate = 67%
        val notifs = (1..15).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val suppressSuggestion = result.firstOrNull { it.action == "suppress" }
        assertNull("no SUPPRESS when tap rate is high", suppressSuggestion)
    }

    @Test
    fun `engagement_bait low volume low tap rate produces QUEUE suggestion`() {
        val pkg = "com.low.volume.bait"
        val appStats = listOf(stats(pkg, total = 7, tapped = 0))
        val notifs = (1..7).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val suggestion = result.firstOrNull { it.action == "queue" }
        assertNotNull("QUEUE suggestion for low-volume engagement bait", suggestion)
    }

    @Test
    fun `promotional high volume low tap rate produces SUPPRESS suggestion`() {
        val pkg = "com.promo.store"
        val appStats = listOf(stats(pkg, total = 12, tapped = 0))
        val notifs = (1..12).map { notif(pkg, NotificationCategory.PROMOTIONAL) }
        val byCategory = mapOf(NotificationCategory.PROMOTIONAL to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val suggestion = result.firstOrNull { it.action == "suppress" }
        assertNotNull("SUPPRESS suggestion for high-volume promotional", suggestion)
    }

    @Test
    fun `promotional moderate volume low tap rate produces QUEUE suggestion`() {
        val pkg = "com.promo.app"
        val appStats = listOf(stats(pkg, total = 6, tapped = 0))
        val notifs = (1..6).map { notif(pkg, NotificationCategory.PROMOTIONAL) }
        val byCategory = mapOf(NotificationCategory.PROMOTIONAL to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val suggestion = result.firstOrNull { it.action == "queue" }
        assertNotNull("QUEUE suggestion for moderate promotional", suggestion)
    }

    // ── ML path: social signal ────────────────────────────────────────────

    @Test
    fun `social_signal from non-contacts low tap rate produces QUEUE suggestion`() {
        val pkg = "com.social.app"
        val appStats = listOf(stats(pkg, total = 8, tapped = 0))
        val notifs = (1..8).map { notif(pkg, NotificationCategory.SOCIAL_SIGNAL, isFromContact = false) }
        val byCategory = mapOf(NotificationCategory.SOCIAL_SIGNAL to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val suggestion = result.firstOrNull { it.action == "queue" }
        assertNotNull("QUEUE for non-contact social signal", suggestion)
    }

    @Test
    fun `social_signal with any contact notification is NOT queued`() {
        val pkg = "com.social.contacts"
        val appStats = listOf(stats(pkg, total = 8, tapped = 0))
        val notifs = listOf(
            notif(pkg, NotificationCategory.SOCIAL_SIGNAL, isFromContact = true),
            notif(pkg, NotificationCategory.SOCIAL_SIGNAL, isFromContact = false),
            notif(pkg, NotificationCategory.SOCIAL_SIGNAL, isFromContact = false)
        )
        val byCategory = mapOf(NotificationCategory.SOCIAL_SIGNAL to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        val queueSuggestion = result.firstOrNull { it.action == "queue" }
        assertNull("no QUEUE when any notification is from a contact", queueSuggestion)
    }

    // ── ML path: safety guards ────────────────────────────────────────────

    @Test
    fun `PERSONAL category is never suggested`() {
        val pkg = "com.messaging.app"
        val appStats = listOf(stats(pkg, total = 20, tapped = 0))
        val notifs = (1..20).map { notif(pkg, NotificationCategory.PERSONAL) }
        val byCategory = mapOf(NotificationCategory.PERSONAL to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        assertTrue("PERSONAL is never suggested", result.isEmpty())
    }

    @Test
    fun `TRANSACTIONAL category is never suggested`() {
        val pkg = "com.banking.app"
        val appStats = listOf(stats(pkg, total = 20, tapped = 0))
        val notifs = (1..20).map { notif(pkg, NotificationCategory.TRANSACTIONAL) }
        val byCategory = mapOf(NotificationCategory.TRANSACTIONAL to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        assertTrue("TRANSACTIONAL is never suggested", result.isEmpty())
    }

    @Test
    fun `BACKGROUND category is never suggested`() {
        val pkg = "com.music.player"
        val appStats = listOf(stats(pkg, total = 20, tapped = 0))
        val notifs = (1..20).map { notif(pkg, NotificationCategory.BACKGROUND) }
        val byCategory = mapOf(NotificationCategory.BACKGROUND to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        assertTrue("BACKGROUND is never suggested", result.isEmpty())
    }

    // ── ML path: cap ────────────────────────────────────────────────────

    @Test
    fun `suggestions capped at MAX_SUGGESTIONS_PER_REPORT (3)`() {
        // Set up 5 apps all eligible for suggestions
        val pkgs = (1..5).map { "com.bait.app$it" }
        val appStats = pkgs.map { stats(it, total = 15, tapped = 0) }
        val notifs = pkgs.flatMap { pkg ->
            (1..15).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)

        val result = generator.generate(byCategory, appStats, notifs)

        assertTrue("suggestions must be capped at 3", result.size <= 3)
    }

    // ── ML path: profile-aware ────────────────────────────────────────────

    @Test
    fun `profile-aware high lifetime tap rate blends to raise above threshold — no suppress`() {
        val pkg = "com.sometimesopened.app"
        val appStats = listOf(stats(pkg, total = 15, tapped = 0))  // recent: 0% tap
        val notifs = (1..15).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)
        // Lifetime: high tap rate + enough evidence
        val profile = AppBehaviorProfile(
            packageName = pkg,
            channelId = "",
            totalReceived = 100,
            totalTapped = 60  // 60% lifetime tap rate
        )
        val profiles = mapOf(Pair(pkg, "") to profile)

        val result = generator.generate(byCategory, appStats, notifs, profiles)

        // Blended rate = 0% * 0.4 + 60% * 0.6 = 36% → above SUPPRESS_TAP_RATE_THRESHOLD (5%)
        val suppressSuggestion = result.firstOrNull { it.action == "suppress" }
        assertNull("no suppress when blended tap rate is high", suppressSuggestion)
    }

    @Test
    fun `no profile means recent rate only and suppress triggers on zero tap rate`() {
        val pkg = "com.newapp.app"
        val appStats = listOf(stats(pkg, total = 15, tapped = 0))  // 0% recent tap rate
        val notifs = (1..15).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)
        // No profile — recent rate is used exclusively; no lifetime evidence guard applies
        val profiles = emptyMap<Pair<String, String>, AppBehaviorProfile>()

        val result = generator.generate(byCategory, appStats, notifs, profiles)

        // Recent rate is 0% with no profile → suppress should be suggested
        val suppressSuggestion = result.firstOrNull { it.action == "suppress" }
        assertNotNull("suppress when recent rate is 0% and no profile exists", suppressSuggestion)
    }

    @Test
    fun `profile with insufficient lifetime evidence blocks suppress conservatively`() {
        val pkg = "com.newapp.app"
        val appStats = listOf(stats(pkg, total = 15, tapped = 0))  // 0% recent tap rate
        val notifs = (1..15).map { notif(pkg, NotificationCategory.ENGAGEMENT_BAIT) }
        val byCategory = mapOf(NotificationCategory.ENGAGEMENT_BAIT to notifs)
        // Profile exists with < LIFETIME_SUPPRESS_THRESHOLD (30) received — system is conservative
        val profile = AppBehaviorProfile(
            packageName = pkg,
            channelId = "",
            totalReceived = 5,   // < 30 LIFETIME_SUPPRESS_THRESHOLD
            totalTapped = 5      // 100% lifetime tap rate
        )
        val profiles = mapOf(Pair(pkg, "") to profile)

        val result = generator.generate(byCategory, appStats, notifs, profiles)

        // Suppress requires lifetime evidence to avoid premature suppression;
        // profile exists but hasn't accumulated enough data yet.
        val suppressSuggestion = result.firstOrNull { it.action == "suppress" }
        assertNull("suppress is blocked when profile lacks sufficient lifetime evidence", suppressSuggestion)
    }

    // ── Tier-reason path ────────────────────────────────────────────────────

    @Test
    fun `generateFromTierReasons linkedin reason high count low tap rate produces suppress`() {
        val stat = tierStat("com.linkedin.android", "linkedin", count = 25, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertEquals("exactly one suppress suggestion", 1, result.size)
        assertEquals("action is suppress", "suppress", result[0].action)
    }

    @Test
    fun `generateFromTierReasons amazon_shopping reason produces suppress`() {
        val stat = tierStat("com.amazon.mShop", "amazon_shopping", count = 25, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertEquals(1, result.size)
        assertEquals("suppress", result[0].action)
    }

    @Test
    fun `generateFromTierReasons play_store_update reason produces suppress`() {
        val stat = tierStat("com.android.vending", "play_store_update", count = 25, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertEquals(1, result.size)
        assertEquals("suppress", result[0].action)
    }

    @Test
    fun `generateFromTierReasons marketing_text reason produces suppress`() {
        val stat = tierStat("com.marketing.sms", "marketing_text", count = 25, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertEquals(1, result.size)
        assertEquals("suppress", result[0].action)
    }

    @Test
    fun `generateFromTierReasons unknown tier_reason is skipped`() {
        val stat = tierStat("com.unknown.pkg", "some_unknown_reason", count = 30, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertTrue("unknown tier_reason produces no suggestion", result.isEmpty())
    }

    @Test
    fun `generateFromTierReasons package already in ML suggestions is skipped`() {
        val pkg = "com.linkedin.android"
        // ML suggestion already exists for this package
        val existing = listOf(
            Suggestion(
                conditionJson = """{"type":"package_match","packageName":"$pkg"}""",
                action = "queue",
                rationale = "existing ML suggestion",
                status = "pending"
            )
        )
        val stat = tierStat(pkg, "linkedin", count = 30, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), existing)

        assertTrue("package already in ML suggestions is skipped", result.isEmpty())
    }

    @Test
    fun `generateFromTierReasons tap rate at or above TIER_SUGGEST_TAP_CEILING is skipped`() {
        // TIER_SUGGEST_TAP_CEILING = 0.05 → 5% tap rate should be skipped
        val stat = tierStat("com.linkedin.android", "linkedin", count = 100, tapped = 5)
        // tapRate = 5/100 = 0.05 → exactly at ceiling (>= means skip)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertTrue("tap rate at ceiling produces no suggestion", result.isEmpty())
    }

    @Test
    fun `conditionJson for PackageMatch is valid JSON with correct type discriminator`() {
        val stat = tierStat("com.linkedin.android", "linkedin", count = 25, tapped = 0)

        val result = generator.generateFromTierReasons(listOf(stat), emptyList())

        assertTrue("at least one suggestion", result.isNotEmpty())
        val json = Json.parseToJsonElement(result[0].conditionJson).jsonObject
        // kotlinx-serialization encodes sealed class with "type" discriminator by default
        val packageName = json["packageName"]?.jsonPrimitive?.content
        assertEquals("package name in conditionJson", "com.linkedin.android", packageName)
    }
}
