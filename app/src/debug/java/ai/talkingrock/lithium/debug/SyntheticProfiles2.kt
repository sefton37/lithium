package ai.talkingrock.lithium.debug

import ai.talkingrock.lithium.data.model.NotificationRecord
import ai.talkingrock.lithium.data.model.SessionRecord

/**
 * Synthetic data profiles 6–9 for debug simulation.
 *
 * Companion to SyntheticProfiles (profiles 1–5). Each profile returns a
 * Pair of notification records and session records. Timestamps are spread
 * across a 24-hour window ending at call time. id=0 on all records so Room
 * autoGenerates keys on insert. aiClassification and aiConfidence are always
 * null — classification is the system's job, not the fixture's.
 */
object SyntheticProfiles2 {

    private val now = System.currentTimeMillis()
    private val H = 3_600_000L // 1 hour in ms

    // -------------------------------------------------------------------------
    // Profile 6 — "Notification Hoarder Hank"
    // 200 notifications, 0 tapped, 0 sessions.
    // Maximum suggestion generation stress test.
    // -------------------------------------------------------------------------

    fun profile6Hoarder(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val notifications = mutableListOf<NotificationRecord>()

        // --- Instagram (30) ---
        val igPkg = "com.instagram.android"
        // Spread: ~every 48 min across 24h for 30 items
        val igBase = now - 24 * H
        val igInterval = (24 * H) / 30
        val igNotifs = listOf(
            // Engagement notifications (tappable social)
            Triple("@alex_photo liked your photo", "Your post is getting popular", "ig_social"),
            Triple("@runner_jane liked your photo", "Check out who's engaging with you", "ig_social"),
            Triple("@foodie_mark liked your photo", "Your travel post is trending", "ig_social"),
            Triple("@design_kim liked your photo", "3 people liked your last post", "ig_social"),
            Triple("@gamer_pro liked your photo", "See who liked your recent post", "ig_social"),
            Triple("@alex_photo commented on your post", "\"Amazing shot! Where was this?\"", "ig_social"),
            Triple("@runner_jane commented on your post", "\"Love this! 🔥\"", "ig_social"),
            Triple("@foodie_mark commented on your post", "\"So beautiful\"", "ig_social"),
            Triple("New follower: sunrise_captures", "sunrise_captures started following you", "ig_social"),
            Triple("New follower: wanderlust_99", "wanderlust_99 started following you", "ig_social"),
            Triple("New follower: daily_tech_tips", "daily_tech_tips started following you", "ig_social"),
            // Explore / algorithmic
            Triple("See what's trending", "Explore today's top posts and reels", "ig_explore"),
            Triple("Based on your activity", "You might like these posts", "ig_explore"),
            Triple("Reels for you", "Catch up on the latest Reels", "ig_explore"),
            Triple("Suggested: Photography accounts", "Discover accounts you might love", "ig_explore"),
            Triple("Don't miss today's stories", "Friends posted new stories", "ig_explore"),
            Triple("Explore: Trending hashtags", "#photography is trending now", "ig_explore"),
            Triple("Weekly recap ready", "See your Instagram activity this week", "ig_explore"),
            Triple("New from accounts you follow", "You have unseen posts", "ig_explore"),
            Triple("New from accounts you follow", "3 accounts you follow posted today", "ig_explore"),
            Triple("New from accounts you follow", "Don't miss posts from people you follow", "ig_explore"),
            // Shopping
            Triple("Shop: Products you'll love", "Deals picked for you", "ig_shopping"),
            Triple("Shop: Products you'll love", "Trending items in your style", "ig_shopping"),
            Triple("Shop: Products you'll love", "Flash sale: Limited time offers", "ig_shopping"),
            // Live
            Triple("Live: Trending broadcasts", "See who's live right now", "ig_explore"),
            Triple("Live: Trending broadcasts", "A creator you follow just went live", "ig_explore"),
            // Additional algorithmic
            Triple("Reels for you", "New viral Reels are waiting", "ig_explore"),
            Triple("Based on your activity", "Explore content similar to what you like", "ig_explore"),
            Triple("See what's trending", "Top posts in your area", "ig_explore"),
            Triple("Don't miss today's stories", "10+ stories from people you follow", "ig_explore"),
        )
        igNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = igPkg,
                    postedAtMs = igBase + i * igInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Facebook (25) ---
        val fbPkg = "com.facebook.katana"
        val fbBase = now - 23 * H
        val fbInterval = (23 * H) / 25
        val fbNotifs = listOf(
            Triple("People you may know: John D.", "Connect with people from your network", "fb_notification"),
            Triple("On this day: 5 years ago", "You have a memory to look back on", "fb_notification"),
            Triple("Suggested group: Local events", "Join the community in your area", "fb_notification"),
            Triple("Marketplace: Items near you", "New listings in your area", "fb_marketplace"),
            Triple("Marketplace: Items near you", "Popular items selling fast", "fb_marketplace"),
            Triple("Friend posted in group", "Mike posted in Weekend Hikers", "fb_notification"),
            Triple("Friend posted in group", "Sarah posted in Book Club", "fb_notification"),
            Triple("Friend posted in group", "Alex posted in Photography Lovers", "fb_notification"),
            Triple("Event: Concert this weekend", "3 friends are going", "fb_notification"),
            Triple("Gaming: New game available", "Try the latest game in your region", "fb_notification"),
            Triple("Reels: Watch and earn stars", "Watch Reels and support your favorite creators", "fb_notification"),
            Triple("Story: 10 friends posted", "10 friends have stories you haven't seen", "fb_notification"),
            Triple("News: Trending article", "This article is trending in your network", "fb_notification"),
            Triple("Memory: 2 years ago", "You have memories to look back on today", "fb_notification"),
            Triple("Suggested: Pages you might like", "Based on your interests", "fb_notification"),
            Triple("Suggested: Pages you might like", "People like you follow these pages", "fb_notification"),
            Triple("Fundraiser: Help local shelter", "Your friends are donating to this cause", "fb_notification"),
            Triple("Poke from user123", "user123 poked you", "fb_notification"),
            Triple("Birthday: 3 friends today", "Wish your friends a happy birthday", "fb_notification"),
            Triple("Video: Suggested for you", "Watch this trending video", "fb_notification"),
            Triple("Video: Suggested for you", "A video you might enjoy", "fb_notification"),
            Triple("Video: Suggested for you", "Popular video in your network", "fb_notification"),
            Triple("Group: Admin approved your post", "Your post is now visible in the group", "fb_notification"),
            Triple("Live: Friend is live now", "Jennifer is broadcasting live", "fb_notification"),
            Triple("Story: Friends are watching", "See what your friends are watching", "fb_notification"),
        )
        fbNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = fbPkg,
                    postedAtMs = fbBase + i * fbInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Twitter / X (20) ---
        val twPkg = "com.twitter.android"
        val twBase = now - 22 * H
        val twInterval = (22 * H) / 20
        val twNotifs = listOf(
            Triple("Trending: #TechNews", "#TechNews is trending in Technology", "twitter_trending"),
            Triple("#1 trending: Celebrity gossip", "See what everyone is talking about", "twitter_trending"),
            Triple("Topics for you: Science", "Top posts in Science today", "twitter_trending"),
            Triple("Spaces: Live discussion on AI", "Join the conversation on AI", "twitter_trending"),
            Triple("What's happening in your network", "See top tweets from people you follow", "twitter_digest"),
            Triple("Moments: Top stories today", "Catch up on today's biggest stories", "twitter_trending"),
            Triple("Daily digest: Personalized", "Your personalized Twitter digest", "twitter_digest"),
            Triple("@celeb posted a thread", "@celeb: A thread on why I changed my mind...", "twitter_trending"),
            Triple("Trending in US: Politics", "See what's trending in Politics", "twitter_trending"),
            Triple("Communities: Join the conversation", "Conversations happening in Tech community", "twitter_trending"),
            Triple("Communities: Join the conversation", "New discussions in Gaming community", "twitter_trending"),
            Triple("Recommended: Based on who you follow", "Posts you might have missed", "twitter_digest"),
            Triple("Recommended: Based on who you follow", "Trending content from your network", "twitter_digest"),
            Triple("Recommended: Based on who you follow", "Popular posts in your interests", "twitter_digest"),
            Triple("Bookmarks suggestion", "Save this trending thread to read later", "twitter_digest"),
            Triple("New feature: Try Communities", "Connect with people who share your interests", "twitter_digest"),
            Triple("Newsletter: Weekly roundup", "Your weekly Twitter newsletter is ready", "twitter_digest"),
            Triple("Blue subscribers: Exclusive post", "Exclusive content from a creator you follow", "twitter_digest"),
            Triple("Promoted: Ad content", "Sponsored content relevant to you", "twitter_trending"),
            Triple("Trending: #MondayMotivation", "See the top #MondayMotivation posts", "twitter_trending"),
        )
        twNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = twPkg,
                    postedAtMs = twBase + i * twInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- TikTok (20) ---
        val ttPkg = "com.zhiliaoapp.musically"
        val ttBase = now - 21 * H
        val ttInterval = (21 * H) / 20
        val ttNotifs = listOf(
            Triple("For You: Viral dance", "This video is blowing up on TikTok", "tiktok_fyp"),
            Triple("Trending sound: Use this audio", "Thousands of creators are using this sound", "tiktok_fyp"),
            Triple("LIVE: Creator near you", "A creator in your area just went live", "tiktok_live"),
            Triple("New from followed: @creator_dance", "@creator_dance just posted a new video", "tiktok_fyp"),
            Triple("New from followed: @foodie_life", "@foodie_life just posted a new video", "tiktok_fyp"),
            Triple("New from followed: @tech_shorts", "@tech_shorts just posted a new video", "tiktok_fyp"),
            Triple("Trending: Cooking hack", "This cooking tip has 10M views", "tiktok_fyp"),
            Triple("Challenge: Try this trend", "The #SilhouetteChallenge is trending", "tiktok_fyp"),
            Triple("Effects: New filter available", "Try the new face filter before everyone else", "tiktok_fyp"),
            Triple("For You: Comedy sketch", "You'll love this viral comedy clip", "tiktok_fyp"),
            Triple("LIVE: Gaming stream", "Your favorite gaming creator is live", "tiktok_live"),
            Triple("Trending: DIY project", "This DIY hack has everyone talking", "tiktok_fyp"),
            Triple("Suggested: Fitness content", "Workout videos picked for you", "tiktok_fyp"),
            Triple("For You: Pet video", "This pet video will make your day", "tiktok_fyp"),
            Triple("New TikTok from friend", "A friend you know just posted", "tiktok_fyp"),
            Triple("Weekly recap: Your stats", "See how your videos performed this week", "tiktok_fyp"),
            Triple("Creator fund: Earnings update", "Check your latest creator earnings", "tiktok_fyp"),
            Triple("LIVE: Music session", "A musician is performing live now", "tiktok_live"),
            Triple("Trending: Fashion tips", "Style tips that are going viral", "tiktok_fyp"),
            Triple("For You: Travel vlog", "This travel video has 5M views", "tiktok_fyp"),
        )
        ttNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = ttPkg,
                    postedAtMs = ttBase + i * ttInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Reddit (15) ---
        val rdPkg = "com.reddit.frontpage"
        val rdBase = now - 20 * H
        val rdInterval = (20 * H) / 15
        val rdNotifs = listOf(
            Triple("Trending in r/technology", "A post in r/technology is blowing up", "reddit_trending"),
            Triple("Hot post in r/funny: 50k upvotes", "\"When you finally understand recursion\"", "reddit_trending"),
            Triple("Popular near you", "See what's trending in your local community", "reddit_trending"),
            Triple("r/AskReddit: Top question today", "\"What's a skill that took years to master?\"", "reddit_trending"),
            Triple("Your comment got 100 upvotes", "People love your comment in r/technology", "reddit_digest"),
            Triple("Trending in r/news", "Major story breaking in r/news", "reddit_trending"),
            Triple("Suggested: r/science", "Based on your interests, you might like r/science", "reddit_digest"),
            Triple("Weekly digest: Your communities", "Top posts from your subscribed communities", "reddit_digest"),
            Triple("New award on your post", "Your post in r/pics received a Gold award", "reddit_digest"),
            Triple("r/todayilearned: Fascinating fact", "TIL: Honey never expires — archaeologists found...", "reddit_trending"),
            Triple("Breaking news in r/worldnews", "Major international development in r/worldnews", "reddit_trending"),
            Triple("Trending in r/gaming", "The gaming community is talking about this", "reddit_trending"),
            Triple("Your post in r/pics reached 1k", "Your photo post hit 1,000 upvotes!", "reddit_digest"),
            Triple("Community highlight: r/movies", "Best posts this week in r/movies", "reddit_digest"),
            Triple("Recap: Top posts this week", "Your weekly Reddit roundup is ready", "reddit_digest"),
        )
        rdNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = rdPkg,
                    postedAtMs = rdBase + i * rdInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Amazon (15) ---
        val amzPkg = "com.amazon.mShop.android.shopping"
        val amzBase = now - 19 * H
        val amzInterval = (19 * H) / 15
        val amzNotifs = listOf(
            Triple("Deal of the Day: 50% off", "Today's deal ends at midnight — shop now", "amazon_deals"),
            Triple("Lightning Deal: Electronics", "Headphones: 38% off — only 2 hours left", "amazon_deals"),
            Triple("Your wishlist item dropped in price", "Sony WH-1000XM5 is now \$279 (was \$349)", "amazon_deals"),
            Triple("Recommended: Based on browsing", "Items you viewed are on sale this week", "amazon_deals"),
            Triple("Prime exclusive: Members only", "Early access to Black Friday deals starts now", "amazon_deals"),
            Triple("Back in stock: Popular item", "An item in your cart is back in stock", "amazon_deals"),
            Triple("New release in Books", "A new book in a category you like just launched", "amazon_deals"),
            Triple("Coupon: \$10 off \$50", "Use this coupon on your next order", "amazon_deals"),
            Triple("Sponsored: Top rated product", "Customers love this top-rated kitchen gadget", "amazon_deals"),
            Triple("Holiday deals: Shop early", "Get ahead on holiday shopping — deals live now", "amazon_deals"),
            Triple("Subscribe & Save: New items", "New Subscribe & Save products available", "amazon_deals"),
            Triple("Warehouse deals: Open box", "Open-box electronics at steep discounts", "amazon_deals"),
            Triple("Best sellers in Home", "See what's trending in Home & Kitchen", "amazon_deals"),
            Triple("Gift ideas: For the holidays", "Gift guides for everyone on your list", "amazon_deals"),
            Triple("Price alert: Item below \$20", "A tracked item is now under your target price", "amazon_deals"),
        )
        amzNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = amzPkg,
                    postedAtMs = amzBase + i * amzInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "promo",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Gmail (20) ---
        val gmailPkg = "com.google.android.gm"
        val gmailBase = now - 18 * H
        val gmailInterval = (18 * H) / 20
        val gmailNotifs = listOf(
            Triple("Newsletter: Morning Brew", "Today's business news and market recap", "gmail_promotions"),
            Triple("Groupon: Deals near you", "Up to 70% off local restaurants and spas", "gmail_promotions"),
            Triple("LinkedIn: Jobs digest", "10 new jobs matching your profile", "gmail_promotions"),
            Triple("Medium: Daily reads", "Stories curated for your interests today", "gmail_promotions"),
            Triple("Substack: New post", "A newsletter you subscribe to just published", "gmail_promotions"),
            Triple("Newsletter: The Hustle", "The business and tech stories you need", "gmail_promotions"),
            Triple("DoorDash: \$5 off today", "Use code SAVE5 on your next order", "gmail_promotions"),
            Triple("Instacart: Free delivery", "Free delivery on orders over \$35 today only", "gmail_promotions"),
            Triple("Newsletter: TLDR Tech", "Today's most interesting tech stories", "gmail_promotions"),
            Triple("Spotify: New releases", "New albums from artists you follow this week", "gmail_promotions"),
            Triple("Airline: Flight deals", "Flights from your city from \$89 — book now", "gmail_promotions"),
            Triple("Hotel: Last minute rates", "Tonight's deals: hotels near you from \$79", "gmail_promotions"),
            Triple("Newsletter: The Skimm", "Today's news, skimmed for you", "gmail_promotions"),
            Triple("Charity: Year-end giving", "Your donation is tax-deductible through Dec 31", "gmail_promotions"),
            Triple("Survey: Take our poll", "Share your opinion and get a \$5 reward", "gmail_promotions"),
            Triple("Insurance: Quote ready", "Your auto insurance quote is ready to view", "gmail_promotions"),
            Triple("Car dealer: Service reminder", "Your vehicle is due for its 30,000-mile service", "gmail_promotions"),
            Triple("Newsletter: Hacker News Digest", "Top Hacker News stories from yesterday", "gmail_promotions"),
            Triple("App store: Subscription renewing", "Your annual subscription renews in 3 days", "gmail_promotions"),
            Triple("Streaming: New season available", "Season 3 is now streaming — watch now", "gmail_promotions"),
        )
        gmailNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = gmailPkg,
                    postedAtMs = gmailBase + i * gmailInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "email",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- YouTube (15) ---
        val ytPkg = "com.google.android.youtube"
        val ytBase = now - 17 * H
        val ytInterval = (17 * H) / 15
        val ytNotifs = listOf(
            Triple("Recommended: Tech review", "New iPhone 17 Pro full review is trending", "yt_recommendations"),
            Triple("Trending: Music video", "Trending music video has 20M views", "yt_recommendations"),
            Triple("New from subscriptions: Creator A", "TechWithTim just uploaded a new video", "yt_subscriptions"),
            Triple("Shorts: Viral clip", "This 30-second clip is everywhere right now", "yt_recommendations"),
            Triple("Live: Streamer you follow", "Markiplier is live — join the stream", "yt_subscriptions"),
            Triple("Mix: Based on watch history", "A playlist picked just for you", "yt_recommendations"),
            Triple("Premiere: Starting in 1 hour", "A channel you subscribe to premieres soon", "yt_subscriptions"),
            Triple("Community post from creator", "Veritasium posted a community update", "yt_subscriptions"),
            Triple("New from subscriptions: Creator B", "Kurzgesagt just uploaded a new video", "yt_subscriptions"),
            Triple("Trending: Comedy special", "Dave Chappelle's new special is trending", "yt_recommendations"),
            Triple("Shorts: Satisfying compilation", "The most satisfying compilation this week", "yt_recommendations"),
            Triple("Recommended: Documentary", "A documentary matching your watch history", "yt_recommendations"),
            Triple("Your comment got hearts", "The creator hearted your comment", "yt_subscriptions"),
            Triple("Channel milestone: 1M subs", "A channel you follow just hit 1M subscribers", "yt_subscriptions"),
            Triple("Weekly highlights: Best of", "The best videos from this week picked for you", "yt_recommendations"),
        )
        ytNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = ytPkg,
                    postedAtMs = ytBase + i * ytInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- LinkedIn (10) ---
        val liPkg = "com.linkedin.android"
        val liBase = now - 16 * H
        val liInterval = (16 * H) / 10
        val liNotifs = listOf(
            Triple("12 people viewed your profile", "See who's been checking out your profile", "linkedin_network"),
            Triple("Job alert: Software Engineer", "New Software Engineer roles matching your profile", "linkedin_network"),
            Triple("John D. endorsed you for Java", "Your connection endorsed a skill on your profile", "linkedin_network"),
            Triple("Sarah M. posted an article", "Sarah M. shared an article: \"The Future of AI\"", "linkedin_network"),
            Triple("Connection request: Recruiter", "A recruiter from Google sent you a request", "linkedin_network"),
            Triple("Jobs you might be interested in", "10 new roles match your experience", "linkedin_network"),
            Triple("Skill assessment: Take the test", "Validate your Python skills with a badge", "linkedin_network"),
            Triple("Company update: Google hiring", "Google posted about open positions", "linkedin_network"),
            Triple("Post trending in your network", "A post about AI is blowing up in your network", "linkedin_network"),
            Triple("Learning: New course available", "\"Generative AI for Everyone\" is now free", "linkedin_network"),
        )
        liNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = liPkg,
                    postedAtMs = liBase + i * liInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Pinterest (10) ---
        val pinPkg = "com.pinterest"
        val pinBase = now - 15 * H
        val pinInterval = (15 * H) / 10
        val pinNotifs = listOf(
            Triple("Pins for you: Home decor", "Trending home decor ideas this season", "pinterest_home"),
            Triple("Trending idea: Fall recipes", "Fall comfort food recipes are trending", "pinterest_home"),
            Triple("Board suggestion: Travel destinations", "Add these travel pins to your board", "pinterest_home"),
            Triple("Try this: DIY craft project", "This DIY craft has 50k saves this week", "pinterest_home"),
            Triple("Trending: Halloween costumes", "Most-saved Halloween costumes this year", "pinterest_home"),
            Triple("Idea pin: Workout routine", "A workout routine with 100k saves", "pinterest_home"),
            Triple("Shop: Products inspired by your pins", "Items similar to your saved pins are on sale", "pinterest_home"),
            Triple("Weekly inspiration: Your feed", "New pins added to your home feed", "pinterest_home"),
            Triple("Creator: New pins from followed", "A Pinner you follow added new content", "pinterest_home"),
            Triple("Trending: Wedding ideas", "Wedding planning ideas with thousands of saves", "pinterest_home"),
        )
        pinNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = pinPkg,
                    postedAtMs = pinBase + i * pinInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Google News (10) ---
        val gnPkg = "com.google.android.apps.magazines"
        val gnBase = now - 14 * H
        val gnInterval = (14 * H) / 10
        val gnNotifs = listOf(
            Triple("Breaking: Major event", "A major event is unfolding — tap for details", "news_breaking"),
            Triple("For you: Tech industry update", "Big news from Silicon Valley this morning", "news_digest"),
            Triple("Trending: Celebrity news", "This celebrity story is dominating the news", "news_digest"),
            Triple("Local: City council decision", "Your city council voted on a major issue", "news_breaking"),
            Triple("Sports: Game highlights", "Last night's game results and top plays", "news_digest"),
            Triple("Weather: Storm warning", "Severe weather advisory in your area", "news_breaking"),
            Triple("Business: Market update", "Markets open lower as investors react to data", "news_digest"),
            Triple("Science: New discovery", "Scientists announce a major new finding", "news_digest"),
            Triple("Opinion: Editorial piece", "A trending opinion piece on AI and society", "news_digest"),
            Triple("Recap: Week in review", "The biggest stories from the past 7 days", "news_digest"),
        )
        gnNotifs.forEachIndexed { i, (title, text, channel) ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = gnPkg,
                    postedAtMs = gnBase + i * gnInterval,
                    title = title,
                    text = text,
                    channelId = channel,
                    category = "news",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Misc unknown apps (10) ---
        val miscBase = now - 13 * H
        val miscInterval = (13 * H) / 10
        data class MiscEntry(
            val pkg: String,
            val title: String,
            val text: String,
            val channel: String,
            val category: String?
        )
        val miscNotifs = listOf(
            MiscEntry("com.weather.forecast", "Severe weather alert", "A storm warning is active in your area", "weather_alerts", "alert"),
            MiscEntry("com.gamedev.puzzlegame", "Daily puzzle ready!", "Your new daily challenge is waiting. Can you solve it?", "game_daily", null),
            MiscEntry("com.fitness.tracker", "You've been idle for 2 hours", "Time to move! Take a short walk to hit your goal.", "fitness_reminders", "reminder"),
            MiscEntry("com.news.local", "Breaking: Local event", "Major local development — tap to read the full story", "local_breaking", "news"),
            MiscEntry("com.music.player", "New playlist for you", "We made a playlist based on your recent listening", "music_discover", null),
            MiscEntry("com.vpn.free", "Your connection is not secure!", "You're on an unsecured network. Protect yourself with VPN.", "vpn_alerts", "alert"),
            MiscEntry("com.cleaner.booster", "Phone running slow? Clean now!", "Free up 2.3 GB of junk files in one tap", "cleaner_promos", null),
            MiscEntry("com.horoscope.daily", "Your daily reading is ready", "Capricorn: Today brings unexpected opportunities...", "horoscope_daily", null),
            MiscEntry("com.recipe.app", "Tonight's dinner idea", "Try this 30-minute pasta recipe the family will love", "recipe_daily", null),
            MiscEntry("com.dating.app", "Someone liked your profile", "A new match is waiting. See who liked you!", "dating_matches", "social"),
        )
        miscNotifs.forEachIndexed { i, entry ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = entry.pkg,
                    postedAtMs = miscBase + i * miscInterval,
                    title = entry.title,
                    text = entry.text,
                    channelId = entry.channel,
                    category = entry.category,
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        return Pair(notifications, emptyList())
    }

    // -------------------------------------------------------------------------
    // Profile 7 — "Parent Pat"
    // 48 total, 32 tapped.
    // ClassDojo 8 (all tapped), WhatsApp 12 (all tapped, all contacts),
    // MyChart 3 (all tapped), Amazon 5 transactional (all tapped),
    // Facebook 8 (3 tapped contacts + 5 engagement),
    // Instagram 5 (3 tapped contacts/social + 2 engagement),
    // Target 4 promo (0 tapped), System 3 (0 tapped).
    // Total tapped: 8+10+3+5+3+3 = 32.
    // -------------------------------------------------------------------------

    fun profile7Parent(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val notifications = mutableListOf<NotificationRecord>()
        val sessions = mutableListOf<SessionRecord>()

        // Timestamps spread across 24h
        val base = now - 24 * H

        // --- ClassDojo (8) — all tapped, school communication ---
        val dojoTimes = listOf(1L, 3L, 5L, 7L, 10L, 14L, 18L, 22L).map { base + it * H }
        val dojoNotifs = listOf(
            Pair("Mrs. Johnson sent a message", "\"Reminder: Book reports due Friday!\""),
            Pair("New Story posted", "Ms. Rivera posted a new class story"),
            Pair("Direct message from teacher", "\"Emma had a great day today!\""),
            Pair("Portfolio update", "2 new portfolio items from Emma's class"),
            Pair("ClassDojo School", "\"School picture day is next Thursday\""),
            Pair("Mrs. Johnson sent a message", "\"Please sign and return the permission slip\""),
            Pair("Attendance notification", "Emma was marked present today"),
            Pair("New class story posted", "Your child's class posted 3 new photos"),
        )
        dojoTimes.forEachIndexed { i, ts ->
            val removedAt = ts + (8 + i * 3) * 60_000L
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.classdojo.android",
                    postedAtMs = ts,
                    title = dojoNotifs[i].first,
                    text = dojoNotifs[i].second,
                    channelId = "dojo_messages",
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = "REASON_CLICK",
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = true
                )
            )
            val sessionStart = removedAt + 3000L
            val duration = (4 + i) * 60_000L
            sessions.add(
                SessionRecord(
                    id = 0,
                    packageName = "com.classdojo.android",
                    startedAtMs = sessionStart,
                    endedAtMs = sessionStart + duration,
                    durationMs = duration,
                    notificationsReceived = 0
                )
            )
        }

        // --- WhatsApp (12) — all tapped, all contacts, family group chats and direct ---
        val waTimes = listOf(0.5, 2.0, 4.0, 6.0, 8.0, 9.5, 11.0, 13.0, 15.0, 17.0, 19.0, 21.0)
            .map { base + (it * H).toLong() }
        val waNotifs = listOf(
            Triple("Mom", "Are you coming to dinner Sunday?", true),
            Triple("Family Group 🏠", "Dad: I'll pick up the kids today", true),
            Triple("Tom (husband)", "Can you grab milk on the way home?", true),
            Triple("Carpool Group", "Sarah: I can't do pickup today, anyone available?", true),
            Triple("Mom", "The kids are adorable in those photos!", true),
            Triple("Tom (husband)", "Running 20 min late, sorry!", true),
            Triple("Family Group 🏠", "Mom: Dinner at 6:30 works for everyone?", true),
            Triple("Sister Jenny", "Happy birthday to Emma! 🎂", true),
            Triple("School Moms Group", "Anyone know if practice is cancelled?", true),
            Triple("Tom (husband)", "Picked up Emma, she's great. Heading home", true),
            Triple("Mom", "Call me when you get a chance ❤️", true),
            Triple("Carpool Group", "Mike: I can cover pickup Thursday", true),
        )
        waTimes.forEachIndexed { i, ts ->
            val removedAt = ts + (2 + i * 2) * 60_000L
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.whatsapp",
                    postedAtMs = ts,
                    title = waNotifs[i].first,
                    text = waNotifs[i].second,
                    channelId = "whatsapp_messages",
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = "REASON_CLICK",
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = waNotifs[i].third
                )
            )
            val sessionStart = removedAt + 2000L
            val duration = (2 + i % 4) * 60_000L
            sessions.add(
                SessionRecord(
                    id = 0,
                    packageName = "com.whatsapp",
                    startedAtMs = sessionStart,
                    endedAtMs = sessionStart + duration,
                    durationMs = duration,
                    notificationsReceived = 0
                )
            )
        }
        // 10 of 12 WhatsApp are tapped — remove sessions for last 2
        // Per spec: 10 tapped (whatsapp contributes 10 to the 32 tapped total)
        // Remove the last 2 sessions to get to 10
        repeat(2) { sessions.removeLastOrNull() }
        // Mark last 2 whatsapp notifications as not tapped
        val waStartIdx = notifications.size - 2
        for (j in waStartIdx until notifications.size) {
            val old = notifications[j]
            notifications[j] = old.copy(removedAtMs = null, removalReason = null)
        }

        // --- MyChart (3) — all tapped, healthcare ---
        val mcTimes = listOf(9L, 12L, 16L).map { base + it * H }
        val mcNotifs = listOf(
            Pair("New test results available", "Your lab results from Dr. Kim are ready to view"),
            Pair("Appointment reminder", "Tomorrow: Dr. Kim at 10:00 AM — Pediatrics"),
            Pair("Message from your care team", "Your child's prescription is ready for pickup"),
        )
        mcTimes.forEachIndexed { i, ts ->
            val removedAt = ts + 15 * 60_000L
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "org.mychart.android.mychart",
                    postedAtMs = ts,
                    title = mcNotifs[i].first,
                    text = mcNotifs[i].second,
                    channelId = "mychart_messages",
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = "REASON_CLICK",
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            val sessionStart = removedAt + 2000L
            val duration = 8 * 60_000L
            sessions.add(
                SessionRecord(
                    id = 0,
                    packageName = "org.mychart.android.mychart",
                    startedAtMs = sessionStart,
                    endedAtMs = sessionStart + duration,
                    durationMs = duration,
                    notificationsReceived = 0
                )
            )
        }

        // --- Amazon transactional (5) — all tapped, order/shipping ---
        val amzTimes = listOf(2L, 6L, 10L, 14L, 20L).map { base + it * H }
        val amzNotifs = listOf(
            Pair("Your order has shipped", "Crayola 64-pack — arriving Thursday via USPS"),
            Pair("Out for delivery today", "Your package will arrive by 8 PM today"),
            Pair("Package delivered", "Your Amazon order was delivered to the front door"),
            Pair("Your order was delivered", "Lunchbox set — see delivery confirmation photo"),
            Pair("Order confirmation", "Thank you! Your order #112-3456789 is confirmed"),
        )
        amzTimes.forEachIndexed { i, ts ->
            val removedAt = ts + 5 * 60_000L
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.amazon.mShop.android.shopping",
                    postedAtMs = ts,
                    title = amzNotifs[i].first,
                    text = amzNotifs[i].second,
                    channelId = "amazon_orders",
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = "REASON_CLICK",
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            val sessionStart = removedAt + 2000L
            val duration = 5 * 60_000L
            sessions.add(
                SessionRecord(
                    id = 0,
                    packageName = "com.amazon.mShop.android.shopping",
                    startedAtMs = sessionStart,
                    endedAtMs = sessionStart + duration,
                    durationMs = duration,
                    notificationsReceived = 0
                )
            )
        }

        // --- Facebook (8) — 3 tapped (contacts), 5 engagement ---
        val fbTimes = listOf(1L, 4L, 7L, 11L, 13L, 16L, 19L, 22L).map { base + it * H }
        data class FbEntry(val title: String, val text: String, val tapped: Boolean, val isContact: Boolean)
        val fbNotifs = listOf(
            FbEntry("Jenny commented on your photo", "Jenny: \"You all look so happy! ❤️\"", true, true),
            FbEntry("Mike tagged you in a post", "Mike tagged you in \"Saturday BBQ\"", true, true),
            FbEntry("Tom posted on your timeline", "Tom: \"Happy anniversary! Love you 💕\"", true, true),
            FbEntry("Suggested group: Parenting 101", "Join 12,000 parents sharing advice", false, false),
            FbEntry("Memory: 3 years ago today", "You have a memory to look back on", false, false),
            FbEntry("People you may know: Lisa R.", "You have 5 mutual friends with Lisa R.", false, false),
            FbEntry("Event: School fundraiser", "3 friends are attending this event", false, false),
            FbEntry("Trending: Article in Education", "This article is trending among parents", false, false),
        )
        fbTimes.forEachIndexed { i, ts ->
            val entry = fbNotifs[i]
            val removedAt = if (entry.tapped) ts + 4 * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.facebook.katana",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = if (entry.isContact) "fb_notification" else "fb_suggestions",
                    category = "social",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = entry.isContact
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = 6 * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.facebook.katana",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Instagram (5) — 3 tapped (contact social), 2 engagement ---
        val igTimes = listOf(3L, 8L, 13L, 18L, 22L).map { base + it * H }
        data class IgEntry(val title: String, val text: String, val tapped: Boolean, val isContact: Boolean)
        val igNotifs = listOf(
            IgEntry("jenny_smith liked your photo", "Your photo is getting attention", true, true),
            IgEntry("@tom_hubby commented", "@tom_hubby: \"Our little ones! ❤️\"", true, true),
            IgEntry("sister_jen started following you", "Your sister started following your account", true, true),
            IgEntry("Suggested: Parenting accounts", "Accounts similar to ones you follow", false, false),
            IgEntry("Reels for you", "Trending parenting Reels picked for you", false, false),
        )
        igTimes.forEachIndexed { i, ts ->
            val entry = igNotifs[i]
            val removedAt = if (entry.tapped) ts + 3 * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.instagram.android",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = if (entry.isContact) "ig_social" else "ig_explore",
                    category = "social",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = entry.isContact
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = 4 * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.instagram.android",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Target promo (4) — none tapped ---
        val tgtTimes = listOf(5L, 10L, 15L, 20L).map { base + it * H }
        val tgtNotifs = listOf(
            Pair("Weekly ad: New savings", "This week's Circle deals are here"),
            Pair("Cartwheel offer expiring", "Your 20% off kids apparel expires tomorrow"),
            Pair("Buy online, pick up today", "Order by 6 PM for same-day pickup"),
            Pair("Flash sale: 30% off toys", "Today only — huge savings in the toy department"),
        )
        tgtTimes.forEachIndexed { i, ts ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.target.ui",
                    postedAtMs = ts,
                    title = tgtNotifs[i].first,
                    text = tgtNotifs[i].second,
                    channelId = "target_deals",
                    category = "promo",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- System (3) — none tapped ---
        data class SysEntry7(val pkg: String, val title: String, val text: String, val channel: String)
        val sysTimes = listOf(6L, 12L, 22L).map { base + it * H }
        val sysNotifs = listOf(
            SysEntry7("android", "System update available", "Android 15.1 security patch is ready to install", "system_updates"),
            SysEntry7("com.android.systemui", "Low battery", "15% battery remaining — connect charger", "system_battery"),
            SysEntry7("com.google.android.gms", "Storage almost full", "4.2 GB remaining — free up space", "system_storage"),
        )
        sysTimes.forEachIndexed { i, ts ->
            val entry = sysNotifs[i]
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = entry.pkg,
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = entry.channel,
                    category = "sys",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        return Pair(notifications, sessions)
    }

    // -------------------------------------------------------------------------
    // Profile 8 — "Gamer Gabe"
    // 73 total, 34 tapped.
    // Discord 25 (15 tapped), Twitch 12 (4 tapped), YouTube 10 (3 tapped),
    // Clash of Clans 8 (6 tapped), Reddit 8 (1 tapped),
    // Steam 5 (2 tapped), Uber Eats 3 (3 tapped), System 2 (0 tapped).
    // -------------------------------------------------------------------------

    fun profile8Gamer(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val notifications = mutableListOf<NotificationRecord>()
        val sessions = mutableListOf<SessionRecord>()

        val base = now - 24 * H

        // --- Discord (25) — 15 tapped ---
        val discordInterval = (24 * H) / 25
        data class DiscordEntry(val title: String, val text: String, val tapped: Boolean, val isContact: Boolean, val channel: String)
        val discordNotifs = listOf(
            // DMs — likely tapped (contacts)
            DiscordEntry("Alex", "\"Yo you on tonight?\"", true, true, "discord_dm"),
            DiscordEntry("Tyler", "\"GG that last match was insane\"", true, true, "discord_dm"),
            DiscordEntry("Jamie", "\"Wanna queue ranked?\"", true, true, "discord_dm"),
            DiscordEntry("Marcus", "\"Check this clip I just posted\"", true, true, "discord_dm"),
            DiscordEntry("Sam", "\"That strat actually worked lmao\"", true, true, "discord_dm"),
            // Server mentions — mostly tapped
            DiscordEntry("Gaming Squad — #general", "Alex: @Gabe you coming to the raid tonight?", true, true, "discord_mentions"),
            DiscordEntry("Gaming Squad — #clips", "Tyler posted a new clip: \"The 1v5 clutch\"", true, true, "discord_mentions"),
            DiscordEntry("Gaming Squad — #announcements", "Admin: Tournament sign-ups open NOW", true, true, "discord_server"),
            DiscordEntry("Valorant Hub — #lfg", "\"LFG Diamond+ for competitive push\"", true, false, "discord_server"),
            DiscordEntry("Gaming Squad — #voice-chat", "Marcus started a voice channel", true, true, "discord_mentions"),
            // Untapped server noise
            DiscordEntry("Clash Clan Discord — #updates", "New update patch notes posted", false, false, "discord_server"),
            DiscordEntry("Tech Talk — #off-topic", "12 new messages in #off-topic", false, false, "discord_server"),
            DiscordEntry("Gaming Squad — #general", "Jamie: anyone tried the new map?", false, true, "discord_server"),
            DiscordEntry("Valorant Hub — #strategy", "New meta guide posted", false, false, "discord_server"),
            DiscordEntry("Gaming News — #releases", "New game announcement thread", false, false, "discord_server"),
            DiscordEntry("Gaming Squad — #memes", "Sam: 😂 check this clip", false, true, "discord_server"),
            DiscordEntry("Twitch Alerts — #live", "@StreamerFavorite is now LIVE", false, false, "discord_server"),
            DiscordEntry("Gaming Squad — #clips", "5 new clips since last visit", false, false, "discord_server"),
            DiscordEntry("GameDev Circle — #resources", "New Unity tutorial shared", false, false, "discord_server"),
            DiscordEntry("Retro Gaming — #trading", "New trade post: SNES collection", false, false, "discord_server"),
            // Additional tapped ones
            DiscordEntry("Alex", "\"Stream starting in 10 — hop on\"", true, true, "discord_dm"),
            DiscordEntry("Tyler", "\"You see the new patch notes?\"", true, true, "discord_dm"),
            DiscordEntry("Gaming Squad — #voice-chat", "Tyler joined the voice channel", true, true, "discord_mentions"),
            DiscordEntry("Gaming Squad — #clips", "Marcus: @Gabe you're in this clip lol", true, true, "discord_mentions"),
            DiscordEntry("Jamie", "\"GGs tonight, rematch tomorrow?\"", true, true, "discord_dm"),
        )
        discordNotifs.forEachIndexed { i, entry ->
            val ts = base + i * discordInterval
            val removedAt = if (entry.tapped) ts + (1 + i % 5) * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.discord",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = entry.channel,
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = entry.isContact
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = (10 + i % 20) * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.discord",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Twitch (12) — 4 tapped ---
        val twitchInterval = (23 * H) / 12
        data class TwitchEntry(val title: String, val text: String, val tapped: Boolean)
        val twitchNotifs = listOf(
            TwitchEntry("StreamerPro is LIVE", "Playing Valorant — ranked grind day 7", true),
            TwitchEntry("NightOwlGaming is LIVE", "Elden Ring DLC first playthrough", true),
            TwitchEntry("ProClips: Top moment", "This clip is going viral on Twitch", true),
            TwitchEntry("ChaoticNeutral is LIVE", "Just Chatting — AMA session", true),
            TwitchEntry("Drops enabled: Play game", "Watch to earn in-game rewards", false),
            TwitchEntry("SpeedRunner42 is LIVE", "World record attempt — live now", false),
            TwitchEntry("Tournament: Watch live", "Valorant Championship semi-finals live", false),
            TwitchEntry("PixelPainter is LIVE", "Creating pixel art from scratch", false),
            TwitchEntry("Suggested streamer", "Based on your watch history", false),
            TwitchEntry("LoFiGamer is LIVE", "Chill gaming session with lo-fi music", false),
            TwitchEntry("Your followed channel is live", "A streamer you follow is now live", false),
            TwitchEntry("Clip recommended for you", "Top clip in a game you watch", false),
        )
        twitchNotifs.forEachIndexed { i, entry ->
            val ts = base + H + i * twitchInterval
            val removedAt = if (entry.tapped) ts + (2 + i) * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "tv.twitch.android.app",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = "twitch_live",
                    category = "social",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = (45 + i * 5) * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "tv.twitch.android.app",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- YouTube (10) — 3 tapped ---
        val ytInterval = (22 * H) / 10
        data class YtEntry(val title: String, val text: String, val tapped: Boolean)
        val ytNotifs = listOf(
            YtEntry("GameAnalysis: New video", "\"Why the new meta is broken — full breakdown\"", true),
            YtEntry("FragHighlights is LIVE", "Valorant ranked — come watch", true),
            YtEntry("ProGamer uploaded", "\"I reached Radiant using ONLY pistols\"", true),
            YtEntry("Recommended: Speedrun", "This speedrun broke the world record", false),
            YtEntry("Trending in Gaming", "Game review with 2M views in 24 hours", false),
            YtEntry("Shorts: Gaming clip", "This clutch moment is going viral", false),
            YtEntry("Mix: Game music", "Lo-fi gaming beats — study/chill playlist", false),
            YtEntry("Recommended: Tech video", "New GPU benchmark — worth the upgrade?", false),
            YtEntry("Channel premiere", "A channel you follow premieres in 30 min", false),
            YtEntry("Weekly highlights", "Top gaming moments from this week", false),
        )
        ytNotifs.forEachIndexed { i, entry ->
            val ts = base + 2 * H + i * ytInterval
            val removedAt = if (entry.tapped) ts + (3 + i) * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.google.android.youtube",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = if (i < 3) "yt_subscriptions" else "yt_recommendations",
                    category = "social",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = (20 + i * 8) * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.google.android.youtube",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Clash of Clans (8) — 6 tapped ---
        val cocInterval = (20 * H) / 8
        data class CocEntry(val title: String, val text: String, val tapped: Boolean)
        val cocNotifs = listOf(
            CocEntry("Clash of Clans", "Your troops are ready to train!", true),
            CocEntry("Clash of Clans", "Your village is under attack!", true),
            CocEntry("Clash of Clans", "Clan War has started — battle now!", true),
            CocEntry("Clash of Clans", "Builder is free — start a new upgrade", true),
            CocEntry("Clash of Clans", "Clan Games: Event started — earn rewards", true),
            CocEntry("Clash of Clans", "Your Shield is about to expire!", true),
            CocEntry("Clash of Clans", "Season Pass: New month, new rewards", false),
            CocEntry("Clash of Clans", "Special offer: 50% off Gems today only", false),
        )
        cocNotifs.forEachIndexed { i, entry ->
            val ts = base + 3 * H + i * cocInterval
            val removedAt = if (entry.tapped) ts + (1 + i) * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.supercell.clashofclans",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = "coc_gameplay",
                    category = null,
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = (15 + i * 5) * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.supercell.clashofclans",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Reddit (8) — 1 tapped ---
        val rdInterval = (19 * H) / 8
        data class RdEntry(val title: String, val text: String, val tapped: Boolean)
        val rdNotifs = listOf(
            RdEntry("r/Competitiveoverwatch", "Hot post: New hero analysis — 30k upvotes", false),
            RdEntry("r/gaming", "Trending: Game of the year debate thread", false),
            RdEntry("r/Valorant", "Your comment got 500 upvotes", true),
            RdEntry("r/pcgaming", "\"Should I upgrade my GPU?\" — trending", false),
            RdEntry("r/LivestreamFail", "Viral clip: Streamer has meltdown on live", false),
            RdEntry("Weekly digest", "Top posts from r/gaming, r/Valorant, r/pcgaming", false),
            RdEntry("r/GameDeals", "Steam Sale: 1000+ games on sale now", false),
            RdEntry("r/buildapc", "Suggested: Based on your interests", false),
        )
        rdNotifs.forEachIndexed { i, entry ->
            val ts = base + 4 * H + i * rdInterval
            val removedAt = if (entry.tapped) ts + 5 * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.reddit.frontpage",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = "reddit_trending",
                    category = "social",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = 12 * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.reddit.frontpage",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Steam (5) — 2 tapped ---
        val steamInterval = (18 * H) / 5
        data class SteamEntry(val title: String, val text: String, val tapped: Boolean)
        val steamNotifs = listOf(
            SteamEntry("Steam Sale", "Autumn Sale: Up to 90% off — shop now", true),
            SteamEntry("Friend Activity", "Alex is now playing Cyberpunk 2077", true),
            SteamEntry("New DLC available", "DLC for a game in your library is out", false),
            SteamEntry("Game update", "Elden Ring received a major patch", false),
            SteamEntry("Wishlist alert", "A game on your wishlist is now on sale", false),
        )
        steamNotifs.forEachIndexed { i, entry ->
            val ts = base + 5 * H + i * steamInterval
            val removedAt = if (entry.tapped) ts + (2 + i) * 60_000L else null
            val reason = if (entry.tapped) "REASON_CLICK" else null
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.valvesoftware.android.steam.community",
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = "steam_social",
                    category = null,
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = reason,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            if (entry.tapped) {
                val sessionStart = (removedAt ?: ts) + 2000L
                val duration = (8 + i * 3) * 60_000L
                sessions.add(
                    SessionRecord(
                        id = 0,
                        packageName = "com.valvesoftware.android.steam.community",
                        startedAtMs = sessionStart,
                        endedAtMs = sessionStart + duration,
                        durationMs = duration,
                        notificationsReceived = 0
                    )
                )
            }
        }

        // --- Uber Eats (3) — all tapped ---
        val uberTimes = listOf(7L, 13L, 20L).map { base + it * H }
        val uberNotifs = listOf(
            Pair("Your order is confirmed", "Papa John's — estimated 35 min"),
            Pair("Your food is on the way", "Driver Marcus is 10 minutes away"),
            Pair("Your order has arrived", "Enjoy your meal! Rate your experience"),
        )
        uberTimes.forEachIndexed { i, ts ->
            val removedAt = ts + 2 * 60_000L
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.ubercab.eats",
                    postedAtMs = ts,
                    title = uberNotifs[i].first,
                    text = uberNotifs[i].second,
                    channelId = "ubereats_orders",
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = removedAt,
                    removalReason = "REASON_CLICK",
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
            val sessionStart = removedAt + 2000L
            val duration = 3 * 60_000L
            sessions.add(
                SessionRecord(
                    id = 0,
                    packageName = "com.ubercab.eats",
                    startedAtMs = sessionStart,
                    endedAtMs = sessionStart + duration,
                    durationMs = duration,
                    notificationsReceived = 0
                )
            )
        }

        // --- System (2) — none tapped ---
        data class SysEntry8(val pkg: String, val title: String, val text: String, val channel: String)
        val sysTimes = listOf(6L, 18L).map { base + it * H }
        val sysNotifs = listOf(
            SysEntry8("android", "Low battery: 12%", "Connect your charger soon", "system_battery"),
            SysEntry8("com.android.systemui", "Screenshot saved", "Tap to share or edit your screenshot", "system_media"),
        )
        sysTimes.forEachIndexed { i, ts ->
            val entry = sysNotifs[i]
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = entry.pkg,
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = entry.channel,
                    category = "sys",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        return Pair(notifications, sessions)
    }

    // -------------------------------------------------------------------------
    // Profile 9 — "Edge Case Eddie"
    // 33 total. Tests boundary conditions:
    //   Unknown app (5): null title+text, 1500-char text, emoji overload title
    //   Instagram contacts (3): isFromContact=true on a social platform
    //   Twitter rapid-fire (10): all within 60 seconds
    //   System ongoing (3): isOngoing=true
    //   WhatsApp duplicates (2): identical title+text, 1 hour apart
    //   Gmail duplicates (2): same title, different text
    //   Removed quickly (5): removedAtMs = postedAtMs + 5000, reason="REASON_CANCEL"
    //   Mixed signals (3): Slack person-sounding but isFromContact=false,
    //                      Gmail with OTP, Instagram with password-change
    // -------------------------------------------------------------------------

    fun profile9EdgeCase(): Pair<List<NotificationRecord>, List<SessionRecord>> {
        val notifications = mutableListOf<NotificationRecord>()

        val base = now - 24 * H

        // --- Unknown app — 5 boundary notifications ---

        // 1. Null title AND null text
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.unknown.app.xyz",
                postedAtMs = base + 1 * H,
                title = null,
                text = null,
                channelId = "unknown_channel",
                category = null,
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        // 2. Null title, non-null text
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.unknown.app.xyz",
                postedAtMs = base + 2 * H,
                title = null,
                text = "This notification has no title but does have body text",
                channelId = "unknown_channel",
                category = null,
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        // 3. Non-null title, null text
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.unknown.app.xyz",
                postedAtMs = base + 3 * H,
                title = "Title only, no body",
                text = null,
                channelId = "unknown_channel",
                category = null,
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        // 4. 1500-character text (truncation/overflow boundary test)
        val longText = "A".repeat(200) + " " +
            "This is a very long notification body designed to test truncation and overflow handling in the UI and database layer. " +
            "Notifications in Android can technically carry large payloads and the system must handle them gracefully without crashing, " +
            "truncating unexpectedly, or corrupting adjacent records. This text is intentionally verbose to push past typical display limits. " +
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
            "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat. " +
            "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. " +
            "Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum. " +
            "Final padding to reach 1500 chars: " + "X".repeat(100)
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.unknown.app.verbose",
                postedAtMs = base + 4 * H,
                title = "Very long notification body test",
                text = longText.take(1500),
                channelId = "verbose_channel",
                category = null,
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        // 5. Emoji overload title
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.unknown.app.emoji",
                postedAtMs = base + 5 * H,
                title = "🎉🔥💯🚀🎮🏆🎯🌟💎🎊🥳🤩👑🎁✨🌈🦄🍕🎶🔔🎵🌺🎪🎭🎨🎬🎤🎧🎼",
                text = "An emoji-heavy notification to test text rendering and layout",
                channelId = "emoji_channel",
                category = null,
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        // --- Instagram — contacts on social platform (3) ---
        // isFromContact=true: ContactsResolver matched the sender to a device contact
        val igContactTimes = listOf(6L, 10L, 14L).map { base + it * H }
        val igContactNotifs = listOf(
            Pair("@mom_linda liked your photo", "Your post about the family dinner got a like"),
            Pair("@brother_dan commented on your post", "@brother_dan: \"Bro this is hilarious 😂\""),
            Pair("@highschool_friend_kate started following you", "kate_m__ started following your account"),
        )
        igContactTimes.forEachIndexed { i, ts ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.instagram.android",
                    postedAtMs = ts,
                    title = igContactNotifs[i].first,
                    text = igContactNotifs[i].second,
                    channelId = "ig_social",
                    category = "social",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = true // contact found on social platform — tests contact+social overlap logic
                )
            )
        }

        // --- Twitter rapid-fire — 10 within 60 seconds ---
        // All notifications arrive within a 60-second burst at hour 8
        val rapidFireBase = base + 8 * H
        val rapidFireNotifs = listOf(
            Pair("Trending: #EarthquakeAlert", "A major earthquake was just reported"),
            Pair("Breaking: Major storm warning", "Authorities issue emergency weather alert"),
            Pair("#EarthquakeAlert", "Seismic activity detected — people are posting"),
            Pair("Trending in your area", "Local emergency services are responding"),
            Pair("Safety check: Are you safe?", "Facebook Safety Check activated for your area"),
            Pair("#StayIndoors trending", "Officials urge residents to stay inside"),
            Pair("Breaking: Power outages reported", "Thousands without power across the region"),
            Pair("Emergency alert", "Official emergency broadcast in your region"),
            Pair("#EarthquakeAlert: Updates", "Latest updates from authorities and news"),
            Pair("Trending: Relief efforts", "Community organizing for disaster relief"),
        )
        rapidFireNotifs.forEachIndexed { i, (title, text) ->
            // Spread 10 notifications across 60 seconds (6 seconds apart)
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.twitter.android",
                    postedAtMs = rapidFireBase + i * 6_000L,
                    title = title,
                    text = text,
                    channelId = "twitter_breaking",
                    category = "news",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- System ongoing (3) — isOngoing=true, category="service" ---
        data class OngoingEntry(val pkg: String, val title: String, val text: String, val channel: String)
        val ongoingTimes = listOf(2L, 8L, 16L).map { base + it * H }
        val ongoingNotifs = listOf(
            OngoingEntry("android", "VPN Active", "Secure connection running — tap to manage", "service_vpn"),
            OngoingEntry("com.android.systemui", "Music playing", "Spotify — Lo-Fi Beats playlist", "service_media"),
            OngoingEntry("com.android.providers.downloads", "Downloading: System update", "2.3 GB — 67% complete", "service_download"),
        )
        ongoingTimes.forEachIndexed { i, ts ->
            val entry = ongoingNotifs[i]
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = entry.pkg,
                    postedAtMs = ts,
                    title = entry.title,
                    text = entry.text,
                    channelId = entry.channel,
                    category = "service",
                    isOngoing = true,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- WhatsApp duplicates — exact same title+text, 1 hour apart (2) ---
        val waDupeBase = base + 11 * H
        repeat(2) { i ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.whatsapp",
                    postedAtMs = waDupeBase + i * H,
                    title = "Mom",
                    text = "Are you coming to dinner Sunday?",
                    channelId = "whatsapp_messages",
                    category = "msg",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = true
                )
            )
        }

        // --- Gmail duplicates — same title, different text (2) ---
        val gmailDupeBase = base + 15 * H
        val gmailDupeTexts = listOf(
            "Your order #112-3456 has shipped and will arrive by Thursday",
            "Your order #112-3456 is out for delivery and will arrive today"
        )
        gmailDupeTexts.forEachIndexed { i, text ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = "com.google.android.gm",
                    postedAtMs = gmailDupeBase + i * 4 * H,
                    title = "Amazon: Shipping update for your order",
                    text = text,
                    channelId = "gmail_primary",
                    category = "email",
                    isOngoing = false,
                    removedAtMs = null,
                    removalReason = null,
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Removed quickly (5) — dismissed within 5 seconds, reason="REASON_CANCEL" ---
        val quickTimes = listOf(3L, 6L, 9L, 12L, 17L).map { base + it * H }
        val quickNotifs = listOf(
            Triple("com.instagram.android", "Suggested: Explore accounts", "ig_explore"),
            Triple("com.facebook.katana", "People you may know: 5 suggestions", "fb_suggestions"),
            Triple("com.reddit.frontpage", "Weekly digest: Top posts", "reddit_digest"),
            Triple("com.google.android.apps.magazines", "For you: Trending news", "news_digest"),
            Triple("com.amazon.mShop.android.shopping", "Deal of the Day ending soon", "amazon_deals"),
        )
        quickTimes.forEachIndexed { i, ts ->
            notifications.add(
                NotificationRecord(
                    id = 0,
                    packageName = quickNotifs[i].first,
                    postedAtMs = ts,
                    title = quickNotifs[i].second,
                    text = "Dismissed immediately — tests rapid-swipe behavior",
                    channelId = quickNotifs[i].third,
                    category = "social",
                    isOngoing = false,
                    removedAtMs = ts + 5_000L, // removed 5 seconds after arrival
                    removalReason = "REASON_CANCEL",
                    aiClassification = null,
                    aiConfidence = null,
                    ruleIdMatched = null,
                    isFromContact = false
                )
            )
        }

        // --- Mixed signals (3) ---

        // 1. Slack with personal-sounding message text but isFromContact=false
        //    (sender name resembles a person but wasn't matched in device contacts)
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.Slack",
                postedAtMs = base + 19 * H,
                title = "Jordan Lee in #dev-team",
                text = "Hey, can you review my PR before EOD? I think I fixed the auth bug",
                channelId = "slack_channels",
                category = "msg",
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false // person-sounding but not in device contacts
            )
        )

        // 2. Gmail with OTP / security code — sensitive transactional content
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.google.android.gm",
                postedAtMs = base + 20 * H,
                title = "Your verification code",
                text = "Your one-time passcode is 847291. Valid for 10 minutes. Do not share this code.",
                channelId = "gmail_primary",
                category = "email",
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        // 3. Instagram with password-change / security alert text
        notifications.add(
            NotificationRecord(
                id = 0,
                packageName = "com.instagram.android",
                postedAtMs = base + 21 * H,
                title = "Was this you?",
                text = "Your Instagram password was just changed. If this wasn't you, secure your account immediately.",
                channelId = "ig_security",
                category = "alert",
                isOngoing = false,
                removedAtMs = null,
                removalReason = null,
                aiClassification = null,
                aiConfidence = null,
                ruleIdMatched = null,
                isFromContact = false
            )
        )

        return Pair(notifications, emptyList())
    }
}
