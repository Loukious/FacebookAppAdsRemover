package tn.loukious.facebookappadsremover.hooks

import tn.loukious.facebookappadsremover.core.Settings

/** The supported Facebook entry points all use the same rule order/config. */
internal enum class FeedPipeline { CLASSIC, CSR_CACHE, LATE_CACHE, LITHO_RENDER }

/** Explicit configuration for tests and one consistent snapshot per pipeline call. */
internal data class FeedFilterConfig(
    val ads: Boolean,
    val feedGuard: Boolean,
    val enabledCategories: Set<String>,
    val aiContent: Boolean,
    val keywords: List<String>,
)

internal object FeedContentRules {
    private val categorySettings = linkedMapOf(
        "PROMOTION" to Settings.FEED_THREADS,
        "FB_SHORTS" to Settings.FEED_REELS,
        "ENGAGEMENT" to Settings.FEED_SUGGESTIONS,
        "ENGAGEMENT_QP" to Settings.FEED_PYMK,
        "MULTI_FB_STORIES_TRAY" to Settings.FEED_STORIES,
    )

    fun enabledCategoryNames(): Set<String> = config().enabledCategories +
        (if (Settings.getBoolean(Settings.ADS_NEWS_FEED, true)) setOf("SPONSORED") else emptySet())

    fun config(): FeedFilterConfig {
        val keywords = if (Settings.getBoolean(Settings.FEED_KEYWORDS_ENABLED, false)) {
            Settings.getString(Settings.FEED_KEYWORDS, "")
                .split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        } else emptyList()
        return FeedFilterConfig(
            ads = Settings.getBoolean(Settings.ADS_NEWS_FEED, true),
            feedGuard = Settings.getBoolean(Settings.ADS_FEED_GUARD, true),
            enabledCategories = categorySettings.filterValues { Settings.getBoolean(it, false) }.keys,
            aiContent = Settings.getBoolean(Settings.FEED_AI_CONTENT, false),
            keywords = keywords,
        )
    }

    /** Snapshot settings per list/render, not per item; changing a toggle needs no new APK. */
    fun engine(pipeline: FeedPipeline, signals: FeedItemSignals): FeedFilterEngine =
        engine(pipeline, signals, config())

    fun engine(
        pipeline: FeedPipeline,
        signals: FeedItemSignals,
        config: FeedFilterConfig,
    ): FeedFilterEngine {
        val rules = ArrayList<FeedFilterRule>()
        val sponsoredOn = config.ads && (pipeline == FeedPipeline.CLASSIC || config.feedGuard)
        if (sponsoredOn) {
            rules += rule("SPONSORED") { it.sponsored }
        }
        for (category in categorySettings.keys) {
            if (category in config.enabledCategories) {
                rules += rule(category) { it.category == category }
            }
        }
        if (config.aiContent) {
            rules += rule("AI_CONTENT") { it.aiContent }
        }
        if (config.keywords.isNotEmpty()) {
            rules += rule("KEYWORD") { item ->
                val text = item.searchableText?.lowercase() ?: return@rule false
                config.keywords.any(text::contains)
            }
        }
        return FeedFilterEngine(signals, rules)
    }

    private fun rule(name: String, predicate: (FeedItemFacts) -> Boolean): FeedFilterRule =
        object : FeedFilterRule {
            override val id = name
            override fun matches(item: FeedItemFacts): Boolean = predicate(item)
        }
}
