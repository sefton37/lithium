package ai.talkingrock.lithium.ai

import ai.talkingrock.lithium.data.model.NotificationRecord
import kotlin.random.Random

/**
 * Factory for generating synthetic notification histories for testing.
 *
 * Each profile simulates a realistic phone usage pattern with appropriate
 * package distributions, ongoing/non-ongoing ratios, contact matching,
 * channel IDs, and notification content.
 *
 * Contact simulation: each profile defines a set of "known contacts" (package+sender
 * combinations). Notifications from these senders have [NotificationRecord.isFromContact]
 * set to true, simulating what [ContactsResolver] would produce against a real contacts DB.
 */
object SyntheticNotifications {

    private var nextId = 1L
    private fun nextId(): Long = nextId++

    fun resetIds() { nextId = 1L }

    // ── Contact lists per profile ────────────────────────────────────────────

    /** Senders that would match a contacts lookup. Per-profile. */
    private val HEAVY_SOCIAL_CONTACTS = setOf(
        "com.instagram.android" to "user_alex",
        "com.instagram.android" to "user_jordan",
        "com.whatsapp" to "Mom",
        "com.whatsapp" to "Dad",
        "com.whatsapp" to "Work Group",
        "org.thoughtcrime.securesms" to "Sarah",
        "org.thoughtcrime.securesms" to "Mike",
    )

    private val BUSINESS_USER_CONTACTS = setOf(
        "com.microsoft.office.outlook" to "John Smith",
        "com.microsoft.office.outlook" to "Sarah Johnson",
        "com.microsoft.office.outlook" to "Team Standup",
        "com.slack" to "Alice Chen",
        "com.slack" to "Bob Park",
        "com.microsoft.teams" to "Project Alpha",
        "com.google.android.gm" to "David Lee",
        "org.thoughtcrime.securesms" to "Wife",
    )

    private val GAMER_CONTACTS = setOf(
        "com.discord" to "raid-crew",
        "com.discord" to "guildmaster",
        "com.whatsapp" to "Gaming Squad",
        "org.thoughtcrime.securesms" to "Best Friend",
    )

    private val MINIMAL_USER_CONTACTS = setOf(
        "com.whatsapp" to "Partner",
        "com.whatsapp" to "Mom",
        "com.google.android.gm" to "Boss",
    )

    private val SPAM_VICTIM_CONTACTS = setOf(
        "com.whatsapp" to "Sister",
        "org.thoughtcrime.securesms" to "Best Friend",
    )

    private val MEDIA_HEAVY_CONTACTS = setOf(
        "com.whatsapp" to "Partner",
        "org.thoughtcrime.securesms" to "Mom",
        "org.thoughtcrime.securesms" to "Dad",
    )

    private val CONTACT_HEAVY_CONTACTS = setOf(
        "com.whatsapp" to "Mom",
        "com.whatsapp" to "Dad",
        "com.whatsapp" to "Sister",
        "com.whatsapp" to "Brother",
        "com.whatsapp" to "Work Group",
        "com.whatsapp" to "Family Group",
        "org.thoughtcrime.securesms" to "Partner",
        "org.thoughtcrime.securesms" to "Best Friend",
        "org.thoughtcrime.securesms" to "Neighbor",
        "com.google.android.gm" to "Boss",
        "com.google.android.gm" to "HR",
        "com.microsoft.office.outlook" to "Team Lead",
        "com.discord" to "College Friends",
    )

    // ── Profiles ─────────────────────────────────────────────────────────────

