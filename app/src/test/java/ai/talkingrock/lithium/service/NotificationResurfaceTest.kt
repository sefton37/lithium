package ai.talkingrock.lithium.service

import android.app.Notification
import android.app.NotificationManager
import ai.talkingrock.lithium.data.model.NotificationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Objects

/**
 * Unit tests for [NotificationResurface].
 *
 * Uses Robolectric's real Application context (which provides a working
 * NotificationManager shadow) so that channel IDs, group keys, and notification
 * extras can be inspected from the actually-posted [android.app.Notification].
 *
 * Fix #9: post() now takes pre-extracted primitives instead of a StatusBarNotification.
 * Fix #6: notification ID is Objects.hash(pkg, originalId) — no cross-package collisions.
 *
 * Verifies:
 * - Posts to the [NotificationChannelRegistry.CHANNEL_CURATED] channel.
 * - Copies title and text from the passed primitives.
 * - Falls back to app-label / "1 new notification" when title/text are null.
 * - Sets the group to the source package name.
 * - Notification ID is stable across calls with the same pkg+originalId.
 * - Different-package SBNs with the same originalId produce different notification IDs (fix #6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationResurfaceTest {

    private lateinit var resurfacer: NotificationResurface
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        resurfacer = NotificationResurface(context)
        notificationManager = context.getSystemService(NotificationManager::class.java)

        // Register the curated channel so Robolectric tracks posts to it.
        NotificationChannelRegistry(context).registerAll()
    }

    // ── Helper builders ──────────────────────────────────────────────────────

    private fun record(pkg: String = "com.example.app") = NotificationRecord(
        packageName = pkg,
        disposition = "resurfaced",
    )

    /**
     * Computes the notification ID the resurfacer will assign.
     * Fix #6: Objects.hash(pkg, originalId).and(Int.MAX_VALUE)
     */
    private fun expectedNotifId(pkg: String, originalId: Int): Int =
        Objects.hash(pkg, originalId).and(Int.MAX_VALUE)

    /** Returns the active notification posted under [NotificationResurface.TAG_RESURFACE]. */
    private fun getPostedNotification(pkg: String, originalId: Int): android.app.Notification? {
        val notifId = expectedNotifId(pkg, originalId)
        return notificationManager.activeNotifications.firstOrNull {
            it.tag == NotificationResurface.TAG_RESURFACE && it.id == notifId
        }?.notification
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `posts notification to lithium_curated channel`() {
        resurfacer.post(
            record = record(),
            sbnKey = "com.example.app|1|tag",
            pkg = "com.example.app",
            originalTitle = "Test Title",
            originalText = "Test body text",
            originalId = 1,
        )

        val posted = getPostedNotification("com.example.app", 1)
        assertNotNull("A notification must have been posted", posted)
        assertEquals(NotificationChannelRegistry.CHANNEL_CURATED, posted!!.channelId)
    }

    @Test
    fun `copies title and text from passed primitives`() {
        resurfacer.post(
            record = record(),
            sbnKey = "com.example.app|1|tag",
            pkg = "com.example.app",
            originalTitle = "Hello World",
            originalText = "This is the body",
            originalId = 1,
        )

        val posted = getPostedNotification("com.example.app", 1)
        assertNotNull("A notification must have been posted", posted)
        val extras = posted!!.extras
        assertEquals("Hello World", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("This is the body", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun `null title and text falls back to app label and default text`() {
        resurfacer.post(
            record = record("com.example.app"),
            sbnKey = "com.example.app|1|tag",
            pkg = "com.example.app",
            originalTitle = null,
            originalText = null,
            originalId = 1,
        )

        val posted = getPostedNotification("com.example.app", 1)
        assertNotNull("A notification must have been posted", posted)
        val extras = posted!!.extras
        val postedTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val postedText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        // Fallback title is the app label or package name — must not be blank
        assertFalse("Fallback title must not be blank", postedTitle.isNullOrBlank())
        assertEquals("Fallback text must be '1 new notification'", "1 new notification", postedText)
    }

    @Test
    fun `group is set to source package name`() {
        resurfacer.post(
            record = record("com.example.myapp"),
            sbnKey = "com.example.myapp|1|tag",
            pkg = "com.example.myapp",
            originalTitle = "Test",
            originalText = "Body",
            originalId = 1,
        )

        val posted = getPostedNotification("com.example.myapp", 1)
        assertNotNull("A notification must have been posted", posted)
        assertEquals("com.example.myapp", posted!!.group)
    }

    @Test
    fun `notification ID is stable for the same pkg and originalId`() {
        val pkg = "com.example.app"
        val originalId = 42
        val expectedId = expectedNotifId(pkg, originalId)

        resurfacer.post(
            record = record(pkg),
            sbnKey = "$pkg|$originalId|null",
            pkg = pkg,
            originalTitle = "Title",
            originalText = "Text",
            originalId = originalId,
        )

        val posted = notificationManager.activeNotifications.firstOrNull {
            it.tag == NotificationResurface.TAG_RESURFACE && it.id == expectedId
        }
        assertNotNull("Notification must be posted with stable ID derived from pkg+originalId", posted)
    }

    /**
     * Fix #6: Two different packages posting a notification with the same ID must produce
     * different curated notification IDs. The old sbn.key.hashCode() would differ by SBN key,
     * but Objects.hash(pkg, originalId) explicitly anchors to the package.
     */
    @Test
    fun `different-package SBNs with same originalId produce different notification IDs`() {
        val pkg1 = "com.example.pkgA"
        val pkg2 = "com.example.pkgB"
        val sharedOriginalId = 99

        val id1 = expectedNotifId(pkg1, sharedOriginalId)
        val id2 = expectedNotifId(pkg2, sharedOriginalId)

        assertFalse(
            "Different packages with same originalId must produce different notification IDs",
            id1 == id2
        )

        resurfacer.post(
            record = record(pkg1),
            sbnKey = "$pkg1|$sharedOriginalId|tag",
            pkg = pkg1,
            originalTitle = "From A",
            originalText = "Body A",
            originalId = sharedOriginalId,
        )
        resurfacer.post(
            record = record(pkg2),
            sbnKey = "$pkg2|$sharedOriginalId|tag",
            pkg = pkg2,
            originalTitle = "From B",
            originalText = "Body B",
            originalId = sharedOriginalId,
        )

        val active = notificationManager.activeNotifications
            .filter { it.tag == NotificationResurface.TAG_RESURFACE }
        assertEquals("Both notifications must be active (no collision)", 2, active.size)
    }
}
