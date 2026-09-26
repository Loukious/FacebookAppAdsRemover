package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTabPolicyTest {
    private val bar = listOf(
        "Home, tab 1 of 6",
        "Reels, tab 2 of 6",
        "Marketplace, tab 3 of 6",
        "Gaming, tab 4 of 6",
        "Notifications, tab 5 of 6",
        "Menu, tab 6 of 6",
    )

    @Test fun identifiesActualFacebook580BottomNavigation() {
        val destinations = NavigationTabPolicy.identify(bar)
        assertNotNull(destinations)
        assertEquals(NavigationTabPolicy.Destination.REELS, destinations?.get(1))
        assertEquals(NavigationTabPolicy.Destination.MARKETPLACE, destinations?.get(2))
        assertEquals(NavigationTabPolicy.Destination.GAMES, destinations?.get(3))
    }

    @Test fun facebook580StableTabIdsMatchSemanticDestinations() {
        assertEquals(NavigationTabPolicy.Destination.REELS,
            NavigationTabPolicy.fromId(0x8ea18579L))
        assertEquals(NavigationTabPolicy.Destination.MARKETPLACE,
            NavigationTabPolicy.fromId(0x5b56ce1cca15bL))
        assertEquals(NavigationTabPolicy.Destination.GAMES,
            NavigationTabPolicy.fromId(0x1d3400af8f9ceL))
        assertNull(NavigationTabPolicy.fromId(0x7fffffffffffffffL))
    }

    @Test fun eachToggleOnlyHidesItsOwnTab() {
        val destinations = NavigationTabPolicy.identify(bar)!!
        assertEquals(listOf(NavigationTabPolicy.Destination.REELS),
            destinations.filter { NavigationTabPolicy.hide(it, true, false, false) })
        assertEquals(listOf(NavigationTabPolicy.Destination.MARKETPLACE),
            destinations.filter { NavigationTabPolicy.hide(it, false, true, false) })
        assertEquals(listOf(NavigationTabPolicy.Destination.GAMES),
            destinations.filter { NavigationTabPolicy.hide(it, false, false, true) })
        assertEquals(3, destinations.count { NavigationTabPolicy.hide(it, true, true, true) })
    }

    @Test fun unrelatedMenuFeedViewerAndCategoryRowsFailOpen() {
        assertNull(NavigationTabPolicy.parse("Marketplace"))
        assertNull(NavigationTabPolicy.parse("Reels, 4 videos"))
        assertNull(NavigationTabPolicy.identify(listOf("Reels", "Marketplace", "Gaming")))
        assertNull(NavigationTabPolicy.identify(listOf(
            "Reels, tab 1 of 4", "Following, tab 2 of 4",
            "Suggested, tab 3 of 4", "For you, tab 4 of 4",
        )))
        assertNull(NavigationTabPolicy.identify(listOf(
            "Home, tab 1 of 4", "Marketplace, tab 2 of 4",
            "Reels, tab 3 of 4", "Groups, tab 4 of 4",
        ))) // lacks Notifications or Menu anchor
    }

    @Test fun changedOrderAndSmallerTopNavigationRemainRecognized() {
        val top = listOf("Menu, tab 3 of 3", "Home, tab 1 of 3", "Games, tab 2 of 3")
        assertEquals(listOf(NavigationTabPolicy.Destination.MENU,
            NavigationTabPolicy.Destination.HOME, NavigationTabPolicy.Destination.GAMES),
            NavigationTabPolicy.identify(top))
    }

    @Test fun inconsistentTotalAndDuplicateOrdinalFailOpen() {
        assertNull(NavigationTabPolicy.identify(bar.dropLast(1)))
        assertNull(NavigationTabPolicy.identify(bar.dropLast(1) + "Menu, tab 5 of 6"))
        assertFalse(NavigationTabPolicy.hide(NavigationTabPolicy.Destination.HOME, true, true, true))
        assertTrue(NavigationTabPolicy.hide(NavigationTabPolicy.Destination.GAMES, false, false, true))
    }
}
