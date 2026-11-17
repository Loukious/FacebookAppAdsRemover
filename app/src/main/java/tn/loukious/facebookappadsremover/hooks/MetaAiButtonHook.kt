package tn.loukious.facebookappadsremover.hooks

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.text.BoringLayout
import android.text.StaticLayout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Hides the floating "Ask Meta AI" button of the chat list, in the Messenger
 * app and in the chats inside Facebook (both are built from the same code).
 *
 * The button is a Litho view. Its label is localised, but its click listener
 * leads to the button's kind, a constant of an enum whose names are not
 * obfuscated: `AI_FAB` on the chat list, `PEOPLE_FAB` on the People tab. On
 * the chat list the button sits in a small Litho view of its own, and that
 * view is hidden; where it shares its view with a list, only the button goes.
 *
 * The search bar of the chat list says "Ask Meta AI or search". Its text is
 * localised too, so it is learnt from the bar itself (the view tagged
 * `search_bar_button_tag` carries it as its description), remembered, and
 * swapped for Android's own word for search wherever a hint or a text layout
 * is made from exactly that string. It is learnt on the first start, so the
 * bar reads the old way until Messenger is started once more.
 *
 * Messenger gets nothing else of the module: this hook is installed there on
 * the first activity, with the settings it needs.
 */
object MetaAiButtonHook {
    private const val TAG = "FBAR.MetaAi"
    const val MESSENGER = "com.facebook.orca"
    private const val COMPONENT_HOST = "com.facebook.litho.ComponentHost"
    private const val LITHO_VIEW = "com.facebook.litho.LithoView"
    private const val BUTTON = "AI_FAB"
    private const val SEARCH_BAR = "search_bar_button_tag"
    private const val STORE = "fbar_meta_ai"
    private const val STORE_HINT = "searchHint"
    private const val BUDGET = 1500
    private const val DEPTH = 12

    @Volatile private var installed = false
    @Volatile private var waiting = false

