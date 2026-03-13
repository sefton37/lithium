package ai.talkingrock.lithium.debug

import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.SessionRecord

/**
 * Synthetic notification and session data for debug/testing — Profiles 1–5.
 *
 * Each profile represents a realistic user archetype with distinct notification
 * patterns. Notifications span 24 hours ending at "now". Sessions are generated
 * only for tapped notifications (removalReason == "REASON_CLICK").
 *
 * See SyntheticProfiles2.kt for profiles 6–10.
 */
object SyntheticProfiles1 {

    private val H = 3_600_000L  // 1 hour in milliseconds

    // -------------------------------------------------------------------------
    // Internal builder helpers
    // -------------------------------------------------------------------------

    /**
     * Build a notification record. [tapDelaySec] > 0 means the notification was
     * tapped; the function returns a matching SessionRecord alongside it.
     * [dismissDelaySec] > 0 means dismissed (REASON_CANCEL). Both null means
     * the notification is still showing.
     */
    private data class NotifSpec(
        val packageName: String,
        val postedAtMs: Long,
        val title: String?,
        val text: String?,
        val channelId: String?,
        val category: String?,
        val isOngoing: Boolean = false,
        val isFromContact: Boolean = false,
        // mutually exclusive: tap wins if both set
        val tapDelaySec: Long? = null,
        val dismissDelaySec: Long? = null,
        val sessionDurationMs: Long? = null
    )

    private fun buildRecords(specs: List<NotifSpec>): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val notifications = mutableListOf<NotificationRecord>()
        val sessions = mutableListOf<SessionRecord>()

