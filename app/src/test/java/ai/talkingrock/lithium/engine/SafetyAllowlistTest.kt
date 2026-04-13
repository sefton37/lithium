package ai.talkingrock.lithium.engine

import android.app.Notification
import android.service.notification.StatusBarNotification
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SafetyAllowlist].
 *
 * Uses MockK to build fake [StatusBarNotification] instances. [Notification] is created
 * as a real object (fields set directly) because its fields are not getter-backed properties
 * and MockK cannot stub them. Robolectric provides the Android framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyAllowlistTest {

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun sbn(
        packageName: String = "com.example.app",
        isOngoing: Boolean = false,
        category: String? = null,
        flags: Int = 0,
    ): StatusBarNotification {
        // Build a real Notification and set its public fields directly.
        val notification = Notification()
        notification.category = category
        notification.flags = flags

        val sbn = mockk<StatusBarNotification>(relaxed = true)
        every { sbn.packageName } returns packageName
        every { sbn.isOngoing } returns isOngoing
        every { sbn.notification } returns notification

        return sbn
    }

    // ── Exemption cases ───────────────────────────────────────────────────────

    @Test fun `Lithium own package is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(packageName = "ai.talkingrock.lithium")))
    }

    @Test fun `Lithium debug build package is exempt`() {
        // The debug build uses a .debug suffix on the package name. Without this check,
        // the reconnect nudge notification would be QUEUE'd and cancelled, creating a
        // disconnect/reconnect loop in debug builds.
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(packageName = "ai.talkingrock.lithium.debug")))
    }

    @Test fun `android system package is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(packageName = "android")))
    }

    @Test fun `com_android_systemui package is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(packageName = "com.android.systemui")))
    }

    @Test fun `com_samsung_android_incallui package is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(packageName = "com.samsung.android.incallui")))
    }

    @Test fun `com_google_android_dialer package is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(packageName = "com.google.android.dialer")))
    }

    @Test fun `isOngoing true is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(isOngoing = true)))
    }

    @Test fun `CATEGORY_CALL is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(category = Notification.CATEGORY_CALL)))
    }

    @Test fun `CATEGORY_ALARM is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(category = Notification.CATEGORY_ALARM)))
    }

    @Test fun `CATEGORY_TRANSPORT is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(category = Notification.CATEGORY_TRANSPORT)))
    }

    @Test fun `FLAG_FOREGROUND_SERVICE set is exempt`() {
        // android.app.Notification.FLAG_FOREGROUND_SERVICE = 0x00000040
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(flags = 0x00000040)))
    }

    @Test fun `FLAG_FOREGROUND_SERVICE among other flags is exempt`() {
        assertTrue(SafetyAllowlist.isSafetyExempt(sbn(flags = 0x00000040 or 0x00000010)))
    }

    // ── Negative cases ────────────────────────────────────────────────────────

    @Test fun `normal app notification with no exemption conditions returns false`() {
        assertFalse(
            SafetyAllowlist.isSafetyExempt(
                sbn(
                    packageName = "com.example.normalapp",
                    isOngoing = false,
                    category = null,
                    flags = 0,
                )
            )
        )
    }

    @Test fun `marketing notification with CATEGORY_PROMO is not exempt`() {
        assertFalse(
            SafetyAllowlist.isSafetyExempt(
                sbn(
                    packageName = "com.example.shop",
                    category = Notification.CATEGORY_PROMO,
                )
            )
        )
    }

    @Test fun `social notification is not exempt`() {
        assertFalse(
            SafetyAllowlist.isSafetyExempt(
                sbn(packageName = "com.social.app", category = Notification.CATEGORY_SOCIAL)
            )
        )
    }
}
