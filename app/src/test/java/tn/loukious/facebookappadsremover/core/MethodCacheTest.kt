package tn.loukious.facebookappadsremover.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method

class MethodCacheTest {
    private val loader = javaClass.classLoader!!
    private val target = Target::class.java

    class Target {
        fun call() = 0
        fun call(value: Int) = value
        fun arrays(values: Array<String>, flags: BooleanArray) = values.size + flags.size
        fun objects(values: Array<String>) = values.size
        fun nested(values: Array<Array<String>>) = values.size
    }

    @Test fun completedScanWithoutMethodTargetsIsACacheHit() {
        val serialized = MethodCache.encodeMethods(emptyMap())
        val restored = MethodCache.decodeMethods(serialized, loader)
        assertNotNull(restored)
        assertEquals(emptyMap<String, List<Method>>(), restored)
    }

    @Test fun onlyAbsentMethodRolesStillProduceAValidEmptySnapshot() {
        val serialized = MethodCache.encodeMethods(mapOf("missing.anchor" to emptyList()))
        assertEquals("", serialized)
        assertNotNull(MethodCache.decodeMethods(serialized, loader))
    }

    @Test fun overloadedMethodsAndArrayParametersRoundTrip() {
        val methods = mapOf(
            "player" to listOf(
                target.getDeclaredMethod("call"),
                target.getDeclaredMethod("call", Int::class.javaPrimitiveType),
            ),
            "arrays" to listOf(
                target.getDeclaredMethod("arrays", Array<String>::class.java, BooleanArray::class.java),
                target.getDeclaredMethod("objects", Array<String>::class.java),
                target.getDeclaredMethod("nested", Array<Array<String>>::class.java),
            ),
        )
        assertEquals(methods, MethodCache.decodeMethods(MethodCache.encodeMethods(methods), loader))
    }

    @Test fun oneStaleMethodRejectsTheWholeSnapshot() {
        val valid = MethodCache.encodeMethods(mapOf("player" to listOf(target.getDeclaredMethod("call"))))
        assertNull(MethodCache.decodeMethods(valid + "stale=${target.name}#removed:;\n", loader))
    }

    @Test fun missingParameterTypeCannotResolveToTheZeroArgumentOverload() {
        assertNull(MethodCache.decodeMethods("player=${target.name}#call:missing.Type;\n", loader))
    }

    @Test fun malformedNonemptySnapshotsRequireRediscovery() {
        for (serialized in listOf(
            "truncated", "=missingKey", "player=", "player=;", "player=notAMethod;",
            "player=missing.Type#call:;", "player=${target.name}#call;",
        )) {
            assertNull(serialized, MethodCache.decodeMethods(serialized, loader))
        }
    }
}
