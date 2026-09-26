package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * M2.5 newsfeed content filter — port of the original mod's content-filter
 * installer (libnc.so, EQRQYtm1nsiKbj6wysU, .c 118019–118734).
 *
 * The mod hooked the processNewStories Runnable (its build: X.2Qj.run;
 * 576.0.0.42.73: X.2rJ = FeedUnitCollectionManager$processNewStories$…$1).
 * The Runnable's run() opens with:
 *
 *     C2WY c2wy = this.A01;
 *     ImmutableCollection immutableCollection = c2wy.A05;   // new-story edges
 *
 * The mod's beforeHookedMethod filtered that collection (dropping edges by
 * GraphQLFeedStoryCategory) and wrote the survivor list back via
 * ImmutableList.copyOf, so run() only ever saw the filtered set.
 *
 * The mod's category→toggle map was recovered from the decrypted string
 * order inside the native callback — each block is [category compare →
 * toggle read → counter write], interleaved as:
 *
 *   SPONSORED            ← swHOME_ADS              ("Remove 'Sponsored' posts")
 *   PROMOTION            ← swHOME_THREADS          ("Remove posts from Threads")
 *   FB_SHORTS            ← swHOME_REELS            ("Remove Reels")
 *   ENGAGEMENT           ← swHOME_GOIY             ("Remove 'Suggested for you'")
 *   ENGAGEMENT_QP        ← swNhungNguoiBanCoTheBiet ("Remove friend suggestions")
 *   MULTI_FB_STORIES_TRAY← Story24hInNewsFeed      ("Remove Stories in feed")
 *
 * Nothing here references an obfuscated name: the Runnable is found by its
 * unique literal "Added stories to FUC", the edge model and the category enum
 * keep their real names (com.facebook.graphql.model.GraphQLFeedUnitEdge /
 * com.crossapp.graphql.facebook.enums.GraphQLFeedStoryCategory), and the
 * category getter is resolved by return type — the same anchors survive every
 * release.
 */
object NewsfeedFilterHook {

    private const val TAG = "FBAR.Feed"

    // Toggle keys live in core.Settings (defaults mirror the mod's ship
    // state: sponsored removal on, the content-type filters opt-in). The
    // The original mod had one swHOME_ADS master; this port gates SPONSORED
    // only on ads.newsFeed. Other feed categories have independent switches.

    private const val EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
    private const val CATEGORY_ENUM = "com.crossapp.graphql.facebook.enums.GraphQLFeedStoryCategory"
    private const val IMMUTABLE_COLLECTION = "com.google.common.collect.ImmutableCollection"
    private const val IMMUTABLE_LIST = "com.google.common.collect.ImmutableList"

    /** Anchor literal — unique in the secondary dex, lives in the Runnable itself. */
    private const val RUNNABLE_ANCHOR = "Added stories to FUC"

    @Volatile private var appClassLoader: ClassLoader? = null
    @Volatile private var uiFallbackInstalled = false

    private const val AI_UI_LABEL = "AI content"

    /** Resolved-once reflection members (the mod cached its field lookups too). */
    private var edgeClass: Class<*>? = null
    private var categoryClass: Class<*>? = null
    private var copyOf: Method? = null
    private val categoryGetterCache = ConcurrentHashMap<Class<*>, Method>()
    private val collectionFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val categoryGetterMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val collectionFieldMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private var aiScanBatches = 0

    fun init(context: Context) {
        appClassLoader = context.classLoader
        val on = enabledCategories()
        val ai = Settings.getBoolean(Settings.FEED_AI_CONTENT, false)
        val kw = keywordList()
        L.i(TAG, "Newsfeed filter categories: ${if (on.isEmpty()) "(none)" else on.joinToString()}" +
                ", aiContent=$ai, keywords=${if (kw.isEmpty()) "(none)" else kw.size}")
    }

    /**
     * Semantic fallback for already-restored/cached feed rows that can bypass
     * processNewStories. Facebook 580 exposes the disclosure as an accessibility
     * label (`AI content`) inside the top-level RecyclerView item. Hooking the
     * stable AndroidX lifecycle keeps this independent of obfuscated X.* names.
     */
    @Synchronized
    fun installUiFallback(
        module: XposedInterface,
        classLoader: ClassLoader,
    ): Boolean {
        if (uiFallbackInstalled) return true
        val recycler = runCatching {
            Class.forName("androidx.recyclerview.widget.RecyclerView", false, classLoader)
        }.getOrNull() ?: return false
        val onLayout = recycler.declaredMethods.firstOrNull {
            it.name == "onLayout" && it.parameterCount == 5
        } ?: return false
        return runCatching {
            onLayout.isAccessible = true
            module.hook(onLayout).intercept(AiUiFallbackHook)
            View::class.java.getDeclaredMethod(
                "setContentDescription", CharSequence::class.java,
            ).let { module.hook(it).intercept(AiDescriptionHook) }
            uiFallbackInstalled = true
            L.i(TAG, "AI-content UI fallback installed (RecyclerView layout + semantic label)")
            true
        }.getOrElse {
            L.w(TAG, "AI-content UI fallback hook failed", it)
            false
        }
    }

