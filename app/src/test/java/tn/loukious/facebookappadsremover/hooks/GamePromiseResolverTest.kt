package tn.loukious.facebookappadsremover.hooks

import android.fixture.FrameworkPromiseView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GamePromiseResolverTest {
    class Payload

    open class PromiseOwner : FrameworkPromiseView() {
        var value: Any? = null
        fun JoT(id: String, value: Any) { this.value = value }
        fun Jfw(id: String, message: String, code: String) {}
    }

    class Handler(val A08: PromiseOwner) {
        fun Jfv(message: String, code: String, payload: Payload) {}
    }

    class JavascriptWrapper(val A02: Handler) {
        fun postMessage(message: String, origin: String) {}
    }

    class AmbiguousWrapper(val first: PromiseOwner, val second: PromiseOwner)

    @Test fun resolvesTheFacebook587WrapperThroughItsNativeHandler() {
        val owner = PromiseOwner()
        val endpoint = GamePromiseResolver.find(JavascriptWrapper(Handler(owner)), Payload::class.java)!!
        assertSame(owner, endpoint.owner)
        assertEquals("JoT", endpoint.resolve.name)
        endpoint.resolve.invoke(endpoint.owner, "showadasync_1", "completed reward")
        assertEquals("completed reward", owner.value)
    }

    @Test fun existingDirectPromiseOwnerStillResolves() {
        val owner = PromiseOwner()
        assertSame(owner, GamePromiseResolver.find(owner, Payload::class.java)?.owner)
    }

    @Test fun inheritedWebViewLoadDataCannotHideTheNativeRejectHelper() {
        val endpoint = GamePromiseResolver.find(PromiseOwner(), Payload::class.java)!!
        assertEquals("Jfw", endpoint.reject.name)
        assertEquals(PromiseOwner::class.java, endpoint.reject.declaringClass)
    }

    @Test fun inheritedAppPromiseHelpersRemainAvailable() {
        class SubclassedOwner : PromiseOwner()
        val owner = SubclassedOwner()
        val endpoint = GamePromiseResolver.find(owner, Payload::class.java)!!
        endpoint.resolve.invoke(owner, "showadasync_1", "inherited reward")
        assertEquals("inherited reward", owner.value)
        assertEquals("Jfw", endpoint.reject.name)
    }

    @Test fun competingOwnersAreLeftToTheOriginalBridge() {
        assertNull(GamePromiseResolver.find(AmbiguousWrapper(PromiseOwner(), PromiseOwner()), Payload::class.java))
    }

    @Test fun unrelatedFieldGraphsAreNotSearched() {
        class Unrelated(val owner: PromiseOwner)
        class Outer(val unrelated: Unrelated)
        assertNull(GamePromiseResolver.find(Outer(Unrelated(PromiseOwner())), Payload::class.java))
    }
}
