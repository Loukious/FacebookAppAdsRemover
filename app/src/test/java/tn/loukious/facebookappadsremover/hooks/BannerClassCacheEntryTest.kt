package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tn.loukious.facebookappadsremover.core.AdSurface
import tn.loukious.facebookappadsremover.core.BannerClassCacheEntry

class BannerClassCacheEntryTest {
    @Test fun retainsClassAndEverySurfaceAcrossCacheRestarts() {
        val v = BannerClassCacheEntry("X.2Qm", setOf(AdSurface.REELS, AdSurface.NEWS_FEED))
        assertEquals(v, BannerClassCacheEntry.parse(v.encode()))
    }

    @Test fun rejectsOldUnscopedOrMalformedCacheRecords() {
        assertNull(BannerClassCacheEntry.parse("X.2Qm"))
        assertNull(BannerClassCacheEntry.parse("NEWS_FEED|"))
        assertNull(BannerClassCacheEntry.parse("OTHER|X.2Qm"))
        assertNull(BannerClassCacheEntry.parse("NEWS_FEED,OTHER|X.2Qm"))
    }
}
