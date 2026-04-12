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

    // ============================================================
    // Phase 0 extension: 18 additional edge cases
    // ============================================================

    // HIGHEST PRIORITY: security keyword beats ongoing flag
    // An OTP in an ongoing notification must return Tier 3, not Tier 0.
    // Security is checked BEFORE ongoing in TierClassifier — this test guards that ordering.
    @Test fun `security keyword beats ongoing flag — OTP in ongoing notification returns tier 3`() =
        assertEquals(
            3 to "security_2fa",
            classify("com.random.app", text = "Your verification code is 887654", isOngoing = true)
        )

    // transport category checks
    @Test fun `transport category is tier 0 regardless of package`() =
        assertEquals(
            0 to "media_transport",
            classify("com.google.android.apps.messaging", category = "transport")
        )

    @Test fun `transport category checked before ongoing — transport plus ongoing returns media_transport`() =
        assertEquals(
            0 to "media_transport",
            classify("com.random.app", isOngoing = true, category = "transport")
        )

    // dialer: unknown call
    @Test fun `dialer with category=call and isFromContact=false returns tier 2 call_unknown`() =
        assertEquals(
            2 to "call_unknown",
            classify("com.other.dialer.app", category = "call", isFromContact = false)
        )

    // School package substrings
    @Test fun `school package via pikmykid substring returns tier 2`() =
        assertEquals(2 to "school", classify("com.pikmykid.school"))

    @Test fun `school package via donges substring returns tier 2`() =
        assertEquals(2 to "school", classify("com.donges.something"))

    // Financial package prefixes/substrings
    @Test fun `financial via chase prefix returns tier 2`() =
        assertEquals(2 to "financial", classify("com.chase.bank"))

    @Test fun `financial via optum substring returns tier 2`() =
        assertEquals(2 to "financial", classify("com.optum.rx"))

    // LinkedIn/Amazon subdomain prefix matching
    @Test fun `linkedin subdomain returns tier 1`() =
        assertEquals(1 to "linkedin", classify("com.linkedin.sales.navigator"))

    @Test fun `amazon subdomain returns tier 1`() =
        assertEquals(1 to "amazon_shopping", classify("com.amazon.mShop.android.shopping"))

    // Null title and text — no NPE, classifies by package
    @Test fun `null title and null text does not throw and classifies by package`() =
        assertEquals(2 to "default", classify("com.unknown.app", title = null, text = null))

    // Very long text with security keyword still returns tier 3
    @Test fun `very long text containing security keyword is still tier 3`() {
        val longText = "a".repeat(500) + " verification code " + "b".repeat(100)
        assertEquals(3 to "security_2fa", classify("com.random.app", text = longText))
    }

    // GitHub
    @Test fun `github is tier 2`() =
        assertEquals(2 to "github", classify("com.github.android"))

    // Security keyword variants
    @Test fun `two-factor keyword triggers tier 3`() =
        assertEquals(3 to "security_2fa", classify("com.random.app", text = "two-factor authentication required"))

    @Test fun `otp keyword (short) triggers tier 3`() =
        assertEquals(3 to "security_2fa", classify("com.random.app", text = "Your OTP is 123456"))

    @Test fun `sign-in code keyword triggers tier 3`() =
        assertEquals(3 to "security_2fa", classify("com.random.app", text = "Use your sign-in code: 887721"))

    // Marketing keyword case insensitive
    @Test fun `marketing keyword case insensitive — unsubscribe in uppercase triggers tier 1`() =
        assertEquals(1 to "marketing_text", classify("com.random.shop", text = "UNSUBSCRIBE here"))

    // Self-package ignores text content
    @Test fun `self-package with security keyword content stays tier 0`() =
        assertEquals(0 to "self", classify("ai.talkingrock.lithium", text = "verification code 123456"))
}
