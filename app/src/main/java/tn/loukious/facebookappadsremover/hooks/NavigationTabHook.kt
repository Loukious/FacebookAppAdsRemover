package tn.loukious.facebookappadsremover.hooks

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.Optional

/**
 * Hide *buttons*, not destinations: Facebook's TabTag/navigation config and
 * ViewPager stay intact, so menu/search/deep links can still open these pages.
 *
 * Primary FB 580 path: named TabBarContainerLayout.onChildViewAdded(View,View)
 * (a ViewGroup.OnHierarchyChangeListener) receives each icon BEFORE first
 * layout/draw. Icon has one long tab identifier (TabTag.A03). No dependency
 * on obfuscated `X.27i`/`A0E` names or fixed tab positions.
 *
 * Fallback: a bounded Android LinearLayout.onMeasure observer recognizes the
 * actual top/bottom Home tab bar by *all* its sibling accessibility labels.
 * The fallback cannot touch a Reels post, Marketplace menu shortcut, Games
 * content or a non-divisible Litho host simply because it mentions the name.
 */
object NavigationTabHook {
    private const val TAG = "FBAR.NavTabs"
    private const val TAB_BAR_CLASS = "com.facebook.navigation.tabbar.ui.TabBarContainerLayout"

    private data class OriginalVisibility(
        val destination: NavigationTabPolicy.Destination,
        val value: Int,
    )

    private data class Enabled(val reels: Boolean, val marketplace: Boolean, val games: Boolean) {
        val any get() = reels || marketplace || games
        fun hide(destination: NavigationTabPolicy.Destination) =
            NavigationTabPolicy.hide(destination, reels, marketplace, games)
    }

    private val hidden = Collections.synchronizedMap(WeakHashMap<View, OriginalVisibility>())
    private val tabIdFields = ConcurrentHashMap<Class<*>, Optional<Field>>()
    private val hides = AtomicInteger()
    private val nativeLayouts = AtomicInteger()
    @Volatile private var frameworkInstalled = false
    @Volatile private var nativeInstalled = false

    private fun enabled() = Enabled(
        reels = Settings.getBoolean(Settings.NAV_HIDE_REELS_TAB, false),
        marketplace = Settings.getBoolean(Settings.NAV_HIDE_MARKETPLACE_TAB, false),
        games = Settings.getBoolean(Settings.NAV_HIDE_GAMES_TAB, false),
    )

    @Synchronized
    fun installFramework(module: XposedInterface): Boolean {
        if (frameworkInstalled) return true
        var installed = 0
        runCatching {
            val method = LinearLayout::class.java.getDeclaredMethod(
                "onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            method.isAccessible = true
            module.hook(method).intercept(MeasureHooker)
            installed++
        }.onFailure { L.w(TAG, "measure fallback unavailable", it) }
        runCatching {
            module.hook(View::class.java.getDeclaredMethod(
                "setContentDescription", CharSequence::class.java,
            )).intercept(DescriptionHooker)
            installed++
        }.onFailure { L.w(TAG, "semantic-label fallback unavailable", it) }
        frameworkInstalled = installed > 0
        L.i(TAG, "navigation tab fallback: $installed/2 framework hooks")
        return frameworkInstalled
    }

    /** Stable class resolved on the existing secondary-DEX retry cadence. */
    @Synchronized
    fun installNative(module: XposedInterface, classLoader: ClassLoader): Boolean {
        if (nativeInstalled) return true
        val owner = runCatching {
            Class.forName(TAB_BAR_CLASS, false, classLoader)
        }.getOrNull() ?: return false
        val callback = runCatching {
            owner.getDeclaredMethod("onChildViewAdded", View::class.java, View::class.java)
        }.getOrNull()
        val layout = runCatching {
            owner.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (callback == null && layout == null) return false
        var installed = 0
        callback?.let { method -> runCatching {
            method.isAccessible = true
            module.hook(method).intercept(TabAddedHooker)
            installed++
            L.i(TAG, "pre-render nav icon hook: ${owner.name}.${method.name}")
        }.onFailure { L.w(TAG, "tab-added hook failed", it) } }
        layout?.let { method -> runCatching {
            method.isAccessible = true
            module.hook(method).intercept(TabLayoutHooker)
            installed++
            L.i(TAG, "pre-draw nav bar layout hook: ${owner.name}.${method.name}")
        }.onFailure { L.w(TAG, "tab layout hook failed", it) } }
        nativeInstalled = installed > 0
        if (nativeInstalled) {
            val options = enabled()
            L.i(TAG, "nav prefs: reels=${options.reels} marketplace=${options.marketplace} " +
                "games=${options.games}")
        }
        return nativeInstalled
    }

    /** Handles tabs restored/reused by Facebook without onChildViewAdded. */
    private object TabLayoutHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val bar = chain.thisObject as? ViewGroup ?: return result
            if (bar.childCount !in 3..8) return result
            val options = enabled()
            if (!options.any && !hasHiddenChild(bar)) return result
            val count = nativeLayouts.incrementAndGet()
            var resolved = 0
            for (index in 0 until bar.childCount) {
                val child = bar.getChildAt(index)
                val destination = tabId(child)?.let(NavigationTabPolicy::fromId) ?: continue
                resolved++
                setHidden(child, destination, options, "native-layout")
            }
            if (count <= 3 || count % 100 == 0) {
                L.i(TAG, "native layout: children=${bar.childCount} knownTabs=$resolved " +
                    "reels=${options.reels} marketplace=${options.marketplace} games=${options.games}")
            }
            return result
        }
    }

