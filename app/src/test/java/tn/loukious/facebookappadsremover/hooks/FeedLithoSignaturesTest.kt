package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FeedLithoSignaturesTest {
    class Ctx
    class OtherCtx
    class Node

    class ComponentLegacy {
        fun render(context: Ctx): Node = Node()
        fun renderTwo(context: Ctx, props: Int): Node = Node()
    }
    class WrapperLegacy {
        fun render(context: Ctx): Node = Node()
        fun unrelated(context: String): Node = Node()
    }

    class ComponentExpanded {
        fun render(delta: Int, context: Ctx, props: String): Node = Node()
    }
    class WrapperExpanded {
        fun render(delta: Int, context: Ctx, props: String): Node = Node()
    }

    class Effects
    class SecondaryNode
    class Facebook580Component {
        // Actual FB 580 layout: A1F(ctx), A1H(ctx), A1I(ctx,effects).
        fun first(context: Ctx): Node = Node()
        fun secondary(context: Ctx): SecondaryNode = SecondaryNode()
        fun effects(context: Ctx, effects: Effects): Effects = effects
    }
    class Facebook580Wrapper {
        fun first(context: Ctx): Node = Node()
        fun effects(context: Ctx, effects: Effects): Effects = effects
    }

    class ComponentAmbiguous {
        fun render(context: Ctx): Node = Node()
        fun other(context: OtherCtx): Node = Node()
    }
    class WrapperAmbiguous {
        fun render(context: Ctx): Node = Node()
        fun other(context: OtherCtx): Node = Node()
    }
    class WrapperUnrelated {
        fun render(context: OtherCtx): Node = Node()
    }

    @Test fun legacyPairStillChoosesSmallestCommonRenderShape() {
        val r = FeedLithoSignatures.resolve(ComponentLegacy::class.java, WrapperLegacy::class.java)
        assertNotNull(r)
        assertEquals(1, r!!.arity)
        assertEquals(0, r.contextIndex)
        assertEquals(Ctx::class.java, r.contextType)
        assertEquals(2, r.methods.size)
    }

    @Test fun newParamCountAndShiftedContextAreSupported() {
        val r = FeedLithoSignatures.resolve(ComponentExpanded::class.java, WrapperExpanded::class.java)
        assertNotNull(r)
        assertEquals(3, r!!.arity)
        assertEquals(1, r.contextIndex)
        assertEquals(2, r.methods.size)
    }

    @Test fun facebook580RetainsComponentOnlySecondaryLayoutMethod() {
        val r = FeedLithoSignatures.resolve(
            Facebook580Component::class.java, Facebook580Wrapper::class.java,
        )
        assertNotNull(r)
        assertEquals(1, r!!.arity)
        assertEquals(0, r.contextIndex)
        assertEquals(3, r.methods.size)
        assertEquals(setOf("first", "secondary"), r.methods
            .filter { it.declaringClass == Facebook580Component::class.java }
            .map { it.name }.toSet())
    }

    @Test fun ambiguousOrUnrelatedOwnersFailOpen() {
        assertNull(FeedLithoSignatures.resolve(ComponentAmbiguous::class.java,
            WrapperAmbiguous::class.java))
        assertNull(FeedLithoSignatures.resolve(ComponentLegacy::class.java,
            WrapperUnrelated::class.java))
    }
}
