package tn.loukious.facebookappadsremover.hooks

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Method
import java.lang.ref.WeakReference

/**
 * Runtime port of Morphe's "[General] AMOLED black theme".
 *
 * Morphe patches four routes statically. An Xposed module cannot rewrite APK
 * resource tables or arbitrary dex literals, so we intercept the equivalent
 * runtime seams:
 *  1) Facebook's stable Mig/FDS colour resolver classes;
 *  2) framework resource/style seams (Resources, TypedArray, ColorStateList);
 *  3) runtime colour setters used by measured Facebook chrome literals;
 *  4) Color.parseColor for server-driven colour strings.
 *
 * The feed separator is intentionally separate from the global colour rules:
 * Facebook reuses the same dark greys for real surfaces such as comments. We
 * identify the Home feed structurally through AndroidX RecyclerView/ViewPager
 * inheritance (never an obfuscated X.* name) and draw the current semantic
 * NEW_NOTIFICATION_BACKGROUND shade only at top-level feed-item boundaries.
 *
 * Every hook is always installed but reads the toggle on each invocation, so
 * disabling AMOLED restores Facebook's original colours without a new build.
 */
object AmoledThemeHook {
    private const val TAG = "FBAR.Amoled"
    const val CACHE_KEY = "appearance.amoled.fdsViewResolver"

    private const val FDS_COLOR_SCHEME =
        "com.facebook.mig.scheme.schemes.fds.FdsColorScheme"
    private const val NEW_NOTIFICATION_BACKGROUND = "NEW_NOTIFICATION_BACKGROUND"

    // Used only until Facebook resolves NEW_NOTIFICATION_BACKGROUND once in
    // this process. This is the familiar dark unread-notification blue/black;
    // the live token immediately replaces it with the exact build/account
    // value when the Notifications surface is rendered.
    // Facebook 580 resolves NEW_NOTIFICATION_BACKGROUND to this translucent
    // blue-black overlay. It is replaced at runtime whenever the token is
    // resolved, so future builds/accounts can supply their own exact value.
    private const val FALLBACK_SEPARATOR_COLOR = 0x192D88FF

    private val hookedMethods = AmoledMethodRegistry()

