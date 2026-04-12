package ai.talkingrock.lithium.ui.training

import ai.talkingrock.lithium.data.db.PatternStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TrainerLevels], [computeXpForJudgment], [patternKey], and [PatternStat.isMapped].
 * Pure-Kotlin, no Android deps.
 */
class TrainerLevelTest {

    // ── snapshot level thresholds ────────────────────────────────────────────

    @Test fun `snapshot with 0 mapped, 0 total returns Novice level`() {
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 0, total = 0)
        assertEquals("Novice", snap.level.name)
        assertEquals(0, snap.level.ordinal)
    }

    @Test fun `snapshot with 3 mapped, 20 total returns Trainee level`() {
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 3, total = 20)
        assertEquals("Trainee", snap.level.name)
    }

    @Test fun `snapshot with 10 mapped, 100 total returns Trainer level`() {
        // Use total=100 so dynamic expert floor stays at 25 (standard threshold)
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 10, total = 100)
        assertEquals("Trainer", snap.level.name)
    }

    @Test fun `snapshot with 25 mapped, 50 total returns Expert level`() {
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 25, total = 50)
        assertEquals("Expert", snap.level.name)
    }

    @Test fun `snapshot with 50 mapped, 60 total returns Master level`() {
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 50, total = 60)
        assertEquals("Master", snap.level.name)
    }

    // ── Master floor dynamic cap ─────────────────────────────────────────────

    @Test fun `Master floor is capped — with total=10 master floor is min(50, 80% of 10)=8`() {
        // 80% of 10 = 8, which is less than 50 → masterFloor = max(5, 8) = 8
        val ladder = TrainerLevels.ladder(10)
        val master = ladder.last()
        assertEquals("Master", master.name)
        assertEquals("masterFloor should be 8 for total=10", 8, master.floorPatterns)
    }

    @Test fun `ladder with small dataset caps master floor below 50`() {
        // total=5: 80% = 4, coerceAtLeast(5) = 5 → masterFloor = min(50, 5) = 5
        val ladder = TrainerLevels.ladder(5)
        val master = ladder.last()
        assertTrue("master floor should be below 50 for small dataset", master.floorPatterns < 50)
        assertEquals("master floor = 5 for total=5", 5, master.floorPatterns)
    }

    // ── Ladder contiguity ────────────────────────────────────────────────────

    @Test fun `ladder levels are contiguous — ceiling of level N equals floor of level N+1`() {
        val ladder = TrainerLevels.ladder(100)
        for (i in 0 until ladder.lastIndex) {
            val current = ladder[i]
            val next = ladder[i + 1]
            assertEquals(
                "ceiling of ${current.name} should equal floor of ${next.name}",
                next.floorPatterns,
                current.ceilingPatterns
            )
        }
    }

    // ── progressWithinLevel ─────────────────────────────────────────────────

    @Test fun `progressWithinLevel interpolates correctly at midpoint`() {
        // Trainee: floor=3, ceiling=10. At mapped=6: (6-3)/(10-3) = 3/7 ≈ 0.4286
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 6, total = 100)
        assertEquals("Trainee", snap.level.name)
        val expected = 3f / 7f
        assertEquals(expected, snap.progressWithinLevel, 0.001f)
    }

    @Test fun `progressWithinLevel is 1_0 at Master — no ceiling`() {
        // Master has no ceiling → progress is always 1.0
        val snap = TrainerLevels.snapshot(xp = 0, mapped = 50, total = 60)
        assertEquals("Master", snap.level.name)
        assertEquals(1.0f, snap.progressWithinLevel, 0.0f)
    }

    // ── computeXpForJudgment ─────────────────────────────────────────────────

    @Test fun `computeXpForJudgment skip returns 0 XP`() {
        assertEquals(0, computeXpForJudgment("skip", "pkg|reason1", "pkg|reason2", emptyMap()))
    }

    @Test fun `computeXpForJudgment both patterns unseen returns 10 XP`() {
        assertEquals(10, computeXpForJudgment("left", "pkg|a", "pkg|b", emptyMap()))
    }

    @Test fun `computeXpForJudgment one unseen one seen once returns 10 XP — min prior is 0`() {
        val counts = mapOf("pkg|a" to 1)
        // left prior = 1, right prior = 0; min = 0 → 10 XP
        assertEquals(10, computeXpForJudgment("left", "pkg|a", "pkg|b", counts))
    }

    @Test fun `computeXpForJudgment both seen once returns 7 XP`() {
        val counts = mapOf("pkg|a" to 1, "pkg|b" to 1)
        assertEquals(7, computeXpForJudgment("left", "pkg|a", "pkg|b", counts))
    }

    @Test fun `computeXpForJudgment both seen twice returns 4 XP`() {
        val counts = mapOf("pkg|a" to 2, "pkg|b" to 2)
        assertEquals(4, computeXpForJudgment("left", "pkg|a", "pkg|b", counts))
    }

    @Test fun `computeXpForJudgment both seen 3 or more times returns 1 XP`() {
        val counts = mapOf("pkg|a" to 5, "pkg|b" to 10)
        assertEquals(1, computeXpForJudgment("right", "pkg|a", "pkg|b", counts))
    }

    // ── patternKey ───────────────────────────────────────────────────────────

    @Test fun `patternKey uses pipe separator with tierReason`() {
        val record = ai.talkingrock.lithium.data.model.NotificationRecord(
            packageName = "com.example.app",
            tierReason = "marketing_text"
        )
        assertEquals("com.example.app|marketing_text", patternKey(record))
    }

    @Test fun `patternKey with null tierReason produces none sentinel`() {
        val record = ai.talkingrock.lithium.data.model.NotificationRecord(
            packageName = "com.example.app",
            tierReason = null
        )
        assertEquals("com.example.app|none", patternKey(record))
    }

    // ── isMapped ────────────────────────────────────────────────────────────

    @Test fun `isMapped returns true when judged equals MIN_JUDGMENTS_TO_MAP`() {
        val stat = PatternStat(
            pattern = "com.a|reason",
            packageName = "com.a",
            tierReason = "reason",
            total = 10,
            judged = MIN_JUDGMENTS_TO_MAP // = 3
        )
        assertTrue(stat.isMapped())
    }

    @Test fun `isMapped returns false when judged is below MIN_JUDGMENTS_TO_MAP`() {
        val stat = PatternStat(
            pattern = "com.a|reason",
            packageName = "com.a",
            tierReason = "reason",
            total = 10,
            judged = MIN_JUDGMENTS_TO_MAP - 1 // = 2
        )
        assertFalse(stat.isMapped())
    }
}