        for (spec in specs) {
            val tapped = spec.tapDelaySec != null
            val dismissed = !tapped && spec.dismissDelaySec != null

            val removedAtMs: Long? = when {
                tapped    -> spec.postedAtMs + (spec.tapDelaySec!! * 1000L)
                dismissed -> spec.postedAtMs + (spec.dismissDelaySec!! * 1000L)
                else      -> null
            }
            val removalReason: String? = when {
                tapped    -> "REASON_CLICK"
                dismissed -> "REASON_CANCEL"
                else      -> null
            }

            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = spec.packageName,
                    postedAtMs = spec.postedAtMs,
                    title = spec.title,
                    text = spec.text,
                    channelId = spec.channelId,
                    category = spec.category,
                    isOngoing = spec.isOngoing,
                    removedAtMs = removedAtMs,
                    removalReason = removalReason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = spec.isFromContact
                )
            )

            if (tapped && removedAtMs != null) {
                val startedAtMs = removedAtMs + 2_000L   // 2 s after tap to ACTIVITY_RESUMED
                val durationMs  = spec.sessionDurationMs ?: 120_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = spec.packageName,
                        startedAtMs = startedAtMs,
                        endedAtMs   = startedAtMs + durationMs,
                        durationMs  = durationMs,
                        notificationsReceived = 0
                    )
                )
            }
        }

        return Pair(notifications, sessions)
    }

    // =========================================================================
    // Profile 1 — "Doom Scroller Dana"
    // 64 notifications: Instagram 25, TikTok 18, Twitter/X 12,
    //                   WhatsApp 4 (contacts), Chase 2, System 3
    // Tapped: 4 WhatsApp + 2 Instagram DMs (contacts) + 1 TikTok + 1 Chase = 8
    // =========================================================================

    fun profile1DoomScroller(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val now = System.currentTimeMillis()

        val specs = listOf(

            // ── Instagram (25) ───────────────────────────────────────────────
            // 23 dismissed / still showing, 2 tapped (DMs from contacts)

            NotifSpec("com.instagram.android", now - 23 * H,
                "Instagram", "jess_creates liked your photo.",
                "likes", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 8),

            NotifSpec("com.instagram.android", now - 22 * H - 30 * 60_000L,
                "Instagram", "mario_photo and 12 others liked your reel.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 22 * H,
                "Instagram", "sunflower_k commented: 'Obsessed with this! 😍'",
                "comments", null,
                dismissDelaySec = 12),

            NotifSpec("com.instagram.android", now - 21 * H - 15 * 60_000L,
                "Instagram", "travel.daily started following you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 21 * H,
                "Instagram", "neon.vibes and 3 others liked your story.",
                "likes", null,
                dismissDelaySec = 6),

            NotifSpec("com.instagram.android", now - 20 * H - 45 * 60_000L,
                "Instagram", "coast.captures commented: 'Where is this?!'",
                "comments", null,
                dismissDelaySec = 9),

            NotifSpec("com.instagram.android", now - 20 * H,
                "Instagram", "pixel_girl_ liked your photo.",
                "likes", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 19 * H - 20 * 60_000L,
                "Instagram", "wellness.hub started following you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 19 * H,
                "Instagram", "retro.art.fan liked your reel.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 18 * H - 50 * 60_000L,
                "Instagram", "cityscape___ and 7 others liked your post.",
                "likes", null,
                dismissDelaySec = 6),

            NotifSpec("com.instagram.android", now - 18 * H,
                "Instagram", "dana_fanpage commented: 'You're so aesthetic!'",
                "comments", null,
                dismissDelaySec = 10),

            NotifSpec("com.instagram.android", now - 17 * H - 10 * 60_000L,
                "Instagram", "goldenhour.pics started following you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 17 * H,
                "Instagram", "moodboard.co liked your photo.",
                "likes", null,
                dismissDelaySec = 3),

            // Instagram DM from contact — tapped
            NotifSpec("com.instagram.android", now - 16 * H - 30 * 60_000L,
                "Sarah M.", "Sarah M.: omg did you see this reel 😂",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 45,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.instagram.android", now - 16 * H,
                "Instagram", "vintage.looks liked your story.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 15 * H - 20 * 60_000L,
                "Instagram", "arthaus.daily and 19 others liked your reel.",
                "likes", null,
                dismissDelaySec = 7),

            NotifSpec("com.instagram.android", now - 15 * H,
                "Instagram", "run.run.run commented: 'Goals! 🔥'",
                "comments", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 14 * H - 40 * 60_000L,
                "Instagram", "soft.palette started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 14 * H,
                "Instagram", "filter.junkie liked your photo.",
                "likes", null,
                dismissDelaySec = 4),

            // Instagram DM from contact — tapped
            NotifSpec("com.instagram.android", now - 13 * H - 15 * 60_000L,
                "Alex P.", "Alex P.: wait are you going to the show?",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 30,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.instagram.android", now - 13 * H,
                "Instagram", "cactus.home liked your reel.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 12 * H - 30 * 60_000L,
                "Instagram", "morning.light_ and 4 others liked your photo.",
                "likes", null,
                dismissDelaySec = 6),

            NotifSpec("com.instagram.android", now - 11 * H,
                "Instagram", "pastel.wave commented: 'This is everything 💫'",
                "comments", null,
                dismissDelaySec = 8),

            NotifSpec("com.instagram.android", now - 6 * H,
                "Instagram", "grain.and.glow started following you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 2 * H,
                "Instagram", "lens.lover_ liked your photo.",
                "likes", null),    // still showing

            // ── TikTok (18) ──────────────────────────────────────────────────
            // 17 dismissed / still showing, 1 tapped

            NotifSpec("com.zhiliaoapp.musically", now - 23 * H - 30 * 60_000L,
                "TikTok", "trendwatch added your video to their favorites.",
                "activity", null,
                dismissDelaySec = 6),

            NotifSpec("com.zhiliaoapp.musically", now - 22 * H - 45 * 60_000L,
                "TikTok", "You have 47 new likes on your recent video.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 21 * H - 30 * 60_000L,
                "TikTok", "dance.it.out commented: 'Slay!! 👑'",
                "comments", null,
                dismissDelaySec = 7),

            NotifSpec("com.zhiliaoapp.musically", now - 20 * H - 15 * 60_000L,
                "TikTok", "viral.vibe is live now. Tap to watch!",
                "live_notifications", null,
                dismissDelaySec = 3),

            NotifSpec("com.zhiliaoapp.musically", now - 19 * H - 45 * 60_000L,
                "TikTok", "chill.beatz and 82 others liked your video.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 19 * H,
                "TikTok", "quirky.moments started following you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.zhiliaoapp.musically", now - 18 * H - 20 * 60_000L,
                "TikTok", "neon.echo commented: 'Teach me this transition!!'",
                "comments", null,
                dismissDelaySec = 9),

            NotifSpec("com.zhiliaoapp.musically", now - 17 * H - 50 * 60_000L,
                "TikTok", "You have 130 new views in the last hour.",
                "video_stats", null,
                dismissDelaySec = 4),

            NotifSpec("com.zhiliaoapp.musically", now - 16 * H - 20 * 60_000L,
                "TikTok", "soundwave.daily liked your video.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 15 * H - 30 * 60_000L,
                "TikTok", "pop.culture.now is live now. Tap to watch!",
                "live_notifications", null,
                dismissDelaySec = 3),

            // TikTok — tapped (curious about trend)
            NotifSpec("com.zhiliaoapp.musically", now - 14 * H - 10 * 60_000L,
                "TikTok", "This sound is trending — your video could go viral!",
                "trending_sounds", null,
                tapDelaySec = 18,
                sessionDurationMs = 34 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 13 * H - 40 * 60_000L,
                "TikTok", "loop.fanatic and 5 others started following you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.zhiliaoapp.musically", now - 12 * H - 20 * 60_000L,
                "TikTok", "You have 210 new likes today. Keep it up!",
                "likes", null,
                dismissDelaySec = 6),

            NotifSpec("com.zhiliaoapp.musically", now - 11 * H - 10 * 60_000L,
                "TikTok", "glitch.effect commented: 'This is cinematic fr'",
                "comments", null,
                dismissDelaySec = 7),

            NotifSpec("com.zhiliaoapp.musically", now - 9 * H,
                "TikTok", "retro.flick is live now. Tap to watch!",
                "live_notifications", null,
                dismissDelaySec = 3),

            NotifSpec("com.zhiliaoapp.musically", now - 6 * H - 30 * 60_000L,
                "TikTok", "snap.moment_ liked your video.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 4 * H,
                "TikTok", "Your video reached 1K views! 🎉",
                "milestones", null,
                dismissDelaySec = 10),

            NotifSpec("com.zhiliaoapp.musically", now - 1 * H - 20 * 60_000L,
                "TikTok", "daily.dose.dance commented: 'More of this please!!'",
                "comments", null),    // still showing

            // ── Twitter / X (12) ─────────────────────────────────────────────
            // all dismissed

            NotifSpec("com.twitter.android", now - 23 * H - 10 * 60_000L,
                "X", "@techleader retweeted your post.",
                "retweets", null,
                dismissDelaySec = 5),

            NotifSpec("com.twitter.android", now - 22 * H - 20 * 60_000L,
                "X", "@genz.speaks liked your reply.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.twitter.android", now - 21 * H - 5 * 60_000L,
                "X", "@culture.pod started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.twitter.android", now - 20 * H - 35 * 60_000L,
                "X", "@breakingnow: Major update on the global summit talks…",
                "breaking_news", null,
                dismissDelaySec = 6),

            NotifSpec("com.twitter.android", now - 19 * H - 50 * 60_000L,
                "X", "@humor.archive liked your tweet.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.twitter.android", now - 18 * H - 40 * 60_000L,
                "X", "@pop.takes retweeted your post.",
                "retweets", null,
                dismissDelaySec = 5),

            NotifSpec("com.twitter.android", now - 17 * H - 25 * 60_000L,
                "X", "@midnight.thoughts replied: 'This is so real omg'",
                "replies", null,
                dismissDelaySec = 7),

            NotifSpec("com.twitter.android", now - 15 * H - 55 * 60_000L,
                "X", "@sports.flash: Game just went into overtime — follow for updates.",
                "breaking_news", null,
                dismissDelaySec = 4),

            NotifSpec("com.twitter.android", now - 13 * H - 30 * 60_000L,
                "X", "@vibes.only started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.twitter.android", now - 10 * H,
                "X", "@astro.fan liked your tweet.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.twitter.android", now - 7 * H,
                "X", "@night.owl__ replied: 'Same honestly 😂'",
                "replies", null,
                dismissDelaySec = 5),

            NotifSpec("com.twitter.android", now - 3 * H,
                "X", "@daily.scroll retweeted your post.",
                "retweets", null),   // still showing

            // ── WhatsApp (4, all contacts, all tapped) ───────────────────────

            NotifSpec("com.whatsapp", now - 20 * H - 10 * 60_000L,
                "Mom", "Mom: Did you eat today? Call me when you can ❤️",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 120,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.whatsapp", now - 14 * H - 50 * 60_000L,
                "Jake 🎸", "Jake 🎸: Yo we still on for Saturday?",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 55,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.whatsapp", now - 9 * H - 30 * 60_000L,
                "Roommates 🏠", "Priya: someone left the dishes again lol",
                "messaging_group", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 200,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.whatsapp", now - 2 * H - 20 * 60_000L,
                "Jake 🎸", "Jake 🎸: nvm found tickets!! 🎉",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 35,
                sessionDurationMs = 2 * 60_000L),

            // ── Chase Bank (2, 1 tapped) ──────────────────────────────────────

            NotifSpec("com.chase.sig.android", now - 11 * H - 40 * 60_000L,
                "Chase", "Your payment of \$148.50 to Spotify posted.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 15),

            NotifSpec("com.chase.sig.android", now - 5 * H - 20 * 60_000L,
                "Chase", "Low balance alert: Your checking account has \$42.17.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 28,
                sessionDurationMs = 90_000L),

            // ── System (3) ────────────────────────────────────────────────────

            NotifSpec("android", now - 22 * H - 55 * 60_000L,
                "Battery saver on", "Battery is low (15%). Turn on Battery Saver.",
                "low_battery", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isOngoing = false,
                dismissDelaySec = 60),

            NotifSpec("android", now - 12 * H - 0 * 60_000L,
                "Software update", "Android 15 QPR2 is ready to install.",
                "system_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 30),

            NotifSpec("com.google.android.gms", now - 1 * H - 0 * 60_000L,
                "Google Play", "2 apps updated in the background.",
                "app_updates", null,
                dismissDelaySec = 10)
        )

        return buildRecords(specs)
    }

    // =========================================================================
    // Profile 2 — "Shopping Spree Sam"
    // 48 notifications: Amazon 17 (12 promo + 5 delivery), Target 8, Shein 6,
    //                   Gmail 8 (6 promo + 2 personal/contacts), Uber Eats 4,
    //                   Chase 3, System 2
    // Tapped: 3 Amazon promo + 5 Amazon delivery + 1 Target + 2 Gmail personal
    //         + 3 Chase = 14
    // =========================================================================

    fun profile2ShoppingSam(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val now = System.currentTimeMillis()

        val specs = listOf(

            // ── Amazon promos (12, 3 tapped) ──────────────────────────────────

            NotifSpec("com.amazon.mShop.android.shopping", now - 23 * H - 30 * 60_000L,
                "Amazon", "Flash Sale! 40% off headphones — today only.",
                "marketing", null,
                tapDelaySec = 22,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 23 * H,
                "Amazon", "Your wish list item dropped in price: Sony WH-1000XM5.",
                "price_alerts", null,
                tapDelaySec = 38,
                sessionDurationMs = 12 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 22 * H - 15 * 60_000L,
                "Amazon", "Lightning Deal ending soon: LEGO Technic 42161.",
                "marketing", null,
                dismissDelaySec = 10),

            NotifSpec("com.amazon.mShop.android.shopping", now - 21 * H - 45 * 60_000L,
                "Amazon", "Recommended for you: Kitchen gadgets under \$25.",
                "recommendations", null,
                dismissDelaySec = 8),

            NotifSpec("com.amazon.mShop.android.shopping", now - 21 * H,
                "Amazon", "Subscribe & Save: Your monthly order ships tomorrow.",
                "subscriptions", null,
                dismissDelaySec = 15),

            NotifSpec("com.amazon.mShop.android.shopping", now - 20 * H - 30 * 60_000L,
                "Amazon", "Deal of the Day: Instant Pot Duo 7-in-1 — 35% off.",
                "marketing", null,
                tapDelaySec = 50,
                sessionDurationMs = 9 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 20 * H,
                "Amazon", "New arrivals in Home & Kitchen — check them out.",
                "marketing", null,
                dismissDelaySec = 6),

            NotifSpec("com.amazon.mShop.android.shopping", now - 19 * H - 10 * 60_000L,
                "Amazon", "Prime Video: New episodes of The Boys are available.",
                "entertainment", null,
                dismissDelaySec = 12),

            NotifSpec("com.amazon.mShop.android.shopping", now - 18 * H - 50 * 60_000L,
                "Amazon", "Your cart has items waiting — complete your purchase.",
                "cart_reminders", null,
                dismissDelaySec = 9),

            NotifSpec("com.amazon.mShop.android.shopping", now - 17 * H - 20 * 60_000L,
                "Amazon", "Coupon clipped: 20% off cleaning supplies.",
                "coupons", null,
                dismissDelaySec = 5),

            NotifSpec("com.amazon.mShop.android.shopping", now - 10 * H,
                "Amazon", "Today's deals: Up to 50% off fitness equipment.",
                "marketing", null,
                dismissDelaySec = 7),

            NotifSpec("com.amazon.mShop.android.shopping", now - 3 * H,
                "Amazon", "Prime member exclusive: Extra 10% off select items.",
                "marketing", null,
                dismissDelaySec = 6),

            // ── Amazon deliveries (5, all tapped) ────────────────────────────

            NotifSpec("com.amazon.mShop.android.shopping", now - 22 * H - 30 * 60_000L,
                "Amazon", "Your package is out for delivery today.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 15,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 18 * H - 0 * 60_000L,
                "Amazon", "Your package was delivered — left at front door.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 10,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 15 * H - 30 * 60_000L,
                "Amazon", "Shipment update: Your order is arriving tomorrow.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 20,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 8 * H - 30 * 60_000L,
                "Amazon", "Delivered: COSRX Snail 96 Mucin Power Essence.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 12,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.amazon.mShop.android.shopping", now - 1 * H - 45 * 60_000L,
                "Amazon", "Your order #114-4927601 has shipped! Arriving Thursday.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 18,
                sessionDurationMs = 4 * 60_000L),

            // ── Target (8, 1 tapped) ──────────────────────────────────────────

            NotifSpec("com.target.ui", now - 23 * H - 15 * 60_000L,
                "Target", "Your Circle Week deals are here — 30% off home.",
                "promotions", null,
                dismissDelaySec = 8),

            NotifSpec("com.target.ui", now - 22 * H - 40 * 60_000L,
                "Target", "Pickup ready: Your order at Target Midtown is ready.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 25,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.target.ui", now - 21 * H - 20 * 60_000L,
                "Target", "Back in stock: Threshold™ Sheet Set you saved.",
                "back_in_stock", null,
                dismissDelaySec = 10),

            NotifSpec("com.target.ui", now - 19 * H - 35 * 60_000L,
                "Target", "Earn 5% back on groceries this weekend with RedCard.",
                "promotions", null,
                dismissDelaySec = 6),

            NotifSpec("com.target.ui", now - 16 * H - 0 * 60_000L,
                "Target", "Today only: Buy 2 get 1 free on all skincare.",
                "promotions", null,
                dismissDelaySec = 7),

            NotifSpec("com.target.ui", now - 13 * H - 10 * 60_000L,
                "Target", "Your wish list item is on sale: Ninja Creami.",
                "price_alerts", null,
                dismissDelaySec = 9),

            NotifSpec("com.target.ui", now - 7 * H - 0 * 60_000L,
                "Target", "Same-day delivery available: Order by 2PM today.",
                "promotions", null,
                dismissDelaySec = 5),

            NotifSpec("com.target.ui", now - 2 * H - 0 * 60_000L,
                "Target", "New arrivals in Furniture — shop the latest styles.",
                "promotions", null),   // still showing

            // ── Shein (6, all dismissed) ──────────────────────────────────────

            NotifSpec("com.zzkko", now - 22 * H - 50 * 60_000L,
                "SHEIN", "🔥 Flash sale starting now — up to 80% off!",
                "promotions", null,
                dismissDelaySec = 5),

            NotifSpec("com.zzkko", now - 20 * H - 45 * 60_000L,
                "SHEIN", "Your items are still in the cart — grab them before they're gone!",
                "cart_reminders", null,
                dismissDelaySec = 6),

            NotifSpec("com.zzkko", now - 17 * H - 30 * 60_000L,
                "SHEIN", "New in: 300+ spring arrivals just dropped.",
                "new_arrivals", null,
                dismissDelaySec = 4),

            NotifSpec("com.zzkko", now - 14 * H - 15 * 60_000L,
                "SHEIN", "Exclusive coupon: 15% off your next order. Tap to claim.",
                "coupons", null,
                dismissDelaySec = 5),

            NotifSpec("com.zzkko", now - 9 * H - 0 * 60_000L,
                "SHEIN", "Points expiring soon — redeem before midnight!",
                "loyalty", null,
                dismissDelaySec = 7),

            NotifSpec("com.zzkko", now - 2 * H - 30 * 60_000L,
                "SHEIN", "Your order #SHE-8821940 has been shipped.",
                "order_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 12),

            // ── Gmail promos (6, all dismissed) ──────────────────────────────

            NotifSpec("com.google.android.gm", now - 23 * H - 5 * 60_000L,
                "Promotions", "Wayfair: Up to 70% off furniture — this weekend only.",
                "email_promotions", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.gm", now - 21 * H - 30 * 60_000L,
                "Promotions", "Chewy: Your auto-ship order is processing.",
                "email_promotions", null,
                dismissDelaySec = 8),

            NotifSpec("com.google.android.gm", now - 18 * H - 10 * 60_000L,
                "Promotions", "Sephora: Your Beauty Insider points are ready to redeem.",
                "email_promotions", null,
                dismissDelaySec = 6),

            NotifSpec("com.google.android.gm", now - 15 * H - 20 * 60_000L,
                "Promotions", "Etsy: A shop you favorited has a sale right now.",
                "email_promotions", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.gm", now - 10 * H - 40 * 60_000L,
                "Promotions", "Ulta Beauty: Double points on all purchases this week.",
                "email_promotions", null,
                dismissDelaySec = 4),

            NotifSpec("com.google.android.gm", now - 4 * H - 0 * 60_000L,
                "Promotions", "ASOS: Your wishlist items are 25% off today.",
                "email_promotions", null,
                dismissDelaySec = 5),

            // ── Gmail personal (2, both tapped, from contacts) ───────────────

            NotifSpec("com.google.android.gm", now - 16 * H - 45 * 60_000L,
                "Taylor H.", "Taylor H.: Re: Weekend brunch — I can do Sunday!",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 90,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.google.android.gm", now - 7 * H - 30 * 60_000L,
                "Dr. Patel's Office", "Appointment reminder: Tuesday March 17 at 10:30 AM.",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 60,
                sessionDurationMs = 3 * 60_000L),

            // ── Uber Eats (4, all dismissed) ──────────────────────────────────

            NotifSpec("com.ubercab.eats", now - 20 * H - 5 * 60_000L,
                "Uber Eats", "Craving something? \$0 delivery from Chipotle right now.",
                "promotions", null,
                dismissDelaySec = 6),

            NotifSpec("com.ubercab.eats", now - 14 * H - 50 * 60_000L,
                "Uber Eats", "Your favorite Pad Thai spot is open — order now.",
                "recommendations", null,
                dismissDelaySec = 5),

            NotifSpec("com.ubercab.eats", now - 8 * H - 0 * 60_000L,
                "Uber Eats", "Lunchtime deal: 20% off your first order today.",
                "promotions", null,
                dismissDelaySec = 7),

            NotifSpec("com.ubercab.eats", now - 1 * H - 15 * 60_000L,
                "Uber Eats", "Dinner sorted: Get free delivery on orders over \$20.",
                "promotions", null,
                dismissDelaySec = 4),

            // ── Chase (3, all tapped) ─────────────────────────────────────────

            NotifSpec("com.chase.sig.android", now - 19 * H - 0 * 60_000L,
                "Chase", "Transaction alert: \$67.43 at Target.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 20,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.chase.sig.android", now - 12 * H - 30 * 60_000L,
                "Chase", "Transaction alert: \$234.99 at Amazon.com.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 18,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.chase.sig.android", now - 4 * H - 10 * 60_000L,
                "Chase", "Your statement is ready — \$1,847.22 due April 14.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 35,
                sessionDurationMs = 5 * 60_000L),

            // ── System (2) ────────────────────────────────────────────────────

            NotifSpec("android", now - 21 * H - 15 * 60_000L,
                "Storage low", "You're running low on storage. Free up space.",
                "storage_warnings", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 20),

            NotifSpec("com.google.android.gms", now - 6 * H - 0 * 60_000L,
                "Google Play", "3 apps updated in the background.",
                "app_updates", null,
                dismissDelaySec = 8)
        )

        return buildRecords(specs)
    }

    // =========================================================================
    // Profile 3 — "Remote Worker Riley"
    // 78 notifications: Slack 35, Teams 18, Gmail 15, Calendar 6, System 4
    // Tapped: 20 Slack + 12 Teams + 8 Gmail + 6 Calendar = 46
    // =========================================================================

    fun profile3RemoteWorkerRiley(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val now = System.currentTimeMillis()

        val specs = listOf(

            // ── Slack (35, 20 tapped) ─────────────────────────────────────────

            NotifSpec("com.Slack", now - 23 * H - 50 * 60_000L,
                "#general", "alex.k: Good morning team! Sprint planning in 10.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 45,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.Slack", now - 23 * H - 30 * 60_000L,
                "Alex K. (DM)", "alex.k: Hey, can you review the PR before standup?",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 30,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.Slack", now - 23 * H - 10 * 60_000L,
                "#engineering", "priya.m: Jenkins build failed on main. Investigating.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 15,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.Slack", now - 22 * H - 45 * 60_000L,
                "#engineering", "bot: ✅ Build #4421 passed on branch feature/auth-refresh.",
                "channels", null,
                dismissDelaySec = 10),

            NotifSpec("com.Slack", now - 22 * H - 20 * 60_000L,
                "Priya M. (DM)", "priya.m: Fixed. Root cause was a missing env var in CI.",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 25,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.Slack", now - 22 * H - 0 * 60_000L,
                "#product", "sam.t: Updated the wireframes — check Figma link in thread.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 60,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.Slack", now - 21 * H - 15 * 60_000L,
                "#engineering", "riley: Updating the auth service now, ETA 30 min.",
                "channels", null,
                dismissDelaySec = 3),

            NotifSpec("com.Slack", now - 21 * H - 0 * 60_000L,
                "Alex K. (DM)", "alex.k: LGTM on the PR, just one nit.",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 20,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.Slack", now - 20 * H - 30 * 60_000L,
                "#general", "hiring.bot: 🎉 New hire Marcus starts Monday!",
                "channels", null,
                dismissDelaySec = 8),

            NotifSpec("com.Slack", now - 20 * H - 10 * 60_000L,
                "#engineering", "priya.m: Riley can you help debug the token refresh?",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 35,
                sessionDurationMs = 15 * 60_000L),

            NotifSpec("com.Slack", now - 19 * H - 45 * 60_000L,
                "#product", "sam.t: Figma updated with your feedback. Thanks!",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 40,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.Slack", now - 19 * H - 20 * 60_000L,
                "#engineering", "bot: PR #882 merged to main by alex.k.",
                "channels", null,
                dismissDelaySec = 6),

            NotifSpec("com.Slack", now - 18 * H - 55 * 60_000L,
                "Marcus L. (DM)", "marcus.l: Hey Riley! Excited to join the team Monday.",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 90,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.Slack", now - 18 * H - 30 * 60_000L,
                "#engineering", "priya.m: Thanks Riley, token refresh is working now!",
                "channels", null,
                dismissDelaySec = 5),

            NotifSpec("com.Slack", now - 18 * H - 0 * 60_000L,
                "#general", "ops.bot: ⚠️ Staging environment experiencing high latency.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 22,
                sessionDurationMs = 10 * 60_000L),

            NotifSpec("com.Slack", now - 17 * H - 30 * 60_000L,
                "#general", "ops.bot: ✅ Staging environment back to normal.",
                "channels", null,
                dismissDelaySec = 7),

            NotifSpec("com.Slack", now - 17 * H - 0 * 60_000L,
                "Alex K. (DM)", "alex.k: Lunch at 12:30? There's a new ramen spot.",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 55,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.Slack", now - 16 * H - 30 * 60_000L,
                "#product", "sam.t: Design review meeting moved to 3PM today.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 40,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.Slack", now - 16 * H - 0 * 60_000L,
                "#engineering", "riley: @channel PR #884 is up for review — auth refresh complete.",
                "channels", null,
                dismissDelaySec = 4),

            NotifSpec("com.Slack", now - 15 * H - 30 * 60_000L,
                "#engineering", "priya.m: Reviewing #884 now.",
                "channels", null,
                dismissDelaySec = 5),

            NotifSpec("com.Slack", now - 15 * H - 0 * 60_000L,
                "Priya M. (DM)", "priya.m: One question on line 142 — left a comment.",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 30,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.Slack", now - 14 * H - 30 * 60_000L,
                "#engineering", "bot: PR #884 approved by priya.m. Ready to merge.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 18,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.Slack", now - 14 * H - 0 * 60_000L,
                "#general", "sam.t: All hands meeting recording is posted in #company-wide.",
                "channels", null,
                dismissDelaySec = 8),

            NotifSpec("com.Slack", now - 13 * H - 30 * 60_000L,
                "#company-wide", "ceo.bot: Q1 goals update — read when you get a chance.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 120,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.Slack", now - 13 * H - 0 * 60_000L,
                "Alex K. (DM)", "alex.k: Good call merging before the design review.",
                "direct_messages", null,
                dismissDelaySec = 5),

            NotifSpec("com.Slack", now - 12 * H - 30 * 60_000L,
                "#product", "sam.t: Design review starting in 5 — join the Zoom.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 12,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.Slack", now - 12 * H - 0 * 60_000L,
                "#engineering", "bot: Scheduled job failed: nightly-cache-clear.",
                "channels", null,
                dismissDelaySec = 6),

            NotifSpec("com.Slack", now - 11 * H - 30 * 60_000L,
                "Priya M. (DM)", "priya.m: I'll handle the cache job. Wrapping up here.",
                "direct_messages", null,
                dismissDelaySec = 4),

            NotifSpec("com.Slack", now - 9 * H - 0 * 60_000L,
                "#engineering", "marcus.l: Hey all! Finishing onboarding docs before Monday.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 75,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.Slack", now - 7 * H - 30 * 60_000L,
                "#general", "bot: 🎂 Today is priya.m's work anniversary — 3 years!",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 20,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.Slack", now - 6 * H - 30 * 60_000L,
                "#engineering", "bot: Dependency update PR opened by renovate[bot] — needs review.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 55,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.Slack", now - 5 * H - 0 * 60_000L,
                "Alex K. (DM)", "alex.k: EOD check-in: you good for tomorrow's demo?",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 40,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.Slack", now - 3 * H - 0 * 60_000L,
                "#engineering", "bot: Nightly build scheduled for 11PM.",
                "channels", null,
                dismissDelaySec = 5),

            NotifSpec("com.Slack", now - 1 * H - 30 * 60_000L,
                "Priya M. (DM)", "priya.m: Cache job fixed. See you tomorrow!",
                "direct_messages", null,
                dismissDelaySec = 6),

            NotifSpec("com.Slack", now - 30 * 60_000L,
                "#engineering", "bot: ✅ Nightly build #4422 passed.",
                "channels", null),   // still showing

            // ── Microsoft Teams (18, 12 tapped) ──────────────────────────────

            NotifSpec("com.microsoft.teams", now - 23 * H - 45 * 60_000L,
                "Weekly Sync", "jordan.b: Agenda for today's sync is pinned in the tab.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 55,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 23 * H - 25 * 60_000L,
                "Jordan B.", "jordan.b: Riley, can you present the Q1 metrics today?",
                "chat", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 30,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 22 * H - 55 * 60_000L,
                "Weekly Sync", "Meeting starting now — tap to join.",
                "meetings", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 8,
                sessionDurationMs = 55 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 21 * H - 50 * 60_000L,
                "Engineering Channel", "dev.bot: Build pipeline status: All green.",
                "channels", null,
                dismissDelaySec = 7),

            NotifSpec("com.microsoft.teams", now - 21 * H - 20 * 60_000L,
                "Jordan B.", "jordan.b: Great presentation! I'll share the recording.",
                "chat", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 25,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 20 * H - 45 * 60_000L,
                "Product Team", "sam.t: Roadmap doc updated — please review by EOD.",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 50,
                sessionDurationMs = 10 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 19 * H - 30 * 60_000L,
                "Engineering Channel", "priya.m: Hotfix deployed to production successfully.",
                "channels", null,
                dismissDelaySec = 8),

            NotifSpec("com.microsoft.teams", now - 18 * H - 45 * 60_000L,
                "Jordan B.", "jordan.b: Heads up — client call moved to 2PM tomorrow.",
                "chat", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 45,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 17 * H - 15 * 60_000L,
                "Product Team", "bot: 📋 Action items from Weekly Sync added to Planner.",
                "channels", null,
                dismissDelaySec = 6),

            NotifSpec("com.microsoft.teams", now - 16 * H - 45 * 60_000L,
                "Design Review", "Meeting in 15 minutes.",
                "meetings", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = false,
                tapDelaySec = 10,
                sessionDurationMs = 48 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 15 * H - 45 * 60_000L,
                "Jordan B.", "jordan.b: Notes from design review sent to the channel.",
                "chat", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 35,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 14 * H - 20 * 60_000L,
                "Engineering Channel", "alex.k: PR #884 is merged — nice work Riley!",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 20,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 12 * H - 50 * 60_000L,
                "Product Team", "sam.t: Anyone available for a quick 15-min sync?",
                "channels", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 40,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 11 * H - 15 * 60_000L,
                "Jordan B.", "jordan.b: Client loved the demo! They want a follow-up.",
                "chat", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 30,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 9 * H - 30 * 60_000L,
                "Engineering Channel", "marcus.l: What's the onboarding doc link again?",
                "channels", null,
                dismissDelaySec = 4),

            NotifSpec("com.microsoft.teams", now - 6 * H - 0 * 60_000L,
                "Jordan B.", "jordan.b: EOD status? Good to go for tomorrow's demo?",
                "chat", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 55,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.microsoft.teams", now - 3 * H - 30 * 60_000L,
                "Engineering Channel", "bot: Tomorrow's client demo scheduled for 10AM.",
                "channels", null,
                dismissDelaySec = 6),

            NotifSpec("com.microsoft.teams", now - 1 * H - 0 * 60_000L,
                "Jordan B.", "jordan.b: Night! See you at 9:45 for demo prep.",
                "chat", null),   // still showing

            // ── Gmail (15, 8 tapped) ──────────────────────────────────────────

            NotifSpec("com.google.android.gm", now - 23 * H - 40 * 60_000L,
                "Jordan B.", "Sprint recap + action items from last week's sync.",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 70,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.google.android.gm", now - 22 * H - 10 * 60_000L,
                "GitHub", "PR #882 was merged — view the diff.",
                "email_notifications", null,
                dismissDelaySec = 8),

            NotifSpec("com.google.android.gm", now - 21 * H - 35 * 60_000L,
                "HR Team", "Benefits open enrollment closes March 31 — action required.",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 95,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.google.android.gm", now - 20 * H - 55 * 60_000L,
                "Jira", "Issue WEB-1204 assigned to you: Fix token refresh edge case.",
                "email_notifications", null,
                dismissDelaySec = 7),

            NotifSpec("com.google.android.gm", now - 20 * H - 15 * 60_000L,
                "Jira", "Issue WEB-1204 status changed to In Review.",
                "email_notifications", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.gm", now - 19 * H - 0 * 60_000L,
                "Jordan B.", "Client call prep notes — please review before 2PM tomorrow.",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 65,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.google.android.gm", now - 17 * H - 45 * 60_000L,
                "GitHub", "PR #884 approved by priya.m — ready to merge.",
                "email_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 40,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.google.android.gm", now - 16 * H - 20 * 60_000L,
                "Confluence", "Page edited: Q1 Engineering Roadmap — jordan.b updated 3 sections.",
                "email_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 80,
                sessionDurationMs = 9 * 60_000L),

            NotifSpec("com.google.android.gm", now - 15 * H - 10 * 60_000L,
                "Promotions", "LinkedIn: You appeared in 14 searches this week.",
                "email_promotions", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.gm", now - 13 * H - 45 * 60_000L,
                "Priya M.", "Re: Cache job — I've opened a ticket for the root cause.",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 55,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.google.android.gm", now - 11 * H - 0 * 60_000L,
                "GitHub", "PR #885 opened by marcus.l — needs review.",
                "email_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 45,
                sessionDurationMs = 12 * 60_000L),

            NotifSpec("com.google.android.gm", now - 8 * H - 30 * 60_000L,
                "Promotions", "O'Reilly: New book releases in software engineering.",
                "email_promotions", null,
                dismissDelaySec = 4),

            NotifSpec("com.google.android.gm", now - 6 * H - 15 * 60_000L,
                "Jordan B.", "Demo prep checklist attached — let me know if anything's missing.",
                "email", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 50,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.google.android.gm", now - 3 * H - 0 * 60_000L,
                "Promotions", "JetBrains: IntelliJ IDEA 2025.1 is now available.",
                "email_promotions", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.gm", now - 1 * H - 0 * 60_000L,
                "GitHub", "Scheduled workflow completed successfully on main.",
                "email_notifications", null),   // still showing

            // ── Calendar (6, all tapped) ──────────────────────────────────────

            NotifSpec("com.google.android.calendar", now - 23 * H - 55 * 60_000L,
                "Weekly Sync", "Starting in 5 minutes — Teams call.",
                "event_reminders", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 10,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.calendar", now - 20 * H - 25 * 60_000L,
                "Design Review", "Starting in 15 minutes — Teams call.",
                "event_reminders", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 12,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.calendar", now - 18 * H - 55 * 60_000L,
                "Lunch with Alex", "Starting in 30 minutes — Hana Ramen on 5th.",
                "event_reminders", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 15,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.calendar", now - 16 * H - 55 * 60_000L,
                "Design Review", "Starting in 5 minutes — Teams call.",
                "event_reminders", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 8,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.calendar", now - 12 * H - 45 * 60_000L,
                "Quick sync with Sam", "Starting in 15 minutes — Google Meet.",
                "event_reminders", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 12,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.calendar", now - 10 * H - 55 * 60_000L,
                "Client Demo Prep", "Tomorrow at 9:45 AM — add prep notes tonight.",
                "event_reminders", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 30,
                sessionDurationMs = 5 * 60_000L),

            // ── System (4) ────────────────────────────────────────────────────

            NotifSpec("android", now - 22 * H - 0 * 60_000L,
                "VPN connected", "Work VPN is active.",
                "vpn", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isOngoing = true,
                dismissDelaySec = null),   // ongoing, never dismissed

            NotifSpec("android", now - 15 * H - 0 * 60_000L,
                "Battery saver off", "Battery charged to 80%. Battery Saver turned off.",
                "low_battery", null,
                dismissDelaySec = 15),

            NotifSpec("com.google.android.gms", now - 7 * H - 0 * 60_000L,
                "Google Play", "4 apps updated in the background.",
                "app_updates", null,
                dismissDelaySec = 8),

            NotifSpec("android", now - 2 * H - 0 * 60_000L,
                "Do Not Disturb", "Scheduled Focus mode ends at 6PM.",
                "dnd_status", null,
                dismissDelaySec = 10)
        )

        return buildRecords(specs)
    }

    // =========================================================================
    // Profile 4 — "Teenage Tyler"
    // 93 notifications: Snapchat 22, Instagram 28, TikTok 20, Messages 8,
    //                   Google Classroom 5, YouTube 10
    // Tapped: 18 Snap + 15 Insta + 12 TikTok + 8 Messages + 5 Classroom + 4 YT
    //         = 62
    // =========================================================================

    fun profile4TeenageTyler(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val now = System.currentTimeMillis()

        val specs = listOf(

            // ── Snapchat (22, 18 tapped) ──────────────────────────────────────

            NotifSpec("com.snapchat.android", now - 23 * H - 50 * 60_000L,
                "jayden_snaps", "jayden_snaps sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 12,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.snapchat.android", now - 23 * H - 35 * 60_000L,
                "emma_rllyreal", "emma_rllyreal sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 10,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.snapchat.android", now - 23 * H - 10 * 60_000L,
                "Group: lunch crew 🍕", "jayden_snaps: yo did you see that",
                "chat_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 20,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.snapchat.android", now - 22 * H - 45 * 60_000L,
                "zoe.official", "zoe.official sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 8,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.snapchat.android", now - 22 * H - 20 * 60_000L,
                "Snapchat", "Your Snap Score went up! You're on a roll 🔥",
                "activity", null,
                dismissDelaySec = 5),

            NotifSpec("com.snapchat.android", now - 21 * H - 55 * 60_000L,
                "marcus_14", "marcus_14 sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 15,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.snapchat.android", now - 21 * H - 30 * 60_000L,
                "Group: lunch crew 🍕", "emma_rllyreal: 😂😂😂",
                "chat_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 18,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.snapchat.android", now - 21 * H - 5 * 60_000L,
                "jayden_snaps", "jayden_snaps sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 9,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.snapchat.android", now - 20 * H - 40 * 60_000L,
                "Snapchat", "🔔 Story update from zoe.official",
                "stories", null,
                dismissDelaySec = 4),

            NotifSpec("com.snapchat.android", now - 20 * H - 15 * 60_000L,
                "zoe.official", "zoe.official sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 11,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.snapchat.android", now - 19 * H - 50 * 60_000L,
                "Group: lunch crew 🍕", "marcus_14: bro no way 💀",
                "chat_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 22,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.snapchat.android", now - 19 * H - 20 * 60_000L,
                "emma_rllyreal", "emma_rllyreal sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 13,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.snapchat.android", now - 18 * H - 55 * 60_000L,
                "Snapchat", "New story from jayden_snaps — watch before it's gone!",
                "stories", null,
                dismissDelaySec = 3),

            NotifSpec("com.snapchat.android", now - 18 * H - 30 * 60_000L,
                "marcus_14", "marcus_14 sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 7,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.snapchat.android", now - 17 * H - 55 * 60_000L,
                "Group: lunch crew 🍕", "jayden_snaps: coming or nah",
                "chat_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 25,
                sessionDurationMs = 9 * 60_000L),

            NotifSpec("com.snapchat.android", now - 17 * H - 20 * 60_000L,
                "zoe.official", "zoe.official sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 14,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.snapchat.android", now - 16 * H - 45 * 60_000L,
                "jayden_snaps", "jayden_snaps sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 10,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.snapchat.android", now - 16 * H - 10 * 60_000L,
                "emma_rllyreal", "emma_rllyreal sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 16,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.snapchat.android", now - 15 * H - 40 * 60_000L,
                "Snapchat", "🔥 Streak reminder: Keep your streak with jayden_snaps!",
                "streaks", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 30,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.snapchat.android", now - 15 * H - 5 * 60_000L,
                "marcus_14", "marcus_14 sent you a Snap!",
                "snap_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 9,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.snapchat.android", now - 14 * H - 30 * 60_000L,
                "Group: lunch crew 🍕", "zoe.official: lmaooo 💀",
                "chat_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 18,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.snapchat.android", now - 13 * H - 50 * 60_000L,
                "Snapchat", "Spotlight: Your snap got 500 views!",
                "activity", null,
                dismissDelaySec = 6),

            // ── Instagram (28, 15 tapped) ─────────────────────────────────────

            NotifSpec("com.instagram.android", now - 23 * H - 45 * 60_000L,
                "Instagram", "jayden_snaps liked your photo.",
                "likes", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 23 * H - 20 * 60_000L,
                "zoe.official", "zoe.official: omg tyler you have to see this reel",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 12,
                sessionDurationMs = 18 * 60_000L),

            NotifSpec("com.instagram.android", now - 22 * H - 50 * 60_000L,
                "Instagram", "emma_rllyreal liked your reel.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 22 * H - 25 * 60_000L,
                "Instagram", "xoxo.content started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 22 * H - 0 * 60_000L,
                "jayden_snaps", "jayden_snaps: bro check my story 😂",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 8,
                sessionDurationMs = 12 * 60_000L),

            NotifSpec("com.instagram.android", now - 21 * H - 35 * 60_000L,
                "Instagram", "night.clips commented: 'W rizz fr'",
                "comments", null,
                dismissDelaySec = 6),

            NotifSpec("com.instagram.android", now - 21 * H - 10 * 60_000L,
                "Instagram", "You have 63 new likes on your latest reel.",
                "likes", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 20,
                sessionDurationMs = 9 * 60_000L),

            NotifSpec("com.instagram.android", now - 20 * H - 45 * 60_000L,
                "Instagram", "marcus_14 liked your story.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 20 * H - 20 * 60_000L,
                "emma_rllyreal", "emma_rllyreal: are you going to kayla's thing",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 15,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.instagram.android", now - 19 * H - 55 * 60_000L,
                "Instagram", "genz.wave started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 19 * H - 30 * 60_000L,
                "Instagram", "skate.lab_ commented: 'Hardflip tutorial when?'",
                "comments", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 22,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.instagram.android", now - 19 * H - 5 * 60_000L,
                "Instagram", "You have 120 new likes today. 🔥",
                "likes", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 16,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.instagram.android", now - 18 * H - 40 * 60_000L,
                "zoe.official", "zoe.official: wait come look at this",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 10,
                sessionDurationMs = 14 * 60_000L),

            NotifSpec("com.instagram.android", now - 18 * H - 15 * 60_000L,
                "Instagram", "lofi.drift started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 17 * H - 50 * 60_000L,
                "Instagram", "jayden_snaps liked your reel.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 17 * H - 25 * 60_000L,
                "marcus_14", "marcus_14: bro ur reel hit different 🔥",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 18,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.instagram.android", now - 17 * H - 0 * 60_000L,
                "Instagram", "skate.world commented: 'Teach me that trick'",
                "comments", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 25,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.instagram.android", now - 16 * H - 30 * 60_000L,
                "Instagram", "daily.streetwear started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 16 * H - 0 * 60_000L,
                "Instagram", "emma_rllyreal liked your photo.",
                "likes", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 19,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.instagram.android", now - 15 * H - 30 * 60_000L,
                "zoe.official", "zoe.official: did you post yet",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 14,
                sessionDurationMs = 6 * 60_000L),

            NotifSpec("com.instagram.android", now - 14 * H - 55 * 60_000L,
                "Instagram", "Your reel reached 2K views! 🎉",
                "milestones", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 16,
                sessionDurationMs = 10 * 60_000L),

            NotifSpec("com.instagram.android", now - 14 * H - 20 * 60_000L,
                "Instagram", "new.skate.page started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 13 * H - 45 * 60_000L,
                "jayden_snaps", "jayden_snaps: let's film tomorrow",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 11,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.instagram.android", now - 13 * H - 10 * 60_000L,
                "Instagram", "chill.clips_ liked your reel.",
                "likes", null,
                dismissDelaySec = 4),

            NotifSpec("com.instagram.android", now - 12 * H - 30 * 60_000L,
                "Instagram", "smooth.grip commented: 'Best skate account rn'",
                "comments", null,
                dismissDelaySec = 5),

            NotifSpec("com.instagram.android", now - 9 * H - 0 * 60_000L,
                "Instagram", "You have 300 new likes this week. Keep posting! 🔥",
                "likes", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 20,
                sessionDurationMs = 7 * 60_000L),

            NotifSpec("com.instagram.android", now - 4 * H - 30 * 60_000L,
                "Instagram", "push.tricks_ started following you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.instagram.android", now - 1 * H - 0 * 60_000L,
                "emma_rllyreal", "emma_rllyreal: ngl your edit was fire",
                "direct_messages", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 9,
                sessionDurationMs = 4 * 60_000L),

            // ── TikTok (20, 12 tapped) ────────────────────────────────────────

            NotifSpec("com.zhiliaoapp.musically", now - 23 * H - 55 * 60_000L,
                "TikTok", "You have 88 new likes on your latest video.",
                "likes", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 14,
                sessionDurationMs = 22 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 23 * H - 30 * 60_000L,
                "TikTok", "skate.daily followed you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.zhiliaoapp.musically", now - 23 * H - 5 * 60_000L,
                "TikTok", "flip.clips commented: 'How long did this take to learn?'",
                "comments", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 18,
                sessionDurationMs = 8 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 22 * H - 40 * 60_000L,
                "TikTok", "This sound is trending — remix your video now.",
                "trending_sounds", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 24,
                sessionDurationMs = 12 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 22 * H - 15 * 60_000L,
                "TikTok", "You have 240 new followers today!",
                "follows", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 12,
                sessionDurationMs = 15 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 21 * H - 50 * 60_000L,
                "TikTok", "tricks.and.tips is live — watch now!",
                "live_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 8,
                sessionDurationMs = 28 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 21 * H - 20 * 60_000L,
                "TikTok", "noscope.skate commented: 'W vid bro 💯'",
                "comments", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 20 * H - 55 * 60_000L,
                "TikTok", "Your video is on the Explore page — check it out!",
                "milestones", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 10,
                sessionDurationMs = 18 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 20 * H - 25 * 60_000L,
                "TikTok", "5K views on your latest video! Keep it up 🔥",
                "milestones", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 15,
                sessionDurationMs = 12 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 19 * H - 55 * 60_000L,
                "TikTok", "grind.crew followed you.",
                "follows", null,
                dismissDelaySec = 4),

            NotifSpec("com.zhiliaoapp.musically", now - 19 * H - 25 * 60_000L,
                "TikTok", "smooth.street commented: 'Drop a tutorial pls'",
                "comments", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 22,
                sessionDurationMs = 9 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 18 * H - 50 * 60_000L,
                "TikTok", "daily.sk8 is live — watch now!",
                "live_notifications", null,
                dismissDelaySec = 4),

            NotifSpec("com.zhiliaoapp.musically", now - 18 * H - 20 * 60_000L,
                "TikTok", "You have 10K total followers now! 🎉",
                "milestones", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 9,
                sessionDurationMs = 20 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 17 * H - 45 * 60_000L,
                "TikTok", "nosegrind.nation commented: 'Goated fr'",
                "comments", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 17 * H - 10 * 60_000L,
                "TikTok", "Check your For You page — new trending videos.",
                "recommendations", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 16,
                sessionDurationMs = 40 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 16 * H - 30 * 60_000L,
                "TikTok", "park.rats_ followed you.",
                "follows", null,
                dismissDelaySec = 3),

            NotifSpec("com.zhiliaoapp.musically", now - 15 * H - 55 * 60_000L,
                "TikTok", "You have 15K total views this week.",
                "video_stats", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 17,
                sessionDurationMs = 10 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 14 * H - 20 * 60_000L,
                "TikTok", "street.sk8board commented: 'Need to collab'",
                "comments", null,
                dismissDelaySec = 5),

            NotifSpec("com.zhiliaoapp.musically", now - 9 * H - 0 * 60_000L,
                "TikTok", "Your video got featured in a collection!",
                "milestones", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 13,
                sessionDurationMs = 16 * 60_000L),

            NotifSpec("com.zhiliaoapp.musically", now - 2 * H - 0 * 60_000L,
                "TikTok", "late.night.sk8 is live — watch now!",
                "live_notifications", null),   // still showing

            // ── Messages (8, all tapped — all from contacts) ──────────────────

            NotifSpec("com.google.android.apps.messaging", now - 23 * H - 15 * 60_000L,
                "Mom", "Mom: Tyler dinner is ready, come downstairs",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 300,
                sessionDurationMs = 90_000L),

            NotifSpec("com.google.android.apps.messaging", now - 21 * H - 0 * 60_000L,
                "Jayden", "Jayden: bro you up",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 25,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 19 * H - 40 * 60_000L,
                "Dad", "Dad: Pick you up at 6 from Marcus's",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 180,
                sessionDurationMs = 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 18 * H - 0 * 60_000L,
                "Jayden", "Jayden: at the spot in 20",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 40,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 16 * H - 20 * 60_000L,
                "Mom", "Mom: Be home by 9 ok?",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 120,
                sessionDurationMs = 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 14 * H - 0 * 60_000L,
                "Emma", "Emma: are you coming tonight or not",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 35,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 10 * H - 30 * 60_000L,
                "Dad", "Dad: Leaving in 10, come outside",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 45,
                sessionDurationMs = 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 3 * H - 0 * 60_000L,
                "Marcus", "Marcus: clip from today goes hard ngl",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 22,
                sessionDurationMs = 5 * 60_000L),

            // ── Google Classroom (5, all tapped) ─────────────────────────────

            NotifSpec("com.google.android.apps.classroom", now - 22 * H - 30 * 60_000L,
                "Algebra II", "Mr. Thompson posted an assignment: Chapter 9 Review.",
                "assignment_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 75,
                sessionDurationMs = 5 * 60_000L),

            NotifSpec("com.google.android.apps.classroom", now - 19 * H - 10 * 60_000L,
                "English Lit", "Ms. Rivera: Essay draft due Friday — check the rubric.",
                "announcement_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 90,
                sessionDurationMs = 4 * 60_000L),

            NotifSpec("com.google.android.apps.classroom", now - 15 * H - 20 * 60_000L,
                "AP History", "Mr. Chen graded your quiz: 88/100. Nice work!",
                "grade_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 20,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.google.android.apps.classroom", now - 10 * H - 0 * 60_000L,
                "Algebra II", "Reminder: Chapter 9 Review due tomorrow.",
                "assignment_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 60,
                sessionDurationMs = 35 * 60_000L),

            NotifSpec("com.google.android.apps.classroom", now - 5 * H - 0 * 60_000L,
                "English Lit", "Ms. Rivera commented on your draft.",
                "comment_notifications", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 45,
                sessionDurationMs = 8 * 60_000L),

            // ── YouTube (10, 4 tapped) ────────────────────────────────────────

            NotifSpec("com.google.android.youtube", now - 22 * H - 10 * 60_000L,
                "YouTube", "skate.universe posted: 'BEST TRICKS OF 2025 🔥'",
                "subscription_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 20,
                sessionDurationMs = 18 * 60_000L),

            NotifSpec("com.google.android.youtube", now - 21 * H - 0 * 60_000L,
                "YouTube", "nollie.nation posted: 'Park Session — Uncut Footage'",
                "subscription_updates", null,
                dismissDelaySec = 6),

            NotifSpec("com.google.android.youtube", now - 19 * H - 30 * 60_000L,
                "YouTube", "grind.spot posted: 'Street skating downtown LA'",
                "subscription_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 25,
                sessionDurationMs = 22 * 60_000L),

            NotifSpec("com.google.android.youtube", now - 17 * H - 0 * 60_000L,
                "YouTube", "YouTube Shorts: Videos we think you'll love.",
                "recommendations", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.youtube", now - 14 * H - 45 * 60_000L,
                "YouTube", "smooth.concrete posted: 'Manual pad tutorial for beginners'",
                "subscription_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 18,
                sessionDurationMs = 20 * 60_000L),

            NotifSpec("com.google.android.youtube", now - 12 * H - 0 * 60_000L,
                "YouTube", "New trending: 'Best skate fails of the week'",
                "recommendations", null,
                dismissDelaySec = 4),

            NotifSpec("com.google.android.youtube", now - 9 * H - 30 * 60_000L,
                "YouTube", "deck.life posted: 'How I film my own skating (solo setup)'",
                "subscription_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 22,
                sessionDurationMs = 25 * 60_000L),

            NotifSpec("com.google.android.youtube", now - 7 * H - 0 * 60_000L,
                "YouTube", "YouTube: Watch something new tonight — top picks for you.",
                "recommendations", null,
                dismissDelaySec = 5),

            NotifSpec("com.google.android.youtube", now - 4 * H - 0 * 60_000L,
                "YouTube", "kickflip.archive posted: 'Old school tricks are back'",
                "subscription_updates", null,
                dismissDelaySec = 6),

            NotifSpec("com.google.android.youtube", now - 1 * H - 30 * 60_000L,
                "YouTube", "park.rats_ posted: 'Midnight session highlights'",
                "subscription_updates", null)   // still showing
        )

        return buildRecords(specs)
    }

    // =========================================================================
    // Profile 5 — "Minimalist Maya"
    // 7 total: Messages 3, Chase 2, System 2. 4 tapped. Sparse data path test.
    // =========================================================================

    fun profile5MinimalistMaya(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val now = System.currentTimeMillis()

        val specs = listOf(

            // ── Messages (3, all from contacts, all tapped) ───────────────────

            NotifSpec("com.google.android.apps.messaging", now - 14 * H - 20 * 60_000L,
                "Lena", "Lena: Are we still on for Thursday?",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 180,
                sessionDurationMs = 3 * 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 9 * H - 5 * 60_000L,
                "Lena", "Lena: Perfect, I'll book the place.",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 120,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.google.android.apps.messaging", now - 2 * H - 30 * 60_000L,
                "Dr. Nguyen", "Dr. Nguyen: Your lab results are ready in the portal.",
                "messaging", "android.intent.category.STATUS_BAR_NOTIFICATION",
                isFromContact = true,
                tapDelaySec = 90,
                sessionDurationMs = 5 * 60_000L),

            // ── Chase (2, 1 tapped) ───────────────────────────────────────────

            NotifSpec("com.chase.sig.android", now - 11 * H - 0 * 60_000L,
                "Chase", "Transaction: \$38.40 at Whole Foods Market.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                tapDelaySec = 60,
                sessionDurationMs = 2 * 60_000L),

            NotifSpec("com.chase.sig.android", now - 5 * H - 30 * 60_000L,
                "Chase", "Your scheduled transfer of \$500 to Savings completed.",
                "account_alerts", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 30),

            // ── System (2, neither tapped) ────────────────────────────────────

            NotifSpec("com.google.android.gms", now - 18 * H - 0 * 60_000L,
                "Google Play", "1 app updated in the background.",
                "app_updates", null,
                dismissDelaySec = 15),

            NotifSpec("android", now - 7 * H - 0 * 60_000L,
                "Software update", "Android 15 QPR2 ready to install. Restart when convenient.",
                "system_updates", "android.intent.category.STATUS_BAR_NOTIFICATION",
                dismissDelaySec = 45)
        )

        return buildRecords(specs)
    }
}
