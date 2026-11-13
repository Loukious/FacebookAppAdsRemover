package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Delivers the SDK callback sequence only when an actual reward can complete. */
internal object GameRewardCallbacks {
    val completionNames = setOf(
        "onRewardedVideoCompleted", "onRewardedAdCompleted", "onRewardedInterstitialCompleted",
        "onAdComplete", "onAdCompleted",
    )
    private val loadNames = setOf("onAdLoaded", "onLoggingImpression", "onInterstitialDisplayed")
    private val closeNames = setOf(
        "onRewardedVideoClosed", "onRewardedInterstitialClosed", "onAdClosed", "onInterstitialDismissed",
    )

    data class Delivery(val invoked: Int, val completed: Int)

    fun isListener(type: Class<*>): Boolean {
        val seen = HashSet<Class<*>>()
        fun listenerInterface(current: Class<*>?): Boolean {
            if (current == null || !seen.add(current)) return false
            if (current.isInterface && current.name.startsWith("com.facebook.ads.") &&
                current.name.endsWith("AdListener")) return true
            return current.interfaces.any(::listenerInterface) || listenerInterface(current.superclass)
        }
        if (listenerInterface(type)) return true
        return (type.declaredMethods + type.methods).any {
            it.name in completionNames && it.returnType == Void.TYPE && it.parameterCount <= 1 &&
                !Modifier.isStatic(it.modifiers)
        }
    }

    /** 587 load config -> SDK implementation -> callback owner -> listener. */
    fun findListeners(root: Any?, mayTraverse: (Any) -> Boolean): List<Any> {
        if (root == null) return emptyList()
        val found = ArrayList<Any>()
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        fun enqueue(value: Any?, depth: Int) {
            if (value != null && (isListener(value.javaClass) || mayTraverse(value))) {
                queue.add(value to depth)
            }
        }
        queue.add(root to 0)
        while (queue.isNotEmpty() && seen.size < 96 && found.size < 8) {
            val (value, depth) = queue.removeFirst()
            if (seen.put(value, true) != null) continue
            if (isListener(value.javaClass)) {
                found.add(value)
                continue
            }
            if (depth >= 5 || !mayTraverse(value)) continue
            var type: Class<*>? = value.javaClass
            var fields = 0
            while (type != null && type != Any::class.java &&
                !type.name.startsWith("android.") && !type.name.startsWith("java.") && fields < 48) {
                for (field in type.declaredFields) {
                    if (fields >= 48) break
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    fields++
                    val child = runCatching {
                        field.isAccessible = true
                        field.get(value)
                    }.getOrNull() ?: continue
                    // Older SDKs keep callbacks in a collection field. Its entries occupy
                    // this field hop, without opening arbitrary collection internals.
                    when (child) {
                        is Iterable<*> -> child.take(12).forEach { enqueue(it, depth + 1) }
                        is Array<*> -> child.take(12).forEach { enqueue(it, depth + 1) }
                        else -> enqueue(child, depth + 1)
                    }
                }
                type = type.superclass
            }
        }
        return found
    }

    fun deliver(
        listener: Any,
        ad: Any,
        methods: List<Method>,
        onInvoked: (Method) -> Unit = {},
        onFailure: (Method, Throwable) -> Unit = { _, _ -> },
    ): Delivery {
        val compatible = methods.filter {
            !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                (it.parameterCount == 0 ||
                    (it.parameterCount == 1 && it.parameterTypes[0].isInstance(ad)))
        }.distinctBy { it.name + it.parameterTypes.joinToString { type -> type.name } }
        if (compatible.none { it.name in completionNames }) return Delivery(0, 0)

        var invoked = 0
        var completed = 0
        for (phase in listOf(loadNames, completionNames, closeNames)) {
            if (phase === closeNames && completed == 0) break
            for (method in compatible.filter { it.name in phase }) {
                try {
                    method.isAccessible = true
                    if (method.parameterCount == 0) method.invoke(listener) else method.invoke(listener, ad)
                    invoked++
                    if (method.name in completionNames) completed++
                    onInvoked(method)
                } catch (t: Throwable) {
                    onFailure(method, t)
                }
            }
        }
        return Delivery(invoked, completed)
    }
}
