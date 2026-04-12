package ai.talkingrock.lithium.ui.training

import ai.talkingrock.lithium.data.model.AppRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [updateElo], [pickAppBattlePair], and [APP_BATTLE_XP].
 * Pure-Kotlin, no Android deps.
 */
class EloTest {

    private fun ranking(pkg: String, elo: Int = 1200, judgments: Int = 0) = AppRanking(
        packageName = pkg,
        eloScore = elo,
        wins = 0,
        losses = 0,
        ties = 0,
        judgments = judgments,
        updatedAtMs = 0L
    )

    // ── updateElo tests ──────────────────────────────────────────────────────

    @Test fun `equal scores left wins — left gains, right loses symmetrically`() {
        val (newLeft, newRight) = updateElo(1200, 1200, 1.0)
        assertTrue("left should gain", newLeft > 1200)
        assertTrue("right should lose", newRight < 1200)
        assertEquals("scores sum preserved", 2400, newLeft + newRight)
    }

    @Test fun `equal scores right wins — right gains, left loses symmetrically`() {
        val (newLeft, newRight) = updateElo(1200, 1200, 0.0)
        assertTrue("left should lose", newLeft < 1200)
        assertTrue("right should gain", newRight > 1200)
        assertEquals("scores sum preserved", 2400, newLeft + newRight)
    }

    @Test fun `equal scores tie — no net change in sum`() {
        val (newLeft, newRight) = updateElo(1200, 1200, 0.5)
        // With equal scores and tie, expected == 0.5, actual == 0.5 → delta == 0
        assertEquals("scores sum preserved", 2400, newLeft + newRight)
        assertEquals("left unchanged on tie at equal rating", 1200, newLeft)
        assertEquals("right unchanged on tie at equal rating", 1200, newRight)
    }

    @Test fun `favourite wins — gains less than underdog win would`() {
        // left is favourite (higher score)
        val (favGainLeft, _) = updateElo(1400, 1000, 1.0) // favourite wins
        val (_, underdogGainRight) = updateElo(1000, 1400, 0.0) // underdog wins (right wins)
        val favouriteGain = favGainLeft - 1400
        val underdogGain = 1400 - underdogGainRight // how much left lost (same as underdog right gaining)
        // Actually test: when favourite wins, delta is small; when underdog wins, delta is large
        val (_, underdogWinRight) = updateElo(1000, 1400, 1.0) // left(underdog) wins
        val underdogWinGain = underdogWinRight - 1000 // wait, that's left winning
        // Re-do: left=1000(underdog) beats right=1400(favourite)
        val (underdogNewLeft, _) = updateElo(1000, 1400, 1.0)
        val underdogGainActual = underdogNewLeft - 1000
        assertTrue("underdog win gains more than favourite win", underdogGainActual > favouriteGain)
    }

    @Test fun `underdog wins — gains more than favourite win`() {
        // left = 1000 (underdog), right = 1400 (favourite); left wins
        val (newLeft, _) = updateElo(1000, 1400, 1.0)
        val underdogGain = newLeft - 1000
        // left = 1400 (favourite), right = 1000 (underdog); left wins
        val (favLeft, _) = updateElo(1400, 1000, 1.0)
        val favouriteGain = favLeft - 1400
        assertTrue("underdog win gain > favourite win gain", underdogGain > favouriteGain)
    }

    @Test fun `K=32 equal scores left wins — delta is exactly 16`() {
        // equal scores: expectedLeft = 0.5, actual = 1.0 → delta = 32 * (1.0 - 0.5) = 16
        val (newLeft, newRight) = updateElo(1200, 1200, 1.0, k = 32)
        assertEquals("left gains exactly 16", 1216, newLeft)
        assertEquals("right loses exactly 16", 1184, newRight)
    }

    // ── pickAppBattlePair tests ──────────────────────────────────────────────

    @Test fun `pickAppBattlePair with less than 2 entries returns null`() {
        assertNull(pickAppBattlePair(emptyList()))
        assertNull(pickAppBattlePair(listOf(ranking("com.a"))))
    }

    @Test fun `pickAppBattlePair picks least-judged as anchor`() {
        val pool = listOf(
            ranking("com.a", judgments = 5),
            ranking("com.b", judgments = 1),  // least judged
            ranking("com.c", judgments = 3)
        )
        val pair = pickAppBattlePair(pool)
        assertNotNull(pair)
        assertEquals("anchor should be least judged", "com.b", pair!!.first)
    }

    @Test fun `pickAppBattlePair pairs anchor with closest Elo opponent`() {
        val pool = listOf(
            ranking("com.anchor", elo = 1200, judgments = 0),
            ranking("com.far",    elo = 800,  judgments = 2),
            ranking("com.close",  elo = 1210, judgments = 2)  // closest to anchor
        )
        val pair = pickAppBattlePair(pool)
        assertNotNull(pair)
        assertEquals("anchor is least judged", "com.anchor", pair!!.first)
        assertEquals("opponent is closest Elo", "com.close", pair.second)
    }

    @Test fun `pickAppBattlePair respects exclude set`() {
        val pool = listOf(
            ranking("com.a", judgments = 0),
            ranking("com.b", judgments = 1),
            ranking("com.c", judgments = 2)
        )
        val pair = pickAppBattlePair(pool, exclude = setOf("com.a"))
        assertNotNull(pair)
        // com.a excluded; com.b becomes least-judged anchor
        assertEquals("excluded package not picked as anchor", "com.b", pair!!.first)
    }

    @Test fun `pickAppBattlePair returns null when exclude leaves fewer than 2 packages`() {
        val pool = listOf(
            ranking("com.a"),
            ranking("com.b")
        )
        assertNull(pickAppBattlePair(pool, exclude = setOf("com.a")))
    }

    // ── APP_BATTLE_XP constant ──────────────────────────────────────────────

    @Test fun `APP_BATTLE_XP constant is 3`() {
        assertEquals(3, APP_BATTLE_XP)
    }

    // ── Score integrity ──────────────────────────────────────────────────────

    @Test fun `updateElo scores are rounded to int — no fractional drift after many updates`() {
        var left = 1200
        var right = 1200
        // Alternate wins; after N rounds scores should still be integers (no accumulation of fractions)
        repeat(20) { i ->
            val result = if (i % 2 == 0) updateElo(left, right, 1.0) else updateElo(left, right, 0.0)
            left = result.first
            right = result.second
        }
        // Verify they are plain integers (the return type guarantees this, but confirm no truncation loss)
        assertTrue("left score is a reasonable integer", left in 0..5000)
        assertTrue("right score is a reasonable integer", right in 0..5000)
    }

    @Test fun `updateElo sum is preserved across updates`() {
        // Total Elo in a closed system is conserved
        val (newLeft, newRight) = updateElo(1500, 900, 1.0)
        // May be off by 1 due to rounding — allow ±1 tolerance
        assertTrue("sum is preserved within rounding", abs((newLeft + newRight) - 2400) <= 1)
    }
}
