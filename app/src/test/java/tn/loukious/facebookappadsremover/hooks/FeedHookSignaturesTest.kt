package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedHookSignaturesTest {
    private val session = "com.facebook.auth.usersession.FbUserSession"
    private val list = "com.google.common.collect.ImmutableList"
    private val STRING = "java.lang.String"
    private val unknown = "X.Obfuscated"
    private val result = "X.FilterResult"

    private fun method(
        name: String,
        vararg params: String,
        returns: String = result,
        public: Boolean = true,
        static: Boolean = false,
        synthetic: Boolean = false,
        bridge: Boolean = false,
        abstract: Boolean = false,
    ) = FeedHookSignatures.MethodInfo(
        owner = "X.Owner", name = name, parameters = params.toList(), returnType = returns,
        isPublic = public, isStatic = static, isSynthetic = synthetic,
        isBridge = bridge, isAbstract = abstract,
    )

    @Test fun csr580MatchesPublicEntryPointAndExcludesPrivateHelper() {
        // classes2/X/1xc.smali (580): private A00 is a different six-arg
        // method with the same session/list anchors; public ApC is the hook.
        val helper = method("A00", session, list, "java.util.List", "int", "long", "boolean", public = false)
        val entry = method("ApC", session, unknown, list, "int")
        val match = FeedHookSignatures.csr(listOf(helper, entry))
        assertEquals(entry, match?.method)
        assertEquals(2, match?.listArgIndex)
    }

    @Test fun csrSurvivesInsertionAndReorderingAndReportsActualListIndex() {
        val shifted = method("Axy", "boolean", list, "int", session, unknown, "java.lang.String")
        val match = FeedHookSignatures.csr(listOf(shifted))
        assertEquals(shifted, match?.method)
        assertEquals(1, match?.listArgIndex)
        assertEquals(1, FeedHookSignatures.csr(listOf(method("Axy", session, list, "int")))?.listArgIndex)
    }

    @Test fun csrPrefersExactShapeOverSemanticFallback() {
        val reordered = method("candidate", "boolean", list, "int", session)
        val established = method("entry", session, unknown, list, "int")
        assertEquals(established, FeedHookSignatures.csr(listOf(reordered, established))?.method)
        val oldThreeArg = method("old", session, list, "int")
        assertEquals(oldThreeArg, FeedHookSignatures.csr(listOf(reordered, oldThreeArg))?.method)
        // Two established candidates are ambiguous, even if their arities differ.
        assertNull(FeedHookSignatures.csr(listOf(established, oldThreeArg)))
        assertNull(FeedHookSignatures.csr(listOf(established, method("other", session, unknown, list, "int"))))
    }

    @Test fun csrFallbackIsBoundedToSevenArguments() {
        val seven = method("seven", "boolean", list, "int", "long", session, unknown, STRING)
        assertEquals(1, FeedHookSignatures.csr(listOf(seven))?.listArgIndex)
        assertNull(FeedHookSignatures.csr(listOf(method("eight", "boolean", list, "int", "long", session, unknown, STRING, "float"))))
    }

    @Test fun csrRejectsMissingDuplicateOrAmbiguousAnchorSignatures() {
        assertNull(FeedHookSignatures.csr(listOf(method("a", session, unknown, "int"))))
        assertNull(FeedHookSignatures.csr(listOf(method("a", session, list, list, "int"))))
        assertNull(FeedHookSignatures.csr(listOf(method("a", session, list, "int", "int"))))
        assertNull(FeedHookSignatures.csr(listOf(method("a", session, list, "int", returns = "void"))))
        assertNull(FeedHookSignatures.csr(listOf(method("a", session, list, "int", returns = "java.lang.Object"))))
        val first = method("a", session, list, "int")
        val second = method("b", session, unknown, list, "int")
        assertNull(FeedHookSignatures.csr(listOf(first, second)))
    }

    @Test fun csrRejectsStaticSyntheticBridgeAbstractAndPrivateCandidates() {
        val core = arrayOf(session, unknown, list, "int")
        assertNull(FeedHookSignatures.csr(listOf(method("static", *core, static = true))))
        assertNull(FeedHookSignatures.csr(listOf(method("synthetic", *core, synthetic = true))))
        assertNull(FeedHookSignatures.csr(listOf(method("bridge", *core, bridge = true))))
        assertNull(FeedHookSignatures.csr(listOf(method("abstract", *core, abstract = true))))
        assertNull(FeedHookSignatures.csr(listOf(method("private", *core, public = false))))
    }

    @Test fun storage580AndReorderedExtraParametersAreResolved() {
        // classes2/X/1vb.smali (580): A07(X/1xw, ImmutableList, int)V.
        val original = method("A07", unknown, list, "int", returns = "void")
        assertEquals(1, FeedHookSignatures.late(listOf(original), FeedHookSignatures.LateKind.STORAGE)?.listArgIndex)
        val changed = method("A07", "boolean", "int", list, unknown, returns = "void")
        assertEquals(2, FeedHookSignatures.late(listOf(changed), FeedHookSignatures.LateKind.STORAGE)?.listArgIndex)
    }

    @Test fun storagePrefersEstablishedSignatureOverAdditionalCandidates() {
        val exact = method("exact", unknown, list, "int", returns = "void")
        val fallback = method("reordered", "int", list, unknown, "boolean", returns = "void")
        assertEquals(exact, FeedHookSignatures.late(listOf(fallback, exact), FeedHookSignatures.LateKind.STORAGE)?.method)
        assertNull(FeedHookSignatures.late(listOf(exact, method("alsoExact", unknown, list, "int", returns = "void")), FeedHookSignatures.LateKind.STORAGE))
    }

    @Test fun vending580AndReorderedExtraParametersAreResolved() {
        // classes2/X/1wj.smali (580): A09(ImmutableList, String)V.
        val original = method("A09", list, "java.lang.String", returns = "void")
        assertEquals(0, FeedHookSignatures.late(listOf(original), FeedHookSignatures.LateKind.VENDING)?.listArgIndex)
        val changed = method("A09", "java.lang.String", "boolean", list, returns = "void")
        assertEquals(2, FeedHookSignatures.late(listOf(changed), FeedHookSignatures.LateKind.VENDING)?.listArgIndex)
    }

    @Test fun vendingPrefersEstablishedSignatureOverAdditionalCandidates() {
        val exact = method("exact", list, STRING, returns = "void")
        val fallback = method("reordered", STRING, "boolean", list, returns = "void")
        assertEquals(exact, FeedHookSignatures.late(listOf(fallback, exact), FeedHookSignatures.LateKind.VENDING)?.method)
        assertNull(FeedHookSignatures.late(listOf(exact, method("alsoExact", list, STRING, returns = "void")), FeedHookSignatures.LateKind.VENDING))
    }

    @Test fun lifecycleWithReorderedExtraParametersIsResolved() {
        val original = method("a", session, unknown, list, returns = "void")
        assertEquals(2, FeedHookSignatures.late(listOf(original), FeedHookSignatures.LateKind.LIFECYCLE)?.listArgIndex)
        val changed = method("z", list, "boolean", unknown, session, returns = "void")
        assertEquals(0, FeedHookSignatures.late(listOf(changed), FeedHookSignatures.LateKind.LIFECYCLE)?.listArgIndex)
    }

    @Test fun lifecyclePrefersEstablishedSignatureOverAdditionalCandidates() {
        val exact = method("exact", session, unknown, list, returns = "void")
        val fallback = method("reordered", list, unknown, session, returns = "void")
        assertEquals(exact, FeedHookSignatures.late(listOf(fallback, exact), FeedHookSignatures.LateKind.LIFECYCLE)?.method)
        assertNull(FeedHookSignatures.late(listOf(exact, method("alsoExact", session, unknown, list, returns = "void")), FeedHookSignatures.LateKind.LIFECYCLE))
    }

    @Test fun lateFallbackIsBoundedToSevenArguments() {
        val seven = method("seven", "boolean", STRING, list, "long", "int", unknown, "float", returns = "void")
        assertEquals(2, FeedHookSignatures.late(listOf(seven), FeedHookSignatures.LateKind.STORAGE)?.listArgIndex)
        val eight = method("eight", "boolean", STRING, list, "long", "int", unknown, "float", "double", returns = "void")
        assertNull(FeedHookSignatures.late(listOf(eight), FeedHookSignatures.LateKind.STORAGE))
    }

    @Test fun lateRejectsWrongKindReturnDuplicatesAndCompetingMethods() {
        val storage = method("a", unknown, list, "int", returns = "void")
        assertNull(FeedHookSignatures.late(listOf(storage), FeedHookSignatures.LateKind.VENDING))
        assertNull(FeedHookSignatures.late(listOf(storage), FeedHookSignatures.LateKind.LIFECYCLE))
        assertNull(FeedHookSignatures.late(listOf(method("a", unknown, list, "int")), FeedHookSignatures.LateKind.STORAGE))
        assertNull(FeedHookSignatures.late(listOf(method("a", unknown, list, list, "int", returns = "void")), FeedHookSignatures.LateKind.STORAGE))
        assertNull(FeedHookSignatures.late(listOf(method("a", session, list, returns = "void")), FeedHookSignatures.LateKind.LIFECYCLE))
        val fallbackA = method("a", "int", list, unknown, "boolean", returns = "void")
        val fallbackB = method("b", list, unknown, "int", "boolean", returns = "void")
        assertNull(FeedHookSignatures.late(listOf(fallbackA, fallbackB), FeedHookSignatures.LateKind.STORAGE))
    }

    @Test fun reflectionAdapterPreservesOrderAndVisibility() {
        val actual = LocalFixture::class.java.getDeclaredMethod("localMethod", String::class.java, Int::class.javaPrimitiveType)
        val described = FeedHookSignatures.describe(actual)
        assertEquals(listOf("java.lang.String", "int"), described.parameters)
        assertEquals("java.lang.String", described.returnType)
        assertEquals("localMethod", described.name)
        assertTrue(described.isPublic)
        assertFalse(described.isStatic)
    }

    class LocalFixture {
        fun localMethod(label: String, value: Int): String = "$label$value"
    }
}
