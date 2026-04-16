package ai.talkingrock.lithium.ai

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import ai.talkingrock.lithium.ai.scoring.ScoringRefit
import ai.talkingrock.lithium.data.Prefs
import ai.talkingrock.lithium.data.db.LithiumDatabase
import ai.talkingrock.lithium.data.db.NotificationDao
import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.Report
import ai.talkingrock.lithium.data.model.Suggestion
import ai.talkingrock.lithium.data.repository.BehaviorProfileRepository
import ai.talkingrock.lithium.data.repository.ReportRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [AiAnalysisWorker].
 *
 * AI engines (AiEngine, LlamaEngine) are mocked with MockK.
 * PatternAnalyzer, ReportGenerator, SuggestionGenerator are also mocked.
 * Focus: orchestration logic, not ML inference.
 *
 * Uses in-memory Room for the NotificationDao + SessionDao so that DAO calls
 * operate against real SQL instead of mocks — this keeps the integration real
 * for the data layer while keeping AI calls fast/deterministic.
 */
@RunWith(AndroidJUnit4::class)
class AiAnalysisWorkerTest {

    private lateinit var context: Context
    private lateinit var db: LithiumDatabase
    private lateinit var notificationDao: NotificationDao

    // All external dependencies mocked
    private lateinit var aiEngine: AiEngine
    private lateinit var llamaEngine: LlamaEngine
    private lateinit var classifier: NotificationClassifier
    private lateinit var patternAnalyzer: PatternAnalyzer
    private lateinit var reportGenerator: ReportGenerator
    private lateinit var suggestionGenerator: SuggestionGenerator
    private lateinit var reportRepository: ReportRepository
    private lateinit var behaviorProfileRepository: BehaviorProfileRepository
    private lateinit var scoringRefit: ScoringRefit
    private lateinit var sharedPreferences: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LithiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notificationDao = db.notificationDao()

