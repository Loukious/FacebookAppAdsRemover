package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class GameRewardCallbacksTest {
    class Ad

    open class RewardListener(val events: MutableList<String>) {
        fun onAdLoaded(ad: Ad) { events.add("loaded") }
        open fun onRewardedVideoCompleted() { events.add("reward") }
        fun onRewardedVideoClosed() { events.add("closed") }
    }

    class LoadOnlyListener(val events: MutableList<String>) {
        fun onAdLoaded(ad: Ad) { events.add("loaded") }
        fun onRewardedVideoClosed() { events.add("closed") }
    }

    class FailingListener(events: MutableList<String>) : RewardListener(events) {
        override fun onRewardedVideoCompleted() { throw IllegalStateException("reward rejected") }
    }

    class LoadConfig(val A01: SdkImplementation) {
        fun withRewardData(data: Any) = this
    }
    class SdkImplementation(val A00: CallbackOwner)
    class CallbackOwner(val A04: RewardListener)
    class CallbackCollection(val callbacks: Any)

    private fun deliver(listener: Any, ad: Any = Ad()) = GameRewardCallbacks.deliver(
        listener, ad, listener.javaClass.methods.toList(),
    )

    @Test fun rewardIsDeliveredBeforeClose() {
        val events = mutableListOf<String>()
        val result = deliver(RewardListener(events))
        assertEquals(listOf("loaded", "reward", "closed"), events)
        assertEquals(3, result.invoked)
        assertEquals(1, result.completed)
    }

    @Test fun loadedAndClosedCallbacksCannotSubstituteForAReward() {
        val events = mutableListOf<String>()
        assertEquals(0, deliver(LoadOnlyListener(events)).completed)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun failedRewardLeavesTheSdkCloseSequenceUntouched() {
        val events = mutableListOf<String>()
        val result = deliver(FailingListener(events))
        assertEquals(listOf("loaded"), events)
        assertEquals(1, result.invoked)
        assertEquals(0, result.completed)
    }

    @Test fun loadConfigIsNotMistakenForItsNestedRewardListener() {
        val listener = RewardListener(mutableListOf())
        val config = LoadConfig(SdkImplementation(CallbackOwner(listener)))
        assertFalse(GameRewardCallbacks.isListener(config.javaClass))
        val found = GameRewardCallbacks.findListeners(config) { true }
        assertEquals(1, found.size)
        assertSame(listener, found.single())
    }

    @Test fun legacyListenerBehindFiveFieldHopsIsFound() {
        class Extra(val nested: Any)
        val listener = RewardListener(mutableListOf())
        val root = Extra(Extra(LoadConfig(SdkImplementation(CallbackOwner(listener)))))
        assertSame(listener, GameRewardCallbacks.findListeners(root) { true }.single())
    }

    @Test fun deeperUnrelatedGraphsAreNotSearched() {
        class Extra(val nested: Any)
        val root = Extra(Extra(Extra(LoadConfig(SdkImplementation(CallbackOwner(RewardListener(mutableListOf())))))))
        assertEquals(emptyList<Any>(), GameRewardCallbacks.findListeners(root) { true })
    }

    @Test fun listenerStoredInAnIterableFieldIsFound() {
        val listener = RewardListener(mutableListOf())
        val root = CallbackCollection(listOf("ignored", listener))
        val found = GameRewardCallbacks.findListeners(root) { it is CallbackCollection }
        assertSame(listener, found.single())
    }

    @Test fun listenerStoredInAnArrayFieldIsFound() {
        val listener = RewardListener(mutableListOf())
        val root = CallbackCollection(arrayOf("ignored", listener))
        val found = GameRewardCallbacks.findListeners(root) { it is CallbackCollection }
        assertSame(listener, found.single())
    }

    @Test fun collectionTraversalStopsAfterTwelveEntries() {
        val listener = RewardListener(mutableListOf())
        val entries = List<Any>(12) { "ignored" } + listener
        for (callbacks in listOf(entries, entries.toTypedArray())) {
            val found = GameRewardCallbacks.findListeners(CallbackCollection(callbacks)) {
                it is CallbackCollection
            }
            assertEquals(emptyList<Any>(), found)
        }
    }

    @Test fun collectionEntriesRemainWithinTheLegacyFiveFieldHopBudget() {
        class Outer(val inner: Any)
        val listener = RewardListener(mutableListOf())
        val root = Outer(Outer(Outer(Outer(CallbackCollection(listOf(listener))))))
        val found = GameRewardCallbacks.findListeners(root) { it is Outer || it is CallbackCollection }
        assertSame(listener, found.single())
        val tooDeep = Outer(root)
        assertEquals(emptyList<Any>(), GameRewardCallbacks.findListeners(tooDeep) {
            it is Outer || it is CallbackCollection
        })
    }

    @Test fun legacyNinetySixObjectBudgetIsRetainedAndStillBounded() {
        class EmptyNode
        for (listenerIndex in listOf(82, 83)) {
            val listener = RewardListener(mutableListOf())
            val nodes = MutableList<Any>(144) { EmptyNode() }
            nodes[listenerIndex] = listener
            val root = CallbackCollection(nodes.chunked(12).map { CallbackCollection(it) })
            val found = GameRewardCallbacks.findListeners(root) { it is CallbackCollection || it is EmptyNode }
            // The root and twelve intermediate SDK objects consume thirteen slots.
            if (listenerIndex == 82) assertSame(listener, found.single())
            else assertEquals(emptyList<Any>(), found)
        }
    }
}
