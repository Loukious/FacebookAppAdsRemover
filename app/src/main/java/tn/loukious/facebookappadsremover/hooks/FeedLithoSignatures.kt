package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Match the common Litho layout seam on both feed component and its wrapper.
 * Method names and extra arguments can drift across Facebook releases; a
 * matching semantic context argument on BOTH owners is required. Do not hook
 * a method just because its arity or return type happens to look plausible.
 */
internal object FeedLithoSignatures {
    private const val MAX_PARAMS = 6
    private const val MAX_CONTEXT_INDEX = 2
    private const val MAX_METHODS_PER_OWNER = 4

    data class Resolution(
        val methods: List<Method>,
        val contextType: Class<*>,
        val contextIndex: Int,
        val arity: Int,
    )

    fun resolve(componentClass: Class<*>, wrapperClass: Class<*>): Resolution? {
        val component = candidates(componentClass)
        val wrapper = candidates(wrapperClass)
        // Existing FB576-580 1/2-argument signatures remain the first choice;
        // arities 3..6 and a shifted context param are bounded fallbacks.
        for (arity in 1..MAX_PARAMS) {
            for (contextIndex in 0..minOf(arity - 1, MAX_CONTEXT_INDEX)) {
                val componentGroup = component.filter { it.parameterCount == arity }
                val wrapperGroup = wrapper.filter { it.parameterCount == arity }
                val sharedContexts = componentGroup.map { it.parameterTypes[contextIndex] }
                    .intersect(wrapperGroup.map { it.parameterTypes[contextIndex] }.toSet())
                    .filter(::plausibleContext)
                if (sharedContexts.size != 1) continue // Fail open on ambiguity.
                val context = sharedContexts.single()
                val componentMethods = componentGroup.filter {
                    it.parameterTypes[contextIndex] == context
                }
                val wrapperMethods = wrapperGroup.filter {
                    it.parameterTypes[contextIndex] == context
                }
                if (componentMethods.isEmpty() || wrapperMethods.isEmpty()) continue
                if (componentMethods.size > MAX_METHODS_PER_OWNER ||
                    wrapperMethods.size > MAX_METHODS_PER_OWNER) continue
                val methods = (componentMethods + wrapperMethods)
                    .distinctBy { method -> method.declaringClass.name + method.name +
                        method.parameterTypes.joinToString { it.name } }
                return Resolution(methods, context, contextIndex, arity)
            }
        }
        return null
    }

    private fun candidates(owner: Class<*>): List<Method> = owner.declaredMethods.filter { method ->
        method.parameterCount in 1..MAX_PARAMS &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.isSynthetic && !method.isBridge &&
            !method.returnType.isPrimitive && !method.returnType.isArray &&
            method.returnType != Any::class.java &&
            !method.returnType.name.startsWith("java.") &&
            !method.returnType.name.startsWith("kotlin.")
    }

    private fun plausibleContext(type: Class<*>): Boolean =
        !type.isPrimitive && !type.isArray && !type.isInterface &&
            type != Any::class.java &&
            !type.name.startsWith("java.") &&
            !type.name.startsWith("kotlin.") &&
            !type.name.startsWith("android.")
}
