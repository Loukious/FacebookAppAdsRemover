package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelFactorySignatureTest {
    private val facebook578 = listOf(
        "com.facebook.auth.usersession.FbUserSession", "X.Mode", "X.Primary",
        "X.Label", "X.Label", "X.Icon", "java.lang.Boolean",
        "java.lang.Boolean", "java.lang.Object", "java.lang.String",
        "java.lang.String", "kotlin.jvm.functions.Function1",
        "kotlin.jvm.functions.Function1", "kotlin.jvm.functions.Function1",
        "kotlin.jvm.functions.Function1", "int", "boolean", "boolean", "boolean",
    )

    @Test fun facebook578And580AreCompatible() {
        assertTrue(ReelFactorySignature.accepts(facebook578))
        assertTrue(ReelFactorySignature.accepts(facebook578.toMutableList().apply {
            this[8] = "java.lang.String"
        }))
        assertTrue(ReelFactorySignature.accepts(facebook578 + "boolean"))
    }

    @Test fun futureTrailingFlagsArePreserved() {
        assertTrue(ReelFactorySignature.accepts(facebook578 + List(4) { "boolean" }))
    }

    @Test fun changedMeaningOfKnownSlotsFailsClosed() {
        assertFalse(ReelFactorySignature.accepts(facebook578.dropLast(1)))
        assertFalse(ReelFactorySignature.accepts(facebook578.take(8) + "int" + facebook578.drop(9)))
        assertFalse(ReelFactorySignature.accepts(facebook578.take(10) + "int" + facebook578.drop(11)))
        assertFalse(ReelFactorySignature.accepts(facebook578.take(11) + "java.lang.String" + facebook578.drop(11)))
        assertFalse(ReelFactorySignature.accepts(facebook578 + "java.lang.Object"))
    }
}
