package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryMenuSignatureTest {
    private val nativeStoryItem = listOf(
        "X.Handler", "X.Icon", "java.lang.CharSequence",
        "java.lang.Integer", "java.lang.Integer", "java.lang.Integer",
        "java.lang.String", "java.lang.String", "int", "boolean",
    )

    @Test fun facebook587NativeSaveMenuItemIsRecognizedWithoutAnalytics() {
        assertTrue(StoryMenuSignature.accepts(nativeStoryItem, "X.Icon"))
    }

    @Test fun unrelatedMenuConstructorsAreRejected() {
        assertFalse(StoryMenuSignature.accepts(nativeStoryItem, "X.OtherIcon"))
        assertFalse(StoryMenuSignature.accepts(nativeStoryItem.dropLast(1), "X.Icon"))
        assertFalse(StoryMenuSignature.accepts(nativeStoryItem.toMutableList().apply {
            this[3] = "int"
        }, "X.Icon"))
        assertFalse(StoryMenuSignature.accepts(nativeStoryItem.toMutableList().apply {
            this[9] = "java.lang.Boolean"
        }, "X.Icon"))
    }
}