    @Volatile private var frameworkInstalled = false
    @Volatile private var unreadNotificationColor = FALLBACK_SEPARATOR_COLOR
    @Volatile private var loggedUnreadColor = false

    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.APPEARANCE_AMOLED, false)

    /**
     * The system's accent shades (`system_accent1_*`, Android 12+), or null
     * when the phone has none or the "Material You accent" switch is off.
     * The same colour seams as AMOLED carry it: see [AccentColorRule].
     */
    private fun accent(): IntArray? = when {
        !Settings.getBoolean(Settings.APPEARANCE_ACCENT, false) -> null
        accentState == LOADED -> accentPalette
        else -> loadAccentPalette()
    }

    private const val NOT_LOADED = 0
    private const val LOADING = 1
    private const val LOADED = 2
    @Volatile private var accentState = NOT_LOADED
    @Volatile private var accentPalette: IntArray? = null
    /** Its own lock: the object's monitor is held while hooks are installed. */
    private val accentLock = Any()

    /**
     * Reading the shades goes through Resources.getColor, which is hooked
     * above and asks for the palette again: while it is being loaded that
     * inner call gets none, so the shades themselves come back unchanged.
     */
    private fun loadAccentPalette(): IntArray? = synchronized(accentLock) {
        if (accentState != NOT_LOADED) return accentPalette
        accentState = LOADING
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) accentPalette = runCatching {
            val res = Resources.getSystem()
            intArrayOf(
                android.R.color.system_accent1_0, android.R.color.system_accent1_10,
                android.R.color.system_accent1_50, android.R.color.system_accent1_100,
                android.R.color.system_accent1_200, android.R.color.system_accent1_300,
                android.R.color.system_accent1_400, android.R.color.system_accent1_500,
                android.R.color.system_accent1_600, android.R.color.system_accent1_700,
                android.R.color.system_accent1_800, android.R.color.system_accent1_900,
                android.R.color.system_accent1_1000,
            ).map { res.getColor(it, null) }.toIntArray()
        }.onFailure { L.w(TAG, "system accent palette unavailable", it) }.getOrNull()
        accentState = LOADED
        accentPalette?.let { L.i(TAG, "system accent palette: " + it.joinToString(" ") { c -> "#%06X".format(c and 0xFFFFFF) }) }
        accentPalette
    }

    /** AMOLED first, then the accent: a colour AMOLED blackened is no longer blue. */
    private fun literal(color: Int): Int {
        val c = if (enabled()) AmoledColorRule.untokened(color) else color
        // Paints and resources ask this thousands of times a frame: only a colour that could be
        // swapped is worth the look at the switch and the palette.
        return if (AccentColorRule.isLiteral(c)) AccentColorRule.literal(c, accent()) else c
    }

    @Synchronized
    fun installFramework(module: XposedInterface): Boolean {
        if (frameworkInstalled) return true
        var installed = 0

        fun hook(method: Method, hooker: Hooker) {
            runCatching {
                module.hook(method).intercept(hooker)
                installed++
            }.onFailure { L.w(TAG, "framework hook failed: $method", it) }
        }

        runCatching { Color::class.java.getDeclaredMethod("parseColor", String::class.java) }
            .getOrNull()?.let { hook(it, UntokenedResultHook) }

        Resources::class.java.declaredMethods
            .filter { it.name == "getColor" && it.returnType == Int::class.javaPrimitiveType }
            .forEach { hook(it, UntokenedResultHook) }
        TypedArray::class.java.declaredMethods
            .filter { it.name == "getColor" && it.returnType == Int::class.javaPrimitiveType }
            .forEach { hook(it, UntokenedResultHook) }
        ColorStateList::class.java.declaredMethods
            .filter {
                it.returnType == Int::class.javaPrimitiveType &&
                    (it.name == "getDefaultColor" || it.name == "getColorForState")
            }
            .forEach { hook(it, UntokenedResultHook) }
        runCatching {
            ColorStateList::class.java.getDeclaredMethod("valueOf", Int::class.javaPrimitiveType)
        }.getOrNull()?.let { hook(it, LiteralColorArgHook) }
        runCatching { View::class.java.getDeclaredMethod("setBackgroundColor", Int::class.javaPrimitiveType) }
            .getOrNull()?.let { hook(it, ViewBackgroundColorHook) }
        runCatching { View::class.java.getDeclaredMethod("setContentDescription", CharSequence::class.java) }
            .getOrNull()?.let { hook(it, ContentDescriptionHook) }
        runCatching { ColorDrawable::class.java.getDeclaredMethod("setColor", Int::class.javaPrimitiveType) }
            .getOrNull()?.let { hook(it, LiteralColorArgHook) }
        runCatching { GradientDrawable::class.java.getDeclaredMethod("setColor", Int::class.javaPrimitiveType) }
            .getOrNull()?.let { hook(it, LiteralColorArgHook) }
        runCatching { Drawable::class.java.getDeclaredMethod("setTint", Int::class.javaPrimitiveType) }
            .getOrNull()?.let { hook(it, LiteralColorArgHook) }
        runCatching { Paint::class.java.getDeclaredMethod("setColor", Int::class.javaPrimitiveType) }
            .getOrNull()?.let { hook(it, LiteralColorArgHook) }

        frameworkInstalled = installed > 0
        if (frameworkInstalled) L.i(TAG, "AMOLED framework routes installed: $installed")
        return frameworkInstalled
    }

    /** Stable-name resolver arm. Re-run on the secondary-dex probe cadence. */
    @Synchronized
    fun installFacebookResolvers(module: XposedInterface, classLoader: ClassLoader): Boolean {
        val classNames = listOf(
            "com.facebook.mig.scheme.schemes.DarkColorScheme",
            "com.facebook.fds.core.theme.component.FDSColors",
            "com.facebook.mig.scheme.schemes.fds.FdsColorScheme",
            "com.facebook.mig.scheme.schemes.fds.FdsDarkColorScheme",
        )
        var resolvedCore = 0
        var newlyHooked = 0
        classNames.forEachIndexed { index, name ->
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: return@forEachIndexed
            if (index < 2) resolvedCore++
            cls.declaredMethods
                .filter { it.returnType == Int::class.javaPrimitiveType }
                .forEach { method ->
                    runCatching {
                        val installed = hookedMethods.installOnce(method) {
                            method.isAccessible = true
                            module.hook(method).intercept(ResolverResultHook)
                        }
                        if (installed) newlyHooked++
                    }.onFailure { L.w(TAG, "resolver hook failed: $method", it) }
                }
        }
        runCatching {
            val recyclerView = Class.forName("androidx.recyclerview.widget.RecyclerView", false, classLoader)
            recyclerView.declaredMethods
                .filter {
                    (it.name == "onLayout" && it.parameterCount == 5) ||
                        (it.name == "draw" && it.parameterCount == 1 &&
                            it.parameterTypes[0] == Canvas::class.java)
                }
                .forEach { method ->
                    val installed = hookedMethods.installOnce(method) {
                        method.isAccessible = true
                        val hooker = if (method.name == "onLayout") {
                            FeedRecyclerLayoutHook
                        } else {
                            FeedRecyclerDrawHook
                        }
                        module.hook(method).intercept(hooker)
                    }
                    if (installed) newlyHooked++
                }
        }.onFailure { L.w(TAG, "feed separator RecyclerView hook failed", it) }
        if (newlyHooked > 0) L.i(TAG, "AMOLED Facebook resolver methods installed: +$newlyHooked")
        return resolvedCore == 2
    }

    /**
     * Morphe route 1's fourth resolver: the stable FdsColorScheme wrapper
     * calls an obfuscated static `(Context, FDS token) -> int` method used by
     * ordinary Android Views. Its owner/name rotates, so discover it from the
     * stable wrapper body and cache the resolved Method for later launches.
     */
    fun discoverViewResolver(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): List<Method>? = runCatching {
        val wrappers = bridge.findMethod {
            matcher {
                declaredClass(FDS_COLOR_SCHEME)
                returnType(java.lang.Integer.TYPE)
                paramCount(1)
            }
        }.filter { wrapper ->
            wrapper.invokes.any { called ->
                called.returnTypeName == "int" &&
                    called.paramTypeNames.size == 2 &&
                    called.paramTypeNames[0] == "android.content.Context"
            }
        }
        check(wrappers.size == 1) {
            "Expected one FdsColorScheme token wrapper, found ${wrappers.size}"
        }
        val resolvers = wrappers.single().invokes.filter { called ->
                called.returnTypeName == "int" &&
                    called.paramTypeNames.size == 2 &&
                    called.paramTypeNames[0] == "android.content.Context" &&
                    !called.paramTypeNames[1].startsWith("java.") &&
                    !called.paramTypeNames[1].startsWith("android.")
        }.distinctBy { it.descriptor }
        check(resolvers.size == 1) {
            "Expected one FDS view color resolver, found ${resolvers.size}"
        }
        val method = resolvers.single().getMethodInstance(classLoader)
        check(hookResolver(module, method)) { "FDS view resolver could not be hooked" }
        L.i(TAG, "AMOLED FDS view resolver installed: ${method.declaringClass.name}.${method.name}")
        listOf(method)
    }.onFailure { L.w(TAG, "FDS view resolver discovery failed", it) }.getOrNull()

    fun installViewResolverCached(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.size != 1) return false
        return hookResolver(module, methods.single())
    }

    @Synchronized
    private fun hookResolver(module: XposedInterface, method: Method): Boolean {
        if (method.returnType != Int::class.javaPrimitiveType || method.parameterCount != 2 ||
            method.parameterTypes[0] != android.content.Context::class.java ||
            !method.parameterTypes[1].isEnum
        ) return false
        return runCatching {
            hookedMethods.installOnce(method) {
                method.isAccessible = true
                module.hook(method).intercept(ResolverResultHook)
            }
            true
        }.onFailure { L.w(TAG, "FDS view resolver hook failed: $method", it) }
            .getOrDefault(false)
    }

    private object UntokenedResultHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val color = result as? Int ?: return result
            return literal(color)
        }
    }

    /** Set while the module sets a colour of its own, which the colour seams must pass untouched. */
    private val ownColor = ThreadLocal<Boolean>()

    private object LiteralColorArgHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (ownColor.get() == true) return chain.proceed()
            val color = chain.args.getOrNull(0) as? Int ?: return chain.proceed()
            val replacement = literal(color)
            if (replacement == color) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = replacement
            return chain.proceed(args)
        }
    }

    private object ViewBackgroundColorHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val color = chain.args.getOrNull(0) as? Int ?: return chain.proceed()
            val replacement = literal(color)
            if (replacement == color) return chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = replacement
            return chain.proceed(args)
        }
    }

    private object FeedRecyclerLayoutHook : Hooker {
        @Volatile private var feedRef: WeakReference<View>? = null

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (!enabled()) return result
            val view = chain.thisObject as? View ?: return result
            val knownFeed = feedRef?.get()
            if (knownFeed === view || (knownFeed == null && looksLikeNewsFeed(view))) {
                if (knownFeed == null) {
                    feedRef = WeakReference(view)
                    L.i(TAG, "identified News Feed RecyclerView for separator drawing")
                }
            }
            return result
        }

        fun isFeed(view: View): Boolean = feedRef?.get() === view

        private fun looksLikeNewsFeed(view: View): Boolean {
            val dm = view.resources.displayMetrics
            if (view.width < dm.widthPixels * 9 / 10 || view.height < dm.heightPixels * 7 / 10) {
                return false
            }
            var parent = view.parent
            var inMainPager = false
            repeat(12) {
                val p = parent ?: return@repeat
                if (inheritsFrom(p, "androidx.viewpager.widget.ViewPager")) inMainPager = true
                parent = p.parent
            }
            return inMainPager
        }

        private fun inheritsFrom(instance: Any, className: String): Boolean {
            var cls: Class<*>? = instance.javaClass
            while (cls != null) {
                if (cls.name == className) return true
                cls = cls.superclass
            }
            return false
        }
    }

    private object FeedRecyclerDrawHook : Hooker {
        private val separatorPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (!enabled()) return result
            val group = chain.thisObject as? ViewGroup ?: return result
            if (!FeedRecyclerLayoutHook.isFeed(group)) return result
            val canvas = chain.args.getOrNull(0) as? Canvas ?: return result

            // Paint.setColor is one of the hooked seams: the separator's colour is already final,
            // and a muted system accent makes it dark and grey enough to be taken for a background.
            ownColor.set(true)
            try { separatorPaint.color = currentFeedSeparatorColor() } finally { ownColor.set(false) }
            val thickness = (group.resources.displayMetrics.density * 4f).coerceAtLeast(2f)
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.height <= 0) continue
                val bottom = child.bottom + child.translationY
                if (bottom <= thickness || bottom >= group.height) continue
                canvas.drawRect(
                    0f,
                    bottom - thickness,
                    group.width.toFloat(),
                    bottom,
                    separatorPaint,
                )
            }
            return result
        }
    }

    private object ContentDescriptionHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            if (!enabled()) return result
            val view = chain.thisObject as? View ?: return result
            if (chain.args.getOrNull(0)?.toString() == "Log out") {
                runCatching { view.background?.mutate()?.setTint(AmoledColorRule.BLACK) }
            }
            return result
        }
    }

    private object ResolverResultHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val color = result as? Int ?: return result
            val tokenName = chain.args.asSequence()
                .mapNotNull { (it as? Enum<*>)?.name }
                .firstOrNull()
            if (!enabled()) return AccentColorRule.resolver(color, tokenName, accent())
            if (tokenName == NEW_NOTIFICATION_BACKGROUND) {
                unreadNotificationColor = color
                if (!loggedUnreadColor) {
                    loggedUnreadColor = true
                    L.i(TAG, "captured NEW_NOTIFICATION_BACKGROUND=#%08X".format(color))
                }
            }
            return AccentColorRule.resolver(AmoledColorRule.resolver(color, tokenName), tokenName, accent())
        }
    }

    /** The separator is the unread-notification tint, so it follows the accent with it. */
    private fun currentFeedSeparatorColor(): Int = AmoledColorRule.opaqueOverBlack(
        AccentColorRule.resolver(unreadNotificationColor, NEW_NOTIFICATION_BACKGROUND, accent()),
    )
}
