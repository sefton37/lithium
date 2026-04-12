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

    // ── Phase 3 additions ─────────────────────────────────────────────────────

    /**
     * Edge-case notification records covering boundary conditions for TierClassifier,
     * ReportGenerator, and ingestion pipeline.
     *
     * Each record has id=0 (Room auto-assigns on insert) since these are used for DB tests.
     */
    fun edgeCaseNotifications(baseTimeMs: Long = System.currentTimeMillis()): List<NotificationRecord> = listOf(
        // null title AND null text — fullText becomes ""; should not NPE
        NotificationRecord(
            packageName = "com.edge.nullboth",
            postedAtMs = baseTimeMs - 1_000,
            title = null,
            text = null,
            channelId = "default",
            isOngoing = false,
        ),
        // empty string title
        NotificationRecord(
            packageName = "com.edge.emptytitle",
            postedAtMs = baseTimeMs - 2_000,
            title = "",
            text = "Some content",
            channelId = "default",
            isOngoing = false,
        ),
        // very long text (1000+ chars) containing a security keyword — still tier 3
        NotificationRecord(
            packageName = "com.edge.longtext",
            postedAtMs = baseTimeMs - 3_000,
            title = "Alert",
            text = "A".repeat(800) + " Your verification code is 123456 " + "B".repeat(200),
            channelId = "security",
            isOngoing = false,
        ),
        // Self-package notification — should always be tier 0 regardless of text
        NotificationRecord(
            packageName = "ai.talkingrock.lithium",
            postedAtMs = baseTimeMs - 4_000,
            title = "Your verification code is 999999",
            text = "Service notification",
            channelId = "service",
            isOngoing = false,
        ),
        // transport category on a messaging package — transport wins, tier 0
        NotificationRecord(
            packageName = "com.google.android.apps.messaging",
            postedAtMs = baseTimeMs - 5_000,
            title = "Music playing",
            text = "Background sync",
            channelId = "transport",
            category = "transport",
            isOngoing = false,
        ),
        // category=call + isFromContact=true → call_known, tier 3
        NotificationRecord(
            packageName = "com.google.android.dialer",
            postedAtMs = baseTimeMs - 6_000,
            title = "Mom",
            text = "Incoming call",
            channelId = "calls",
            category = "call",
            isOngoing = false,
            isFromContact = true,
        ),
        // category=reminder on unknown package → calendar, tier 2
        NotificationRecord(
            packageName = "com.unknown.reminder.app",
            postedAtMs = baseTimeMs - 7_000,
            title = "Meeting",
            text = "In 15 minutes",
            channelId = "reminders",
            category = "reminder",
            isOngoing = false,
        ),
        // isOngoing=true AND security keyword in text — security wins per ordering
        NotificationRecord(
            packageName = "com.google.android.apps.messaging",
            postedAtMs = baseTimeMs - 8_000,
            title = "Ongoing notification",
            text = "Your verification code is 456789",
            channelId = "otp",
            isOngoing = true,
        ),
        // "uninstalled" app — label lookup fails, classifies by package (default tier 2)
        NotificationRecord(
            packageName = "com.uninstalled.ghost.app.xyz",
            postedAtMs = baseTimeMs - 9_000,
            title = "Notification",
            text = "Content from an uninstalled app",
            channelId = "default",
            isOngoing = false,
        ),
        // Duplicate package+channel — same packageName+channelId, posted twice (different ID on insert)
        NotificationRecord(
            packageName = "com.duplicate.app",
            postedAtMs = baseTimeMs - 10_000,
            title = "First",
            text = "First occurrence",
            channelId = "dup_channel",
            isOngoing = false,
        ),
        NotificationRecord(
            packageName = "com.duplicate.app",
            postedAtMs = baseTimeMs - 10_001,
            title = "Second",
            text = "Second occurrence",
            channelId = "dup_channel",
            isOngoing = false,
        ),
        // Very recent post time (within 1ms of sinceMs — boundary test)
        NotificationRecord(
            packageName = "com.edge.recent",
            postedAtMs = baseTimeMs - 1,
            title = "Just now",
            text = "Almost at threshold",
            channelId = "default",
            isOngoing = false,
        ),
        // Post time of 0 (epoch) — should not crash any date arithmetic
        NotificationRecord(
            packageName = "com.edge.epoch",
            postedAtMs = 0L,
            title = "Epoch notification",
            text = "From the dawn of Unix time",
            channelId = "default",
            isOngoing = false,
        ),
    )

    /**
     * Generates [count] synthetic notifications with a realistic tier distribution.
     *
     * Distribution matches the spec in TESTING_STRATEGY.md §3.1:
     *   40% tier 0 (ongoing media, system)
     *   20% tier 1 (LinkedIn, Amazon, marketing)
     *   30% tier 2 (Gmail, calendar, financial, default)
     *   10% tier 3 (SMS from contact, OTP, calls)
     *
     * Each record has:
     * - [tierReason] pre-set (no NULL) simulating post-backfill state
     * - [isOngoing] consistent with tier
     * - [isFromContact] at ~15% for eligible tier-3 packages
     * - [postedAtMs] spread over the last 30 days from [baseTimeMs]
     *
     * Uses seeded [Random] for reproducibility.
     */
    fun largeSyntheticDataset(
        count: Int = 5_000,
        seed: Int = 99,
        baseTimeMs: Long = System.currentTimeMillis(),
    ): List<NotificationRecord> {
        val rng = Random(seed)
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        // Tier-0 packages (40%)
        val tier0Packages = listOf(
            "com.spotify.music" to "media_player",
            "com.google.android.apps.youtube.music" to "media_player",
            "com.google.android.youtube" to "ongoing_persistent",
            "com.google.android.apps.podcasts" to "ongoing_persistent",
            "com.wdjt.android.weather" to "ongoing_persistent",
            "com.android.systemui" to "system_status",
        )

        // Tier-1 packages (20%)
        val tier1Packages = listOf(
            "com.linkedin.android" to "linkedin",
            "com.amazon.mShop.android.shopping" to "amazon_shopping",
            "com.android.vending" to "play_store_update",
            "com.temu" to "marketing_text",
            "com.zzkko" to "marketing_text",
        )

        // Tier-2 packages (30%)
        val tier2Packages = listOf(
            "com.google.android.gm" to "gmail",
            "com.google.android.calendar" to "calendar",
            "com.chase.sig.android" to "financial",
            "com.github.android" to "github",
            "com.microsoft.office.outlook" to "default",
            "com.slack" to "default",
            "com.random.app.alpha" to "default",
            "com.random.app.beta" to "default",
        )

        // Tier-3 packages (10%) — SMS from contact, OTP, calls
        val tier3Packages = listOf(
            "com.google.android.apps.messaging" to "sms_known",
            "com.google.android.dialer" to "call_known",
            "com.azure.authenticator" to "security_2fa",
        )

        return List(count) { index ->
            val tierRoll = rng.nextFloat()
            val (pkg, tierReason, tier, isOngoing, isFromContact) = when {
                tierRoll < 0.40f -> {
                    val (p, r) = tier0Packages.random(rng)
                    // media_player uses dedicated set; others are ongoing
                    val ongoing = p in setOf(
                        "com.spotify.music",
                        "com.google.android.apps.youtube.music",
                        "com.google.android.youtube",
                        "com.google.android.apps.podcasts",
                        "com.wdjt.android.weather",
                    )
                    TierSlot(p, r, 0, ongoing, false)
                }
                tierRoll < 0.60f -> {
                    val (p, r) = tier1Packages.random(rng)
                    TierSlot(p, r, 1, false, false)
                }
                tierRoll < 0.90f -> {
                    val (p, r) = tier2Packages.random(rng)
                    TierSlot(p, r, 2, false, false)
                }
                else -> {
                    val (p, r) = tier3Packages.random(rng)
                    val fromContact = rng.nextFloat() < 0.15f
                    TierSlot(p, r, 3, false, fromContact)
                }
            }

            NotificationRecord(
                id = 0L, // Room auto-assigns
                packageName = pkg,
                postedAtMs = baseTimeMs - rng.nextLong(0, thirtyDaysMs),
                title = syntheticTitle(pkg, tier, rng),
                text = syntheticText(pkg, tier, rng),
                channelId = syntheticChannel(pkg, rng),
                category = null,
                isOngoing = isOngoing,
                isFromContact = isFromContact,
                tier = tier,
                tierReason = tierReason,
            )
        }
    }

    /** Internal data holder for tier slot calculation. */
    private data class TierSlot(
        val pkg: String,
        val tierReason: String,
        val tier: Int,
        val isOngoing: Boolean,
        val isFromContact: Boolean,
    )

    private fun syntheticTitle(pkg: String, tier: Int, rng: Random): String = when (pkg) {
        "com.spotify.music", "com.google.android.apps.youtube.music" ->
            "Now Playing: Track ${rng.nextInt(500)}"
        "com.linkedin.android" -> "LinkedIn"
        "com.amazon.mShop.android.shopping" -> "Amazon"
        "com.google.android.gm" -> listOf("New Email", "RE: Meeting", "Invoice", "Fwd: FYI").random(rng)
        "com.azure.authenticator" -> "Verification"
        "com.google.android.dialer" -> "Incoming Call"
        "com.google.android.apps.messaging" -> listOf("Mom", "Work", "Alex Smith", "Jordan Lee").random(rng)
        else -> "Notification ${rng.nextInt(1000)}"
    }

    private fun syntheticText(pkg: String, tier: Int, rng: Random): String = when {
        tier == 3 && pkg == "com.azure.authenticator" ->
            "Your verification code is ${rng.nextInt(100000, 999999)}"
        tier == 3 && pkg == "com.google.android.apps.messaging" ->
            listOf("Hey!", "Are you free?", "Call me back", "Running late").random(rng)
        tier == 1 && pkg == "com.linkedin.android" ->
            listOf("5 people viewed your profile", "New job matches for you", "Connect with colleagues").random(rng)
        tier == 1 && pkg == "com.amazon.mShop.android.shopping" ->
            listOf("Lightning Deal: 60% off!", "Your wishlist item is on sale", "Deal of the day").random(rng)
        tier == 0 ->
            "Track ${rng.nextInt(200)} — Artist ${rng.nextInt(100)}"
        else ->
            listOf(
                "New message from ${randomUser(rng)}",
                "Meeting in ${rng.nextInt(5, 60)} minutes",
                "Your order has shipped",
                "Update available",
            ).random(rng)
    }

    private fun syntheticChannel(pkg: String, rng: Random): String = when (pkg) {
        "com.spotify.music", "com.google.android.apps.youtube.music" -> "playback"
        "com.google.android.apps.messaging" -> "messages"
        "com.google.android.gm" -> "email"
        "com.linkedin.android" -> "network"
        "com.amazon.mShop.android.shopping" -> listOf("deals", "shipping").random(rng)
        "com.azure.authenticator" -> "otp"
        else -> "default"
    }
}
