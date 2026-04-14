package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.repository.NotificationRepository
import ai.talkingrock.lithium.ai.AppLabelResolver
import ai.talkingrock.lithium.ui.chat.RuleDraftFields
import ai.talkingrock.lithium.ui.chat.RuleDraftState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuleExtractorTest {

    private lateinit var engine: LlamaEngine
    private lateinit var repo: NotificationRepository
    private lateinit var labelResolver: AppLabelResolver
    private lateinit var extractor: RuleExtractor

    @Before fun setUp() {
        engine = mockk()
        repo = mockk()
        labelResolver = mockk()
        every { engine.isModelLoaded() } returns true
        every { labelResolver.label(any()) } answers { firstArg<String>().substringAfterLast('.') }
        coEvery { repo.getDistinctPackageNames() } returns listOf("com.slack", "com.whatsapp")
        extractor = RuleExtractor(engine, repo, labelResolver)
    }

    @Test fun `extract returns blank draft when model not loaded`() = runTest {
        every { engine.isModelLoaded() } returns false
        val result = extractor.extract("mute slack")
        assertEquals("mute slack", result.originalInput)
        assertNull(result.packageName)
        assertEquals("suppress", result.action)
    }

    @Test fun `package name validated against known list — hallucination becomes null`() = runTest {
        coEvery { engine.generate(any(), any()) } returns "com.fakeapp"
        val result = extractor.extract("mute fakeapp")
        assertNull(result.packageName)
    }

    @Test fun `known package passes validation`() = runTest {
        coEvery { engine.generate(match { it.contains("package") }, any()) } returns "com.slack"
        coEvery { engine.generate(match { !it.contains("package") }, any()) } returns "unknown"
        val result = extractor.extract("mute slack DMs")
        assertEquals("com.slack", result.packageName)
    }

    @Test fun `unknown output yields null field`() = runTest {
        coEvery { engine.generate(any(), any()) } returns "unknown"
        val result = extractor.extract("some rule")
        assertNull(result.packageName)
        assertNull(result.channelId)
        assertNull(result.category)
    }

    @Test fun `action queue is parsed from noisy output`() = runTest {
        coEvery { engine.generate(match { it.contains("package") }, any()) } returns "unknown"
        coEvery { engine.generate(match { it.contains("channel") }, any()) } returns "unknown"
        coEvery { engine.generate(match { it.contains("category") }, any()) } returns "unknown"
        coEvery { engine.generate(match { it.contains("contact") }, any()) } returns "no"
        // keyword extraction was added after this test was written; return empty to skip keyword filter
        coEvery { engine.generate(match { it.contains("keyword") }, any()) } returns "unknown"
        coEvery { engine.generate(match { it.contains("action") }, any()) } returns "I think queue"
        val result = extractor.extract("hold these for later")
        assertEquals("queue", result.action)
    }

    @Test fun `notFromContact true when response contains yes`() = runTest {
        coEvery { engine.generate(match { it.contains("contact") }, any()) } returns "yes"
        coEvery { engine.generate(match { !it.contains("contact") }, any()) } returns "unknown"
        val result = extractor.extract("only non-contacts")
        assertTrue(result.notFromContact)
    }

    @Test fun `refine preserves userEditedFields`() = runTest {
        coEvery { engine.generate(any(), any()) } returns "unknown"
        val existing = RuleDraftState(
            originalInput = "mute slack",
            packageName = "com.whatsapp",
            action = "queue",
            userEditedFields = setOf(RuleDraftFields.PACKAGE, RuleDraftFields.ACTION),
        )
        val refined = extractor.refine(existing, "also before 9am")
        assertEquals("com.whatsapp", refined.packageName)
        assertEquals("queue", refined.action)
        assertTrue(refined.userEditedFields.contains(RuleDraftFields.PACKAGE))
    }
}
