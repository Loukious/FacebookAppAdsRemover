package tn.loukious.facebookappadsremover.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoledMethodRegistryTest {
    private class Resolver {
        fun resolve(): Int = 0
        fun resolve(token: Int): Int = token
    }

    private class OtherResolver {
        fun resolve(): Int = 0
    }

    @Test
    fun repeatedReflectionProbesRegisterEachResolverOnlyOnce() {
        val registry = AmoledMethodRegistry()
        val first = Resolver::class.java.getDeclaredMethod("resolve")
        var registrations = 0
        assertTrue(registry.installOnce(first) { registrations++ })

        repeat(5) {
            val fresh = Resolver::class.java.declaredMethods.single { it.parameterCount == 0 }
            assertNotSame(first, fresh)
            assertFalse(registry.installOnce(fresh) { registrations++ })
        }

        assertEquals(1, registrations)
    }

    @Test
    fun failedRegistrationCanBeRetriedWithAFreshReflectionMethod() {
        val registry = AmoledMethodRegistry()
        var attempts = 0
        val failure = IllegalStateException("framework hook rejected")
        val first = Resolver::class.java.getDeclaredMethod("resolve")
        val result = runCatching {
            registry.installOnce(first) {
                attempts++
                throw failure
            }
        }
        assertEquals(failure, result.exceptionOrNull())

        val fresh = Resolver::class.java.getDeclaredMethod("resolve")
        assertNotSame(first, fresh)
        assertTrue(registry.installOnce(fresh) { attempts++ })
        assertFalse(registry.installOnce(first) { attempts++ })
        assertEquals(2, attempts)
    }

    @Test
    fun overloadsAndDifferentDeclaringClassesKeepSeparateHooks() {
        val registry = AmoledMethodRegistry()
        val methods = listOf(
            Resolver::class.java.getDeclaredMethod("resolve"),
            Resolver::class.java.getDeclaredMethod("resolve", Int::class.javaPrimitiveType),
            OtherResolver::class.java.getDeclaredMethod("resolve"),
        )
        var registrations = 0
        methods.forEach { assertTrue(registry.installOnce(it) { registrations++ }) }
        methods.forEach { method ->
            val fresh = method.declaringClass.getDeclaredMethod(method.name, *method.parameterTypes)
            assertFalse(registry.installOnce(fresh) { registrations++ })
        }
        assertEquals(3, registrations)
    }
}
