package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/** Resolves a promise owner behind the JavaScript wrapper and its request delegate. */
internal object GamePromiseResolver {
    data class Endpoint(val owner: Any, val resolve: Method, val reject: Method)

    fun find(target: Any, payloadType: Class<*>): Endpoint? {
        endpoint(target)?.let { return it }
        val candidates = IdentityHashMap<Any, Endpoint>()
        for (delegate in references(target)) {
            endpoint(delegate)?.let { candidates[delegate] = it }
            // Facebook 587: JavascriptInterface.A02 -> native request handler;
            // the handler's A08 points at the WebView owning JoT/Jfw promises.
            if (methods(delegate.javaClass).any { method ->
                    method.returnType == Void.TYPE && method.parameterTypes.toList() ==
                        listOf(String::class.java, String::class.java, payloadType)
                }) {
                for (owner in references(delegate)) {
                    endpoint(owner)?.let { candidates[owner] = it }
                }
            }
        }
        return candidates.values.singleOrNull()
    }

    private fun endpoint(owner: Any): Endpoint? {
        val methods = methods(owner.javaClass)
        val resolve = methods.filter {
            it.returnType == Void.TYPE && it.parameterTypes.toList() ==
                listOf(String::class.java, Any::class.java) && it.name != "postMessage" &&
                it.annotations.none { annotation ->
                    annotation.annotationClass.java.name == "android.webkit.JavascriptInterface"
                }
        }.singleOrNull() ?: return null
        val reject = methods.filter {
            it.returnType == Void.TYPE && it.parameterTypes.toList() ==
                listOf(String::class.java, String::class.java, String::class.java)
        }.singleOrNull() ?: return null
        return Endpoint(owner, resolve.apply { isAccessible = true }, reject.apply { isAccessible = true })
    }

    private fun methods(type: Class<*>): List<Method> =
        (type.declaredMethods + type.methods).filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                // WebView.loadData(String, String, String) shares the native reject shape.
                // Only app-owned helpers are candidates, including inherited Facebook methods.
                !it.declaringClass.name.startsWith("android.") &&
                !it.declaringClass.name.startsWith("java.") &&
                !it.declaringClass.name.startsWith("javax.")
        }.distinctBy { it.name + it.parameterTypes.joinToString { parameter -> parameter.name } }

    private fun references(owner: Any): List<Any> {
        val result = ArrayList<Any>()
        var type: Class<*>? = owner.javaClass
        while (type != null && type != Any::class.java &&
            !type.name.startsWith("android.") && !type.name.startsWith("java.") && result.size < 24) {
            for (field in type.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()?.let { value ->
                    val name = value.javaClass.name
                    if (!name.startsWith("java.") && !name.startsWith("android.")) result.add(value)
                }
                if (result.size >= 24) break
            }
            type = type.superclass
        }
        return result
    }
}