    /** listener object -> whether it is the Meta AI button's. Keyed by listener: Litho recycles hosts. */
    private val verdicts = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val listenerInfoField: Field? by lazy {
        runCatching { View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true } }.getOrNull()
    }
    private var clickField: Field? = null
    /** enum class -> whether it is the enum of the Meta AI button's kinds. */
    private val kinds = Collections.synchronizedMap(HashMap<Class<*>, Boolean>())

    private fun enabled() = Settings.getBoolean(Settings.MESSENGER_HIDE_META_AI, false)
    private fun plainSearch() = Settings.getBoolean(Settings.MESSENGER_PLAIN_SEARCH, false)

    /** listener object -> whether it is the search bar's. */
    private val searchBars = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    /** The search bar's text as Messenger words it, once known; what it is swapped for. */
    @Volatile private var hint: String? = null
    private val plain: String by lazy {
        runCatching { Resources.getSystem().getString(android.R.string.search_go) }.getOrDefault("Search")
    }
    @Volatile private var textHooked = false

    /** Messenger: Litho is not loaded yet when the package is, so the hook waits for an activity. */
    fun startInMessenger(module: XposedInterface) {
        if (waiting) return
        waiting = true
        val onResume = Activity::class.java.getDeclaredMethod("onResume").apply { isAccessible = true }
        module.hook(onResume).intercept(object : Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val result = chain.proceed()
                if (!installed) runCatching {
                    val activity = chain.thisObject as Activity
                    Settings.init(module, activity.applicationContext)
                    install(module, activity.classLoader, activity.applicationContext)
                }.onFailure { L.w(TAG, "messenger start failed", it) }
                return result
            }
        })
    }

    @Synchronized
    fun install(module: XposedInterface, classLoader: ClassLoader, context: Context? = null): Boolean {
        if (installed) return true
        if (context != null && plainSearch()) runCatching { installText(module, context) }
            .onFailure { L.w(TAG, "search text hooks failed", it) }
        val cls = runCatching { Class.forName(COMPONENT_HOST, false, classLoader) }.getOrNull() ?: return false
        var count = 0
        for (m in cls.declaredMethods) {
            val p = m.parameterTypes
            if (m.name != "onLayout" || p.size != 5 || Modifier.isAbstract(m.modifiers)) continue
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept(Layout)
                count++
            }.onFailure { L.w(TAG, "hook ${m.name} failed", it) }
        }
        installed = count > 0
        if (installed) L.i(TAG, "Meta AI button: $count layout hooks")
        return installed
    }

    private object Layout : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val v = chain.thisObject as? View ?: return result
            if (v.isClickable && enabled()) runCatching { if (isButton(v)) hide(v) }
                .onFailure { L.w(TAG, "hide failed", it) }
            if (v.isClickable && textHooked) runCatching { learn(v) }
            return result
        }
    }

    private fun listenerOf(v: View): Any? {
        val li = listenerInfoField?.get(v) ?: return null
        val f = clickField ?: runCatching {
            li.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        }.getOrNull()?.also { clickField = it } ?: return null
        return f.get(li)
    }

    // ---- search bar ----

    private var store: SharedPreferences? = null

    /** Swaps the search bar's text wherever Android makes a hint or a text layout of it. */
    private fun installText(module: XposedInterface, context: Context) {
        if (textHooked) return
        store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        hint = store?.getString(STORE_HINT, null)
        val methods = TextView::class.java.declaredMethods.filter { it.name == "setHint" } +
            BoringLayout::class.java.declaredMethods.filter { it.name == "isBoring" || it.name == "make" } +
            StaticLayout.Builder::class.java.declaredMethods.filter { it.name == "obtain" }
        var count = 0
        for (m in methods) {
            val p = m.parameterTypes
            if (p.isEmpty() || p[0] != CharSequence::class.java) continue
            runCatching {
                m.isAccessible = true
                module.hook(m).intercept(Text)
                count++
            }
        }
        textHooked = count > 0
        L.i(TAG, "Meta AI search text: $count text hooks, hint ${if (hint == null) "not known yet" else "known"}")
    }

    private object Text : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val h = hint ?: return chain.proceed()
            val text = chain.args.getOrNull(0) as? CharSequence ?: return chain.proceed()
            if (text.length != h.length || text.toString() != h) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = plain
            // StaticLayout.Builder.obtain(text, start, end, paint, width): the end follows the text.
            if (args.size >= 3 && args[1] == 0 && args[2] == h.length) args[2] = plain.length
            return chain.proceed(args)
        }
    }

    /** Takes the search bar's wording from the bar itself. */
    private fun learn(v: View) {
        val text = v.contentDescription?.toString() ?: return
        if (text == hint || text == plain || text.isEmpty()) return
        val listener = listenerOf(v) ?: return
        val bar = searchBars.getOrPut(listener) {
            runCatching { hasString(listener, IdentityHashMap(), intArrayOf(0), 0) }.getOrDefault(false)
        }
        if (!bar) return
        hint = text
        store?.edit()?.putString(STORE_HINT, text)?.apply()
        L.i(TAG, "Meta AI search text learnt")
    }

    private fun hasString(o: Any?, seen: IdentityHashMap<Any, Boolean>, budget: IntArray, depth: Int): Boolean {
        if (o == null || budget[0]++ > BUDGET || depth > DEPTH) return false
        if (o is String) return o == SEARCH_BAR
        if (o is Enum<*> || o is Number || o is Boolean || o is Char) return false
        if (o is Context || o is View || o is Collection<*> || o is Map<*, *>) return false
        if (seen.put(o, true) != null) return false
        val cls = o.javaClass
        if (cls.isArray || cls.name.startsWith("android.") || cls.name.startsWith("java.")) return false
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val value = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                if (hasString(value, seen, budget, depth + 1)) return true
            }
            c = c.superclass
        }
        return false
    }

    // ---- floating button ----

    private fun isButton(v: View): Boolean {
        val listener = listenerOf(v) ?: return false
        return verdicts.getOrPut(listener) {
            val found = runCatching { find(listener, IdentityHashMap(), intArrayOf(0), 0) }.getOrDefault(false)
            if (found) L.i(TAG, "Meta AI button found")
            found
        }
    }

    private fun hide(button: View) {
        // The Litho view the button lives in, if it holds nothing but the button: it is a strip
        // at the bottom of the chat list, far smaller than the list itself.
        var own: View? = null
        var p = button.parent
        while (p is View) {
            if (p.javaClass.name == LITHO_VIEW) { own = p; break }
            p = p.parent
        }
        val around = own?.parent as? View
        val target = if (own != null && around != null && own.height * 4 < around.height) own
            else button.parent as? View ?: button
        if (target.visibility != View.GONE) target.visibility = View.GONE
    }

    /**
     * The other tabs carry the same button under other constants of the same
     * enum (`PEOPLE_FAB`, ...): any constant of the enum that has `AI_FAB` counts.
     */
    private fun isButtonKind(e: Enum<*>): Boolean {
        val cls = if (e.javaClass.isEnum) e.javaClass else e.javaClass.superclass ?: return false
        return kinds.getOrPut(cls) {
            cls.enumConstants?.any { (it as? Enum<*>)?.name == BUTTON } == true
        }
    }

    /** Bounded walk of the listener object graph looking for a constant of the Meta AI button's enum. */
    private fun find(o: Any?, seen: IdentityHashMap<Any, Boolean>, budget: IntArray, depth: Int): Boolean {
        if (o == null || budget[0]++ > BUDGET || depth > DEPTH) return false
        if (o is Enum<*>) return isButtonKind(o)
        if (o is String || o is Number || o is Boolean || o is Char) return false
        if (o is Context || o is View || o is Collection<*> || o is Map<*, *>) return false
        if (seen.put(o, true) != null) return false
        val cls = o.javaClass
        if (cls.isArray || cls.name.startsWith("android.") || cls.name.startsWith("java.")) return false
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val value = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                if (find(value, seen, budget, depth + 1)) return true
            }
            c = c.superclass
        }
        return false
    }
}