    private object TabAddedHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            // Facebook must assign its own selection/context before we hide
            // the icon. The hierarchy callback still precedes first draw.
            val result = chain.proceed()
            val child = chain.args.getOrNull(1) as? View ?: return result
            val options = enabled()
            if (!options.any) return result
            val destination = tabId(child)?.let(NavigationTabPolicy::fromId)
                ?: return result
            setHidden(child, destination, options, "native")
            return result
        }
    }

    private fun tabId(view: View): Long? {
        val cls = view.javaClass
        val field = tabIdFields.computeIfAbsent(cls) { type ->
            var current: Class<*>? = type
            var resolved: Field? = null
            while (current != null && current != View::class.java && resolved == null) {
                val fields = current.declaredFields.filter {
                    it.type == Long::class.javaPrimitiveType &&
                        !Modifier.isStatic(it.modifiers)
                }
                if (fields.size == 1) resolved = fields.single()
                else if (fields.size > 1) break // Ambiguous; don't guess.
                current = current.superclass
            }
            resolved?.let { runCatching { it.isAccessible = true } }
            Optional.ofNullable(resolved)
        }.orElse(null) ?: return null
        return runCatching { field.getLong(view) }.getOrNull()
    }

    private object MeasureHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val layout = chain.thisObject as? LinearLayout ?: return chain.proceed()
            if (layout.childCount !in 3..8) return chain.proceed()
            val options = enabled()
            if (!options.any && !hasHiddenChild(layout)) return chain.proceed()
            reconcile(layout, options)
            return chain.proceed()
        }
    }

    private object DescriptionHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val view = chain.thisObject as? View ?: return result
            // Only navigation-specific labels can trigger a parent scan. This
            // avoids walking the actual feed on every description assignment.
            if (NavigationTabPolicy.parse(view.contentDescription?.toString()) == null) {
                return result
            }
            val layout = view.parent as? LinearLayout ?: return result
            if (layout.childCount in 3..8) reconcile(layout, enabled())
            return result
        }
    }

    private fun hasHiddenChild(layout: ViewGroup): Boolean {
        for (index in 0 until layout.childCount) {
            if (hidden.containsKey(layout.getChildAt(index))) return true
        }
        return false
    }

    private fun reconcile(layout: LinearLayout, options: Enabled) {
        val count = layout.childCount
        if (count !in 3..8) return
        val children = (0 until count).map(layout::getChildAt)
        val destinations = NavigationTabPolicy.identify(
            children.map { it.contentDescription?.toString() },
        ) ?: return
        for (index in children.indices) {
            setHidden(children[index], destinations[index], options, "semantic")
        }
    }

    private fun setHidden(
        view: View,
        destination: NavigationTabPolicy.Destination,
        options: Enabled,
        path: String,
    ) {
        val previous = hidden[view]
        if (!options.hide(destination)) {
            if (previous != null) {
                hidden.remove(view)
                view.visibility = previous.value
                L.i(TAG, "restored tab=${previous.destination} path=$path")
            }
            return
        }
        if (previous != null && previous.destination != destination) {
            // Facebook reused a View for a different tab. Restore first so
            // a change of identity doesn't leave the wrong destination gone.
            hidden.remove(view)
            view.visibility = previous.value
        }
        if (hidden[view] == null) {
            if (view.visibility == View.GONE) return // FB already owns this GONE.
            hidden[view] = OriginalVisibility(destination, view.visibility)
            val count = hides.incrementAndGet()
            if (count <= 12 || count % 50 == 0) {
                L.i(TAG, "hidden tab=$destination path=$path total=$count")
            }
        }
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }
}
