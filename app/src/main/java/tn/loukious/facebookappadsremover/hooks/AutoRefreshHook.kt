package tn.loukious.facebookappadsremover.hooks

import tn.loukious.facebookappadsremover.core.Discovery
import tn.loukious.facebookappadsremover.core.HookTarget
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method

/**
 * News Feed auto-refresh block — port of the original mod's "Block automatic
 * NewsFeed refresh (Beta)" (installer §0 #15 `oRfibd$EKUKH` @1297197 →
 * `yJvWuDhOP2sJgQUAMjl2.AdjzonOq0OhXgiiuU6H` @1442918).
 *
 * The mod ran a dexplore dex-scan (filter strings: '-refreshForRevisit',
 * 'maybeRefreshForWarmStart', 'NewsFeedFragment.onResume', 'onAppForeground',
 * 'NewsFeedFragmentDataController', 'forceRefresh', 'AUTO_REFRESH',
 * 'AppJobNewsFeedAppStateManager', 'FriendlyFeed Feed Prefetch',
 * 'BaseFeedCSRDataLoaderAdapter'), cached the results in prefs, then
 * registered FOUR beforeHookedMethod blockers, each gated on the
 * swNewsFeedAutoReload pref and finishing with setResult(null):
 *
 *   X/Bz8RgSjtmmXR1q1o9x5B  → "BLOCK onAppForeground"
 *   X/WIl2ZQBzBL6YJN2TYmaU  → "BLOCK refreshForRevisit reason=" (reason = args[1])
 *   X/WPUwyvVt8PqyoOf4m806  → "BLOCK maybeRefreshForWarmStart"
 *   X/cdzLRGdWgtzkiC6HtEHg  → "BLOCK forceRefresh reason=" (reason = args[1])
 *
 * 576 equivalents (verified in the 576 decompile, 2026-09-06 — the mod's
 * filter strings survive as body literals, so each site anchors on its own
 * string):
 *  1. refreshForRevisit → `X.2NL` = NewsFeedFragmentDataController
 *     `.refreshForRevisit(boolean,boolean,boolean,int,String,boolean)` — the
 *     literal "-refreshForRevisit" is built inside the body; reason is now
 *     args[4] (was args[1] in the mod's build).
 *  2. maybeRefreshForWarmStart → `X.9gyY` = BaseFeedCSRDataLoaderAdapter
 *     `.A05(String)` (int) — logs "maybeRefreshForWarmStart".
 *  3. forceRefresh → same adapter `.A0M(Enum,String)` — logs "AUTO_REFRESH" +
 *     "forceRefresh"; reason = args[1], matching the mod's args.length≥2 read.
 *  4. onAppForeground → `X.2L0` = NewsFeedFragment `.A0j()` (void) — the only
 *     "onAppForeground" string literal in the dex; calls
 *     refreshForRevisit(..., "onAppForeground", ...) plus the
 *     decideForegroundAutoScroll auto-scroll.
 *
 * 588 (verified 2026-11-16): refreshForRevisit lost a parameter (5 now, the
 * reason is still args[4]); the warm-start check is `(long, String): int` and
 * exists twice (adapter + subclass override); NewsFeedFragment.onAppForeground
 * is `A0z()`.
 *
 * Deliberate deviation: none — setResult(null) maps to returning the default
 * value per return type (false/0/null), exactly what Xposed's setResult(null)
 * coerced to in the mod.
 */
object AutoRefreshHook {

    private const val TAG = "FBAR.Refresh"

    /** Cache key — combined methods map; the class map doubles as sentinel. */
    const val CACHE_KEY = "feed.autoRefreshBlock"