    private fun enabledCategories(): Set<String> = FeedContentRules.enabledCategoryNames()

    /** The keyword list, split on commas/semicolons/newlines, lowercased. */
    private fun keywordList(): List<String> =
        Settings.getString(Settings.FEED_KEYWORDS, "")
            .split(',', ';', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    /** Classic-feed adapter: rules live in FeedContentRules, not this hook. */
    private val classicSignals = object : FeedItemSignals {
        override fun category(item: Any): String? = categoryOf(item)
        override fun sponsored(item: Any): Boolean = categoryOf(item) == "SPONSORED"
        override fun aiContent(item: Any): Boolean = AiTransparencyInspector.isAiContent(item)
        override fun searchableText(item: Any): String? =
            runCatching { item.toString() }.getOrNull()
    }

    /**
     * Finds the processNewStories Runnable class via DexKit and hooks its
     * run() — the exact hook the mod installed (X.2Qj.run, beforeHookedMethod).
     *
     * @return the Runnable class name when found, for the discovery cache.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): String? {
        val hits = runCatching {
            bridge.findClass {
                matcher { addUsingString(RUNNABLE_ANCHOR, StringMatchType.Equals) }
            }
        }.getOrElse {
            L.w(TAG, "DexKit query failed for newsfeed filter", it)
            return null
        }
        val className = hits.firstOrNull()?.name
        if (className == null) {
            L.w(TAG, "NOT_FOUND newsfeed processNewStories Runnable (anchor: $RUNNABLE_ANCHOR)")
            return null
        }
        return if (hookRunnable(module, classLoader, className)) className else null
    }

    /** Cache-hit path: hook the previously discovered Runnable directly. */
    fun installCached(module: XposedInterface, classLoader: ClassLoader, className: String): Boolean =
        hookRunnable(module, classLoader, className)

    private fun hookRunnable(module: XposedInterface, classLoader: ClassLoader, className: String): Boolean {
        val cls = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (cls == null) {
            L.w(TAG, "class resolve failed: $className")
            return false
        }
        val run = runCatching { cls.getDeclaredMethod("run") }.getOrNull()
        if (run == null) {
            L.w(TAG, "no run() on $className — not the Runnable?")
            return false
        }
        runCatching {
            module.hook(run).intercept(FilterHook)
            FeedFilterDiagnostics.installed(FeedPipeline.CLASSIC)
            FeedFilterDiagnostics.scheduleHealth()
            L.i(TAG, "hooked processNewStories Runnable: $className.run()")
        }.onFailure {
            L.w(TAG, "hook failed on $className.run()", it)
            return false
        }

        return true
    }

    /**
     * beforeHookedMethod port: filter this.A01.A05 (the new-story
     * ImmutableCollection) by story category, write back ImmutableList.copyOf.
     */
    private object FilterHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            FeedFilterDiagnostics.invoked(FeedPipeline.CLASSIC)
            runCatching { filterCollection(chain.thisObject) }
                .onFailure { L.w(TAG, "feed filter pass failed", it) }
            return chain.proceed()
        }
    }

    /** @return true if anything was removed. */
    private fun filterCollection(runnable: Any?): Boolean {
        val engine = FeedContentRules.engine(FeedPipeline.CLASSIC, classicSignals)
        val aiOn = Settings.getBoolean(Settings.FEED_AI_CONTENT, false)
        if (!engine.active) return false

        val holderField = findHolderField(runnable ?: return false) ?: return false
        val holder = runCatching { holderField.get(runnable) }.getOrNull() ?: return false
        val collectionField = findCollectionField(holder) ?: return false
        val collection = runCatching { collectionField.get(holder) }.getOrNull() ?: return false
        val elements = (collection as? Iterable<*>)?.toList() ?: return false
        if (elements.isEmpty()) return false

        val partition = engine.partition(elements)
        FeedFilterDiagnostics.batch(FeedPipeline.CLASSIC, partition)
        val removed = partition.removedByRule
        if (aiOn) {
            aiScanBatches++
            val aiMatches = removed["AI_CONTENT"] ?: 0
            if (aiMatches > 0 || aiScanBatches <= 8 || aiScanBatches % 50 == 0) {
                L.i(TAG, "AI main scan batch=$aiScanBatches " +
                    "inspected=${partition.evaluatedByRule["AI_CONTENT"] ?: 0} " +
                    "matched=$aiMatches totalEdges=${elements.size}")
            }
        }
        if (removed.isEmpty()) return false

        val copy = copyOfMethod()?.let { m ->
            runCatching { m.invoke(null, partition.kept as Iterable<*>) }.getOrNull()
        }
        if (copy != null) {
            runCatching { collectionField.set(holder, copy) }.onFailure {
                L.w(TAG, "write-back failed; keeping original collection", it)
                return false
            }
        } else {
            return false
        }
        L.i(TAG, "removed ${partition.removed}/${elements.size} row(s): " +
                removed.entries.joinToString { "${it.key}=${it.value}" })
        return true
    }

    /**
     * The Runnable holds its owner state in a synthetic field (this.A01 → the
     * collection holder). Names drift per release, so locate it structurally:
     * the field whose value declares an ImmutableCollection field.
     */
    private fun findHolderField(runnable: Any): Field? {
        var cls: Class<*>? = runnable.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = runCatching { f.get(runnable) }.getOrNull() ?: continue
                if (findCollectionField(v) != null) return f
            }
            cls = cls.superclass
        }
        return null
    }

    /** The ImmutableCollection-typed field on the holder (the mod's A05). */
    private fun findCollectionField(holder: Any): Field? =
        collectionFieldFor(holder.javaClass)?.also { it.isAccessible = true }

    private fun collectionFieldFor(cls: Class<*>): Field? {
        // ConcurrentHashMap can't hold nulls — misses tracked separately.
        collectionFieldCache[cls]?.let { return it }
        if (cls in collectionFieldMisses) return null
        var c: Class<*>? = cls
        var result: Field? = null
        val collType = runCatching {
            Class.forName(IMMUTABLE_COLLECTION, false, appClassLoader)
        }.getOrNull()
        while (c != null && result == null) {
            // ImmutableList and friends extend ImmutableCollection — declared
            // type may be any subtype (the mod's A05 was ImmutableList).
            result = c.declaredFields.firstOrNull {
                collType != null && collType.isAssignableFrom(it.type) &&
                    !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            c = c.superclass
        }
        if (result != null) collectionFieldCache[cls] = result
        else collectionFieldMisses.add(cls)
        return result
    }

    /** GraphQLFeedUnitEdge → its GraphQLFeedStoryCategory enum name, or null. */
    private fun categoryOf(edge: Any?): String? {
        if (edge == null) return null
        val ec = edgeClass ?: runCatching {
            Class.forName(EDGE_CLASS, false, appClassLoader).also { edgeClass = it }
        }.getOrNull() ?: return null
        if (!ec.isInstance(edge)) return null
        var getter = categoryGetterCache[ec]
        if (getter == null && ec !in categoryGetterMisses) {
            getter = resolveCategoryGetter(ec)
            if (getter != null) categoryGetterCache[ec] = getter
            else categoryGetterMisses.add(ec)
        }
        if (getter == null) return null
        val category = runCatching { getter.invoke(edge) }.getOrNull() ?: return null
        return (category as? Enum<*>)?.name
    }

    /** The getter whose return type IS the category enum (B9B in this build). */
    private fun resolveCategoryGetter(edgeCls: Class<*>): Method? {
        val cat = categoryClass ?: runCatching {
            Class.forName(CATEGORY_ENUM, false, appClassLoader).also { categoryClass = it }
        }.getOrNull() ?: return null
        var c: Class<*>? = edgeCls
        while (c != null) {
            c.declaredMethods.firstOrNull { it.returnType == cat && it.parameterCount == 0 }?.let {
                return it.also { it.isAccessible = true }
            }
            c = c.superclass
        }
        return null
    }

    private fun copyOfMethod(): Method? {
        copyOf?.let { return it }
        val m = runCatching {
            val list = Class.forName(IMMUTABLE_LIST, false, appClassLoader)
            list.getDeclaredMethod("copyOf", Iterable::class.java).also { it.isAccessible = true }
        }.getOrNull()
        copyOf = m
        return m
    }

    /** UI-only safety net; shared data-layer AI classification lives in AiTransparencyInspector. */
    private data class HiddenRowState(
        val height: Int,
        val visibility: Int,
    )

    private object AiUiFallbackHook : Hooker {
        private const val MAX_DESCENDANTS = 320
        private const val MAX_VIRTUAL_NODES = 480
        private const val MAX_UNLINKED_VIRTUAL_IDS = 48
        private val childIdMethod = runCatching {
            AccessibilityNodeInfo::class.java.getDeclaredMethod(
                "getChildId", Int::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
        }.getOrNull()
        private val hiddenRows = Collections.synchronizedMap(WeakHashMap<View, HiddenRowState>())
        @Volatile private var lastScanLogMs = 0L

        private class UiScan {
            var providers = 0
            var virtualNodes = 0
            var virtualMatches = 0
            var hostChildren = 0
            var resolvedChildren = 0
            var resolvedUnlinked = 0
        }

        private val detachRestore = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                restoreRow(v)
            }
        }

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val recycler = chain.thisObject as? ViewGroup ?: return result
            if (!looksLikeMainFeedRecycler(recycler)) return result
            // Do not create Litho accessibility nodes during mount/layout: that
            // can mutate Facebook's accessibility tree while it is assembling.
            // Query already-mounted virtual nodes on the next idle frame only.
            recycler.postDelayed({
                if (recycler.isAttachedToWindow) scanRecycler(recycler)
            }, 650L)
            return result
        }

        private fun scanRecycler(recycler: ViewGroup) {
            val enabled = Settings.getBoolean(Settings.FEED_AI_CONTENT, false)
            val scan = UiScan()
            var matched = 0
            for (i in 0 until recycler.childCount) {
                val row = recycler.getChildAt(i)
                if (enabled && hiddenRows.containsKey(row)) continue
                if (enabled && containsAiDisclosure(row, scan)) {
                    matched++
                    hideRow(row)
                } else {
                    restoreRow(row)
                }
            }
            val now = SystemClock.uptimeMillis()
            if (enabled && now - lastScanLogMs >= 10000) {
                lastScanLogMs = now
                L.i(TAG, "AI UI scan rows=${recycler.childCount} matches=$matched " +
                    "providers=${scan.providers} virtualNodes=${scan.virtualNodes} " +
                    "hostChildren=${scan.hostChildren} resolvedChildren=${scan.resolvedChildren} " +
                    "resolvedUnlinked=${scan.resolvedUnlinked} virtualMatches=${scan.virtualMatches} " +
                    "childIdAvailable=${childIdMethod != null}")
            }
        }

        fun onDescription(view: View, description: String?) {
            if (!Settings.getBoolean(Settings.FEED_AI_CONTENT, false) || !isAiLabel(description)) return
            val (recycler, row) = findEnclosingFeedRow(view) ?: return
            L.i(TAG, "AI content label on real View: ${view.javaClass.name}")
            recycler.post {
                if (row.parent === recycler && containsAiDisclosure(row)) hideRow(row)
            }
        }

        private fun findEnclosingFeedRow(view: View): Pair<ViewGroup, View>? {
            var child: View = view
            repeat(36) {
                val parent = child.parent as? ViewGroup ?: return null
                if (inheritsFrom(parent, "androidx.recyclerview.widget.RecyclerView")) {
                    return if (looksLikeMainFeedRecycler(parent)) parent to child else null
                }
                child = parent
            }
            return null
        }

        private fun looksLikeMainFeedRecycler(view: View): Boolean {
            val dm = view.resources.displayMetrics
            if (view.width < dm.widthPixels * 9 / 10 || view.height < dm.heightPixels * 7 / 10) {
                return false
            }
            var parent = view.parent
            repeat(12) {
                val p = parent ?: return@repeat
                if (inheritsFrom(p, "androidx.viewpager.widget.ViewPager")) return true
                parent = p.parent
            }
            return false
        }

        private fun inheritsFrom(instance: Any, className: String): Boolean {
            var cls: Class<*>? = instance.javaClass
            while (cls != null) {
                if (cls.name == className) return true
                cls = cls.superclass
            }
            return false
        }

        private fun containsAiDisclosure(root: View, scan: UiScan = UiScan()): Boolean {
            val stack = ArrayDeque<View>()
            stack.add(root)
            var visited = 0
            while (stack.isNotEmpty() && visited++ < MAX_DESCENDANTS) {
                val view = stack.removeLast()
                if (isAiLabel(view.contentDescription?.toString())) return true
                if (view is TextView && isAiLabel(view.text?.toString())) return true
                // Facebook's LithoViews expose the AI badge as virtual
                // accessibility nodes, not View children. This is the same
                // semantic tree visible in `uiautomator dump`.
                if (containsVirtualAiDisclosure(view, scan)) return true
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
                }
            }
            return false
        }

        private fun containsVirtualAiDisclosure(view: View, scan: UiScan): Boolean {
            if (scan.virtualNodes >= MAX_VIRTUAL_NODES) return false
            val provider = runCatching { view.accessibilityNodeProvider }.getOrNull()
                ?: return false
            scan.providers++
            val host = runCatching {
                provider.createAccessibilityNodeInfo(View.NO_ID)
            }.getOrNull() ?: return false
            val pending = ArrayDeque<AccessibilityNodeInfo>()
            pending.add(host)
            scan.hostChildren += host.childCount
            if (host.childCount == 0) {
                // Some Litho providers expose virtual nodes but don't link
                // them from HOST_VIEW_ID when queried in-process. Their IDs
                // are normally small local integers. Probe only a bounded
                // set and only on already-mounted feed rows.
                for (virtualId in 0 until MAX_UNLINKED_VIRTUAL_IDS) {
                    if (scan.virtualNodes + pending.size >= MAX_VIRTUAL_NODES) break
                    runCatching { provider.createAccessibilityNodeInfo(virtualId) }
                        .getOrNull()?.let {
                            pending.add(it)
                            scan.resolvedUnlinked++
                        }
                }
            }
            while (pending.isNotEmpty() && scan.virtualNodes < MAX_VIRTUAL_NODES) {
                val node = pending.removeLast()
                scan.virtualNodes++
                try {
                    if (isAiLabel(node.text?.toString()) ||
                        isAiLabel(node.contentDescription?.toString())
                    ) {
                        scan.virtualMatches++
                        // Recycle any queued nodes before returning.
                        while (pending.isNotEmpty()) recycleNode(pending.removeLast())
                        return true
                    }
                    val childCount = node.childCount.coerceAtMost(80)
                    for (i in 0 until childCount) {
                        val child = runCatching { node.getChild(i) }.getOrNull()
                            ?: virtualChild(provider, node, i)
                        if (child != null) {
                            scan.resolvedChildren++
                            pending.add(child)
                        }
                    }
                } finally {
                    recycleNode(node)
                }
            }
            while (pending.isNotEmpty()) recycleNode(pending.removeLast())
            return false
        }

        private fun virtualChild(
            provider: android.view.accessibility.AccessibilityNodeProvider,
            parent: AccessibilityNodeInfo,
            index: Int,
        ): AccessibilityNodeInfo? {
            val method = childIdMethod ?: return null
            val childId = runCatching { method.invoke(parent, index) as? Long }.getOrNull()
                ?: return null
            // AccessibilityNodeInfo.makeNodeId packs the virtual descendant
            // into the low 32 bits of the child node's long source ID.
            val virtualId = childId.toInt()
            return runCatching { provider.createAccessibilityNodeInfo(virtualId) }.getOrNull()
        }

        @Suppress("DEPRECATION")
        private fun recycleNode(node: AccessibilityNodeInfo) {
            runCatching { node.recycle() }
        }

        private fun isAiLabel(value: String?): Boolean {
            val text = value?.trim().orEmpty()
            return text.equals(AI_UI_LABEL, ignoreCase = true) ||
                text.startsWith("$AI_UI_LABEL•", ignoreCase = true) ||
                text.startsWith("$AI_UI_LABEL ·", ignoreCase = true)
        }

        private fun hideRow(row: View) {
            if (hiddenRows.containsKey(row)) return
            val lp = row.layoutParams ?: return
            hiddenRows[row] = HiddenRowState(lp.height, row.visibility)
            row.addOnAttachStateChangeListener(detachRestore)
            lp.height = 0
            row.layoutParams = lp
            row.visibility = View.GONE
            row.requestLayout()
            L.i(TAG, "UI fallback hid AI-content feed row")
        }

        private fun restoreRow(row: View) {
            val state = hiddenRows.remove(row) ?: return
            row.removeOnAttachStateChangeListener(detachRestore)
            row.layoutParams?.let { lp ->
                lp.height = state.height
                row.layoutParams = lp
            }
            row.visibility = state.visibility
            row.requestLayout()
        }
    }

    private object AiDescriptionHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val view = chain.thisObject as? View ?: return result
            AiUiFallbackHook.onDescription(view, chain.args.getOrNull(0)?.toString())
            return result
        }
    }
}
