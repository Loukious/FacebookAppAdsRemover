package tn.loukious.facebookappadsremover.hooks

import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/** Shared, bounded GraphQL TreeJNI AI-transparency check across all feed paths. */
internal object AiTransparencyInspector {
    private const val FEED_EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
    private const val STORY_CLASS = "com.facebook.graphql.model.GraphQLStory"
    /** GraphQLFeedUnitEdge.node; FB 580's non-inflating virtual cache key. */
    private const val PRIMARY_NODE_HASH = 0x0033ae02
    // The story also carries gen_ai_transparency_label_info (0x39dd8998),
    // but its existence and "AI content" title are *not* a safe keep/remove
    // decision: ordinary stories carry that optional label data on FB 580.
    private const val DETECTED_INFO_HASH = -0x4b06197c
    private const val DETECTED_BOOLEAN_HASH = 0x723ea5fe
    private const val DISCLOSURE_INFO_HASH = 0x73da0c74
    private const val DISCLOSURE_BOOLEAN_HASH = -0x439184bd
    private val AI_FIELD_HASHES = intArrayOf(
        -1133610173, // was_self_disclosed_as_ai_generated
        1097347298,  // is_self_disclosed_as_ai_generated
        1916708350,  // was_detected_as_ai_generated
    )

    /** Visited-object cap — the walk is bounded even on surprise shapes. */
    private const val MAX_VISIT = 96

    /** edge -> holder -> story -> transparency (+ one wrapper for drift). */
    private const val MAX_DEPTH = 4

    /** Optional.empty = "checked, no hasFieldValue method" (ConcurrentHashMap holds no nulls). */
    private val hasFieldValueCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()
    private val cachedBooleanCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()
    private val booleanValueCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()
    private val getTreeCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()
    private val virtualModelCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()

    /** TreeJNI-model getters per class, resolved once. */
    private val modelGetterCache = ConcurrentHashMap<Class<*>, List<Method>>()

    /**
     * Root class → the getter chain that reached the transparency holder
     * (edge getter → holder getter). Once found, later rows walk only the
     * cached path instead of re-probing every branch.
     */
    private val pathCache = ConcurrentHashMap<Class<*>, List<Method>>()

