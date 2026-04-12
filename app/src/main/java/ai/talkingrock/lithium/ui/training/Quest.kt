package ai.talkingrock.lithium.ui.training

/**
 * A curated training quest — a focused slice of the dataset where targeted
 * judgments most sharpen the model. Selecting a quest narrows the ambiguous
 * candidate pool to rows matching its filter.
 *
 * Quests are persistent in identity (via [id]) but stateless in definition —
 * progress is derived from judgments tagged with the quest at submit time.
 */
data class Quest(
    val id: String,
    val name: String,
    val description: String,
    val goalXp: Int,
    /** Package-name prefixes. If empty, all packages match. */
    val packagePrefixes: List<String>,
    /** If true, restrict to rows where ai_classification IS NULL. */
    val onlyUnclassified: Boolean = false
)

object Quests {
    /** Sentinel id for "no specific quest — any ambiguous pair." */
    const val FREE_PLAY_ID = "free_play"

    val FREE_PLAY = Quest(
        id = FREE_PLAY_ID,
        name = "Free play",
        description = "Any ambiguous pair across your entire history.",
        goalXp = 0,
        packagePrefixes = emptyList()
    )

    val all: List<Quest> = listOf(
        FREE_PLAY,
        Quest(
            id = "messaging",
            name = "Sharpen Messaging",
            description = "Teach Lithium which SMS and chat notifications actually matter.",
            goalXp = 100,
            packagePrefixes = listOf(
                "com.google.android.apps.messaging",
                "com.whatsapp",
                "org.telegram.",
                "org.thoughtcrime.securesms",
                "com.discord"
            )
        ),
        Quest(
            id = "email",
            name = "Sort Email",
            description = "Separate real email from promotional and newsletter noise.",
            goalXp = 100,
            packagePrefixes = listOf(
                "com.google.android.gm",
                "com.microsoft.office.outlook",
                "com.yahoo.mobile",
                "ch.protonmail"
            )
        ),
        Quest(
            id = "shopping",
            name = "Tame Shopping",
            description = "Recognise genuine updates from promotional pings.",
            goalXp = 100,
            packagePrefixes = listOf(
                "com.amazon.",
                "com.ebay.",
                "com.shopify.",
                "com.etsy.",
                "com.target.",
                "com.walmart.",
                "com.bestbuy."
            )
        ),
        Quest(
            id = "unclassified",
            name = "Label the Unknown",
            description = "Judge notifications the AI classifier has never seen. High XP, high impact.",
            goalXp = 100,
            packagePrefixes = emptyList(),
            onlyUnclassified = true
        )
    )

    fun byId(id: String?): Quest = all.firstOrNull { it.id == id } ?: FREE_PLAY
}
