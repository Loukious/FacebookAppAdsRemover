package tn.loukious.facebookappadsremover.hooks

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.MethodCache
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Instant-games ad & reward removal — port of the previous version of this
 * module's game-ad machinery (Patches.kt game-ads family, dropped in the
 * rewrite).
 *
 * Games inside Facebook (Instant Games / playable ads) get their ads through
 * several independent channels, so the old port attacked each seam:
 *
 *  1. Native request handlers — void methods taking an org.json.JSONObject,
 *     found via the "Invalid JSON content received by onXxxAsync" log anchors
 *     (onGetInterstitialAdAsync / onGetRewardedInterstitialAsync /
 *     onRewardedVideoAsync / onLoadAdAsync / onShowAdAsync). Interception
 *     either resolves the request's promise as success (reward granted, no ad
 *     rendered) or rejects it outright.
 *  2. The JS bridge — postMessage(String, String) on the same class, plus any
 *     @JavascriptInterface object registered through
 *     WebView.addJavascriptInterface (plugin dexes register delegates the
 *     DexKit scan can't know about). Same resolve/reject flow, keyed on the
 *     payload's "type" field.
 *  3. Promise helpers — resolve(promiseId, value) / reject(promiseId, reason…)
 *     on the bridge class. Late rejects are converted to successes so a dead
 *     ad pipeline still grants the reward.
 *  4. Script deliveries — evaluateJavascript/loadUrl/postWebMessage: the
 *     promise result is delivered back into the game webview as generated
 *     JavaScript; the JSON carrying a tracked promiseId is rewritten in place
 *     (a JS promise settles once, so the failure call is never shadowed).
 *  5. Service dispatch — void(Bundle, String) methods whose second arg is a
 *     game-ad message type; the bundle is rebuilt as a payload and resolved.
 *  6. Activities — Audience Network / Neko playable ad activities are closed
 *     as soon as they appear (with the success result intent so the game
 *     treats the ad as watched), Audience Network reward listeners are fired
 *     manually via an object-graph walk, and hard-blocked launches
 *     (NekoPlayableAdActivity) never start at all.
 *  7. View surface — a self-rearming banner-sweep script is injected into
 *     every game webview, and native Audience Network views are hidden.
 *
 * Promise snapshots are the thread that ties 1–4 together: every observed
 * request remembers its payload, and every later resolve/reject/delivery
 * consults the snapshot so the outcome is forced to success even when the ad
 * pipeline dies in between.
 *
 * Dropped from the old port, deliberately: all diagnostics machinery
 * (GADIAG dumps, state dumps, delivery logging), the obfuscated-name →
 * message-type map (inference now relies on the payload "type" field only),
 * and the Audience Network close-button autoclick family (dead code in the
 * old port — scheduleAudienceNetworkRewardClose had no call sites).
 */
object GameAdsHook {

    private const val TAG = "FBAR.GameAds"

    /** Discovery-cache key — holds the comma-joined bridge class name(s). */
    const val CACHE_KEY = "gameads.bridge"

    // ------------------------------------------------------------------
    // Constants (ported verbatim)
    // ------------------------------------------------------------------

    /** Stable Audience Network activity names — public SDK classes. */
    private const val AUDIENCE_NETWORK_ACTIVITY_CLASS = "com.facebook.ads.AudienceNetworkActivity"
    private const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS =
        "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"

    /** Neko playable-ads activity — always blocked, never even started. */
    private const val NEKO_PLAYABLE_ACTIVITY_CLASS =
        "com.facebook.neko.playables.activity.NekoPlayableAdActivity"

    private const val GAME_AD_REJECTION_MESSAGE = "Game ad request blocked"
    private const val GAME_AD_REJECTION_CODE = "CLIENT_UNSUPPORTED_OPERATION"

    /** Prefix for synthesized ad-instance ids handed back to the game. */
    private const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "facebook_app_ads_remover_noop_ad"

    /** How long a request stays "recent" (re-resolvable, banner-hideable). */
    private const val GAME_AD_RECENT_WINDOW_MS = 30_000L

    /** How long a promise snapshot is consulted for rewrites. */
    private const val GAME_AD_PROMISE_WINDOW_MS = 10 * 60_000L

    /**
     * Self-rearming banner sweep for game webviews: hides Audience Network
     * banner containers (iframes near the bottom, ad-marker ids/classes) via
     * a MutationObserver + a 1s interval, so re-rendered banners disappear
     * too. Ported verbatim from the old port.
     */
    private val GAME_AD_WEBVIEW_HIDE_SCRIPT = """
        (function(){
          if (window.__fbAppAdsRemoverBannerSweep) return;
          window.__fbAppAdsRemoverBannerSweep = true;
          function textOf(el) {
            try { return (el.innerText || el.textContent || '').toLowerCase(); } catch (e) { return ''; }
          }
          function attrsOf(el) {
            try { return ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('src') || '')).toLowerCase(); } catch (e) { return ''; }
          }
          function nearBottom(el) {
            try {
              var r = el.getBoundingClientRect();
              return r.height > 0 && r.height < Math.max(260, window.innerHeight * 0.35) && r.bottom > window.innerHeight * 0.55;
            } catch (e) { return false; }
          }
          function isAd(el) {
            var t = textOf(el);
            var a = attrsOf(el);
            if (t.indexOf('ads served by meta') >= 0 || t.indexOf('ad choices') >= 0) return true;
            if (!nearBottom(el)) return false;
            if ((el.tagName || '').toLowerCase() === 'iframe') return true;
            return /audiencenetwork|adchoices|fbinstant.*ad|instant.*ad|banner.?ad|ad.?banner|ad-container|ad_container|sponsored/.test(a);
          }
          function hide(el) {
            try {
              var target = el;
              for (var i = 0; i < 4 && target.parentElement && nearBottom(target.parentElement); i++) target = target.parentElement;
              target.style.setProperty('display', 'none', 'important');
              target.style.setProperty('visibility', 'hidden', 'important');
              target.style.setProperty('height', '0px', 'important');
              target.style.setProperty('min-height', '0px', 'important');
              target.style.setProperty('pointer-events', 'none', 'important');
            } catch (e) {}
          }
          function sweep() {
            try {
              document.querySelectorAll('iframe, div, section, aside, [id], [class], [aria-label]').forEach(function(el) {
                if (isAd(el)) hide(el);
              });
            } catch (e) {}
          }
          sweep();
          new MutationObserver(sweep).observe(document.documentElement || document.body, {childList:true, subtree:true, attributes:true});
          setInterval(sweep, 1000);
        })();
    """

    /** Message types that flow through the game-ad bridge. */
    private val GAME_AD_MESSAGE_TYPES = setOf(
        "getinterstitialadasync",
        "getrewardedvideoasync",
        "getrewardedinterstitialasync",
        "loadadasync",
        "showadasync",
        "loadbanneradasync",
        "hidebanneradasync"
    )

    /**
     * Rewarded and banner lifecycle messages are resolved as success instead
     * of "unavailable" so the game grants the reward without showing an ad.
     */
    private val GAME_AD_AUTOFIX_MESSAGE_TYPES = setOf(
        "getrewardedvideoasync",
        "getrewardedinterstitialasync",
        "loadbanneradasync",
        "hidebanneradasync"
    )

    private val GAME_AD_REWARD_MESSAGE_TYPES = setOf(
        "getrewardedvideoasync",
        "getrewardedinterstitialasync"
    )

    private val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
        NEKO_PLAYABLE_ACTIVITY_CLASS
    )

    private val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
        NEKO_PLAYABLE_ACTIVITY_CLASS
    )

    /** Zero-arg callback names that mark a rewarded ad as completed. */
    private val AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES = setOf(
        "onRewardedVideoCompleted",
        "onRewardedAdCompleted",
        "onRewardedInterstitialCompleted",
        "onAdComplete",
        "onAdCompleted"
    )

    /** DexKit anchors — log literals inside the native request handlers. */
    private val GAME_AD_METHOD_TAGS = listOf(
        "Invalid JSON content received by onGetInterstitialAdAsync: ",
        "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
        "Invalid JSON content received by onRewardedVideoAsync: ",
        "Invalid JSON content received by onLoadAdAsync: ",
        "Invalid JSON content received by onShowAdAsync: "
    )

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    /** Snapshot of an observed game-ad promise, keyed by promise id. */
    private data class GameAdPromiseSnapshot(
        val payload: JSONObject,
        val messageType: String?,
        val timestampMs: Long
    )

    /** A recent request target+payload, for re-resolution after ad deaths. */
    private data class GameAdPayloadSnapshot(
        val target: Any,
        val payload: JSONObject,
        val messageType: String?,
        val timestampMs: Long
    )

    private val gameAdInstanceIds = ConcurrentHashMap<String, String>()
    private val gameAdInstanceTypes = ConcurrentHashMap<String, String>()
    private val gameAdPromiseSnapshots = ConcurrentHashMap<String, GameAdPromiseSnapshot>()
    private val recentGameAdTargets = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val recentGameAdPayloads = Collections.synchronizedList(ArrayList<GameAdPayloadSnapshot>())

    private val lastGameAdActivityCloseMs = AtomicLong(0L)

    /**
     * Set when a request was rejected as unavailable. Retained from the old
     * port for parity, where it was likewise never written — the
     * recent-unavailable gate on Audience Network launches is therefore inert
     * (those activities launch and get closed by the lifecycle handler).
     */
    private val lastUnavailableGameAdMs = AtomicLong(0L)

    // Install-once guards so the full-scan and cache-hit paths (and the
    // optional early arm) never double-hook.
    private val activityLifecycleInstalled = AtomicBoolean(false)
    private val activityLaunchBlockersInstalled = AtomicBoolean(false)
    private val javascriptInterfaceWatcherInstalled = AtomicBoolean(false)
    private val scriptResultHooksInstalled = AtomicBoolean(false)
    private val surfaceWatcherInstalled = AtomicBoolean(false)
    private val audienceNetworkRewardHooksInstalled = AtomicBoolean(false)

    private val gameAdMethodsHooked = ConcurrentHashMap.newKeySet<String>()
    private val gameAdResultHookedClasses = ConcurrentHashMap.newKeySet<String>()
    private val gameAdServiceDispatchHookedClasses = ConcurrentHashMap.newKeySet<String>()
    private val audienceNetworkRewardClassesHooked =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val audienceNetworkRewardAdListeners =
        Collections.synchronizedMap(WeakHashMap<Any, Any>())

    /**
     * Master toggle — same switch as AdFilterHook (mod: one swHOME_ADS).
     * Re-read inside every interceptor so flipping it applies without a
     * re-install.
     */
    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.ADS_GAME_ADS, true)

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /**
     * Full-scan install (DexKit available). Discovers the native request
     * handlers via their log anchors, hooks the bridge class end to end, and
     * arms every framework seam (lifecycle, launch blockers, JS-interface
     * watcher, script seams, Audience Network fallbacks).
     *
     * @return the bridge class name(s) hosting the request handlers — the
     *   cacheable class names for [MethodCache][tn.loukious.facebookappadsremover.core.MethodCache]
     *   (comma-joined under [CACHE_KEY]; empty list should be stored as
     *   "absent" so a NOT_FOUND scan doesn't rescan forever).
     */
    fun install(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        context: Context
    ): List<String> {
        installFrameworkSeams(module, context)
        installAudienceNetworkRewardFallbacks(module, classLoader)

        val requestMethods = resolveGameAdRequestMethods(classLoader, bridge)
        if (requestMethods.isEmpty()) {
            L.w(TAG, "no game ad request methods found — bridge hooks limited to runtime seams")
        }

        val bridgeClasses = LinkedHashSet<String>()
        requestMethods.forEach { method ->
            bridgeClasses.add(method.declaringClass.name)
            runCatching { hookRequest(module, method) }
                .onFailure { L.w(TAG, "request hook failed ${method.declaringClass.name}.${method.name}", it) }
        }

        // The bridge class itself: postMessage entry + promise helpers.
        requestMethods.firstOrNull()?.declaringClass?.let { bridgeClass ->
            runCatching {
                resolveBridgePostMessageMethod(bridgeClass)?.let { entry ->
                    hookBridgeEntry(module, entry, "dex scan")
                }
            }.onFailure { L.w(TAG, "postMessage entry hook failed for ${bridgeClass.name}", it) }
            runCatching { hookResultMethods(module, bridgeClass) }
                .onFailure { L.w(TAG, "result helper hooks failed for ${bridgeClass.name}", it) }
            runCatching { hookServiceDispatchMethods(module, bridgeClass) }
                .onFailure { L.w(TAG, "service dispatch hooks failed for ${bridgeClass.name}", it) }
        }

        L.i(TAG, "game ads installed (full scan): ${bridgeClasses.size} bridge class(es), " +
            "${requestMethods.size} request method(s)")
        return bridgeClasses.toList()
    }

    /**
     * Cache-hit install (no DexKit). The bridge class names rotate per FB
     * release, so the cached names are re-resolved structurally: every
     * void(JSONObject) method of the class is hooked as a request handler and
     * the postMessage/promise-helper shapes are re-armed. The hook logic is
     * payload-gated (a message only acts when its "type" is a game-ad type),
     * so shape-matched extra methods are inert.
     *
     * @param cached bridge class names stored by [install]
     * @return true when the bridge was armed or nothing was cached
     *   (framework seams install regardless); false when every cached class
     *   failed to load — the caller may treat that as a cache miss.
     */
    fun installCached(
        module: XposedInterface,
        classLoader: ClassLoader,
        context: Context,
        cached: Set<String>
    ): Boolean {
        installFrameworkSeams(module, context)
        installAudienceNetworkRewardFallbacks(module, classLoader)

        if (cached.isEmpty()) {
            L.i(TAG, "game ads cache-hit: no bridge classes recorded — framework seams only")
            return true
        }

        var armed = 0
        cached.forEach { className ->
            runCatching {
                val bridgeClass = Class.forName(className, false, classLoader)

                (bridgeClass.declaredMethods + bridgeClass.methods)
                    .filter { method ->
                        !Modifier.isStatic(method.modifiers) &&
                            method.returnType == Void.TYPE &&
                            method.parameterCount == 1 &&
                            method.parameterTypes[0] == JSONObject::class.java &&
                            method.name != "<init>" &&
                            method.name != "<clinit>"
                    }
                    .distinctBy { "${it.declaringClass.name}#${it.name}" }
                    .forEach { method ->
                        runCatching { hookRequest(module, method) }.onFailure {
                            L.w(TAG, "cached request hook failed ${className}.${method.name}", it)
                        }
                    }

                resolveBridgePostMessageMethod(bridgeClass)?.let { entry ->
                    hookBridgeEntry(module, entry, "cache hit")
                }
                hookResultMethods(module, bridgeClass)
                hookServiceDispatchMethods(module, bridgeClass)
                armed++
            }.onFailure { L.w(TAG, "cached game-ads class $className failed to install", it) }
        }

        L.i(TAG, "game ads installed (cache hit): $armed/${cached.size} bridge class(es)")
        return armed > 0
    }

    /**
     * Optional early arm — framework-only seams (no DexKit, no bridge
     * classes), safe to call from onAppCreated like the old port's early
     * global fallbacks: closes the cold-start window before the discovery
     * pass finishes. Both [install] and [installCached] call it too; every
     * sub-installer is idempotent.
     */
    fun installEarly(module: XposedInterface, context: Context) {
        installFrameworkSeams(module, context)
    }

    // ------------------------------------------------------------------
    // ModuleMain wiring (shared MethodCache class-map channel)
    // ------------------------------------------------------------------

    /**
     * True when the shared discovery cache already carries this hook's
     * class-map entry — a cache written before the game-ads port must force
     * a rescan (checked from ModuleMain.installFromCache).
     */
    fun cachePresent(context: Context): Boolean =
        runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull() != null

    /**
     * Cache-hit install through the shared class map: reads the stored
     * bridge-class CSV and delegates to [installCached]. No-op on a miss;
     * the "absent" sentinel means the anchors found nothing on this build
     * (framework seams still install from [installEarly]).
     */
    fun installFromCache(module: XposedInterface, classLoader: ClassLoader, context: Context) {
        val state = runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull()
        when {
            state == null -> return
            state == "absent" ->
                L.i(TAG, "bridge anchor not found on this build — framework seams only")
            else -> runCatching {
                installCached(module, classLoader, context, state.split(',').toSet())
            }.onFailure { L.e(TAG, "cached installation failed", it) }
        }
    }

    /**
     * Full-scan install (called from ModuleMain.installAdFilters) that also
     * reduces its result to the class-map entry for [CACHE_KEY] — "absent"
     * when the anchors found nothing, so a NOT_FOUND build doesn't rescan.
     */
    fun installAndCache(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        context: Context,
    ): String =
        runCatching { install(module, bridge, classLoader, context) }
            .onFailure { L.e(TAG, "installation failed", it) }
            .getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }?.joinToString(",") ?: "absent"

    /** All framework seams that don't need the app classloader. */
    private fun installFrameworkSeams(module: XposedInterface, context: Context) {
        // Runtime-discovered hooks (addJavascriptInterface watcher, loadClass
        // watcher) fire without module in scope — keep the handle for them.
        moduleHooker = module
        runCatching { installActivityLifecycleHandler(context) }
            .onFailure { L.w(TAG, "activity lifecycle handler registration failed", it) }
        runCatching { installActivityLaunchBlockers(module) }
            .onFailure { L.w(TAG, "activity launch blocker install failed", it) }
        runCatching { installJavascriptInterfaceWatcher(module) }
            .onFailure { L.w(TAG, "Javascript interface watcher install failed", it) }
        runCatching { installSurfaceWatcher(module) }
            .onFailure { L.w(TAG, "view surface watcher install failed", it) }
    }

    // ------------------------------------------------------------------
    // Discovery (full scan)
    // ------------------------------------------------------------------

    /**
     * Native request handlers via their "Invalid JSON content received by
     * onXxxAsync: " log anchors — the one stable fingerprint of methods
     * whose names rotate every release.
     */
    private fun resolveGameAdRequestMethods(
        classLoader: ClassLoader,
        bridge: DexKitBridge
    ): List<Method> {
        val methods = LinkedHashMap<String, Method>()
        GAME_AD_METHOD_TAGS.forEach { tag ->
            runCatching {
                bridge.findMethod {
                    matcher {
                        returnType(Void.TYPE)
                        paramTypes("org.json.JSONObject")
                        usingStrings(tag)
                    }
                }.mapNotNull { methodData ->
                    runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
                }.filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.name != "<init>" &&
                        method.name != "<clinit>"
                }.forEach { method ->
                    method.isAccessible = true
                    methods.putIfAbsent("${method.declaringClass.name}.${method.name}", method)
                }
            }.onFailure { L.w(TAG, "request-method query failed for anchor \"$tag\"", it) }
        }
        return methods.values.toList()
    }

    /** postMessage(String, String) on the bridge class. */
    private fun resolveBridgePostMessageMethod(bridgeClass: Class<*>): Method? {
        return (bridgeClass.declaredMethods + bridgeClass.methods).firstOrNull { method ->
            method.name == "postMessage" &&
                method.parameterCount == 2 &&
                method.parameterTypes.all { it == String::class.java }
        }?.apply { isAccessible = true }
    }

    // ------------------------------------------------------------------
    // Hook installers
    // ------------------------------------------------------------------

    /** Hooks a native request handler (void, JSONObject payload). */
    private fun hookRequest(module: XposedInterface, method: Method) {
        if (!gameAdMethodsHooked.add(methodHookKey(method))) return
        method.isAccessible = true
        module.hook(method).intercept(RequestHooker(method))
        L.i(TAG, "hooked game ad request ${method.declaringClass.name}.${method.name}")
    }

    /** Hooks a JS bridge entry method (String payload). */
    private fun hookBridgeEntry(module: XposedInterface, method: Method, source: String) {
        if (!gameAdMethodsHooked.add(methodHookKey(method))) return
        method.isAccessible = true
        module.hook(method).intercept(BridgeHooker(method))
        L.i(TAG, "hooked game ad bridge entry ${method.declaringClass.name}.${method.name} via $source")
    }

    /**
     * Promise helpers on the bridge class: resolve(promiseId, value) gets its
     * value forced to success; reject(promiseId, reason…) and the
     * (String, String, JSONObject) bridge variant get converted into an
     * original-pipeline resolve with a success payload.
     */
    private fun hookResultMethods(module: XposedInterface, bridgeClass: Class<*>) {
        if (!gameAdResultHookedClasses.add(bridgeClass.name)) return

        val resolveMethod = resolveGameAdResolveMethod(bridgeClass)
        val rejectMethod = resolveGameAdRejectMethod(bridgeClass)
        val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(bridgeClass)
        var hooked = 0

        resolveMethod?.let { method ->
            runCatching {
                module.hook(method).intercept(ResolveHooker(method))
                hooked++
            }.onFailure { L.w(TAG, "resolve hook failed for ${bridgeClass.name}", it) }
        }
        if (rejectMethod != null && resolveMethod != null) {
            runCatching {
                module.hook(rejectMethod).intercept(RejectHooker(rejectMethod, resolveMethod))
                hooked++
            }.onFailure { L.w(TAG, "reject hook failed for ${bridgeClass.name}", it) }
        }
        if (bridgeRejectMethod != null && resolveMethod != null && bridgeRejectMethod != rejectMethod) {
            runCatching {
                module.hook(bridgeRejectMethod)
                    .intercept(BridgeRejectHooker(bridgeRejectMethod, resolveMethod))
                hooked++
            }.onFailure { L.w(TAG, "bridge reject hook failed for ${bridgeClass.name}", it) }
        }

        if (hooked > 0) {
            L.i(TAG, "hooked $hooked game ad result helper method(s) in ${bridgeClass.name}")
        }
    }

    /** void(Bundle, String) service-dispatch methods on the bridge class. */
    private fun hookServiceDispatchMethods(module: XposedInterface, bridgeClass: Class<*>) {
        if (!gameAdServiceDispatchHookedClasses.add(bridgeClass.name)) return

        val methods = (bridgeClass.declaredMethods + bridgeClass.methods)
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0] == Bundle::class.java
            }
            .distinctBy { method ->
                method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
            }

        var hooked = 0
        methods.forEach { method ->
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(ServiceDispatchHooker(method))
                hooked++
            }.onFailure { L.w(TAG, "service dispatch hook failed ${bridgeClass.name}.${method.name}", it) }
        }
        if (hooked > 0) {
            L.i(TAG, "hooked $hooked game ad service dispatch method(s) in ${bridgeClass.name}")
        }
    }

    // ------------------------------------------------------------------
    // Framework seams: activity lifecycle
    // ------------------------------------------------------------------

    /**
     * Game-ad activity close — replaces the old port's per-class
     * onResume/onStart/onCreate hooks and its global Activity.onResume hook
     * with one ActivityLifecycleCallbacks registration (the DownloadHook
     * pattern). Activities that slip past the launch blockers are closed the
     * moment they are created or resumed; AN activities additionally get
     * their reward listeners fired first so the game still pays out.
     */
    private fun installActivityLifecycleHandler(context: Context) {
        if (!activityLifecycleInstalled.compareAndSet(false, true)) return
        val app = context.applicationContext as? Application ?: context as? Application
        if (app == null) {
            L.w(TAG, "no Application context — game ad activity handler not registered")
            return
        }
        app.registerActivityLifecycleCallbacks(GameAdActivityTracker())
        L.i(TAG, "game ad activity lifecycle handler registered")
    }

    private class GameAdActivityTracker : Application.ActivityLifecycleCallbacks {

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            handle(activity, "created")
        }

        override fun onActivityResumed(activity: Activity) {
            handle(activity, "resumed")
        }

        private fun handle(activity: Activity, stage: String) {
            runCatching {
                // The banner sweep rides every resume (like the old global
                // fallback): game surfaces host the webview anywhere below
                // the decor view.
                scheduleGameAdSurfaceSweep(
                    activity.window?.decorView,
                    "activity $stage ${activity.javaClass.name}"
                )
                if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
                handleGameAdActivity(activity, "activity lifecycle $stage")
            }.onFailure { L.w(TAG, "game ad activity handling failed", it) }
        }

        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    // ------------------------------------------------------------------
    // Framework seams: launch blocking
    // ------------------------------------------------------------------

    /**
     * Blocks game-ad activity launches at the source — every
     * execStartActivity / startActivity* overload taking an Intent on
     * Instrumentation, Activity and ContextWrapper. Hard-blocked targets
     * (Neko playable ads) never start; the Audience Network pair is left to
     * the lifecycle handler (the recent-unavailable gate was inert in the
     * old port too — see [lastUnavailableGameAdMs]).
     */
    private fun installActivityLaunchBlockers(module: XposedInterface) {
        if (!activityLaunchBlockersInstalled.compareAndSet(false, true)) return

        val methods = LinkedHashMap<String, Method>()
        listOf(Instrumentation::class.java, Activity::class.java, ContextWrapper::class.java)
            .forEach { type ->
                (type.declaredMethods + type.methods)
                    .filter { method ->
                        method.name in setOf(
                            "execStartActivity", "startActivity",
                            "startActivityForResult", "startActivityIfNeeded"
                        ) && method.parameterTypes.any { it == Intent::class.java }
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        val signature = buildString {
                            append(method.declaringClass.name)
                            append('.')
                            append(method.name)
                            append('(')
                            append(method.parameterTypes.joinToString(",") { it.name })
                            append(')')
                        }
                        methods.putIfAbsent(signature, method)
                    }
            }

        var hooked = 0
        methods.values.forEach { method ->
            runCatching {
                module.hook(method).intercept(LaunchBlockHooker(method))
                hooked++
            }.onFailure {
                L.w(TAG, "launch blocker hook failed ${method.declaringClass.name}.${method.name}", it)
            }
        }
        L.i(TAG, "hooked $hooked game ad activity launch blocker method(s)")
    }

    /** True when a launch to this game-ad activity must be blocked. */
    private fun shouldBlockGameAdActivityLaunch(className: String): Boolean {
        return className in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES ||
            (className in setOf(AUDIENCE_NETWORK_ACTIVITY_CLASS, AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS) &&
                isRecentUnavailableGameAd())
    }

    /** Explicit-component target that is a game-ad activity, or null. */
    private fun resolveBlockedGameAdActivity(intent: Intent): String? {
        val explicitTarget = intent.component?.className
        if (explicitTarget != null && explicitTarget in GAME_AD_ACTIVITY_CLASS_NAMES) {
            return explicitTarget
        }
        return null
    }

    // ------------------------------------------------------------------
    // Framework seams: JS bridge watcher + script deliveries
    // ------------------------------------------------------------------

    /**
     * The instant-games delegate that receives ad messages is not always the
     * DexKit bridge — the game webview can register a delegate from a lazily
     * loaded plugin dex whose obfuscated name is unknowable in advance.
     * WebView.addJavascriptInterface is a stable framework seam that observes
     * every delegate object at registration time; its JS entry methods are
     * then hooked with the same bridge logic.
     */
    private fun installJavascriptInterfaceWatcher(module: XposedInterface) {
        if (!javascriptInterfaceWatcherInstalled.compareAndSet(false, true)) return
        runCatching {
            val addInterfaceMethod = WebView::class.java.getDeclaredMethod(
                "addJavascriptInterface",
                Any::class.java,
                String::class.java
            )
            addInterfaceMethod.isAccessible = true
            module.hook(addInterfaceMethod).intercept(JavascriptInterfaceWatcher)
            L.i(TAG, "waiting for game webview Javascript bridges")
        }.onFailure { L.w(TAG, "WebView.addJavascriptInterface hook failed", it) }

        // Promise deliveries can happen before the DexKit scan, so the
        // webview script seams are installed alongside the bridge watcher.
        installScriptResultHooks(module)
    }

    /** Hooks a registered bridge object's entry + helper methods. */
    private fun hookGameAdBridgeObject(bridgeObject: Any, source: String) {
        val bridgeClass = bridgeObject.javaClass

        val entryMethods = (bridgeClass.declaredMethods + bridgeClass.methods).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount in 1..2 &&
                method.parameterTypes[0] == String::class.java &&
                method.isAnnotationPresent(JavascriptInterface::class.java)
        }.ifEmpty {
            (bridgeClass.declaredMethods + bridgeClass.methods).filter { method ->
                method.name == "postMessage" &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
        }

        entryMethods.forEach { method ->
            if (!gameAdMethodsHooked.add(methodHookKey(method))) return@forEach
            runCatching {
                method.isAccessible = true
                moduleHooker?.let { module ->
                    module.hook(method).intercept(BridgeHooker(method))
                    L.i(TAG, "hooked game bridge entry ${bridgeClass.name}.${method.name} via $source")
                }
            }.onFailure {
                L.w(TAG, "game bridge entry hook failed ${bridgeClass.name}.${method.name}", it)
            }
        }

        moduleHooker?.let { module ->
            runCatching { hookResultMethods(module, bridgeClass) }
                .onFailure { L.w(TAG, "result hooks failed for ${bridgeClass.name}", it) }
            runCatching { hookServiceDispatchMethods(module, bridgeClass) }
                .onFailure { L.w(TAG, "service dispatch hooks failed for ${bridgeClass.name}", it) }
        }
    }

    /**
     * The promise result is delivered back into the game webview as generated
     * JavaScript (or a WebMessage) by plumbing whose shape varies per
     * delegate. These framework seams see every delivery regardless.
     */
    private fun installScriptResultHooks(module: XposedInterface) {
        if (!scriptResultHooksInstalled.compareAndSet(false, true)) return

        runCatching {
            hookWebViewScriptDelivery(
                module, "evaluateJavascript", String::class.java, ValueCallback::class.java
            )
        }.onFailure { L.w(TAG, "evaluateJavascript script seam failed", it) }
        runCatching {
            hookWebViewScriptDelivery(module, "loadUrl", String::class.java)
        }.onFailure { L.w(TAG, "loadUrl script seam failed", it) }
        runCatching {
            val webMessageClass = Class.forName("android.webkit.WebMessage")
            val getData = webMessageClass.getDeclaredMethod("getData")
            val constructor = webMessageClass.getConstructor(String::class.java)
            val postWebMessage = WebView::class.java.getDeclaredMethod(
                "postWebMessage",
                webMessageClass,
                android.net.Uri::class.java
            )
            postWebMessage.isAccessible = true
            module.hook(postWebMessage).intercept(PostWebMessageHooker(getData, constructor))
            L.i(TAG, "watching WebView.postWebMessage for game ad promise results")
        }.onFailure { L.w(TAG, "postWebMessage script seam failed", it) }
    }

    private fun hookWebViewScriptDelivery(
        module: XposedInterface,
        name: String,
        vararg parameterTypes: Class<*>
    ) {
        val method = WebView::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        module.hook(method).intercept(ScriptDeliveryHooker(name))
        L.i(TAG, "watching WebView.$name for game ad promise results")
    }

    // ------------------------------------------------------------------
    // Framework seams: view surface
    // ------------------------------------------------------------------

    /**
     * View-level safety net: Audience Network native views are hidden the
     * moment they mount, and every WebView gets the banner-sweep script
     * (re-injected on load/attach). The old port's feed-marker branches of
     * this watcher belong to the feed hooks, not the game-ads family, and
     * are not ported here.
     */
    private fun installSurfaceWatcher(module: XposedInterface) {
        if (!surfaceWatcherInstalled.compareAndSet(false, true)) return

        var hooked = 0
        runCatching {
            (ViewGroup::class.java.declaredMethods + ViewGroup::class.java.methods)
                .filter { method ->
                    method.name == "addView" &&
                        method.parameterTypes.any { it == View::class.java }
                }
                .distinctBy { method ->
                    method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(AddViewWatcher)
                        hooked++
                    }.onFailure {
                        L.w(TAG, "addView watcher hook failed ${method.name}", it)
                    }
                }
        }.onFailure { L.w(TAG, "addView watcher install failed", it) }

        runCatching {
            (WebView::class.java.declaredMethods + WebView::class.java.methods)
                .filter { method ->
                    method.name in setOf("loadUrl", "loadData", "loadDataWithBaseURL") ||
                        method.name == "onAttachedToWindow"
                }
                .distinctBy { method ->
                    method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(WebViewLifecycleWatcher)
                        hooked++
                    }.onFailure {
                        L.w(TAG, "WebView lifecycle hook failed ${method.name}", it)
                    }
                }
        }.onFailure { L.w(TAG, "WebView lifecycle watcher install failed", it) }

        L.i(TAG, "hooked $hooked game ad view surface watcher method(s)")
    }

    /** Re-runs the sweep after the tree settles (mount timing varies). */
    private fun scheduleGameAdSurfaceSweep(view: View?, reason: String) {
        val root = view?.rootView ?: view ?: return
        longArrayOf(0L, 250L, 1_000L, 2_500L, 5_000L).forEach { delayMs ->
            root.postDelayed({ sweepGameAdSurface(root, reason) }, delayMs)
        }
    }

    /**
     * Walks the tree injecting the hide script into every WebView and hiding
     * native Audience Network views (the game-ad slice of the old sweep).
     */
    private fun sweepGameAdSurface(view: View?, reason: String): Boolean {
        if (view == null) return false

        var hidden = false
        if (view is WebView) {
            injectGameAdHidingScript(view)
        }
        if (isPotentialNativeGameAdView(view)) {
            hidden = hideNativeGameAdView(view, reason) || hidden
        }

        val group = view as? ViewGroup ?: return hidden
        for (index in 0 until group.childCount) {
            hidden = sweepGameAdSurface(group.getChildAt(index), reason) || hidden
        }
        return hidden
    }

    private fun injectGameAdHidingScript(webView: WebView) {
        webView.post {
            runCatching {
                webView.evaluateJavascript(GAME_AD_WEBVIEW_HIDE_SCRIPT, null)
            }
        }
    }

    /** Audience Network native ad view by stable class-name shape. */
    private fun isPotentialNativeGameAdView(view: View?): Boolean {
        val className = view?.javaClass?.name?.lowercase() ?: return false
        return className == "com.facebook.ads.adview" ||
            (className.endsWith(".adview") &&
                (className.startsWith("com.facebook.ads.") || className.contains("audiencenetwork"))) ||
            className.contains("adchoices")
    }

    /**
     * Collapses a native ad view (and its banner-sized ancestors) to zero
     * height — the game-ad slice of the old hideLikelyAdContainer machinery;
     * the feed-card target resolution is not ported.
     */
    private fun hideNativeGameAdView(view: View, reason: String): Boolean {
        var target = view
        while (true) {
            val parent = target.parent as? View ?: break
            if (!isLikelyBannerSized(parent, parent.rootView)) break
            target = parent
        }

        var hidden = false
        if (target.visibility != View.GONE) {
            target.visibility = View.GONE
            hidden = true
        }
        target.minimumHeight = 0
        target.layoutParams?.let { params ->
            params.height = 0
            target.layoutParams = params
            hidden = true
        }
        target.requestLayout()
        if (hidden) {
            L.i(TAG, "hid native game ad view via $reason target=${target.javaClass.name}")
        }
        return hidden
    }

    /** Short, bottom-half-of-screen view — banner-shaped. */
    private fun isLikelyBannerSized(view: View, root: View?): Boolean {
        val rootHeight = root?.height?.takeIf { it > 0 } ?: return view.height in 1..360
        val height = view.height
        if (height <= 0 || height > maxOf(360, rootHeight / 3)) return false
        val location = IntArray(2)
        return runCatching {
            view.getLocationOnScreen(location)
            location[1] + height > rootHeight / 2
        }.getOrDefault(true)
    }

    // ------------------------------------------------------------------
    // Audience Network reward fallbacks
    // ------------------------------------------------------------------

    /**
     * Rewarded ads rendered by the Audience Network SDK are completed without
     * rendering: show() calls fire the game's listener callbacks directly,
     * listener/load registrations are remembered so the callbacks can be
     * invoked later, and a ClassLoader.loadClass watcher catches the SDK
     * classes that only load lazily from their own dex.
     */
    private fun installAudienceNetworkRewardFallbacks(module: XposedInterface, classLoader: ClassLoader) {
        if (!audienceNetworkRewardHooksInstalled.compareAndSet(false, true)) return

        // Stable public SDK class names (com.facebook.ads.*).
        listOf(
            "com.facebook.ads.RewardedVideoAd",
            "com.facebook.ads.RewardedInterstitialAd",
            "com.facebook.ads.RewardedVideoAdListener",
            "com.facebook.ads.RewardedInterstitialAdListener",
            "com.facebook.ads.RewardedVideoAd\$RewardedVideoAdLoadConfigBuilder",
            "com.facebook.ads.RewardedInterstitialAd\$RewardedInterstitialAdLoadConfigBuilder"
        ).forEach { className ->
            runCatching {
                tryHookAudienceNetworkRewardClass(module, classLoader.loadClass(className))
            }.onFailure { L.w(TAG, "Audience Network reward class hook failed: $className", it) }
        }

        // Dynamically loaded SDK classes: the dex attaches after install, so
        // watch classloading itself. The name filter keeps the hot path cheap.
        runCatching {
            (ClassLoader::class.java.declaredMethods + ClassLoader::class.java.methods)
                .filter { method ->
                    method.name == "loadClass" &&
                        method.parameterTypes.isNotEmpty() &&
                        method.parameterTypes[0] == String::class.java
                }
                .distinctBy { method ->
                    method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        module.hook(method).intercept(LoadClassWatcher)
                    }.onFailure {
                        L.w(TAG, "loadClass watcher hook failed ${method.toGenericString()}", it)
                    }
                }
            L.i(TAG, "hooked Audience Network reward dynamic class fallback")
        }.onFailure { L.w(TAG, "Audience Network dynamic class fallback install failed", it) }
    }

    private fun tryHookAudienceNetworkRewardClass(module: XposedInterface, clazz: Class<*>) {
        val className = clazz.name
        if (!isAudienceNetworkRewardRelevantClass(className) ||
            !audienceNetworkRewardClassesHooked.add(className)
        ) {
            return
        }

        var hooked = 0
        val methods = runCatching { clazz.declaredMethods + clazz.methods }
            .getOrDefault(emptyArray())
        methods.distinctBy { method ->
            method.name + method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }.forEach { method ->
            runCatching {
                method.isAccessible = true
                when {
                    isAudienceNetworkRewardShowMethod(clazz, method) -> {
                        module.hook(method).intercept(RewardShowHooker(method))
                        hooked++
                    }
                    isAudienceNetworkRewardListenerRegistrationMethod(method) -> {
                        module.hook(method).intercept(RewardListenerHooker(method))
                        hooked++
                    }
                    isAudienceNetworkRewardLoadMethod(clazz, method) -> {
                        module.hook(method).intercept(RewardLoadHooker(method))
                        hooked++
                    }
                    else -> {}
                }
            }.onFailure {
                L.w(TAG, "Audience Network reward method hook failed ${className}.${method.name}", it)
            }
        }

        if (hooked > 0) {
            L.i(TAG, "hooked $hooked Audience Network reward method(s) in $className")
        }
    }

    private fun isAudienceNetworkRewardRelevantClass(className: String): Boolean {
        val normalized = className.lowercase()
        return (normalized.startsWith("com.facebook.ads.") ||
            normalized.startsWith("com.facebook.audiencenetwork.") ||
            normalized.contains("audiencenetwork")) &&
            (
                normalized.contains("reward") ||
                    normalized.contains("adlistener") ||
                    normalized.contains("adconfig") ||
                    normalized.endsWith(".ad")
                )
    }

    private fun isAudienceNetworkRewardShowMethod(clazz: Class<*>, method: Method): Boolean {
        val className = clazz.name.lowercase()
        return className.contains("reward") &&
            method.name == "show" &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterCount <= 1 &&
            (method.returnType == Void.TYPE ||
                method.returnType == Boolean::class.javaPrimitiveType ||
                method.returnType == Boolean::class.java)
    }

    private fun isAudienceNetworkRewardLoadMethod(clazz: Class<*>, method: Method): Boolean {
        return clazz.name.lowercase().contains("reward") &&
            method.name.lowercase().contains("load") &&
            !Modifier.isStatic(method.modifiers) &&
            method.parameterCount >= 1
    }

    private fun isAudienceNetworkRewardListenerRegistrationMethod(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || method.parameterCount == 0) return false
        val name = method.name.lowercase()
        if (name.contains("listener")) return true
        return method.parameterTypes.any { type ->
            val typeName = type.name.lowercase()
            typeName.contains("listener") &&
                (typeName.contains("reward") || typeName.contains("ad"))
        }
    }

    private fun rememberAudienceNetworkRewardListeners(owner: Any?, args: List<Any?>?, method: Method) {
        if (owner == null || args == null) return
        args.forEach { arg ->
            if (arg != null && isAudienceNetworkRewardListenerObject(arg)) {
                audienceNetworkRewardAdListeners[owner] = arg
                L.i(
                    TAG,
                    "remembered Audience Network reward listener ${arg.javaClass.name} " +
                        "from ${method.declaringClass.name}.${method.name}"
                )
            } else {
                findAudienceNetworkRewardListeners(arg).firstOrNull()?.let { listener ->
                    audienceNetworkRewardAdListeners[owner] = listener
                    L.i(
                        TAG,
                        "remembered nested Audience Network reward listener ${listener.javaClass.name} " +
                            "from ${method.declaringClass.name}.${method.name}"
                    )
                }
            }
        }
    }

    private fun isAudienceNetworkRewardListenerObject(value: Any?): Boolean {
        if (value == null) return false
        val type = value.javaClass
        val className = type.name.lowercase()
        if (className.contains("listener") && (className.contains("reward") || className.contains("ad"))) {
            return true
        }
        if (audienceNetworkInterfacesFor(type).any { iface ->
                val ifaceName = iface.name.lowercase()
                ifaceName.contains("listener") && (ifaceName.contains("reward") || ifaceName.contains("ad"))
            }) {
            return true
        }
        return audienceNetworkMethodsFor(type).any { method ->
            method.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES ||
                method.name.contains("Reward", ignoreCase = true) ||
                method.name.contains("InterstitialDismissed", ignoreCase = true)
        }
    }

    private fun audienceNetworkInterfacesFor(type: Class<*>): List<Class<*>> {
        val interfaces = LinkedHashSet<Class<*>>()
        fun collect(current: Class<*>?) {
            if (current == null || current == Any::class.java) return
            current.interfaces.forEach { iface ->
                if (interfaces.add(iface)) collect(iface)
            }
            collect(current.superclass)
        }
        collect(type)
        return interfaces.toList()
    }

    /**
     * Fires every reward listener reachable from the ad object (remembered
     * listeners first, then an object-graph walk), so the game pays out
     * without the ad rendering.
     */
    private fun completeAudienceNetworkRewardObject(adObject: Any, source: String): Boolean {
        val listeners = LinkedHashSet<Any>()
        synchronized(audienceNetworkRewardAdListeners) {
            audienceNetworkRewardAdListeners[adObject]?.let { listeners.add(it) }
        }
        listeners.addAll(findAudienceNetworkRewardListeners(adObject))

        var invoked = 0
        listeners.forEach { listener ->
            invoked += invokeAudienceNetworkRewardListenerCallbacks(listener, adObject, source)
        }

        if (invoked > 0) {
            L.i(
                TAG,
                "completed Audience Network reward callbacks invoked=$invoked " +
                    "listeners=${listeners.size} via $source"
            )
            completeRecentGameAdRequests(source)
            return true
        }
        L.w(TAG, "no Audience Network reward listener completed for ${adObject.javaClass.name} via $source")
        return false
    }

    private fun findAudienceNetworkRewardListeners(root: Any?): List<Any> {
        if (root == null) return emptyList()

        val listeners = LinkedHashSet<Any>()
        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(root to 0)

        var inspected = 0
        while (!queue.isEmpty() && inspected < 96 && listeners.size < 8) {
            val (value, depth) = queue.removeFirst()
            if (seen.put(value, true) != null) continue
            inspected++

            if (value !== root && isAudienceNetworkRewardListenerObject(value)) {
                listeners.add(value)
                continue
            }
            if (depth >= 5 || !shouldQueueAudienceNetworkObject(value)) continue

            audienceNetworkFieldsFor(value.javaClass).forEach { field ->
                val fieldValue = runCatching { field.get(value) }.getOrNull() ?: return@forEach
                when (fieldValue) {
                    is Iterable<*> -> fieldValue.take(12).forEach { item ->
                        if (item != null &&
                            (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))
                        ) {
                            queue.add(item to depth + 1)
                        }
                    }
                    is Array<*> -> fieldValue.take(12).forEach { item ->
                        if (item != null &&
                            (isAudienceNetworkRewardListenerObject(item) || shouldQueueAudienceNetworkObject(item))
                        ) {
                            queue.add(item to depth + 1)
                        }
                    }
                    else -> if (isAudienceNetworkRewardListenerObject(fieldValue) ||
                        shouldQueueAudienceNetworkObject(fieldValue)
                    ) {
                        queue.add(fieldValue to depth + 1)
                    }
                }
            }
        }

        return listeners.toList()
    }

    /**
     * The listener callback ladder: load/impression/display, then the reward
     * completion, then the close — the order a real rewarded ad would fire.
     */
    private fun invokeAudienceNetworkRewardListenerCallbacks(listener: Any, adObject: Any, source: String): Int {
        var invoked = 0
        val methodGroups = listOf(
            setOf("onAdLoaded", "onLoggingImpression", "onInterstitialDisplayed"),
            setOf(
                "onRewardedVideoCompleted",
                "onRewardedAdCompleted",
                "onRewardedInterstitialCompleted",
                "onAdComplete",
                "onAdCompleted"
            ),
            setOf("onRewardedVideoClosed", "onRewardedInterstitialClosed", "onAdClosed", "onInterstitialDismissed")
        )

        methodGroups.forEach { group ->
            audienceNetworkMethodsFor(listener.javaClass)
                .filter { method -> method.name in group }
                .forEach { method ->
                    val args = audienceNetworkCallbackArgs(method, adObject) ?: return@forEach
                    runCatching {
                        method.invoke(listener, *args)
                        invoked++
                        L.i(TAG, "invoked Audience Network callback ${listener.javaClass.name}.${method.name} via $source")
                    }.onFailure {
                        L.w(TAG, "Audience Network callback failed ${listener.javaClass.name}.${method.name}", it)
                    }
                }
        }

        return invoked
    }

    private fun audienceNetworkCallbackArgs(method: Method, adObject: Any): Array<Any?>? {
        return when (method.parameterCount) {
            0 -> emptyArray()
            1 -> {
                val paramType = method.parameterTypes[0]
                if (paramType.isAssignableFrom(adObject.javaClass)) arrayOf(adObject) else null
            }
            else -> null
        }
    }

    private fun audienceNetworkFieldsFor(type: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = type
        while (current != null &&
            current != Any::class.java &&
            current != Activity::class.java &&
            fields.size < 48
        ) {
            current.declaredFields.forEach { field ->
                if (!Modifier.isStatic(field.modifiers) && fields.size < 48) {
                    field.isAccessible = true
                    fields.add(field)
                }
            }
            current = current.superclass
        }
        return fields
    }

    private fun audienceNetworkMethodsFor(type: Class<*>): List<Method> {
        val methods = LinkedHashMap<String, Method>()
        var current: Class<*>? = type
        while (current != null &&
            current != Any::class.java &&
            current != Activity::class.java
        ) {
            // Local copy: `current` is a captured var, so it can't smart-cast
            // to non-null inside the lambda below.
            val cls: Class<*> = current
            cls.declaredMethods.forEach { method ->
                if (!Modifier.isStatic(method.modifiers)) {
                    method.isAccessible = true
                    methods.putIfAbsent("${cls.name}.${method.name}/${method.parameterCount}", method)
                }
            }
            current = cls.superclass
        }
        return methods.values.toList()
    }

    private fun shouldQueueAudienceNetworkObject(value: Any): Boolean {
        val type = value.javaClass
        if (type.isPrimitive ||
            value is String ||
            value is Number ||
            value is Boolean ||
            value is CharSequence
        ) {
            return false
        }
        return shouldTraverseAudienceNetworkObject(value, false)
    }

    private fun shouldTraverseAudienceNetworkObject(value: Any, isRootActivity: Boolean): Boolean {
        if (isRootActivity) return true
        val className = value.javaClass.name.lowercase()
        return className.startsWith("com.facebook.ads.") ||
            className.startsWith("com.facebook.audiencenetwork.") ||
            className.contains("audiencenetwork") ||
            className.contains("reward") ||
            className.contains("interstitial") ||
            className.contains("fullscreen") ||
            className.contains("listener") ||
            className.contains(".ads.")
    }

    // ------------------------------------------------------------------
    // Activity handling
    // ------------------------------------------------------------------

    /** Closes a game-ad activity; AN activities get reward completion first. */
    private fun handleGameAdActivity(activity: Activity, source: String) {
        if (!enabled()) return
        when (activity.javaClass.name) {
            AUDIENCE_NETWORK_ACTIVITY_CLASS,
            AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS -> {
                forceAudienceNetworkRewardCompletion(activity, source)
                finishGameAdActivity(activity, source)
            }
            else -> finishGameAdActivity(activity, source)
        }
    }

    private fun finishGameAdActivity(activity: Activity, source: String) {
        if (activity.isFinishing) return
        lastGameAdActivityCloseMs.set(System.currentTimeMillis())
        completeRecentGameAdRequests(source)
        if (activity.javaClass.name in GAME_AD_ACTIVITY_CLASS_NAMES) {
            // The game reads the result as "ad watched" — reward granted.
            activity.setResult(Activity.RESULT_OK, Intent().putExtra("success", true))
        } else {
            activity.setResult(Activity.RESULT_CANCELED, Intent())
        }
        activity.finish()
        L.i(TAG, "closed game ad activity ${activity.javaClass.name} via $source")
    }

    /**
     * Walks the activity's object graph (bounded) and invokes every
     * reward-completion callback found — the AN activity holds the ad
     * controller whose listeners the game installed.
     */
    private fun forceAudienceNetworkRewardCompletion(activity: Activity, source: String) {
        if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return

        val seen = IdentityHashMap<Any, Boolean>()
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add(activity to 0)

        var inspected = 0
        var invoked = 0
        while (!queue.isEmpty() && inspected < 96) {
            val (value, depth) = queue.removeFirst()
            if (seen.put(value, true) != null) continue
            inspected++

            invoked += invokeAudienceNetworkRewardCompletionMethods(value)
            if (depth >= 5 || !shouldTraverseAudienceNetworkObject(value, value === activity)) continue

            audienceNetworkFieldsFor(value.javaClass).forEach { field ->
                val fieldValue = runCatching { field.get(value) }.getOrNull() ?: return@forEach
                when (fieldValue) {
                    is Iterable<*> -> fieldValue.take(12).forEach { item ->
                        if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1)
                    }
                    is Array<*> -> fieldValue.take(12).forEach { item ->
                        if (item != null && shouldQueueAudienceNetworkObject(item)) queue.add(item to depth + 1)
                    }
                    else -> if (shouldQueueAudienceNetworkObject(fieldValue)) {
                        queue.add(fieldValue to depth + 1)
                    }
                }
            }
        }

        L.i(TAG, "forced Audience Network reward callbacks invoked=$invoked inspected=$inspected via $source")
    }

    private fun invokeAudienceNetworkRewardCompletionMethods(target: Any): Int {
        var invoked = 0
        audienceNetworkMethodsFor(target.javaClass)
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 0 &&
                    (
                        method.name in AUDIENCE_NETWORK_REWARD_COMPLETION_METHOD_NAMES ||
                            (method.name.contains("Reward", ignoreCase = true) &&
                                method.name.contains("Complete", ignoreCase = true))
                        )
            }
            .forEach { method ->
                runCatching {
                    method.invoke(target)
                    invoked++
                }.onFailure {
                    L.w(
                        TAG,
                        "Audience Network reward callback invoke failed ${target.javaClass.name}.${method.name}",
                        it
                    )
                }
            }
        return invoked
    }

    // ------------------------------------------------------------------
    // Payload bookkeeping (promise snapshots)
    // ------------------------------------------------------------------

    /**
     * Message type from the payload's "type" field only. The old port also
     * mapped obfuscated handler names to types — dropped here (no hardcoded
     * obfuscated names); handlers that don't see a typed payload simply
     * aren't autofixed.
     */
    private fun inferGameAdMessageType(payload: Any?): String? {
        return (payload as? JSONObject)?.optString("type")?.takeIf { it.isNotBlank() }
    }

    private fun rememberGameAdPayload(target: Any?, payload: Any?, messageType: String?) {
        if (target == null || payload !is JSONObject || messageType !in GAME_AD_MESSAGE_TYPES) return

        val now = System.currentTimeMillis()
        recentGameAdTargets[target] = now

        val snapshotPayload = runCatching { JSONObject(payload.toString()) }.getOrNull() ?: payload
        extractGameAdContent(snapshotPayload)
            ?.optString("adInstanceID")
            ?.takeIf { it.isNotBlank() }
            ?.let { adInstanceId ->
                messageType?.let { type -> gameAdInstanceTypes[adInstanceId] = type }
            }
        extractPromiseId(snapshotPayload)?.let { promiseId ->
            gameAdPromiseSnapshots.entries.removeIf { now - it.value.timestampMs > GAME_AD_PROMISE_WINDOW_MS }
            gameAdPromiseSnapshots[promiseId] = GameAdPromiseSnapshot(snapshotPayload, messageType, now)
        }
        synchronized(recentGameAdPayloads) {
            recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
            recentGameAdPayloads.add(GameAdPayloadSnapshot(target, snapshotPayload, messageType, now))
            while (recentGameAdPayloads.size > 20) {
                recentGameAdPayloads.removeAt(0)
            }
        }
    }

    /**
     * Re-resolves every recent request that should autofix (ad pipeline died
     * in between) and tells recent targets their banners are gone.
     */
    private fun completeRecentGameAdRequests(source: String) {
        val now = System.currentTimeMillis()
        val snapshots = synchronized(recentGameAdPayloads) {
            recentGameAdPayloads.removeAll { now - it.timestampMs > GAME_AD_RECENT_WINDOW_MS }
            recentGameAdPayloads.toList()
        }

        var resolved = 0
        snapshots.asReversed().forEach { snapshot ->
            if (shouldAutofixGameAdMessage(snapshot.messageType) &&
                resolveGameAdPayload(snapshot.target, snapshot.payload, snapshot.messageType)
            ) {
                dispatchPostResolveGameAdSignals(snapshot.target, snapshot.payload, snapshot.messageType)
                resolved++
            }
        }

        val targets = synchronized(recentGameAdTargets) {
            recentGameAdTargets.entries.removeIf { now - it.value > GAME_AD_RECENT_WINDOW_MS }
            recentGameAdTargets.keys.toList()
        }
        targets.forEach { target ->
            dispatchGameEvent(target, "hidebannerad", JSONObject().put("completed", true))
        }

        if (resolved > 0) {
            L.i(TAG, "re-resolved $resolved recent game ad request(s) via $source")
        }
    }

    /** Banner lifecycle messages get a hidebannerad event after resolving. */
    private fun dispatchPostResolveGameAdSignals(target: Any?, payload: Any?, messageType: String?) {
        when (messageType) {
            "loadbanneradasync", "hidebanneradasync" -> {
                val content = buildGameAdSuccessPayload(payload, messageType)
                if (dispatchGameEvent(target, "hidebannerad", content)) {
                    L.i(TAG, "dispatched hidebannerad for game banner message type=$messageType")
                }
            }
        }
    }

    private fun shouldConvertGameAdRejectToSuccess(promiseId: String, reason: String): Boolean {
        val snapshot = gameAdPromiseSnapshots[promiseId]
        if (shouldAutofixGameAdMessage(snapshot?.messageType)) return true
        if (snapshot != null && shouldForceGameAdSuccess(snapshot.payload, snapshot.messageType)) return true

        val normalized = reason.lowercase()
        if (!isRecentGameAdActivityClose()) return false
        return normalized.contains("banner")
    }

    private fun shouldAutofixGameAdMessage(messageType: String?): Boolean {
        return messageType in GAME_AD_AUTOFIX_MESSAGE_TYPES
    }

    /**
     * True when the message should be resolved as success on the spot:
     * banner lifecycle messages, rewarded requests, and reward-flavored
     * load/show calls. The game then grants the reward without any ad
     * rendering.
     */
    private fun shouldForceGameAdSuccess(payload: Any?, messageType: String?): Boolean {
        if (shouldAutofixGameAdMessage(messageType)) return true
        if (messageType !in setOf("loadadasync", "showadasync")) return false
        return hasRewardGameAdSignal(payload, messageType)
    }

    private fun hasRewardGameAdSignal(payload: Any?, messageType: String?): Boolean {
        if (messageType in GAME_AD_REWARD_MESSAGE_TYPES) return true

        val content = extractGameAdContent(payload)
        val adInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
        val knownType = adInstanceId?.let { gameAdInstanceTypes[it] }
        if (knownType in GAME_AD_REWARD_MESSAGE_TYPES) return true

        val placementText = listOf(
            content?.optString("placementID").orEmpty(),
            content?.optString("adType").orEmpty(),
            content?.optString("type").orEmpty(),
            content?.optString("format").orEmpty()
        ).joinToString(" ").lowercase()
        if (placementText.contains("reward")) return true

        return payload?.toString()?.lowercase()?.contains("rewarded") == true
    }

    private fun isRecentUnavailableGameAd(): Boolean {
        val rejectedAt = lastUnavailableGameAdMs.get()
        return rejectedAt > 0 && System.currentTimeMillis() - rejectedAt < GAME_AD_RECENT_WINDOW_MS
    }

    private fun isRecentGameAdActivityClose(): Boolean {
        val closedAt = lastGameAdActivityCloseMs.get()
        return closedAt > 0 && System.currentTimeMillis() - closedAt < 15_000L
    }

    /** Message type guessed from a reject reason (no snapshot available). */
    private fun gameAdPromiseTypeFromReason(reason: String): String? {
        val normalized = reason.lowercase()
        return when {
            normalized.contains("reward") && normalized.contains("interstitial") ->
                "getrewardedinterstitialasync"
            normalized.contains("reward") -> "getrewardedvideoasync"
            normalized.contains("interstitial") -> "getinterstitialadasync"
            normalized.contains("banner") -> "loadbanneradasync"
            normalized.contains("show") || normalized.contains("watch") || normalized.contains("complete") ->
                "showadasync"
            normalized.contains("load") -> "loadadasync"
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Resolve / reject plumbing
    // ------------------------------------------------------------------

    /** Resolves a request's promise with a success payload via reflection. */
    private fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
        if (target == null || payload == null) return false

        val promiseId = extractPromiseId(payload)
        if (promiseId == null) {
            L.w(TAG, "unable to extract promiseID for resolved game ad payload")
            return false
        }

        val resolveMethod = resolveGameAdResolveMethod(target.javaClass)
        if (resolveMethod == null) {
            L.w(TAG, "unable to resolve success helper for resolved game ad payload")
            return false
        }

        val successPayload = buildGameAdSuccessPayload(payload, messageType)
        return runCatching {
            resolveMethod.invoke(target, promiseId, successPayload)
            true
        }.getOrElse {
            L.w(TAG, "failed to resolve game ad payload", it)
            false
        }
    }

    /**
     * Rejects a request outright (no autofix possible): via the bridge
     * reject helper when present, else the promise reject helper.
     */
    private fun rejectGameAdPayload(
        target: Any?,
        payload: Any?,
        message: String = GAME_AD_REJECTION_MESSAGE,
        code: String = GAME_AD_REJECTION_CODE
    ): Boolean {
        if (target == null || payload == null) return false

        val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(target.javaClass)
        if (bridgeRejectMethod != null) {
            val success = runCatching {
                bridgeRejectMethod.invoke(target, message, code, payload)
                true
            }.getOrElse {
                L.w(TAG, "failed to reject game ad payload via bridge reject helper", it)
                false
            }
            if (success) {
                return true
            }
        }

        val promiseId = extractPromiseId(payload)
        if (promiseId == null) {
            L.w(TAG, "unable to extract promiseID for rejected game ad payload")
            return false
        }
        val rejectMethod = resolveGameAdRejectMethod(target.javaClass)
        if (rejectMethod == null) {
            L.w(TAG, "unable to resolve reject helper for rejected game ad payload")
            return false
        }
        return runCatching {
            rejectMethod.invoke(target, promiseId, message, code)
            true
        }.getOrElse {
            L.w(TAG, "failed to reject game ad payload", it)
            false
        }
    }

    /**
     * The promise resolve helper: void, (String promiseId, non-primitive
     * value). The JS bridge entry itself fits this shape but is NOT a
     * promise helper — invoking it re-posts the message into the native
     * pipeline — so annotated entries and the postMessage name are excluded.
     */
    private fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
        if (type == null) return null

        val candidates = (type.declaredMethods + type.methods).filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                !method.parameterTypes[1].isPrimitive &&
                !method.isAnnotationPresent(JavascriptInterface::class.java) &&
                method.name != "postMessage"
        }

        return (candidates.firstOrNull { it.parameterTypes[1] == Any::class.java }
            ?: candidates.firstOrNull { JSONObject::class.java.isAssignableFrom(it.parameterTypes[1]) }
            ?: candidates.firstOrNull()
            )?.apply { isAccessible = true }
    }

    /** void(String, String, JSONObject) bridge reject helper. */
    private fun resolveGameAdBridgeRejectMethod(type: Class<*>?): Method? {
        if (type == null) return null
        return (type.declaredMethods + type.methods).firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == JSONObject::class.java
        }?.apply { isAccessible = true }
    }

    /** void(String, String, String) promise reject helper. */
    private fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
        if (type == null) return null
        return (type.declaredMethods + type.methods).firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 3 &&
                method.parameterTypes.all { it == String::class.java }
        }?.apply { isAccessible = true }
    }

    // ------------------------------------------------------------------
    // Game event dispatch
    // ------------------------------------------------------------------

    /**
     * Dispatches a game event (e.g. hidebannerad) through the bridge's
     * enum-dispatch method: void(non-String event, Any content).
     */
    private fun dispatchGameEvent(target: Any?, eventType: String, content: Any?): Boolean {
        if (target == null) return false
        val dispatchMethod = resolveGameEventDispatchMethod(target.javaClass) ?: return false
        val eventValue = resolveGameEventValue(dispatchMethod.parameterTypes[0], eventType) ?: return false

        return runCatching {
            dispatchMethod.invoke(target, eventValue, content ?: JSONObject.NULL)
            true
        }.getOrElse {
            L.w(TAG, "failed to dispatch game event type=$eventType", it)
            false
        }
    }

    private fun resolveGameEventDispatchMethod(type: Class<*>?): Method? {
        if (type == null) return null
        return (type.declaredMethods + type.methods).firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] != String::class.java &&
                method.parameterTypes[1] == Any::class.java
        }?.apply { isAccessible = true }
    }

    /** Enum constant (or static field) matching the event's toString(). */
    private fun resolveGameEventValue(eventType: Class<*>, eventName: String): Any? {
        val valuesMethod = (eventType.declaredMethods + eventType.methods).firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                method.returnType.isArray &&
                method.returnType.componentType == eventType
        }?.apply { isAccessible = true }

        val values = runCatching { valuesMethod?.invoke(null) as? Array<*> }.getOrNull().orEmpty()
        values.firstOrNull { value -> value?.toString() == eventName }?.let { return it }

        return eventType.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                field.type == eventType &&
                runCatching {
                    field.isAccessible = true
                    field.get(null)?.toString() == eventName
                }.getOrDefault(false)
        }?.let { field -> runCatching { field.get(null) }.getOrNull() }
    }

    // ------------------------------------------------------------------
    // Payload builders
    // ------------------------------------------------------------------

    private fun extractGameAdContent(payload: Any?): JSONObject? {
        val json = payload as? JSONObject ?: return null
        return json.optJSONObject("content")
    }

    private fun buildGameAdPayloadFromServiceBundle(bundle: Bundle, messageType: String): JSONObject {
        return JSONObject().apply {
            put("type", messageType)
            put("content", bundleToJsonObject(bundle))
        }
    }

    private fun bundleToJsonObject(bundle: Bundle): JSONObject {
        val json = JSONObject()
        runCatching { bundle.keySet().toList() }
            .getOrDefault(emptyList())
            .forEach { key ->
                val value = runCatching { bundle.get(key) }.getOrNull()
                putJsonCompatibleValue(json, key, value)
            }
        return json
    }

    private fun putJsonCompatibleValue(json: JSONObject, key: String, value: Any?) {
        when (value) {
            null -> json.put(key, JSONObject.NULL)
            is String -> json.put(key, value)
            is Boolean -> json.put(key, value)
            is Number -> json.put(key, value)
            is JSONObject -> json.put(key, value)
            is JSONArray -> json.put(key, value)
            is Bundle -> json.put(key, bundleToJsonObject(value))
            else -> json.put(key, value.toString())
        }
    }

    /** The success answer for a request: success (+ reward fields), ids kept. */
    private fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
        val effectiveMessageType = messageType
            ?: (payload as? JSONObject)?.optString("type").orEmpty()
        val content = extractGameAdContent(payload)
        val result = JSONObject()

        val placementId = content?.optString("placementID")?.takeIf { it.isNotBlank() }
        val requestedAdInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
        val bannerPosition = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }

        result.put("success", true)
        if (hasRewardGameAdSignal(payload, effectiveMessageType)) {
            result.put("completed", true)
            result.put("didComplete", true)
            result.put("watched", true)
            result.put("rewarded", true)
            result.put("completionGesture", "post")
        }

        if (placementId != null) {
            result.put("placementID", placementId)
        }
        if (bannerPosition != null) {
            result.put("bannerPosition", bannerPosition)
        }

        val adInstanceId = when {
            requestedAdInstanceId != null -> {
                gameAdInstanceIds.putIfAbsent(requestedAdInstanceId, requestedAdInstanceId)
                requestedAdInstanceId
            }
            placementId != null && effectiveMessageType != "loadbanneradasync" ->
                resolveGameAdInstanceId(placementId, effectiveMessageType, bannerPosition)
            else -> null
        }

        if (adInstanceId != null) {
            result.put("adInstanceID", adInstanceId)
            effectiveMessageType.takeIf { it.isNotBlank() }?.let { type ->
                gameAdInstanceTypes.putIfAbsent(adInstanceId, type)
            }
        }

        return result
    }

    /** Original resolve value merged with forced success fields. */
    private fun forceGameAdSuccessResult(
        promiseId: String,
        original: Any?,
        payload: JSONObject?,
        messageType: String?
    ): JSONObject {
        val result = when (original) {
            is JSONObject -> copyJsonObject(original)
            else -> JSONObject()
        }
        val success = buildGameAdSuccessPayload(
            payload ?: JSONObject().put("content", JSONObject().put("promiseID", promiseId)),
            messageType
        )

        val keys = success.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, success.opt(key))
        }

        result.put("success", true)
        if (hasRewardGameAdSignal(payload, messageType)) {
            result.put("completed", true)
            result.put("didComplete", true)
            result.put("watched", true)
            result.put("rewarded", true)
            result.put("completionGesture", "post")
        }
        return result
    }

    private fun copyJsonObject(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result.put(key, source.opt(key))
        }
        return result
    }

    /** Stable synthetic instance id per (type, placement, position). */
    private fun resolveGameAdInstanceId(
        placementId: String,
        messageType: String?,
        bannerPosition: String?
    ): String {
        val key = listOf(messageType.orEmpty(), placementId, bannerPosition.orEmpty()).joinToString("|")
        return gameAdInstanceIds.computeIfAbsent(key) {
            val suffix = key.hashCode().toLong() and 0xffffffffL
            "${GAME_AD_SUCCESS_INSTANCE_PREFIX}_$suffix"
        }
    }

    private fun extractPromiseId(payload: Any?): String? {
        return (payload as? JSONObject)
            ?.optJSONObject("content")
            ?.optString("promiseID")
            ?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // Script-delivery rewriting
    // ------------------------------------------------------------------

    /**
     * Rewrites a delivery when it carries a tracked promise id that should
     * succeed. Returns the last rewritten delivery, or null to leave it be.
     */
    private fun rewriteGameAdDeliveryIfNeeded(delivery: String): String? {
        if (gameAdPromiseSnapshots.isEmpty()) return null
        val promiseIds = gameAdPromiseSnapshots.keys.filter { delivery.contains(it) }
        if (promiseIds.isEmpty()) return null

        var result: String? = null
        promiseIds.forEach { promiseId ->
            val snapshot = gameAdPromiseSnapshots[promiseId] ?: return@forEach
            if (!shouldForceGameAdSuccess(snapshot.payload, snapshot.messageType)) return@forEach

            val rewritten = rewritePromiseJsonInDelivery(delivery, promiseId, snapshot) ?: return@forEach
            if (rewritten != delivery) {
                result = rewritten
            }
        }
        return result
    }

    /**
     * Rewrites the JSON object containing the promiseId inside the delivery
     * script: error fields are dropped, success/reward outcome fields are
     * forced. The JSON is spliced in place — a JS promise settles once, so
     * the original failure call must be replaced, never shadowed.
     */
    private fun rewritePromiseJsonInDelivery(
        delivery: String,
        promiseId: String,
        snapshot: GameAdPromiseSnapshot
    ): String? {
        return runCatching {
            val index = delivery.indexOf(promiseId)
            if (index < 0) return null

            var start = delivery.lastIndexOf('{', index)
            if (start < 0) return null

            // Expand to an enclosing object when the promiseId is nested
            // (e.g. inside "content") — the rewrite covers the whole response.
            while (start > 0) {
                val outerStart = delivery.lastIndexOf('{', start - 1)
                if (outerStart < 0) break
                val outer = extractBalancedJson(delivery, outerStart) ?: break
                if (!outer.contains(promiseId)) break
                start = outerStart
            }

            val balanced = extractBalancedJson(delivery, start) ?: return null
            val end = start + balanced.length - 1

            val originalJson = runCatching { JSONObject(delivery.substring(start, end + 1)) }.getOrNull()
                ?: return null
            val success = forceGameAdSuccessResult(
                promiseId = promiseId,
                original = originalJson,
                payload = snapshot.payload,
                messageType = snapshot.messageType
            )
            forceSuccessDeep(success, hasRewardGameAdSignal(snapshot.payload, snapshot.messageType))

            L.i(
                TAG,
                "rewrote game ad promise delivery promise=$promiseId " +
                    "type=${snapshot.messageType} result=${success.toString().take(400)}"
            )
            delivery.substring(0, start) + success + delivery.substring(end + 1)
        }.getOrNull()
    }

    /** Brace-depth scanner with string/escape awareness. */
    private fun extractBalancedJson(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        var cursor = start
        while (cursor < text.length) {
            val c = text[cursor]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return text.substring(start, cursor + 1)
                }
            }
            cursor++
        }
        return null
    }

    /**
     * Outcome fields can live at the top level or inside "content"; error
     * payloads at any level are dropped so the game cannot read a failure
     * reason.
     */
    private fun forceSuccessDeep(json: JSONObject, reward: Boolean) {
        val keys = ArrayList<String>()
        val keyIterator = json.keys()
        while (keyIterator.hasNext()) {
            keys.add(keyIterator.next() as String)
        }
        keys.forEach { key ->
            val value = json.opt(key)
            when {
                key == "error" || key == "errorMessage" ||
                    (key == "code" && json.opt(key) is String) ->
                    json.remove(key)
                value is JSONObject -> forceSuccessDeep(value, reward)
                else -> Unit
            }
        }
        if (json.has("success") || json.has("error") || reward || json.has("completed")) {
            json.put("success", true)
            if (reward) {
                json.put("completed", true)
                json.put("didComplete", true)
                json.put("watched", true)
                json.put("rewarded", true)
                json.put("completionGesture", "post")
            }
        }
    }

    // ------------------------------------------------------------------
    // Hookers
    // ------------------------------------------------------------------

    /**
     * The XposedInterface handle for the hook callbacks installed from
     * runtime-discovered objects (the addJavascriptInterface watcher fires
     * without module in scope). Set by both install entries.
     */
    @Volatile
    private var moduleHooker: XposedInterface? = null

    /**
     * Native request handler: remember the payload, then resolve as success
     * or reject; the original only runs when neither helper is available.
     */
    private class RequestHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val payload = chain.args.getOrNull(0) ?: return chain.proceed()
            val messageType = inferGameAdMessageType(payload)

            rememberGameAdPayload(chain.thisObject, payload, messageType)
            if (!shouldForceGameAdSuccess(payload, messageType)) return chain.proceed()

            if (resolveGameAdPayload(chain.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(chain.thisObject, payload, messageType)
                L.i(TAG, "resolved game ad request as success in ${method.declaringClass.name}.${method.name}")
                return null
            }
            if (rejectGameAdPayload(chain.thisObject, payload)) {
                L.i(TAG, "rejected game ad request in ${method.declaringClass.name}.${method.name}")
                return null
            }
            L.w(TAG, "unable to resolve or reject game ad request ${method.declaringClass.name}.${method.name}")
            return chain.proceed()
        }
    }

    /**
     * JS bridge entry: parses the String message and applies the same
     * resolve/reject flow, gated on the payload's game-ad "type".
     */
    private class BridgeHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val rawMessage = chain.args.getOrNull(0) as? String ?: return chain.proceed()
            val payload = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return chain.proceed()
            val messageType = payload.optString("type")
            if (messageType !in GAME_AD_MESSAGE_TYPES) return chain.proceed()

            rememberGameAdPayload(chain.thisObject, payload, messageType)
            if (!shouldForceGameAdSuccess(payload, messageType)) return chain.proceed()

            if (resolveGameAdPayload(chain.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(chain.thisObject, payload, messageType)
                L.i(TAG, "resolved game ad bridge message type=$messageType " +
                    "in ${method.declaringClass.name}.${method.name}")
                return null
            }
            if (rejectGameAdPayload(chain.thisObject, payload)) {
                L.i(TAG, "rejected game ad bridge message type=$messageType " +
                    "in ${method.declaringClass.name}.${method.name}")
                return null
            }
            L.w(TAG, "unable to resolve or reject game ad bridge message type=$messageType " +
                "in ${method.declaringClass.name}.${method.name}")
            return chain.proceed()
        }
    }

    /**
     * Promise resolve helper: the value argument is replaced with a forced
     * success before the original runs.
     */
    private class ResolveHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val promiseId = chain.args.getOrNull(0) as? String ?: return chain.proceed()
            val snapshot = gameAdPromiseSnapshots[promiseId] ?: return chain.proceed()
            if (snapshot.messageType !in GAME_AD_MESSAGE_TYPES) return chain.proceed()
            if (!enabled()) return chain.proceed()
            if (!shouldForceGameAdSuccess(snapshot.payload, snapshot.messageType)) return chain.proceed()

            val newArgs = chain.args.toTypedArray()
            newArgs[1] = forceGameAdSuccessResult(
                promiseId = promiseId,
                original = chain.args.getOrNull(1),
                payload = snapshot.payload,
                messageType = snapshot.messageType
            )
            L.i(TAG, "forced successful game ad resolve promise=$promiseId type=${snapshot.messageType}")
            return chain.proceed(newArgs)
        }
    }

    /**
     * Promise reject helper: converted into a resolve call with a success
     * payload (invoked through reflection — note that goes through this same
     * hook, whose rewrite is idempotent), then the original reject is
     * skipped.
     */
    private class RejectHooker(
        private val rejectMethod: Method,
        private val resolveMethod: Method
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val promiseId = chain.args.getOrNull(0) as? String ?: return chain.proceed()
            val reason = chain.args.drop(1).joinToString(" ") { it?.toString().orEmpty() }
            if (!enabled()) return chain.proceed()
            if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return chain.proceed()

            val snapshot = gameAdPromiseSnapshots[promiseId]
            val success = forceGameAdSuccessResult(
                promiseId = promiseId,
                original = null,
                payload = snapshot?.payload,
                messageType = snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason)
            )
            return runCatching {
                resolveMethod.invoke(chain.thisObject, promiseId, success)
                L.i(
                    TAG,
                    "converted game ad reject to success promise=$promiseId " +
                        "type=${snapshot?.messageType} reason=$reason"
                )
                null
            }.getOrElse {
                L.w(TAG, "failed to convert game ad reject to success promise=$promiseId", it)
                chain.proceed()
            }
        }
    }

    /** (String, String, JSONObject) bridge reject — same reject conversion. */
    private class BridgeRejectHooker(
        private val bridgeRejectMethod: Method,
        private val resolveMethod: Method
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val payload = chain.args.getOrNull(2) as? JSONObject ?: return chain.proceed()
            val promiseId = extractPromiseId(payload) ?: return chain.proceed()
            val reason = chain.args.take(2).joinToString(" ") { it?.toString().orEmpty() }
            if (!enabled()) return chain.proceed()
            if (!shouldConvertGameAdRejectToSuccess(promiseId, reason)) return chain.proceed()

            val snapshot = gameAdPromiseSnapshots[promiseId]
            val success = forceGameAdSuccessResult(
                promiseId = promiseId,
                original = null,
                payload = snapshot?.payload ?: payload,
                messageType = snapshot?.messageType ?: gameAdPromiseTypeFromReason(reason)
            )
            return runCatching {
                resolveMethod.invoke(chain.thisObject, promiseId, success)
                L.i(
                    TAG,
                    "converted game ad bridge reject to success promise=$promiseId " +
                        "type=${snapshot?.messageType} reason=$reason"
                )
                null
            }.getOrElse {
                L.w(TAG, "failed to convert game ad bridge reject to success promise=$promiseId", it)
                chain.proceed()
            }
        }
    }

    /** void(Bundle, String) service dispatch — bundle rebuilt as payload. */
    private class ServiceDispatchHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val bundle = chain.args.getOrNull(0) as? Bundle ?: return chain.proceed()
            val messageType = chain.args.getOrNull(1)?.toString()?.lowercase()
                ?.takeIf { it in GAME_AD_MESSAGE_TYPES } ?: return chain.proceed()
            val payload = buildGameAdPayloadFromServiceBundle(bundle, messageType)

            rememberGameAdPayload(chain.thisObject, payload, messageType)
            if (!shouldForceGameAdSuccess(payload, messageType)) return chain.proceed()

            if (resolveGameAdPayload(chain.thisObject, payload, messageType)) {
                dispatchPostResolveGameAdSignals(chain.thisObject, payload, messageType)
                L.i(
                    TAG,
                    "resolved game ad service dispatch type=$messageType " +
                        "in ${method.declaringClass.name}.${method.name}"
                )
                return null
            }
            return chain.proceed()
        }
    }

    /** WebView.addJavascriptInterface — hooks each registered delegate. */
    private object JavascriptInterfaceWatcher : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val bridgeObject = chain.args.getOrNull(0) ?: return result
            runCatching { hookGameAdBridgeObject(bridgeObject, "addJavascriptInterface") }
                .onFailure {
                    L.w(TAG, "failed to hook game bridge object ${bridgeObject.javaClass.name}", it)
                }
            return result
        }
    }

    /** evaluateJavascript / loadUrl — rewrites the delivered script in place. */
    private class ScriptDeliveryHooker(private val name: String) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val script = chain.args.getOrNull(0) as? String ?: return chain.proceed()
            val rewritten = rewriteGameAdDeliveryIfNeeded(script) ?: return chain.proceed()
            val newArgs = chain.args.toTypedArray()
            newArgs[0] = rewritten
            return chain.proceed(newArgs)
        }
    }

    /** postWebMessage — WebMessage data rewritten, message replaced. */
    private class PostWebMessageHooker(
        private val getData: Method,
        private val constructor: java.lang.reflect.Constructor<*>
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val message = chain.args.getOrNull(0) ?: return chain.proceed()
            val data = runCatching { getData.invoke(message) as? String }.getOrNull()
                ?: return chain.proceed()
            val rewritten = rewriteGameAdDeliveryIfNeeded(data) ?: return chain.proceed()
            return runCatching {
                val newArgs = chain.args.toTypedArray()
                newArgs[0] = constructor.newInstance(rewritten)
                L.i(TAG, "rewrote game ad web message promise delivery")
                chain.proceed(newArgs)
            }.getOrElse {
                L.w(TAG, "game ad web message rewrite failed", it)
                chain.proceed()
            }
        }
    }

    /**
     * Activity launch blocker: completes pending requests, then swallows the
     * launch (false for boolean-returning overloads, else null).
     */
    private class LaunchBlockHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val intent = chain.args.firstOrNull { it is Intent } as? Intent ?: return chain.proceed()
            val blockedClassName = resolveBlockedGameAdActivity(intent) ?: return chain.proceed()
            if (!shouldBlockGameAdActivityLaunch(blockedClassName)) return chain.proceed()

            completeRecentGameAdRequests("launch blocker $blockedClassName")
            L.i(
                TAG,
                "blocked game ad activity launch to $blockedClassName " +
                    "via ${method.declaringClass.name}.${method.name}"
            )
            return if (method.returnType == Boolean::class.javaPrimitiveType) {
                java.lang.Boolean.FALSE
            } else {
                null
            }
        }
    }

    /** Audience Network rewarded show() — completes the reward, skips the ad. */
    private class RewardShowHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val adObject = chain.thisObject ?: return chain.proceed()
            if (!enabled()) return chain.proceed()
            if (!completeAudienceNetworkRewardObject(
                    adObject,
                    "show ${method.declaringClass.name}.${method.name}"
                )
            ) {
                return chain.proceed()
            }
            L.i(TAG, "skipped Audience Network rewarded show via ${method.declaringClass.name}.${method.name}")
            return when (method.returnType) {
                Boolean::class.javaPrimitiveType, Boolean::class.java -> java.lang.Boolean.TRUE
                else -> null
            }
        }
    }

    /**
     * Listener registration on a rewarded ad class — the listener is
     * remembered before and after the call (builders return new instances,
     * so the result is checked too).
     */
    private class RewardListenerHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args, method)
            val result = chain.proceed()
            rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args, method)
            rememberAudienceNetworkRewardListeners(result, chain.args, method)
            return result
        }
    }

    /** Rewarded ad load() — remembers any listeners riding the load config. */
    private class RewardLoadHooker(private val method: Method) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            rememberAudienceNetworkRewardListeners(chain.thisObject, chain.args, method)
            return chain.proceed()
        }
    }

    /** ClassLoader.loadClass — hooks relevant lazily loaded SDK classes. */
    private object LoadClassWatcher : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val clazz = result as? Class<*> ?: return result
            if (isAudienceNetworkRewardRelevantClass(clazz.name)) {
                runCatching { moduleHooker?.let { tryHookAudienceNetworkRewardClass(it, clazz) } }
                    .onFailure { L.w(TAG, "dynamic Audience Network class hook failed: ${clazz.name}", it) }
            }
            return result
        }
    }

    /**
     * ViewGroup.addView — hides native ad views as they mount and injects
     * the banner sweep into freshly added WebViews.
     */
    private object AddViewWatcher : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val child = chain.args.firstOrNull { it is View } as? View ?: return@runCatching
                if (isPotentialNativeGameAdView(child)) {
                    hideNativeGameAdView(child, "native ad view add ${child.javaClass.name}")
                    scheduleGameAdSurfaceSweep(child, "native ad view add ${child.javaClass.name}")
                } else if (child is WebView) {
                    injectGameAdHidingScript(child)
                }
            }.onFailure { L.w(TAG, "addView game-ad handling failed", it) }
            return result
        }
    }

    /** WebView load/attach — (re-)injects the banner sweep script. */
    private object WebViewLifecycleWatcher : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val webView = chain.thisObject as? WebView ?: return@runCatching
                injectGameAdHidingScript(webView)
                scheduleGameAdSurfaceSweep(webView, "webview ${chain.executable.name}")
            }.onFailure { L.w(TAG, "webview game-ad script injection failed", it) }
            return result
        }
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    /** Dedup key for hooked methods (full signature). */
    private fun methodHookKey(method: Method): String {
        return "${method.declaringClass.name}#${method.name}(" +
            method.parameterTypes.joinToString(",") { it.name } +
            "):${method.returnType.name}"
    }
}