        aiEngine = mockk(relaxed = true)
        llamaEngine = mockk(relaxed = true)
        classifier = mockk(relaxed = true)
        patternAnalyzer = mockk(relaxed = true)
        reportGenerator = mockk(relaxed = true)
        suggestionGenerator = mockk(relaxed = true)
        reportRepository = mockk(relaxed = true)
        behaviorProfileRepository = mockk(relaxed = true)
        scoringRefit = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)

        // Defaults: models do NOT load (heuristic path)
        every { aiEngine.isModelLoaded() } returns false
        every { llamaEngine.isModelLoaded() } returns false

        // Default: data-ready pref already set (skip notification)
        every { sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false) } returns true
        every { sharedPreferences.getInt(Prefs.PREF_RETENTION_DAYS, Prefs.DEFAULT_RETENTION_DAYS) } returns 30

        // Default pattern analyzer returns empty map and empty lists
        coEvery { patternAnalyzer.getNotificationsByCategory(any()) } returns emptyMap()
        coEvery { patternAnalyzer.getAppStats(any()) } returns emptyList()
        coEvery { patternAnalyzer.getContactVsAlgorithmicRatio(any()) } returns Pair(0, 0)

        // Default report generator returns a stub report
        every { reportGenerator.generate(any(), any(), any()) } returns
                Report(generatedAtMs = System.currentTimeMillis(), summaryJson = "{}")

        // Default report repository: insert returns a valid ID
        coEvery { reportRepository.insertReport(any()) } returns 1L
        coEvery { reportRepository.insertSuggestions(any()) } returns Unit

        // Default suggestion generator: no suggestions
        coEvery { suggestionGenerator.generate(any(), any(), any(), any()) } returns emptyList()
        coEvery { suggestionGenerator.generateFromTierReasons(any(), any()) } returns emptyList()

        // Default behavior profile repository
        coEvery { behaviorProfileRepository.getProfileMap() } returns emptyMap()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    private fun buildWorker(): AiAnalysisWorker {
        return TestListenableWorkerBuilder<AiAnalysisWorker>(context)
            .setWorkerFactory(AiAnalysisWorkerFactory(
                notificationDao = notificationDao,
                sessionDao = db.sessionDao(),
                aiEngine = aiEngine,
                llamaEngine = llamaEngine,
                classifier = classifier,
                patternAnalyzer = patternAnalyzer,
                reportGenerator = reportGenerator,
                suggestionGenerator = suggestionGenerator,
                reportRepository = reportRepository,
                behaviorProfileRepository = behaviorProfileRepository,
                scoringRefit = scoringRefit,
                sharedPreferences = sharedPreferences,
                modelDir = "/dev/null",
            ))
            .build()
    }

    private suspend fun insertNotif(
        aiClassification: String? = null,
        pkg: String = "com.test",
    ): Long = notificationDao.insertOrReplace(
        NotificationRecord(
            packageName = pkg,
            postedAtMs = System.currentTimeMillis(),
            title = "Title",
            text = "Body",
            tier = 2,
            tierReason = "default",
            aiClassification = aiClassification,
        )
    )

    // ── 1. no unclassified rows → classification skipped, report still generated ──

    @Test
    fun noUnclassifiedRows_reportStillGenerated() = runTest {
        // Insert a row that's already classified
        insertNotif(aiClassification = "personal")

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { reportRepository.insertReport(any()) }
        coVerify(exactly = 0) { classifier.classify(any(), any()) }
    }

    // ── 2. 500 unclassified rows → classifies all MAX_BATCH_SIZE rows ─────────

    @Test
    fun maxBatchSizeUnclassifiedRows_classifiesAll() = runTest {
        repeat(AiAnalysisWorker.MAX_BATCH_SIZE) { insertNotif(aiClassification = null) }
        coEvery { classifier.classify(any(), any()) } returns
                ClassificationResult("personal", 0.9f)

        buildWorker().doWork()

        coVerify(exactly = AiAnalysisWorker.MAX_BATCH_SIZE) { classifier.classify(any(), any()) }
    }

    // ── 3. classifier throws for one record → continues, returns SUCCESS ──────

    @Test
    fun classifierThrowsForOneRecord_continuesAndSucceeds() = runTest {
        val id1 = insertNotif(aiClassification = null)
        val id2 = insertNotif(aiClassification = null)

        coEvery { classifier.classify(match { it.id == id1 }, any()) } throws RuntimeException("fail")
        coEvery { classifier.classify(match { it.id == id2 }, any()) } returns
                ClassificationResult("personal", 0.9f)

        val result = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ── 4. patternAnalyzer failure → returns FAILURE ─────────────────────────

    @Test
    fun patternAnalyzerFailure_returnsFailure() = runTest {
        coEvery { patternAnalyzer.getNotificationsByCategory(any()) } throws RuntimeException("boom")

        val result = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    // ── 5. reportRepository.insertReport failure → returns FAILURE ────────────

    @Test
    fun insertReportFailure_returnsFailure() = runTest {
        coEvery { reportRepository.insertReport(any()) } throws RuntimeException("db error")

        val result = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }

    // ── 6. insertSuggestions failure → non-fatal, still returns SUCCESS ───────

    @Test
    fun insertSuggestionsFailure_nonFatal_returnsSuccess() = runTest {
        coEvery { suggestionGenerator.generate(any(), any(), any(), any()) } returns listOf(
            Suggestion(reportId = 0, action = "suppress", conditionJson = "{}", rationale = "test")
        )
        coEvery { reportRepository.insertSuggestions(any()) } throws RuntimeException("db write fail")

        val result = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ── 7. retention cleanup: deleteOlderThan called with correct threshold ───

    @Test
    fun retentionCleanup_deletesOldRows() = runTest {
        // Insert an old row so we can verify deletion
        val oldId = notificationDao.insertOrReplace(
            NotificationRecord(
                packageName = "com.old",
                postedAtMs = 1000L,  // very old
                tier = 2,
                tierReason = "default",
            )
        )

        // Set retention to 30 days (default)
        every { sharedPreferences.getInt(Prefs.PREF_RETENTION_DAYS, any()) } returns 30

        buildWorker().doWork()

        // The old row should be deleted (1000ms is older than 30 days ago)
        val fetched = notificationDao.getById(oldId)
        assertTrue("Old row should be deleted by retention cleanup", fetched == null)
    }

    // ── 8. step 2.5 readiness check: below threshold → DATA_READY_NOTIFIED stays false ──

    @Test
    fun readinessCheck_belowThreshold_prefNotSet() = runTest {
        // pref not yet set
        every { sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false) } returns false
        // Only insert a few classified rows (below threshold of 50)
        repeat(5) { insertNotif(aiClassification = "personal") }

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor

        buildWorker().doWork()

        // Should NOT have set DATA_READY_NOTIFIED to true
        coVerify(exactly = 0) { editor.putBoolean(Prefs.DATA_READY_NOTIFIED, true) }
    }

    // ── 9. step 2.5 readiness check: above both thresholds → DATA_READY_NOTIFIED set true ──

    @Test
    fun readinessCheck_aboveThresholds_prefSetTrue() = runTest {
        every { sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false) } returns false

        // Insert enough classified rows across enough distinct packages
        val packages = (1..4).map { "com.app$it" }
        packages.forEach { pkg ->
            repeat(15) { insertNotif(aiClassification = "personal", pkg = pkg) }
        }
        // Total: 60 rows across 4 packages — above DATA_READY_MIN_COUNT=50, DATA_READY_MIN_APPS=3

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        buildWorker().doWork()

        coVerify(atLeast = 1) { editor.putBoolean(Prefs.DATA_READY_NOTIFIED, true) }
    }

    // ── 10. step 2.5: already notified → check skipped ────────────────────────

    @Test
    fun readinessCheck_alreadyNotified_checkSkipped() = runTest {
        every { sharedPreferences.getBoolean(Prefs.DATA_READY_NOTIFIED, false) } returns true

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPreferences.edit() } returns editor

        buildWorker().doWork()

        // Should not have called edit() for data-ready purposes
        coVerify(exactly = 0) { editor.putBoolean(Prefs.DATA_READY_NOTIFIED, any()) }
    }

    // ── 11. tier-reason suggestions: generated when generateFromTierReasons returns results ──

    @Test
    fun tierReasonSuggestions_generatedWhenReturnedByGenerator() = runTest {
        val tierSuggestion = Suggestion(
            reportId = 0, action = "suppress",
            conditionJson = """{"type":"package_match","packageName":"com.linkedin.android"}""",
            rationale = "LinkedIn sends many low-engagement notifications"
        )
        coEvery { suggestionGenerator.generateFromTierReasons(any(), any()) } returns listOf(tierSuggestion)

        val suggestionsSlot = slot<List<Suggestion>>()
        coEvery { reportRepository.insertSuggestions(capture(suggestionsSlot)) } returns Unit

        buildWorker().doWork()

        coVerify(exactly = 1) { reportRepository.insertSuggestions(any()) }
        val captured = suggestionsSlot.captured
        assertTrue(captured.any { it.rationale.contains("LinkedIn") })
    }

    // ── 12. tier-reason suggestions: generateFromTierReasons receives ML suggestions for dedup ──

    @Test
    fun tierReasonSuggestions_mlSuggestionsPassedForDedup() = runTest {
        val mlSuggestion = Suggestion(
            reportId = 0, action = "suppress",
            conditionJson = """{"type":"package_match"}""",
            rationale = "ML generated"
        )
        coEvery { suggestionGenerator.generate(any(), any(), any(), any()) } returns listOf(mlSuggestion)

        val mlSuggestionsSlot = slot<List<Suggestion>>()
        coEvery { suggestionGenerator.generateFromTierReasons(any(), capture(mlSuggestionsSlot)) } returns emptyList()

        buildWorker().doWork()

        val passedMlSuggestions = mlSuggestionsSlot.captured
        assertEquals(1, passedMlSuggestions.size)
        assertEquals("ML generated", passedMlSuggestions[0].rationale)
    }

    // ── 13. combined suggestion count: both ML and tier-path included ─────────

    @Test
    fun combinedSuggestions_bothPathsIncluded() = runTest {
        val mlSuggestion = Suggestion(
            reportId = 0, action = "suppress",
            conditionJson = """{"type":"package_match"}""",
            rationale = "ML App"
        )
        val tierSuggestion = Suggestion(
            reportId = 0, action = "queue",
            conditionJson = """{"type":"package_match"}""",
            rationale = "Tier App"
        )

        coEvery { suggestionGenerator.generate(any(), any(), any(), any()) } returns listOf(mlSuggestion)
        coEvery { suggestionGenerator.generateFromTierReasons(any(), any()) } returns listOf(tierSuggestion)

        val suggestionsSlot = slot<List<Suggestion>>()
        coEvery { reportRepository.insertSuggestions(capture(suggestionsSlot)) } returns Unit

        buildWorker().doWork()

        val captured = suggestionsSlot.captured
        assertEquals(2, captured.size)
    }

    // ── 14. completion notification posted — worker returns SUCCESS ───────────

    @Test
    fun completionNotification_workerSucceedsAndReturns() = runTest {
        val result = buildWorker().doWork()
        // Smoke test: SUCCESS implies postCompletionNotification ran without crash
        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ── 15. zero suggestions → worker still returns SUCCESS ──────────────────

    @Test
    fun zeroSuggestions_workerSucceeds() = runTest {
        coEvery { suggestionGenerator.generate(any(), any(), any(), any()) } returns emptyList()
        coEvery { suggestionGenerator.generateFromTierReasons(any(), any()) } returns emptyList()

        val result = buildWorker().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ── 16. one suggestion → insertSuggestions called with size=1 list ────────

    @Test
    fun oneSuggestion_insertCalledWithCorrectCount() = runTest {
        val suggestion = Suggestion(
            reportId = 0, action = "suppress",
            conditionJson = """{"type":"package_match"}""",
            rationale = "Single app suggestion"
        )
        coEvery { suggestionGenerator.generate(any(), any(), any(), any()) } returns listOf(suggestion)
        coEvery { suggestionGenerator.generateFromTierReasons(any(), any()) } returns emptyList()

        val suggestionsSlot = slot<List<Suggestion>>()
        coEvery { reportRepository.insertSuggestions(capture(suggestionsSlot)) } returns Unit

        buildWorker().doWork()

        val captured = suggestionsSlot.captured
        assertEquals(1, captured.size)
    }
}

/**
 * WorkerFactory for AiAnalysisWorker that bypasses Hilt.
 *
 * Accepts all AiAnalysisWorker dependencies directly and provides them
 * via constructor injection.
 */
class AiAnalysisWorkerFactory(
    private val notificationDao: NotificationDao,
    private val sessionDao: ai.talkingrock.lithium.data.db.SessionDao,
    private val aiEngine: AiEngine,
    private val llamaEngine: LlamaEngine,
    private val classifier: NotificationClassifier,
    private val patternAnalyzer: PatternAnalyzer,
    private val reportGenerator: ReportGenerator,
    private val suggestionGenerator: SuggestionGenerator,
    private val reportRepository: ReportRepository,
    private val behaviorProfileRepository: BehaviorProfileRepository,
    private val scoringRefit: ScoringRefit,
    private val sharedPreferences: SharedPreferences,
    private val modelDir: String,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return if (workerClassName == AiAnalysisWorker::class.java.name) {
            AiAnalysisWorker(
                appContext = appContext,
                workerParams = workerParameters,
                notificationDao = notificationDao,
                sessionDao = sessionDao,
                aiEngine = aiEngine,
                llamaEngine = llamaEngine,
                classifier = classifier,
                patternAnalyzer = patternAnalyzer,
                reportGenerator = reportGenerator,
                suggestionGenerator = suggestionGenerator,
                reportRepository = reportRepository,
                behaviorProfileRepository = behaviorProfileRepository,
                scoringRefit = scoringRefit,
                sharedPreferences = sharedPreferences,
                modelDir = modelDir,
            )
        } else null
    }
}
