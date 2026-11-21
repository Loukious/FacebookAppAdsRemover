package tn.loukious.facebookappadsremover.hooks

import android.app.Activity
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Back on the News Feed leaves Facebook at once.
 *
 * Facebook's own Back on the feed scrolls to the top and refreshes, and only
 * at the top lets the press leave the app. With the switch on the first press
 * puts Facebook in the background, the way its own last press does, and the
 * feed stays where it was.
 *
 * NewsFeedFragment.onBackPressed first offers the press to whatever is open
 * above the feed and only then asks itself `isAtTop()` to choose what to do.
 * That question is the sign that the press is the feed's own. A hook cannot
 * cut the method short there, so from that point on the rest of it is made
 * harmless instead:
 *
 * - `isAtTop()` answers no, which takes the "scroll up" branch, not the
 *   "refresh and ask again" one;
 * - `scrollToTop()` does nothing;
 * - the refresh that follows the scroll, a static `(reason, fragment)` method
 *   of the fragment, does nothing;
 *
 * and when the method returns the task goes to the background.
 *
 * Nothing obfuscated is named. The fragment is recognised when it is created,
 * by the original class name Facebook's build leaves in every class
 * (`__redex_internal_original_name`); `onBackPressed`, `isAtTop` and
 * `scrollToTop` are interface methods and keep their names; the refresh is
 * found by its shape.
 */
object BackExitHook {
    private const val TAG = "FBAR.Back"
    private const val FRAGMENT = "androidx.fragment.app.Fragment"
    private const val ORIGINAL_NAME = "__redex_internal_original_name"
    private const val FEED = "NewsFeedFragment"

    /** Fragment classes already looked at. */
    private val seen = ConcurrentHashMap<Class<*>, Boolean>()

    /** One Back press on the feed, on the thread that handles it. */
    private class Press {
        /** The press reached the feed itself: nothing above the feed took it. */
        var own = false
    }

    private val press = ThreadLocal<Press>()

    fun install(module: XposedInterface, classLoader: ClassLoader): Boolean {
        val fragment = runCatching { classLoader.loadClass(FRAGMENT) }.getOrNull() ?: return false
        var hooked = false
        for (constructor in fragment.declaredConstructors) {
            runCatching {
                module.hook(constructor).intercept(Created(module))
                hooked = true
            }.onFailure { L.w(TAG, "fragment constructor not hooked", it) }
        }
        if (hooked) L.i(TAG, "waiting for the News Feed fragment")
        return hooked
    }

    /** Fragment(): the first fragment of each class is checked for being the feed. */
    private class Created(private val module: XposedInterface) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val cls = chain.thisObject?.javaClass ?: return result
            if (seen.putIfAbsent(cls, true) == null && isFeed(cls)) hookFeed(module, cls)
            return result
        }
    }

    internal fun isFeed(cls: Class<*>): Boolean = runCatching {
        val field = cls.getDeclaredField(ORIGINAL_NAME)
        Modifier.isStatic(field.modifiers) && field.apply { isAccessible = true }.get(null) == FEED
    }.getOrDefault(false)

    private fun hookFeed(module: XposedInterface, cls: Class<*>) {
        val back = own(cls, "onBackPressed", java.lang.Boolean.TYPE)
        val atTop = own(cls, "isAtTop", java.lang.Boolean.TYPE)
        val scroll = own(cls, "scrollToTop", java.lang.Void.TYPE)
        val refreshes = cls.declaredMethods.filter { isRefresh(it, cls) }
        if (back == null || atTop == null || scroll == null) {
            L.w(
                TAG,
                "${cls.name}: onBackPressed=${back != null} isAtTop=${atTop != null} " +
                    "scrollToTop=${scroll != null} refresh=${refreshes.size}, not hooked",
            )
            return
        }
        runCatching {
            module.hook(atTop).intercept(AtTop)
            module.hook(scroll).intercept(Skip)
            refreshes.forEach { module.hook(it).intercept(Skip) }
            module.hook(back).intercept(Back)
            if (refreshes.isEmpty()) L.w(TAG, "${cls.name}: refresh not found, Back may still refresh the feed")
            // Compiled code has the small callees inlined into onBackPressed, where
            // their hooks would never run.
            if (!runCatching { module.deoptimize(back) }.getOrDefault(false)) {
                L.w(TAG, "onBackPressed not deoptimized")
            }
            L.i(TAG, "hooked ${cls.name}: onBackPressed, isAtTop, scrollToTop, ${refreshes.size} refresh")
        }.onFailure { L.w(TAG, "feed Back not hooked", it) }
    }

    private fun own(cls: Class<*>, name: String, returns: Class<*>): Method? = cls.declaredMethods.singleOrNull {
        it.name == name && it.parameterCount == 0 && it.returnType == returns && !Modifier.isStatic(it.modifiers)
    }

    /** `static void (reason: Enum, fragment)`: the feed's "refresh, because ...". */
    internal fun isRefresh(method: Method, cls: Class<*>): Boolean {
        if (!Modifier.isStatic(method.modifiers) || method.returnType != java.lang.Void.TYPE) return false
        val params = method.parameterTypes
        return params.size == 2 && params[0].isEnum && params[1] == cls
    }

    private object Back : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.FEED_BACK_EXITS, false) || press.get() != null) {
                return chain.proceed()
            }
            val mine = Press()
            press.set(mine)
            val result = try {
                chain.proceed()
            } finally {
                press.remove()
            }
            if (!mine.own) return result
            val activity = activityOf(chain.thisObject)
            if (activity == null) {
                L.w(TAG, "no activity for the feed fragment")
                return result
            }
            L.i(TAG, "Back on the feed: leaving")
            activity.moveTaskToBack(true)
            return true
        }
    }

    private object AtTop : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val mine = press.get() ?: return chain.proceed()
            mine.own = true
            return false
        }
    }

    /** scrollToTop and the refresh: nothing, once the press is the feed's own. */
    private object Skip : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            return if (press.get()?.own == true) null else chain.proceed()
        }
    }

    private fun activityOf(fragment: Any?): Activity? = runCatching {
        var cls: Class<*>? = fragment?.javaClass
        while (cls != null && cls.name != FRAGMENT) cls = cls.superclass
        cls?.getMethod("getActivity")?.invoke(fragment) as? Activity
    }.getOrNull()
}
