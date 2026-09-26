package com.facebook.graphql.model

/** Test-double for Facebook's stable GraphQLStory class name and TreeJNI API. */
class GraphQLStory(
    private val label: AiLabelInfo? = null,
    private val labelFieldIsPresent: Boolean = label != null,
    private val detected: Boolean = false,
    private val disclosed: Boolean = false,
) : FeedStoryContract {
    fun hasFieldValue(hash: Int): Boolean =
        hash == "gen_ai_transparency_label_info".hashCode() && labelFieldIsPresent

    fun getTree(hash: Int): Any? = when (hash) {
        "gen_ai_transparency_label_info".hashCode() -> label
        "ai_generated_detected_info".hashCode() -> if (detected) AiFlagInfo(
            "was_detected_as_ai_generated".hashCode()) else null
        "ai_generated_self_disclosure_info".hashCode() -> if (disclosed) AiFlagInfo(
            "was_self_disclosed_as_ai_generated".hashCode()) else null
        else -> null
    }
}

interface FeedStoryContract

class GraphQLFeedUnitEdge(
    private val primary: GraphQLStory,
    private val related: GraphQLStory? = null,
) {
    // The real Facebook 580 GraphQLFeedUnitEdge.A03() uses this stable
    // BaseModelWithTree method with the `node` hash, without BOq inflation.
    fun getCachedVirtualModel(hash: Int): Any? =
        if (hash == "node".hashCode()) primary else null
    fun getPrimary(): FeedStoryContract = primary
    fun getRelated(): GraphQLStory? = related
}

class AiFlagInfo(private val positiveHash: Int) {
    fun getBooleanValue(hash: Int) = hash == positiveHash
}

class AiLabelInfo(private val title: String?) {
    fun getString(hash: Int): String? =
        if (hash == "zero_click_transparency_label".hashCode()) title else null
}
