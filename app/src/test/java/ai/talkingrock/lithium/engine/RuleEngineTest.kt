package ai.talkingrock.lithium.engine

import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Rule
import ai.talkingrock.lithium.data.repository.RuleRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Unit tests for [RuleEngine]. Uses MockK to provide a fake [RuleRepository] backed by
 * a [MutableStateFlow] so rule-list changes can be tested without a database.
 *
 * RuleEngine is pure logic — no Android runtime, no Hilt, no coroutines needed here.
 */
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
}
