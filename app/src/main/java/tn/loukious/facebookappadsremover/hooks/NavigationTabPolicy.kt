package tn.loukious.facebookappadsremover.hooks

/**
 * Conservative, Android-free recognition of Facebook's navigation bar.
 * The actual FB 580 tab buttons have descriptions such as
 * "Marketplace, tab 3 of 6". Do not classify generic menu items or text
 * inside a feed/Reels viewer by just seeing the word "Marketplace".
 */
internal object NavigationTabPolicy {
    enum class Destination { HOME, REELS, MARKETPLACE, GAMES, NOTIFICATIONS, MENU, OTHER }

    // Facebook 580 TabTag.A03 / tab-icon A0E. Unlike `X.*` getter names,
    // these are semantic IDs used by Facebook's navigation state itself.
    private const val REELS_TAB_ID = 0x8ea18579L
    private const val MARKETPLACE_TAB_ID = 0x5b56ce1cca15bL
    private const val GAMES_TAB_ID = 0x1d3400af8f9ceL

    fun fromId(id: Long): Destination? = when (id) {
        REELS_TAB_ID -> Destination.REELS
        MARKETPLACE_TAB_ID -> Destination.MARKETPLACE
        GAMES_TAB_ID -> Destination.GAMES
        else -> null
    }

    data class Tab(val destination: Destination, val position: Int, val total: Int)

    private val tabDescription = Regex(
        "^\\s*(.+?)\\s*[,·:]\\s*tab\\s+(\\d+)\\s+of\\s+(\\d+)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** A nonmatching/translated label fails open rather than hiding a menu shortcut. */
    fun parse(description: String?): Tab? {
        val match = tabDescription.matchEntire(description?.trim().orEmpty()) ?: return null
        val position = match.groupValues[2].toIntOrNull() ?: return null
        val total = match.groupValues[3].toIntOrNull() ?: return null
        if (total !in 3..8 || position !in 1..total) return null
        val destination = when (match.groupValues[1].trim().lowercase()) {
            "home" -> Destination.HOME
            "reels", "reel" -> Destination.REELS
            "marketplace" -> Destination.MARKETPLACE
            "gaming", "games" -> Destination.GAMES
            "notifications" -> Destination.NOTIFICATIONS
            "menu", "profile" -> Destination.MENU
            else -> Destination.OTHER
        }
        return Tab(destination, position, total)
    }

    /**
     * Require a single consistent 3–8-tab row containing Home AND a second
     * structural navigation landmark. A Marketplace category filter, Reels
     * menu shortcut, or the inner video tab strip must not qualify.
     */
    fun identify(descriptions: List<String?>): List<Destination>? {
        if (descriptions.size !in 3..8) return null
        val tabs = descriptions.map(::parse)
        if (tabs.any { it == null }) return null
        val resolved = tabs.filterNotNull()
        if (resolved.any { it.total != descriptions.size }) return null
        if (resolved.map { it.position }.toSet().size != resolved.size) return null
        if (resolved.none { it.destination == Destination.HOME }) return null
        if (resolved.none {
                it.destination == Destination.NOTIFICATIONS || it.destination == Destination.MENU
            }) return null
        return resolved.map { it.destination }
    }

    fun hide(destination: Destination, reels: Boolean, marketplace: Boolean, games: Boolean): Boolean =
        when (destination) {
            Destination.REELS -> reels
            Destination.MARKETPLACE -> marketplace
            Destination.GAMES -> games
            else -> false
        }
}
