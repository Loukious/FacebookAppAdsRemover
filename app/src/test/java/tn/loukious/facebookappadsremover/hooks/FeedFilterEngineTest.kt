package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterEngineTest {
    private data class Post(
        val name: String,
        val category: String? = null,
        val sponsored: Boolean = false,
        val ai: Boolean = false,
        val text: String = "",
    )

    private class PostSignals : FeedItemSignals {
        var aiChecks = 0
        var textChecks = 0
        override fun category(item: Any): String? = (item as Post).category
        override fun sponsored(item: Any): Boolean = (item as Post).sponsored
        override fun aiContent(item: Any): Boolean {
            aiChecks++
            return (item as Post).ai
        }
        override fun searchableText(item: Any): String {
            textChecks++
            return (item as Post).text
        }
    }

    private val allRules = FeedFilterConfig(
        ads = true,
        feedGuard = true,
        enabledCategories = setOf("FB_SHORTS", "PROMOTION"),
        aiContent = true,
        keywords = listOf("spoiler"),
    )

    @Test fun allPipelinesShareRulesAndPreserveUnmatchedItems() {
        val items = listOf(
            Post("normal"),
            Post("sponsored", sponsored = true),
            Post("reel", category = "FB_SHORTS"),
            Post("threads", category = "PROMOTION"),
            Post("ai", ai = true),
            Post("keyword", text = "A SPOILER ahead"),
        )
        for (pipeline in FeedPipeline.values()) {
            val result = FeedContentRules.engine(pipeline, PostSignals(), allRules).partition(items)
            assertEquals(pipeline.name, listOf(items[0]), result.kept)
            assertEquals(pipeline.name, 5, result.removed)
            assertEquals(1, result.removedByRule["SPONSORED"])
            assertEquals(1, result.removedByRule["FB_SHORTS"])
            assertEquals(1, result.removedByRule["PROMOTION"])
            assertEquals(1, result.removedByRule["AI_CONTENT"])
            assertEquals(1, result.removedByRule["KEYWORD"])
        }
    }

    @Test fun aiAndKeywordCanRunOnCacheWithAdGuardDisabled() {
        val config = allRules.copy(ads = false, feedGuard = false, enabledCategories = emptySet())
        val result = FeedContentRules.engine(FeedPipeline.CSR_CACHE, PostSignals(), config)
            .partition(listOf(Post("ad", sponsored = true), Post("ai", ai = true),
                Post("keyword", text = "Spoiler")))
        assertEquals(listOf(Post("ad", sponsored = true)), result.kept)
        assertEquals(mapOf("AI_CONTENT" to 1, "KEYWORD" to 1), result.removedByRule)
        assertFalse(result.removedByRule.containsKey("SPONSORED"))
    }

    @Test fun disablingNewsFeedAdsPreservesOtherRulesAcrossPipelines() {
        val config = allRules.copy(ads = false)
        val items = listOf(
            Post("sponsored", sponsored = true),
            Post("reel", category = "FB_SHORTS"),
            Post("ai", ai = true),
            Post("keyword", text = "Spoiler"),
        )
        for (pipeline in FeedPipeline.values()) {
            val result = FeedContentRules.engine(pipeline, PostSignals(), config).partition(items)
            assertEquals(pipeline.name, listOf(items[0]), result.kept)
            assertEquals(pipeline.name, 3, result.removed)
            assertEquals(
                pipeline.name,
                mapOf("FB_SHORTS" to 1, "AI_CONTENT" to 1, "KEYWORD" to 1),
                result.removedByRule,
            )
        }
    }

    @Test fun cacheAdGuardSettingDoesNotDisableClassicSponsoredRule() {
        val config = allRules.copy(feedGuard = false, aiContent = false,
            enabledCategories = emptySet(), keywords = emptyList())
        val signals = PostSignals()
        assertTrue(FeedContentRules.engine(FeedPipeline.CLASSIC, signals, config)
            .decide(Post("sponsored", sponsored = true)).remove)
        assertFalse(FeedContentRules.engine(FeedPipeline.LATE_CACHE, signals, config)
            .decide(Post("sponsored", sponsored = true)).remove)
    }

    @Test fun firstMatchPrecedenceAndLazySignalsAvoidDuplicateAiChecks() {
        val signals = PostSignals()
        val result = FeedContentRules.engine(FeedPipeline.CLASSIC, signals, allRules)
            .partition(listOf(Post("both", sponsored = true, ai = true), Post("ai", ai = true)))
        assertEquals(mapOf("SPONSORED" to 1, "AI_CONTENT" to 1), result.removedByRule)
        assertEquals(1, signals.aiChecks)
        assertEquals(0, signals.textChecks)
        assertEquals(1, result.evaluatedByRule["AI_CONTENT"])
    }

    @Test fun nullUnknownAndRuleErrorsAreRetained() {
        val signals = object : FeedItemSignals {
            override fun category(item: Any) = null
            override fun sponsored(item: Any): Boolean = error("unknown model")
            override fun aiContent(item: Any) = false
            override fun searchableText(item: Any) = null
        }
        val config = allRules.copy(aiContent = false, enabledCategories = emptySet(),
            keywords = emptyList())
        val result = FeedContentRules.engine(FeedPipeline.CSR_CACHE, signals, config)
            .partition(listOf(null, "opaque"))
        assertEquals(listOf(null, "opaque"), result.kept)
        assertEquals(0, result.removed)
        assertEquals(2, result.inspected)
    }
}
