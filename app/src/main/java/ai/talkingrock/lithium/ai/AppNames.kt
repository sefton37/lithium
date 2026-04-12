package ai.talkingrock.lithium.ai

import android.content.Context
import android.content.pm.PackageManager

/**
 * Shared mapping of Android package names to human-readable app names.
 * Used by [ReportGenerator] and [SuggestionGenerator].
 */
object AppNames {

    /**
     * Returns the label Android shows for [packageName] in the launcher /
     * app drawer — the authoritative source. Falls back to [friendlyName]
     * if the package is not installed or the label lookup fails (which is
     * safe behavior on reports that still reference uninstalled apps).
     */
    fun displayName(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        val label = info.loadLabel(pm).toString()
        if (label.isBlank()) friendlyName(packageName) else label
    } catch (_: PackageManager.NameNotFoundException) {
        friendlyName(packageName)
    } catch (_: Exception) {
        friendlyName(packageName)
    }


    private val KNOWN_NAMES = mapOf(
        // Social media
        "com.instagram.android"                     to "Instagram",
        "com.facebook.katana"                       to "Facebook",
        "com.facebook.orca"                         to "Messenger",
        "com.twitter.android"                       to "Twitter / X",
        "com.zhiliaoapp.musically"                  to "TikTok",
        "com.snapchat.android"                      to "Snapchat",
        "com.reddit.frontpage"                      to "Reddit",
        "com.linkedin.android"                      to "LinkedIn",
        "com.google.android.youtube"                to "YouTube",
        "com.pinterest"                             to "Pinterest",
        "com.tumblr"                                to "Tumblr",
        "com.instagram.threads"                     to "Threads",

        // Messaging
        "com.whatsapp"                              to "WhatsApp",
        "org.telegram.messenger"                    to "Telegram",
        "com.discord"                               to "Discord",
        "com.slack"                                 to "Slack",
        "com.Slack"                                 to "Slack",
        "com.microsoft.teams"                       to "Microsoft Teams",
        "com.google.android.gm"                     to "Gmail",
        "com.microsoft.office.outlook"              to "Outlook",
        "org.thoughtcrime.securesms"                to "Signal",

        // E-commerce / shopping
        "com.amazon.mShop.android.shopping"         to "Amazon",
        "com.amazon.avod"                           to "Amazon Prime Video",
        "com.target.ui"                             to "Target",
        "com.zzkko"                                 to "SHEIN",
        "com.ebay.mobile"                           to "eBay",
        "com.walmart.android"                       to "Walmart",
        "com.shopify.mobile"                        to "Shopify",
        "com.bestbuy.android"                       to "Best Buy",
        "com.wayfair.wayfair"                       to "Wayfair",
        "com.temu"                                  to "Temu",

        // Food delivery
        "com.ubercab.eats"                          to "Uber Eats",
        "com.dd.doordash"                           to "DoorDash",
        "com.grubhub.android"                       to "Grubhub",
        "com.instacart.client"                      to "Instacart",

        // Finance
        "com.chase.sig.android"                     to "Chase",
        "com.venmo"                                 to "Venmo",
        "com.paypal.android.p2pmobile"              to "PayPal",
        "com.squareup.cash"                         to "Cash App",

        // Gaming
        "com.supercell.clashofclans"                to "Clash of Clans",
        "com.supercell.clashroyale"                 to "Clash Royale",
        "com.supercell.brawlstars"                  to "Brawl Stars",
        "tv.twitch.android.app"                     to "Twitch",
        "com.valvesoftware.android.steam.community" to "Steam",
        "com.riotgames.league.wildrift"             to "Wild Rift",
        "com.miHoYo.GenshinImpact"                 to "Genshin Impact",

        // Ride-sharing / transport
        "com.ubercab"                               to "Uber",
        "com.lyft.android"                          to "Lyft",

        // Health / family
        "com.classdojo.android"                     to "ClassDojo",
        "org.mychart.android.mychart"               to "MyChart",

        // Google apps (non-system)
        "com.google.android.apps.maps"              to "Google Maps",
        "com.google.android.apps.photos"            to "Google Photos",
        "com.google.android.keep"                   to "Google Keep",

        // Streaming
        "com.netflix.mediaclient"                   to "Netflix",
        "com.spotify.music"                         to "Spotify",
        "com.hbo.hbonow"                            to "Max",
        "com.disney.disneyplus"                     to "Disney+"
    )

    /**
     * Returns a human-readable app name for [packageName].
     * Falls back to extracting and capitalising the last segment of the package name.
     */
    fun friendlyName(packageName: String): String {
        KNOWN_NAMES[packageName]?.let { return it }

        val last = packageName.substringAfterLast('.')
        return last
            .replace(Regex("[^A-Za-z0-9]"), " ")
            .split(Regex("(?<=[a-z])(?=[A-Z])|[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
            .ifBlank { packageName }
    }
}
