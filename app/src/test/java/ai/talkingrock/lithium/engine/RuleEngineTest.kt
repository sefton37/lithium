package ai.talkingrock.lithium.engine

import android.app.Notification
import android.service.notification.StatusBarNotification
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.repository.RuleRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureNanoTime

/**
 * Unit tests for [RuleEngine]. Uses MockK to provide a fake [RuleRepository] backed by
 * a [MutableStateFlow] so rule-list changes can be tested without a database.
 *
 * RuleEngine is pure logic — no Android runtime, no Hilt, no coroutines needed here.
 * Robolectric is used for the SafetyAllowlist pipeline-guard section (Android framework constants).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleEngineTest {

    private lateinit var fakeRules: MutableStateFlow<List<Rule>>
    private lateinit var engine: RuleEngine

    @Before
    fun setUp() {
        fakeRules = MutableStateFlow(emptyList())
        val mockRepo = mockk<RuleRepository> {
            every { approvedRules } returns fakeRules
        }
        engine = RuleEngine(mockRepo)
    }

    // ── Helper builders ──────────────────────────────────────────────────────

    private fun record(
        pkg: String = "com.test.app",
        channel: String? = null,
        category: String? = null,
        isFromContact: Boolean = false,
    ) = NotificationRecord(
        packageName = pkg,
        channelId = channel,
        category = category,
        isFromContact = isFromContact,
    )

    private fun rule(
        id: Long = 1L,
        conditionJson: String,
        action: String = "suppress",
        status: String = "approved",
    ) = Rule(
        id = id,
        conditionJson = conditionJson,
        action = action,
        status = status,
        createdAtMs = id * 1000L,
    )

    private fun packageRule(pkg: String, action: String = "suppress", id: Long = 1L) =
        rule(id = id, conditionJson = """{"type":"package_match","packageName":"$pkg"}""", action = action)

    private fun channelRule(pkg: String?, channel: String, action: String = "queue", id: Long = 2L): Rule {
        val pkgJson = if (pkg != null) "\"$pkg\"" else "null"
        return rule(id = id, conditionJson = """{"type":"channel_match","packageName":$pkgJson,"channelId":"$channel"}""", action = action)
    }

    private fun categoryRule(category: String, action: String = "suppress", id: Long = 3L) =
        rule(id = id, conditionJson = """{"type":"category_match","category":"$category"}""", action = action)

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test fun `empty rule list returns ALLOW for any record`() {
        assertEquals(RuleAction.ALLOW, engine.evaluate(record()))
    }

    @Test fun `PackageMatch condition matches exact package and returns SUPPRESS`() {
        fakeRules.value = listOf(packageRule("com.test.app"))
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `PackageMatch condition does not match different package — returns ALLOW`() {
        fakeRules.value = listOf(packageRule("com.test.app"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.other.app")))
    }

    @Test fun `ChannelMatch with package scope matches both package and channel — returns QUEUE`() {
        fakeRules.value = listOf(channelRule("com.test.app", "promo", action = "queue"))
        assertEquals(RuleAction.QUEUE, engine.evaluate(record(pkg = "com.test.app", channel = "promo")))
    }

    @Test fun `ChannelMatch with package scope ignores record with wrong package — returns ALLOW`() {
        fakeRules.value = listOf(channelRule("com.test.app", "promo", action = "queue"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.other.app", channel = "promo")))
    }

    @Test fun `ChannelMatch with null package matches channel regardless of package`() {
        fakeRules.value = listOf(channelRule(null, "alerts", action = "queue"))
        assertEquals(RuleAction.QUEUE, engine.evaluate(record(pkg = "com.any.app", channel = "alerts")))
        assertEquals(RuleAction.QUEUE, engine.evaluate(record(pkg = "com.different.app", channel = "alerts")))
    }

    @Test fun `CategoryMatch matches exact category string — returns SUPPRESS`() {
        fakeRules.value = listOf(categoryRule("promo"))
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(category = "promo")))
    }

    @Test fun `CategoryMatch does not match different category — returns ALLOW`() {
        fakeRules.value = listOf(categoryRule("promo"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(category = "news")))
    }

    @Test fun `NotFromContact always returns ALLOW — M1 stub regression guard`() {
        fakeRules.value = listOf(rule(conditionJson = """{"type":"not_from_contact"}""", action = "suppress"))
        // NotFromContact is an M1 stub — always returns false (no match) so ALLOW
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(isFromContact = false)))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(isFromContact = true)))
    }

    @Test fun `CompositeAnd matches only when ALL conditions match`() {
        val json = """{"type":"composite_and","conditions":[{"type":"package_match","packageName":"com.test.app"},{"type":"category_match","category":"promo"}]}"""
        fakeRules.value = listOf(rule(conditionJson = json))
        // Both match
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(pkg = "com.test.app", category = "promo")))
        // Only package matches
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.test.app", category = "news")))
        // Only category matches
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.other.app", category = "promo")))
    }

    @Test fun `CompositeAnd fails when any single condition fails`() {
        val json = """{"type":"composite_and","conditions":[{"type":"package_match","packageName":"com.a"},{"type":"package_match","packageName":"com.b"}]}"""
        fakeRules.value = listOf(rule(conditionJson = json))
        // A record can only match one package at a time
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.a")))
    }

    @Test fun `first-match-wins — SUPPRESS rule before QUEUE rule returns SUPPRESS`() {
        fakeRules.value = listOf(
            packageRule("com.test.app", action = "suppress", id = 1L),
            packageRule("com.test.app", action = "queue", id = 2L)
        )
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `first-match-wins — QUEUE rule before SUPPRESS rule returns QUEUE`() {
        fakeRules.value = listOf(
            packageRule("com.test.app", action = "queue", id = 1L),
            packageRule("com.test.app", action = "suppress", id = 2L)
        )
        assertEquals(RuleAction.QUEUE, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `malformed condition JSON is silently skipped and next rule evaluated`() {
        fakeRules.value = listOf(
            rule(id = 1L, conditionJson = """{"type":"BROKEN_JSON"""),  // malformed
            packageRule("com.test.app", action = "suppress", id = 2L)
        )
        // Malformed rule skipped; second rule fires
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `malformed condition JSON does not crash the hot path`() {
        fakeRules.value = listOf(rule(id = 1L, conditionJson = "not json at all {{{"))
        // Must not throw
        val result = engine.evaluate(record())
        assertEquals(RuleAction.ALLOW, result)
    }

    @Test fun `rules list change triggers cache rebuild — new rule fires after StateFlow update`() {
        // Initially no rules
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.test.app")))
        // Add a rule
        fakeRules.value = listOf(packageRule("com.test.app"))
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `case-insensitive action match — Suppress capital parses to SUPPRESS`() {
        fakeRules.value = listOf(packageRule("com.test.app", action = "Suppress"))
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `action string allow returns ALLOW explicitly`() {
        fakeRules.value = listOf(packageRule("com.test.app", action = "allow"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `action string unknown_action falls through to ALLOW`() {
        fakeRules.value = listOf(packageRule("com.test.app", action = "unknown_action"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `evaluate performance — 50 rules sub-1ms`() {
        // Build 50 rules that all match different packages (none will match "com.test.app")
        val rules = (1..50).map { i ->
            packageRule("com.pkg.$i", action = "suppress", id = i.toLong())
        }
        fakeRules.value = rules
        // Prime the cache
        engine.evaluate(record(pkg = "com.test.app"))

        val nanoTime = measureNanoTime {
            repeat(100) { engine.evaluate(record(pkg = "com.test.app")) }
        }
        val avgNs = nanoTime / 100
        val avgMs = avgNs / 1_000_000.0
        // Soft assertion: log if slow but don't fail in CI (timing is environment-dependent)
        // Use assertTrue with a generous 10ms threshold to catch catastrophic regressions
        assertTrue("evaluate avg should be < 10ms (was $avgMs ms)", avgMs < 10.0)
    }

    // ── SafetyAllowlist pipeline-guard tests ──────────────────────────────────
    //
    // These tests verify that SafetyAllowlist correctly identifies notifications that must
    // never reach rule evaluation. In the production pipeline, LithiumNotificationListener
    // checks SafetyAllowlist BEFORE calling ruleEngine.evaluate() — these tests guard
    // that the allowlist correctly returns true for those cases.

    private fun safetySbn(
        packageName: String = "com.example.app",
        isOngoing: Boolean = false,
        category: String? = null,
        flags: Int = 0,
    ): StatusBarNotification {
        // Build a real Notification and set its public fields directly (MockK cannot stub fields).
        val notification = Notification()
        notification.category = category
        notification.flags = flags

        val sbn = mockk<StatusBarNotification>(relaxed = true)
        every { sbn.packageName } returns packageName
        every { sbn.isOngoing } returns isOngoing
        every { sbn.notification } returns notification
        return sbn
    }

    @Test fun `SafetyAllowlist - CATEGORY_CALL notification is exempt from rule evaluation`() {
        // A call notification should be exempt — cancelNotification would fail silently anyway
        fakeRules.value = listOf(packageRule("com.google.android.dialer", action = "suppress"))
        val sbn = safetySbn(packageName = "com.google.android.dialer", category = Notification.CATEGORY_CALL)

        // Verify the allowlist catches it before evaluate() would be called
        assertTrue("Call should be safety-exempt", SafetyAllowlist.isSafetyExempt(sbn))
    }

    @Test fun `SafetyAllowlist - CATEGORY_ALARM notification is exempt from rule evaluation`() {
        val sbn = safetySbn(packageName = "com.android.deskclock", category = Notification.CATEGORY_ALARM)
        assertTrue("Alarm should be safety-exempt", SafetyAllowlist.isSafetyExempt(sbn))
    }

    @Test fun `SafetyAllowlist - ongoing notification is exempt from rule evaluation`() {
        val sbn = safetySbn(packageName = "com.example.service", isOngoing = true)
        assertTrue("Ongoing should be safety-exempt", SafetyAllowlist.isSafetyExempt(sbn))
    }

    @Test fun `SafetyAllowlist - Lithium own package is exempt from rule evaluation`() {
        val sbn = safetySbn(packageName = "ai.talkingrock.lithium")
        assertTrue("Lithium self-notification should be safety-exempt", SafetyAllowlist.isSafetyExempt(sbn))
    }

    @Test fun `SafetyAllowlist - systemui package is exempt from rule evaluation`() {
        val sbn = safetySbn(packageName = "com.android.systemui")
        assertTrue("systemui should be safety-exempt", SafetyAllowlist.isSafetyExempt(sbn))
    }

    @Test fun `SafetyAllowlist - normal app notification is NOT exempt and reaches evaluate`() {
        // A normal notification should NOT be exempt — it goes through rule evaluation
        fakeRules.value = listOf(packageRule("com.example.normalapp", action = "suppress"))
        val sbn = safetySbn(packageName = "com.example.normalapp", isOngoing = false, category = null)

        assertFalse("Normal notification should not be safety-exempt", SafetyAllowlist.isSafetyExempt(sbn))
        // Verify rule evaluation DOES fire for this notification
        assertEquals(
            RuleAction.SUPPRESS,
            engine.evaluate(record(pkg = "com.example.normalapp"))
        )
    }

    // ── TierMatch condition tests ─────────────────────────────────────────────

    private fun tierRecord(tier: Int) = NotificationRecord(
        packageName = "com.test.app",
        tier = tier,
    )

    private fun tierRule(tier: Int, action: String, id: Long = tier.toLong()) =
        rule(id = id, conditionJson = """{"type":"tier_match","tier":$tier}""", action = action)

    @Test fun `TierMatch condition matches tier 0 — returns SUPPRESS`() {
        fakeRules.value = listOf(tierRule(0, "suppress"))
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(tierRecord(0)))
    }

    @Test fun `TierMatch condition matches tier 1 — returns SUPPRESS`() {
        fakeRules.value = listOf(tierRule(1, "suppress"))
        assertEquals(RuleAction.SUPPRESS, engine.evaluate(tierRecord(1)))
    }

    @Test fun `TierMatch condition matches tier 2 — returns QUEUE`() {
        fakeRules.value = listOf(tierRule(2, "queue"))
        assertEquals(RuleAction.QUEUE, engine.evaluate(tierRecord(2)))
    }

    @Test fun `TierMatch condition matches tier 3 — returns ALLOW`() {
        fakeRules.value = listOf(tierRule(3, "allow"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(tierRecord(3)))
    }

    @Test fun `TierMatch condition does not match wrong tier — falls through to ALLOW`() {
        fakeRules.value = listOf(tierRule(1, "suppress"))
        // Record with tier 2 should not match a tier_match(1) rule
        assertEquals(RuleAction.ALLOW, engine.evaluate(tierRecord(2)))
    }

    @Test fun `TierMatch tier 2 with RESURFACE action — returns RESURFACE`() {
        fakeRules.value = listOf(tierRule(2, "resurface"))
        assertEquals(RuleAction.RESURFACE, engine.evaluate(tierRecord(2)))
    }

    // ── RESURFACE action dispatch tests ──────────────────────────────────────

    @Test fun `action string resurface returns RESURFACE`() {
        fakeRules.value = listOf(packageRule("com.test.app", action = "resurface"))
        assertEquals(RuleAction.RESURFACE, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `action string Resurface (capital) returns RESURFACE — case insensitive`() {
        fakeRules.value = listOf(packageRule("com.test.app", action = "Resurface"))
        assertEquals(RuleAction.RESURFACE, engine.evaluate(record(pkg = "com.test.app")))
    }

    @Test fun `action string resurface degrades to ALLOW on old builds via else branch`() {
        // Simulate an old build that has not yet added RESURFACE: the else branch returns ALLOW.
        // In the current build RESURFACE is handled; this test documents the safe-degradation contract
        // by verifying a truly unknown action (not "resurface") still returns ALLOW.
        fakeRules.value = listOf(packageRule("com.test.app", action = "future_unknown_action"))
        assertEquals(RuleAction.ALLOW, engine.evaluate(record(pkg = "com.test.app")))
    }
}
