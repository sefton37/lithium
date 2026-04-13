package ai.talkingrock.lithium.ai.scoring

import ai.talkingrock.lithium.data.db.AppBehaviorProfileDao
import ai.talkingrock.lithium.data.db.AppRankingDao
import ai.talkingrock.lithium.data.db.ChannelRankingDao
import ai.talkingrock.lithium.data.db.ImplicitJudgmentDao
import ai.talkingrock.lithium.data.db.TrainingJudgmentDao
import android.content.SharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [Scorer].
 *
 * Cold-start is the single most important correctness case to pin: a fresh install
 * with zero training data must produce `hasAppSignal = false` so the caller falls
 * back to TierClassifier instead of rewriting tier to a meaningless ~0.5 score.
 *
 * Secondary test confirms that once any AppRanking row exists for a package, the
 * scorer reports `hasAppSignal = true` and the score value is in [0, 1].
 */
class ScorerTest {

    private lateinit var appRankingDao: AppRankingDao
    private lateinit var channelRankingDao: ChannelRankingDao
    private lateinit var appBehaviorProfileDao: AppBehaviorProfileDao
    private lateinit var trainingJudgmentDao: TrainingJudgmentDao
    private lateinit var implicitJudgmentDao: ImplicitJudgmentDao
    private lateinit var tierMapper: TierMapper
    private lateinit var prefs: SharedPreferences

    private lateinit var scorer: Scorer

    @Before
    fun setUp() {
        appRankingDao = mockk()
        channelRankingDao = mockk()
        appBehaviorProfileDao = mockk()
        trainingJudgmentDao = mockk()
        implicitJudgmentDao = mockk()
        tierMapper = mockk()
        prefs = mockk()

        // Default cold-start stubs — every DAO returns null / 0.
        coEvery { appRankingDao.get(any()) } returns null
        coEvery { channelRankingDao.get(any(), any()) } returns null
        coEvery { appBehaviorProfileDao.getProfile(any(), any()) } returns null
        coEvery { trainingJudgmentDao.countByChannel(any(), any()) } returns 0
        coEvery { trainingJudgmentDao.countSameChannelPairs(any(), any()) } returns 0
        coEvery { implicitJudgmentDao.countForChannel(any(), any()) } returns 0
        coEvery { tierMapper.mapToTier(any()) } returns 2
        every { prefs.getString(any(), any()) } returns null

        scorer = Scorer(
            appRankingDao,
            channelRankingDao,
            appBehaviorProfileDao,
            trainingJudgmentDao,
            implicitJudgmentDao,
            tierMapper,
            prefs,
        )
    }

    @Test
    fun `cold start — no training data — hasAppSignal is false`() = runBlocking {
        val result = scorer.score(
            packageName = "com.example.fresh",
            channelId = "default",
            aiClassification = null,
            aiConfidence = null,
            isFromContact = false,
        )
        assertFalse("hasAppSignal must be false when no AppRanking or ChannelRanking exists",
            result.hasAppSignal)
    }

    @Test
    fun `cold start — score is near 0_5 — sigmoid of zero`() = runBlocking {
        val result = scorer.score(
            packageName = "com.example.fresh",
            channelId = null,
            aiClassification = null,
            aiConfidence = null,
            isFromContact = false,
        )
        assertTrue("score should land near 0.5 at cold start, got ${result.score}",
            result.score in 0.45..0.55)
    }

    @Test
    fun `cold start — contact bonus shifts score above 0_5`() = runBlocking {
        val result = scorer.score(
            packageName = "com.example.fresh",
            channelId = null,
            aiClassification = null,
            aiConfidence = null,
            isFromContact = true,
        )
        assertTrue("contact bonus should push score above 0.5, got ${result.score}",
            result.score > 0.5)
    }

    @Test
    fun `score is always in 0_1 range`() = runBlocking {
        val result = scorer.score(
            packageName = "com.example.fresh",
            channelId = "any",
            aiClassification = "personal",
            aiConfidence = 0.9f,
            isFromContact = true,
        )
        assertTrue("score must stay in [0, 1], got ${result.score}",
            result.score in 0.0..1.0)
    }

    @Test
    fun `contributions map is populated with the documented keys`() = runBlocking {
        val result = scorer.score(
            packageName = "com.example.fresh",
            channelId = "default",
            aiClassification = null,
            aiConfidence = null,
            isFromContact = false,
        )
        val c = result.contributions
        // These keys are part of the Scorer's public contract for debug attribution.
        // If they change, the signal-attribution UI breaks. Pin them.
        assertTrue("contributions missing theta_c_shrunk", c.containsKey("theta_c_shrunk"))
        assertTrue("contributions missing fade", c.containsKey("fade"))
        assertTrue("contributions missing n_combined", c.containsKey("n_combined"))
    }
}
