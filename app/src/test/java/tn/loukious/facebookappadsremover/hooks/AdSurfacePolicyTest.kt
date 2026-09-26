package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tn.loukious.facebookappadsremover.core.AdSurface
import tn.loukious.facebookappadsremover.core.AdSurfacePolicy
import tn.loukious.facebookappadsremover.core.AdTargets

class AdSurfacePolicyTest {
    @Test fun everyDiscoveredAdTargetHasExplicitRouting() {
        val keys = AdTargets.all.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        assertEquals(emptyList<String>(), keys.filter {
            AdSurfacePolicy.targetSurfaces(it).isNullOrEmpty()
        })
        assertNull(AdSurfacePolicy.targetSurfaces("new.unclassified.target"))
    }

    @Test fun feedStoryAndReelsAreIndependentForDedicatedTargets() {
        assertEquals(setOf(AdSurface.NEWS_FEED),
            AdSurfacePolicy.targetSurfaces("ads.adChannelFetch"))
        assertEquals(setOf(AdSurface.STORIES),
            AdSurfacePolicy.targetSurfaces("ads.storyAdBucketParse"))
        assertEquals(setOf(AdSurface.REELS),
            AdSurfacePolicy.targetSurfaces("ads.reelsVideoAdQuery"))
    }

    @Test fun sharedTargetsAreExplicitAndOnlyEnabledWhenEverySurfaceEnabled() {
        assertEquals(setOf(AdSurface.NEWS_FEED, AdSurface.REELS),
            AdSurfacePolicy.targetSurfaces("ads.sponsoredPoolAdd"))
        assertEquals(setOf(AdSurface.STORIES, AdSurface.REELS),
            AdSurfacePolicy.targetSurfaces("ads.videoAdFetch"))
        val shared = AdSurfacePolicy.targetSurfaces("ads.videoAdFetch")!!
        assertFalse(shared.all { it == AdSurface.REELS })
        assertTrue(shared.all { it in setOf(AdSurface.STORIES, AdSurface.REELS) })
        assertFalse(AdSurfacePolicy.shouldBlock(shared) { it == AdSurface.REELS })
        assertFalse(AdSurfacePolicy.shouldBlock(shared) { it == AdSurface.STORIES })
        assertTrue(AdSurfacePolicy.shouldBlock(shared) {
            it in setOf(AdSurface.STORIES, AdSurface.REELS)
        })
        assertTrue(AdSurfacePolicy.shouldBlock(setOf(AdSurface.REELS)) {
            it == AdSurface.REELS
        })
        assertFalse(AdSurfacePolicy.shouldBlock(emptySet()) { true })
    }

    @Test fun bannerAnchorsDoNotSilentlyAllBecomeReelsAds() {
        assertEquals(AdSurface.values().toSet(), AdSurfacePolicy.bannerSurfaces("banner_ad"))
        assertEquals(setOf(AdSurface.REELS),
            AdSurfacePolicy.bannerSurfaces("reels_banner_click_wnb_inline"))
        assertEquals(setOf(AdSurface.STORIES, AdSurface.REELS),
            AdSurfacePolicy.bannerSurfaces("bannerAdBreak"))
        assertEquals(emptySet<AdSurface>(),
            AdSurfacePolicy.bannerSurfaces("mailboxinthreadadcontextbannerjni"))
    }
}
