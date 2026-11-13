package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test fun facebook587ExtraPrimaryWrapperShiftsKnownButtonSlots() {
        // Actual 587 shape: two primary wrappers, two labels, icon, boxed
        // flags, test id, two strings, four functions, style, four flags.
        val facebook587 = facebook578.toMutableList().apply {
            add(3, "X.Primary")
            add("boolean")
        }
        val layout = ReelFactorySignature.layout(facebook587)
        assertNotNull(layout)
        layout!!
        assertEquals(2, layout.argumentIndex(2))
        assertEquals(4, layout.argumentIndex(3))
        assertEquals(6, layout.argumentIndex(5))
        assertEquals(9, layout.argumentIndex(8))
        assertEquals(12, layout.argumentIndex(11))
        assertEquals(16, layout.argumentIndex(15))
        assertEquals(19, layout.argumentIndex(18))
        // Known slots map around the optional new secondary text, which the
        // production template preparation clears separately.
        val replaced = (2..18).map(layout::argumentIndex)
        assertFalse(3 in replaced)
        assertFalse(20 in replaced)
        assertTrue(ReelFactorySignature.accepts(facebook587 + List(3) { "boolean" }))

        val nativeSecondaryText = Any()
        val nativeSession = Any()
        val template = arrayOfNulls<Any>(facebook587.size).apply {
            this[0] = nativeSession
            this[3] = nativeSecondaryText
            this[20] = true
        }
        val copied = layout.copyTemplate(template)
        assertNull(copied[3])
        assertSame(nativeSession, copied[0])
        assertEquals(true, copied[20])
        assertSame(nativeSecondaryText, template[3])
    }

    @Test fun legacyTemplateKeepsItsPrimaryLabelAndExtraFlags() {
        val types = facebook578 + "boolean"
        val layout = ReelFactorySignature.layout(types)!!
        val nativeLabel = Any()
        val template = arrayOfNulls<Any>(types.size).apply {
            this[3] = nativeLabel
            this[19] = true
        }
        val copied = layout.copyTemplate(template)
        assertSame(nativeLabel, copied[3])
        assertEquals(true, copied[19])
    }

    @Test fun unrecognizedInsertedWrapperFailsClosed() {
        assertFalse(ReelFactorySignature.accepts(facebook578.toMutableList().apply {
            add(3, "X.UnverifiedWrapper")
        }))
    }

    @Test fun changedMeaningOfKnownSlotsFailsClosed() {
        assertFalse(ReelFactorySignature.accepts(facebook578.dropLast(1)))
        assertFalse(ReelFactorySignature.accepts(facebook578.take(8) + "int" + facebook578.drop(9)))
        assertFalse(ReelFactorySignature.accepts(facebook578.take(10) + "int" + facebook578.drop(11)))
        assertFalse(ReelFactorySignature.accepts(facebook578.take(11) + "java.lang.String" + facebook578.drop(11)))
        assertFalse(ReelFactorySignature.accepts(facebook578 + "java.lang.Object"))
    }
}