    /** Heavy social media user: Instagram, TikTok, Reddit, plus messaging. */
    fun heavySocialMedia(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 42): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = HEAVY_SOCIAL_CONTACTS
        return buildList {
            // Instagram: 200 algorithmically-driven, 30 DMs from contacts, 20 likes
            addAll(batch(200, "com.instagram.android", "suggested_reels", "Suggested for you", { "Check out this trending reel from ${randomUser(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(30, "com.instagram.android", "direct", { contactOrRandom(contacts, "com.instagram.android", rng) }, { "sent you a message" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(20, "com.instagram.android", "social", { "${randomUser(rng)}" }, { "liked your photo" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // TikTok: 150 algorithmic
            addAll(batch(150, "com.zhiliaoapp.musically", "content_recommendation", "Trending", { "${randomUser(rng)} posted a new video" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Reddit: 40 trending, 10 reply notifications
            addAll(batch(40, "com.reddit.frontpage", "trending", "Trending on r/all", { "This post is blowing up in r/${randomSubreddit(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(10, "com.reddit.frontpage", "replies", { randomUser(rng) }, { "replied to your comment in r/${randomSubreddit(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // WhatsApp: 40 messages, 15 from contacts
            addAll(batch(40, "com.whatsapp", "messages", { contactOrRandom(contacts, "com.whatsapp", rng) }, { listOf("Hey!", "Are you free?", "Check this out", "LOL", "On my way").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Signal: 15 messages from contacts
            addAll(batch(15, "org.thoughtcrime.securesms", "messages", { contactOrRandom(contacts, "org.thoughtcrime.securesms", rng) }, { listOf("Call me when you can", "Dinner tonight?", "Running late", "See you soon").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Spotify ongoing
            addAll(batch(100, "com.spotify.music", "playback", "Now Playing", { "Artist ${rng.nextInt(50)} — Track ${rng.nextInt(200)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            // System
            addAll(batch(10, "com.android.systemui", "system", "System", { "Battery at ${rng.nextInt(20, 100)}%" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
        }
    }

    /** Business user: Outlook, Slack, Teams-heavy with sparse social. */
    fun businessUser(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 43): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = BUSINESS_USER_CONTACTS
        return buildList {
            // Outlook: 150 emails, ~40% from contacts
            addAll(batch(150, "com.microsoft.office.outlook", "email", { contactOrRandom(contacts, "com.microsoft.office.outlook", rng) }, { listOf("RE: Q4 Planning", "Meeting moved to 3pm", "Action required: Review PR", "FYI: Deploy schedule", "Weekly sync notes").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Slack: 120 messages
            addAll(batch(120, "com.Slack", "messages", { contactOrRandom(contacts, "com.slack", rng) }, { listOf("#dev: CI is green", "#general: Lunch?", "Thread reply", "DM from PM", "@here standup in 5").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Teams: 80 messages
            addAll(batch(80, "com.microsoft.teams", "chat", { contactOrRandom(contacts, "com.microsoft.teams", rng) }, { listOf("Meeting starting", "Can you review this?", "Updated the doc", "Sprint planning moved").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Signal: 10 personal
            addAll(batch(10, "org.thoughtcrime.securesms", "messages", { contactOrRandom(contacts, "org.thoughtcrime.securesms", rng) }, { listOf("Pick up milk", "Kids are at practice", "Dinner at 7?").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Calendar: 15 reminders
            addAll(batch(15, "com.google.android.calendar", "reminders", "Upcoming event", { "Meeting in ${rng.nextInt(5, 30)} minutes" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // OTP/2FA: 5
            addAll(batch(5, "com.azure.authenticator", "otp", "Verification", { "Your code is ${rng.nextInt(100000, 999999)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // LinkedIn: 15 promotional
            addAll(batch(15, "com.linkedin.android", "network", "LinkedIn", { listOf("5 people viewed your profile", "New job matches", "${randomUser(rng)} endorsed you").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
        }
    }

    /** Gamer: Discord-heavy, game notifications, media controls. */
    fun gamer(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 44): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = GAMER_CONTACTS
        return buildList {
            // Discord: 200 messages
            addAll(batch(200, "com.discord", "messages", { contactOrRandom(contacts, "com.discord", rng) }, { listOf("@everyone Raid in 10!", "LFG ranked", "Nice play!", "Voice chat?", "Server event tonight").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Game notifications: 150 across games
            addAll(batch(50, "com.supercell.clashroyale", "events", "Clash Royale", { listOf("Your clan war has started!", "Free chest available", "New season rewards", "Challenge ending soon").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(40, "com.auxbrain.egginc", "events", "Egg, Inc.", { listOf("Contract available!", "Drone boost ready", "Research complete").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(30, "com.funplus.kingofavalon", "events", "King of Avalon", { listOf("Kingdom under attack!", "Alliance gift ready", "Construction complete").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(30, "com.playstudios.popslots", "events", "Pop Slots", { listOf("Free chips!", "New machine unlocked", "Bonus spin ready").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Twitch: 20 stream alerts
            addAll(batch(20, "tv.twitch.android.app", "live", "Twitch", { "${randomUser(rng)} is live: Playing ${listOf("Valorant", "Minecraft", "Elden Ring", "LoL").random(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // WhatsApp: 15
            addAll(batch(15, "com.whatsapp", "messages", { contactOrRandom(contacts, "com.whatsapp", rng) }, { "yo" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Spotify ongoing: 80
            addAll(batch(80, "com.spotify.music", "playback", "Now Playing", { "Gaming Playlist — Track ${rng.nextInt(200)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
        }
    }

    /** Minimal user: ~30 notifications/day total. */
    fun minimalUser(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 45): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = MINIMAL_USER_CONTACTS
        return buildList {
            addAll(batch(8, "com.whatsapp", "messages", { contactOrRandom(contacts, "com.whatsapp", rng) }, { listOf("Hey", "OK", "See you later").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(5, "com.google.android.gm", "email", { contactOrRandom(contacts, "com.google.android.gm", rng) }, { "RE: ${listOf("Weekend plans", "Doctor appointment", "School update").random(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(3, "com.google.android.dialer", "calls", "Missed call", { "from ${contactOrRandom(contacts, "com.google.android.dialer", rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(5, "com.android.systemui", "system", "System", { listOf("Software update available", "Battery at 15%", "Storage almost full").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(4, "com.wdjt.android.weather", "weather", "Weather", { "Today: ${rng.nextInt(20, 85)}F, ${listOf("Sunny", "Cloudy", "Rain expected").random(rng)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            addAll(batch(5, "com.spotify.music", "playback", "Now Playing", { "Chill Vibes — Track ${rng.nextInt(50)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
        }
    }

    /** Spam victim: 80% promotional, lots of unknown apps. */
    fun spamVictim(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 46): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = SPAM_VICTIM_CONTACTS
        return buildList {
            // Promotional flood
            addAll(batch(60, "com.amazon.mShop.android.shopping", "deals", "Amazon", { listOf("Lightning Deal: 70% off!", "Your wishlist item is on sale", "Deal of the day", "Price drop alert").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(40, "com.temu", "promo", "Temu", { listOf("Free shipping ends tonight!", "90% off everything", "Spin to win!", "Your cart is waiting").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(30, "com.zzkko", "promo", "SHEIN", { listOf("Flash sale!", "New arrivals just dropped", "Points expiring soon", "Exclusive deal for you").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(25, "com.walmart.android", "deals", "Walmart", { listOf("Rollback prices!", "Order now, free pickup", "Weekly ad: save big").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(20, "com.ebay.mobile", "deals", "eBay", { listOf("Bid ending soon!", "Seller lowered the price", "Item back in stock").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Unknown apps
            addAll(batch(30, "com.random.app.one", "default", "CoolApp", { "You have a new reward!" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(20, "com.random.app.two", "default", "SuperApp", { "Don't miss today's bonus!" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Small personal slice
            addAll(batch(10, "com.whatsapp", "messages", { contactOrRandom(contacts, "com.whatsapp", rng) }, { "Hey" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(5, "org.thoughtcrime.securesms", "messages", { contactOrRandom(contacts, "org.thoughtcrime.securesms", rng) }, { "Call me" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Delivery
            addAll(batch(8, "com.amazon.mShop.android.shopping", "shipping", "Amazon", { "Your order has been delivered" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
        }
    }

    /** Media-heavy: 2000+ Spotify, podcast app, YouTube — ongoing flood. */
    fun mediaHeavy(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 47): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = MEDIA_HEAVY_CONTACTS
        return buildList {
            // Spotify: 1500 ongoing media controls
            addAll(batch(1500, "com.spotify.music", "playback", "Now Playing", { "Artist ${rng.nextInt(100)} — Track ${rng.nextInt(500)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            // Podcast: 300 ongoing
            addAll(batch(300, "com.google.android.apps.podcasts", "playback", "Playing", { "Episode ${rng.nextInt(200)}: ${listOf("Tech News", "True Crime", "Comedy Hour", "History Deep Dive").random(rng)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            // YouTube: 200 ongoing
            addAll(batch(200, "com.google.android.youtube", "playback", "Playing", { "${randomUser(rng)} — ${listOf("Tutorial", "Review", "Vlog", "Live Stream").random(rng)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            // Weather ongoing: 50
            addAll(batch(50, "com.wdjt.android.weather", "weather", "Weather", { "Current: ${rng.nextInt(20, 85)}F" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            // Real notifications underneath
            addAll(batch(20, "com.whatsapp", "messages", { contactOrRandom(contacts, "com.whatsapp", rng) }, { "Hey" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(15, "org.thoughtcrime.securesms", "messages", { contactOrRandom(contacts, "org.thoughtcrime.securesms", rng) }, { "Dinner?" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(10, "com.google.android.gm", "email", "Gmail", { "New message from ${randomUser(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(5, "com.android.systemui", "system", "System", { "Battery low" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
        }
    }

    /** Contact-heavy: 60%+ from contacts, WhatsApp/Signal dominant. */
    fun contactHeavy(baseTimeMs: Long = System.currentTimeMillis(), seed: Int = 48): List<NotificationRecord> {
        val rng = Random(seed)
        val contacts = CONTACT_HEAVY_CONTACTS
        return buildList {
            // WhatsApp: 120 messages, mostly from contacts
            addAll(batch(120, "com.whatsapp", "messages", { contactOrRandom(contacts, "com.whatsapp", rng) }, { listOf("Hey!", "OK", "See you", "LOL", "On my way", "Call me", "Running late", "Love you", "Good morning").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Signal: 60 messages
            addAll(batch(60, "org.thoughtcrime.securesms", "messages", { contactOrRandom(contacts, "org.thoughtcrime.securesms", rng) }, { listOf("Hey", "Coming home", "Dinner?", "Pick up kids", "See you soon").random(rng) }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Gmail: 30
            addAll(batch(30, "com.google.android.gm", "email", { contactOrRandom(contacts, "com.google.android.gm", rng) }, { "RE: ${listOf("Weekend", "Project update", "Invoice", "Meeting").random(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Outlook: 20
            addAll(batch(20, "com.microsoft.office.outlook", "email", { contactOrRandom(contacts, "com.microsoft.office.outlook", rng) }, { "RE: ${listOf("Sprint review", "1:1 notes", "Feedback").random(rng)}" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Discord: 15
            addAll(batch(15, "com.discord", "messages", { contactOrRandom(contacts, "com.discord", rng) }, { "New message in #general" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Some promotional noise
            addAll(batch(10, "com.amazon.mShop.android.shopping", "deals", "Amazon", { "Deal of the day" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            addAll(batch(5, "com.linkedin.android", "network", "LinkedIn", { "5 people viewed your profile" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
            // Spotify ongoing
            addAll(batch(40, "com.spotify.music", "playback", "Now Playing", { "Track ${rng.nextInt(100)}" }, baseTimeMs, rng, isOngoing = true, contacts = contacts))
            // System
            addAll(batch(8, "com.android.systemui", "system", "System", { "Update available" }, baseTimeMs, rng, isOngoing = false, contacts = contacts))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun batch(
        count: Int,
        pkg: String,
        channelId: String,
        title: String,
        textFn: () -> String,
        baseTimeMs: Long,
        rng: Random,
        isOngoing: Boolean,
        contacts: Set<Pair<String, String>>
    ): List<NotificationRecord> = batch(count, pkg, channelId, { title }, textFn, baseTimeMs, rng, isOngoing, contacts)

    private fun batch(
        count: Int,
        pkg: String,
        channelId: String,
        titleFn: () -> String,
        textFn: () -> String,
        baseTimeMs: Long,
        rng: Random,
        isOngoing: Boolean,
        contacts: Set<Pair<String, String>>
    ): List<NotificationRecord> = List(count) {
        val title = titleFn()
        val isFromContact = contacts.any { (p, name) ->
            p.equals(pkg, ignoreCase = true) && title.contains(name, ignoreCase = true)
        }
        NotificationRecord(
            id = nextId(),
            packageName = pkg,
            postedAtMs = baseTimeMs - rng.nextLong(0, 24 * 60 * 60 * 1000L),
            title = title,
            text = textFn(),
            channelId = channelId,
            category = null,
            isOngoing = isOngoing,
            isFromContact = isFromContact
        )
    }

    private fun contactOrRandom(
        contacts: Set<Pair<String, String>>,
        pkg: String,
        rng: Random
    ): String {
        val matching = contacts.filter { it.first.equals(pkg, ignoreCase = true) }.map { it.second }
        return if (matching.isNotEmpty() && rng.nextFloat() < 0.5f) {
            matching.random(rng)
        } else {
            randomUser(rng)
        }
    }

    private fun randomUser(rng: Random): String {
        val firsts = listOf("Alex", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Quinn", "Avery", "Blake", "Drew")
        val lasts = listOf("Smith", "Johnson", "Lee", "Chen", "Park", "Kim", "Garcia", "Wilson", "Brown", "Davis")
        return "${firsts.random(rng)} ${lasts.random(rng)}"
    }

    private fun randomSubreddit(rng: Random): String {
        return listOf("technology", "gaming", "funny", "worldnews", "science", "askreddit", "movies", "music", "sports", "pics").random(rng)
    }
}