    /** The four blocker sites, anchored on the mod's own filter strings. */
    private val targets = listOf(
        HookTarget(
            key = "$CACHE_KEY.revisit",
            description = "NewsFeedFragmentDataController.refreshForRevisit (mod: WIl2ZQ blocker)",
        ) { bridge: DexKitBridge ->
            bridge.findMethod { matcher { usingStrings("-refreshForRevisit") } }
        },
        HookTarget(
            key = "$CACHE_KEY.warmStart",
            description = "BaseFeedCSRDataLoaderAdapter maybeRefreshForWarmStart (mod: WPUwyv blocker)",
            // 588 has two: the adapter's method and a subclass override that
            // logs "maybeRefreshForWarmStart skipped: ..." and calls super.
            multi = true,
        ) { bridge: DexKitBridge ->
            bridge.findMethod { matcher { returnType = "int"; usingStrings("maybeRefreshForWarmStart") } }
        },
        HookTarget(
            key = "$CACHE_KEY.force",
            description = "BaseFeedCSRDataLoaderAdapter forceRefresh (mod: cdzLRG blocker)",
        ) { bridge: DexKitBridge ->
            bridge.findMethod { matcher { usingStrings("AUTO_REFRESH", "forceRefresh") } }
        },
        HookTarget(
            key = "$CACHE_KEY.foreground",
            description = "NewsFeedFragment onAppForeground trigger (mod: Bz8RgS blocker)",
        ) { bridge: DexKitBridge ->
            // "onAppForeground" alone is a substring of a dozen unrelated
            // literals in 588 ("onAppForegrounded", ...). The feed's handler is
            // the one that passes it, as the reason, to refreshForRevisit.
            bridge.findMethod {
                matcher {
                    usingEqStrings("onAppForeground")
                    invokeMethods { add { name = "refreshForRevisit" } }
                }
            }.ifEmpty { bridge.findMethod { matcher { usingStrings("onAppForeground") } } }
        },
    )

    /**
     * DexKit path: discover and hook all four blocker sites.
     *
     * @return the hooked methods for the discovery cache, or null if none.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): List<Method>? {
        val reports = try {
            Discovery().discover(bridge, classLoader, targets)
        } catch (t: Throwable) {
            L.w(TAG, "DexKit query failed for auto-refresh blockers", t)
            return null
        }
        val methods = ArrayList<Method>()
        for (report in reports) {
            if (report.status != Discovery.Status.FOUND) {
                L.w(TAG, "${report.status} auto-refresh site: ${report.key}")
                continue
            }
            methods.addAll(hookSite(module, report.methods))
        }
        return if (methods.isEmpty()) null else methods
    }

    /** Cache-hit path: hook the previously discovered sites directly. */
    fun installCached(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.isEmpty()) return false
        return hookSite(module, methods).isNotEmpty()
    }

    private fun hookSite(module: XposedInterface, methods: List<Method>): List<Method> {
        val hooked = ArrayList<Method>(methods.size)
        for (m in methods) {
            // R8 can centralize the anchor literal into a dispatch table —
            // same guard as AdFilterHook/DarkModeHook.
            if (AdFilterHook.isStringDispatchTable(m)) {
                L.w(TAG, "skipping string-table method: ${m.declaringClass.name}.${m.name}")
                continue
            }
            try {
                module.hook(m).intercept(BlockHook)
                hooked.add(m)
                L.i(TAG, "hooked refresh blocker: ${m.declaringClass.name}.${m.name}/${m.parameterCount} (mod log: 'BLOCK ')")
            } catch (t: Throwable) {
                L.w(TAG, "hook failed on ${m.declaringClass.name}.${m.name}", t)
            }
        }
        return hooked
    }

    /**
     * All four blockers: while the toggle is on, log the site (with the
     * reason where the shape provides one) and skip the original — the
     * libxposed equivalent of the mod's setResult(null).
     */
    private object BlockHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.FEED_AUTO_REFRESH_BLOCK, false)) {
                return chain.proceed()
            }
            val exe = chain.executable
            val args = chain.args
            // refreshForRevisit's reason moved to args[4] in 576; forceRefresh
            // keeps it at args[1] (the mod's read).
            val reason = when {
                args.size >= 5 && args[4] is String -> args[4].toString()
                args.size >= 2 && args[1] is String -> args[1].toString()
                else -> "-"
            }
            L.i(TAG, "BLOCK ${exe.declaringClass.name}.${exe.name} reason=$reason (mod: swNewsFeedAutoReload)")
            return defaultValue((exe as? Method)?.returnType ?: java.lang.Void.TYPE)
        }
    }

    /** setResult(null) coercion for primitives (Xposed did this implicitly). */
    private fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Character.TYPE -> ' '
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0.0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Void.TYPE -> null
        else -> null
    }
}
