package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContextualDiscoverySelectionTest {
    @Test fun validatedNativeStoryDoesNotInspectLegacyAnalytics() {
        val native = Any()
        val chosen = ContextualDiscoverySelection.withFallback(
            primary = { native },
            fallback = { error("Legacy lookup must not run after native validation succeeds") },
        )
        assertSame(native, chosen)
    }

    @Test fun rejectedNativeStoryUsesValidatedLegacyCandidate() {
        val legacy = Any()
        val chosen = ContextualDiscoverySelection.withFallback(
            primary = { error("Native menu owner no longer has one StoryCard field") },
            fallback = { legacy },
        )
        assertSame(legacy, chosen)
    }

    @Test fun bothFailedStoryPathsPreserveTheirDiagnostics() {
        val nativeFailure = IllegalStateException("Native row is ambiguous")
        val legacyFailure = IllegalStateException("Analytics action is absent")
        try {
            ContextualDiscoverySelection.withFallback(
                primary = { throw nativeFailure },
                fallback = { throw legacyFailure },
            )
            fail("Neither story path validated")
        } catch (failure: IllegalStateException) {
            assertSame(legacyFailure, failure)
            assertEquals(listOf(nativeFailure), failure.suppressed.toList())
        }
    }

    @Test fun directReelFactoryIgnoresAdditionalHelperCandidates() {
        val chosen = ContextualDiscoverySelection.preferDirect(
            listOf("legacyRender", "helperRender"),
            directMatch = { it == "legacyRender" },
            helperMatch = { error("Helpers must not widen an established direct selection") },
        )
        assertEquals(listOf("legacyRender"), chosen.candidates)
        assertFalse(chosen.includeHelpers)
    }

    @Test fun ambiguousDirectReelsRemainAmbiguousWithoutHelperFallback() {
        val chosen = ContextualDiscoverySelection.preferDirect(
            listOf("firstRender", "secondRender"),
            directMatch = { true },
            helperMatch = { error("Helpers cannot resolve ambiguous direct matches") },
        )
        assertEquals(listOf("firstRender", "secondRender"), chosen.candidates)
        assertFalse(chosen.includeHelpers)
    }

    @Test fun facebook587HelperPathIsSelectedWhenNoDirectFactoryExists() {
        val chosen = ContextualDiscoverySelection.preferDirect(
            listOf("analyticsAnchor", "nativeRender"),
            directMatch = { false },
            helperMatch = { it == "nativeRender" },
        )
        assertEquals(listOf("nativeRender"), chosen.candidates)
        assertTrue(chosen.includeHelpers)
    }
}
