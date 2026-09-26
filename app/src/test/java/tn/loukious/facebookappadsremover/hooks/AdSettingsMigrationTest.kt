package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tn.loukious.facebookappadsremover.core.AdSettingsMigration
import tn.loukious.facebookappadsremover.core.Settings

class AdSettingsMigrationTest {
    @Test fun legacyMasterOffStaysOffInEveryPreviouslyGatedAdFamily() {
        val result = AdSettingsMigration.updates(mapOf(
            Settings.LEGACY_ADS_ENABLED to false,
            Settings.ADS_MARKETPLACE to true,
            Settings.ADS_GAME_ADS to true,
        ))
        for (key in listOf(Settings.ADS_NEWS_FEED, Settings.ADS_STORIES,
            Settings.ADS_REELS, Settings.ADS_MARKETPLACE, Settings.ADS_GAME_ADS)) {
            assertEquals("$key must remain off", false, result[key])
        }
        assertTrue(result[AdSettingsMigration.MARKER] == true)
    }

    @Test fun legacyMasterOnKeepsIndividualMarketplaceAndGameChoices() {
        val result = AdSettingsMigration.updates(mapOf(
            Settings.LEGACY_ADS_ENABLED to true,
            Settings.ADS_MARKETPLACE to false,
            Settings.ADS_GAME_ADS to true,
        ))
        assertEquals(setOf(Settings.ADS_NEWS_FEED, Settings.ADS_STORIES,
            Settings.ADS_REELS, AdSettingsMigration.MARKER), result.keys)
        assertTrue(result.values.all { it })
    }

    @Test fun partialExplicitNewChoicesAreNeverOverwritten() {
        val result = AdSettingsMigration.updates(mapOf(
            Settings.ADS_NEWS_FEED to false,
            Settings.ADS_STORIES to true,
        ))
        assertFalse(Settings.ADS_NEWS_FEED in result)
        assertFalse(Settings.ADS_STORIES in result)
        assertEquals(true, result[Settings.ADS_REELS])
        assertEquals(emptyMap<String, Boolean>(),
            AdSettingsMigration.updates(mapOf(AdSettingsMigration.MARKER to true)))
    }
}
