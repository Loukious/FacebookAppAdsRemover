package tn.loukious.facebookappadsremover.core

/** Pure migration planner; caller writes these values through Xposed remote prefs. */
object AdSettingsMigration {
    const val MARKER = "ads.surfaceSplitMigrated"
    const val RETIRED_SHOPPING = "ads.reelsShopping"

    fun updates(existing: Map<String, *>): Map<String, Boolean> {
        if (existing[MARKER] == true) return emptyMap()
        val legacy = existing[Settings.LEGACY_ADS_ENABLED] as? Boolean
        val result = LinkedHashMap<String, Boolean>()
        for (key in listOf(Settings.ADS_NEWS_FEED, Settings.ADS_STORIES, Settings.ADS_REELS)) {
            if (key !in existing) result[key] = legacy ?: true
        }
        if (legacy == false) {
            // An unchecked old master suppressed these two hooks even when
            // their per-family toggles were visually checked.
            result[Settings.ADS_MARKETPLACE] = false
            result[Settings.ADS_GAME_ADS] = false
        }
        result[MARKER] = true
        return result
    }
}
