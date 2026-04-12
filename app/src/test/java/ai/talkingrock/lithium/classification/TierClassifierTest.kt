package ai.talkingrock.lithium.classification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TierClassifier]. Pure-Kotlin object, no Android deps —
 * runs as a standard JVM test.
 */
class TierClassifierTest {

    private fun classify(
        pkg: String,
        title: String? = null,
        text: String? = null,
        isOngoing: Boolean = false,
        category: String? = null,
        isFromContact: Boolean = false,
    ) = TierClassifier.classify(pkg, title, text, isOngoing, category, isFromContact)

    // --- Tier 0: invisible ---

    @Test fun `self is tier 0`() =
        assertEquals(0 to "self", classify("ai.talkingrock.lithium"))

    @Test fun `spotify is tier 0`() =
        assertEquals(0 to "media_player", classify("com.spotify.music"))

    @Test fun `ongoing is tier 0`() =
        assertEquals(0 to "ongoing_persistent", classify("com.random.app", isOngoing = true))

    // --- Tier 3: security always wins ---

    @Test fun `otp from unknown sender stays tier 3`() =
        assertEquals(
            3 to "security_2fa",
            classify("com.google.android.apps.messaging", text = "Your verification code is 123456", isFromContact = false)
        )

    @Test fun `security keyword in gmail stays tier 3`() =
        assertEquals(
            3 to "security_2fa",
            classify("com.google.android.gm", text = "Sign in attempt from new device", isFromContact = false)
        )

    // --- Contact whitelist: channel behavior ---

    @Test fun `sms from contact is tier 3`() =
        assertEquals(
            3 to "sms_known",
            classify("com.google.android.apps.messaging", text = "hey what's up", isFromContact = true)
        )

    @Test fun `sms from unknown is tier 2`() =
        assertEquals(
            2 to "sms_unknown",
            classify("com.google.android.apps.messaging", text = "hey what's up", isFromContact = false)
        )

    @Test fun `call from contact is tier 3`() =
        assertEquals(3 to "call_known", classify("com.google.android.dialer", isFromContact = true))

    @Test fun `call from unknown is tier 2`() =
        assertEquals(2 to "call_unknown", classify("com.google.android.dialer", isFromContact = false))

    @Test fun `gmail from contact is tier 3`() =
        assertEquals(3 to "gmail_known", classify("com.google.android.gm", text = "lunch?", isFromContact = true))

    @Test fun `gmail from unknown is tier 2`() =
        assertEquals(2 to "gmail", classify("com.google.android.gm", text = "newsletter", isFromContact = false))

    // --- Tier 1: noise ---

    @Test fun `linkedin is tier 1`() =
        assertEquals(1 to "linkedin", classify("com.linkedin.android"))

    @Test fun `marketing keyword is tier 1`() =
        assertEquals(1 to "marketing_text", classify("com.random.shop", text = "50% off limited time"))

    // --- Default ---

    @Test fun `unknown app defaults to tier 2`() =
        assertEquals(2 to "default", classify("com.unknown.app"))
}
