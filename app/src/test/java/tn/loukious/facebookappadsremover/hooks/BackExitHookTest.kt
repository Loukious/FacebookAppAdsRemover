package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackExitHookTest {
    /** Shaped like the feed fragment: the original name and a static (reason, fragment) refresh. */
    class Feed {
        fun scrollToTop() = Unit

        companion object {
            @JvmField val __redex_internal_original_name = "NewsFeedFragment"

            @JvmStatic fun refresh(reason: Thread.State, feed: Feed) = Unit

            @JvmStatic fun notRefresh(reason: String, feed: Feed) = Unit

            @JvmStatic fun alsoNot(reason: Thread.State, other: Other) = Unit

            @JvmStatic fun returnsSomething(reason: Thread.State, feed: Feed) = 1

            @JvmStatic fun controller(feed: Feed) = Controller()

            @JvmStatic fun count(feed: Feed) = 1
        }
    }

    /** Shaped like the data controller the fragment hands out. */
    class Controller {
        fun load(reason: Thread.State, extra: String) = Unit

        fun began(reason: Thread.State) = Unit

        fun unrelated(extra: String) = Unit

        fun answers(reason: Thread.State) = 1
    }

    class Other {
        companion object {
            @JvmField val __redex_internal_original_name = "NewsFeedFragmentDataController"
        }
    }

    class Unnamed

    @Test fun feedIsKnownByItsOriginalName() {
        assertTrue(BackExitHook.isFeed(Feed::class.java))
        assertFalse(BackExitHook.isFeed(Other::class.java))
        assertFalse(BackExitHook.isFeed(Unnamed::class.java))
    }

    @Test fun refreshIsTheStaticReasonAndFragmentMethod() {
        val found = Feed::class.java.declaredMethods.filter { BackExitHook.isRefresh(it, Feed::class.java) }
        assertEquals(listOf("refresh"), found.map { it.name })
    }

    @Test fun controllerRefreshesStartWithTheSameReason() {
        val feed = Feed::class.java
        val refreshes = feed.declaredMethods.filter { BackExitHook.isRefresh(it, feed) }
        val found = BackExitHook.controllerRefreshes(feed, refreshes)
        assertEquals(listOf("began", "load"), found.map { it.name }.sorted())
        assertTrue(found.all { it.declaringClass == Controller::class.java })
        assertTrue(BackExitHook.controllerRefreshes(feed, emptyList()).isEmpty())
    }
}