    fun isAiContent(root: Any?): Boolean {
        if (root == null) return false
        if (root.javaClass.name == FEED_EDGE_CLASS) {
            // FeedUnitEdge.A03/BOq return an interface (X.3Te on FB 580),
            // implemented by its primary GraphQLStory. Following arbitrary
            // related-story/attachment getters can remove innocent posts.
            return primaryStory(root)?.let(::isPrimaryStoryAi) ?: false
        }
        if (root.javaClass.name == STORY_CLASS) return isPrimaryStoryAi(root)
        pathCache[root.javaClass]?.let { path ->
            val result = runPath(root, path)
            if (result == true) return true
            // A cached path which was positive on a previous row may be
            // false on this row while a different disclosure branch is true.
            // Do not turn pathCache into a permanent negative cache.
        }
        val path = ArrayList<Method>()
        return walk(root, root.javaClass, path,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    }

    private fun primaryStory(edge: Any): Any? {
        // Do not invoke unknown interface-returning getters: on FB 580,
        // GraphQLFeedUnitEdge.BOq() calls inflateFeedUnit() as a side effect.
        // The stable BaseModelWithTree API reads the GraphQL `node` field
        // directly, equivalent to A03() without invoking BOq().
        val getter = reflectiveMethod(virtualModelCache, edge.javaClass,
            "getCachedVirtualModel") ?: return null
        val value = runCatching { getter.invoke(edge, PRIMARY_NODE_HASH) }.getOrNull()
        return value?.takeIf { it.javaClass.name == STORY_CLASS }
    }

    private fun isPrimaryStoryAi(story: Any): Boolean {
        if (story.javaClass.name != STORY_CLASS) return false
        val getTree = reflectiveMethod(getTreeCache, story.javaClass, "getTree")
            ?: return false
        for ((holderHash, flagHash) in arrayOf(
            DETECTED_INFO_HASH to DETECTED_BOOLEAN_HASH,
            DISCLOSURE_INFO_HASH to DISCLOSURE_BOOLEAN_HASH,
        )) {
            val holder = runCatching { getTree.invoke(story, holderHash) }.getOrNull()
                ?: continue
            // Facebook's own transparency plugin uses getBooleanValue and
            // doesn't first require native hasFieldValue: Java-side cache and
            // TreeJNI-only models can otherwise yield false negatives.
            if (booleanFlag(holder, flagHash)) return true
        }
        return false
    }

    private fun booleanFlag(holder: Any, hash: Int): Boolean =
        runCatching { cachedBooleanOf(holder.javaClass)?.invoke(holder, hash) == true }
            .getOrDefault(false) ||
            runCatching { booleanValueOf(holder.javaClass)?.invoke(holder, hash) == true }
                .getOrDefault(false)

    private fun runPath(root: Any, path: List<Method>): Boolean? {
        var obj: Any = root
        for (m in path) {
            obj = runCatching { m.invoke(obj) }.getOrNull() ?: return false
        }
        return hasFlag(obj)
    }

    /** Depth-first walk over TreeJNI-model getters; records the path on success. */
    private fun walk(obj: Any, rootClass: Class<*>, path: MutableList<Method>, seen: MutableSet<Any>): Boolean {
        if (seen.size > MAX_VISIT || !seen.add(obj)) return false
        if (isSkippable(obj)) return false
        if (hasFlag(obj)) {
            if (path.isNotEmpty()) pathCache[rootClass] = ArrayList(path)
            return true
        }
        if (path.size >= MAX_DEPTH) return false
        for (m in modelGetters(obj.javaClass)) {
            val v = runCatching { m.invoke(obj) }.getOrNull() ?: continue
            path.add(m)
            if (walk(v, rootClass, path, seen)) return true
            path.removeAt(path.lastIndex)
        }
        return false
    }

    /** A field may exist with value false: require an affirmative Boolean. */
    private fun hasFlag(obj: Any): Boolean {
        val cachedBoolean = cachedBooleanOf(obj.javaClass)
        val booleanValue = booleanValueOf(obj.javaClass)
        for (hash in AI_FIELD_HASHES) {
            val positive = runCatching {
                (cachedBoolean?.invoke(obj, hash) == true ||
                    booleanValue?.invoke(obj, hash) == true)
            }.getOrDefault(false)
            if (positive) return true
        }
        return false
    }

    private fun reflectiveMethod(
        cache: ConcurrentHashMap<Class<*>, java.util.Optional<Method>>,
        cls: Class<*>,
        name: String,
    ): Method? {
        cache[cls]?.let { return it.orElse(null) }
        var c: Class<*>? = cls
        var found: Method? = null
        while (c != null && found == null) {
            val current = c
            found = runCatching {
                current.getDeclaredMethod(name, Int::class.javaPrimitiveType).also {
                    it.isAccessible = true
                }
            }.getOrNull()
            c = current.superclass
        }
        cache[cls] = java.util.Optional.ofNullable(found)
        return found
    }

    private fun booleanValueOf(cls: Class<*>): Method? =
        reflectiveMethod(booleanValueCache, cls, "getBooleanValue")

    private fun cachedBooleanOf(cls: Class<*>): Method? {
        cachedBooleanCache[cls]?.let { return it.orElse(null) }
        var c: Class<*>? = cls
        var found: Method? = null
        while (c != null && found == null) {
            val cur = c
            found = runCatching {
                cur.getDeclaredMethod("getCachedBoolean", Int::class.javaPrimitiveType)
                    .also { it.isAccessible = true }
            }.getOrNull()
            c = cur.superclass
        }
        cachedBooleanCache[cls] = java.util.Optional.ofNullable(found)
        return found
    }

    private fun hasFieldValueOf(cls: Class<*>): Method? {
        hasFieldValueCache[cls]?.let { return it.orElse(null) }
        var c: Class<*>? = cls
        var found: Method? = null
        while (c != null && found == null) {
            val cur: Class<*> = c
            found = runCatching {
                cur.getDeclaredMethod("hasFieldValue", Int::class.javaPrimitiveType)
            }.getOrNull()
            c = cur.superclass
        }
        hasFieldValueCache[cls] = java.util.Optional.ofNullable(found)
        return found?.also { it.isAccessible = true }
    }

    /** No-arg instance getters returning another TreeJNI model. */
    private fun modelGetters(cls: Class<*>): List<Method> {
        modelGetterCache[cls]?.let { return it }
        val result = ArrayList<Method>()
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.parameterCount != 0) continue
                if (java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                if (m.isSynthetic || m.isBridge) continue
                if (m.returnType == cls || !isTreeModel(m.returnType)) continue
                m.isAccessible = true
                result.add(m)
            }
            c = c.superclass
        }
        // The feed edge has numerous TreeJNI getters. Inspect the stable
        // GraphQLStory return type first so an unrelated nested subtree can't
        // exhaust the visit/depth caps before the disclosure is reached.
        result.sortWith(compareBy<Method> {
            when (it.returnType.name) {
                STORY_CLASS -> 0
                "com.facebook.graphql.model.GraphQLFeedUnitEdge" -> 1
                else -> 2
            }
        })
        modelGetterCache[cls] = result
        return result
    }

    /** A TreeJNI model: its hierarchy declares hasFieldValue(int).
     * Facebook's obfuscated models are X.*; package-prefix filtering
     * silently excluded them on FB 580 despite their TreeJNI inheritance.
     */
    private fun isTreeModel(cls: Class<*>): Boolean =
        !cls.isPrimitive && cls != Void.TYPE &&
            !cls.isArray && !cls.isInterface &&
            hasFieldValueOf(cls) != null

    /** Value types we never reflect into. */
    private fun isSkippable(obj: Any): Boolean {
        val n = obj.javaClass.name
        return n.startsWith("java.") || n.startsWith("android.") ||
            n.startsWith("kotlin.") || n.startsWith("com.google.")
    }
}
