package tn.loukious.facebookappadsremover.hooks

import com.facebook.graphql.model.AiLabelInfo
import com.facebook.graphql.model.GraphQLFeedUnitEdge
import com.facebook.graphql.model.GraphQLStory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTransparencyInspectorTest {
    private open class FakeTree(private val values: Map<Int, Boolean> = emptyMap()) {
        fun hasFieldValue(hash: Int): Boolean = hash in values
        fun getCachedBoolean(hash: Int): Boolean = values[hash] ?: false
    }

    private class ObfuscatedTransparency(values: Map<Int, Boolean>) : FakeTree(values)
    private class Story(private val disclosure: ObfuscatedTransparency?) : FakeTree() {
        fun getTransparency(): ObfuscatedTransparency? = disclosure
    }
    private class Edge(private val story: Story) : FakeTree() {
        fun getStory(): Story = story
    }

    private class GraphEdge(private val story: GraphQLStory) : FakeTree() {
        fun getStory(): GraphQLStory = story
    }

    @Test fun acceptsSelfDisclosureAndDetectedTransparency() {
        val self = "was_self_disclosed_as_ai_generated".hashCode()
        val detected = "was_detected_as_ai_generated".hashCode()
        assertTrue(AiTransparencyInspector.isAiContent(
            Edge(Story(ObfuscatedTransparency(mapOf(self to true))))))
        assertTrue(AiTransparencyInspector.isAiContent(
            Edge(Story(ObfuscatedTransparency(mapOf(detected to true))))))
    }

    @Test fun presentButFalseIsNotAiContent() {
        val detected = "was_detected_as_ai_generated".hashCode()
        assertFalse(AiTransparencyInspector.isAiContent(
            Edge(Story(ObfuscatedTransparency(mapOf(detected to false))))))
    }

    @Test fun missingDisclosureIsNotAiContentEvenAfterPositivePathWasCached() {
        assertFalse(AiTransparencyInspector.isAiContent(Edge(Story(null))))
        assertFalse(AiTransparencyInspector.isAiContent(Edge(Story(ObfuscatedTransparency(emptyMap())))))
    }

    @Test fun graphQlLabelTextAloneDoesNotRemoveAnOrdinaryStory() {
        assertFalse(AiTransparencyInspector.isAiContent(
            GraphEdge(GraphQLStory(AiLabelInfo("AI content")))))
        // Presence alone is insufficient: Facebook populates a default
        // transparency subtree on ordinary feed stories as well.
        assertFalse(AiTransparencyInspector.isAiContent(
            GraphEdge(GraphQLStory(AiLabelInfo(null)))))
        assertFalse(AiTransparencyInspector.isAiContent(
            GraphEdge(GraphQLStory(AiLabelInfo("AI info")))))
    }

    @Test fun nullGraphQlTransparencyLabelFailsOpenEvenIfFieldPresent() {
        assertFalse(AiTransparencyInspector.isAiContent(
            GraphEdge(GraphQLStory(labelFieldIsPresent = true))))
        assertFalse(AiTransparencyInspector.isAiContent(
            GraphEdge(GraphQLStory(labelFieldIsPresent = false))))
    }

    @Test fun primaryFeedStoryWinsOverAiLabeledRelatedStory() {
        val primary = GraphQLStory()
        val unrelated = GraphQLStory(AiLabelInfo("AI content"), detected = true)
        assertFalse(AiTransparencyInspector.isAiContent(
            GraphQLFeedUnitEdge(primary, unrelated)))
        assertTrue(AiTransparencyInspector.isAiContent(
            GraphQLFeedUnitEdge(GraphQLStory(AiLabelInfo("AI content"), detected = true), primary)))
    }

    @Test fun primaryStoryDetectedAndDisclosureMetadataWorkWithoutHasFieldValue() {
        assertTrue(AiTransparencyInspector.isAiContent(
            GraphQLFeedUnitEdge(GraphQLStory(detected = true))))
        assertTrue(AiTransparencyInspector.isAiContent(
            GraphQLFeedUnitEdge(GraphQLStory(disclosed = true))))
    }
}
