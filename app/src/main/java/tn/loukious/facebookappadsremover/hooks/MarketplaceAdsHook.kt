package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import tn.loukious.facebookappadsremover.BuildConfig
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.MethodCache
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Marketplace ads removal — port of our own pre-rewrite marketplace guard
 * (Patches.kt, commit "Fix marketplace ads and reels for certain regions"),
 * re-based from the legacy XposedBridge API onto libxposed.
 *
 * Three mechanisms, all preserved from the old implementation:
 *
 *  1. RENDER BLOCK — marketplace sponsored units (seller row + "Sponsored"
 *     label + video card) are rendered by Litho components carrying stable
 *     "Marketplace…Ads…" strings. Nulling their render/layout entry points
 *     makes Litho skip the whole unit. Render time is the earliest reliable
 *     native interception point because the fetching Relay query's name only
 *     exists in the JS bundle.
 *
 *  2. QUERY BLOCK — the marketplace home feed's sponsored tiles are fetched
 *     by dedicated Relay queries ("…MarketplaceHomeFeedAds…",
 *     "…MarketplaceHomeFeedBoostedListingAds…", "…MarketplaceHomeFeedThemedAds…")
 *     issued through the React Native Networking module. Requests carrying
 *     those names are dropped entirely (the RN QueryRenderer keeps rendering
 *     its loading fallback = nothing). The organic feed queries
 *     (MarketplaceHomeFeedQueryRendererQuery / …PaginationQuery) are instead
 *     REWRITTEN: their persisted-query variables expose server-honoured
 *     ad-skip flags, so flipping those to true makes the server omit the ads
 *     instead of the module stripping them from a chunked incremental
 *     response.
 *
 *  3. NET-GUARD CACHE — the marketplace feed query fires within ~3s of
 *     launch, before a full DexKit scan finishes. The resolved Networking
 *     module class name (obfuscated per build, discovered at runtime, never
 *     hardcoded) is persisted keyed by host version + module version and
 *     re-hooked on the next launch, winning the cold-start race.
 *
 * API note: libxposed's [XposedInterface.Chain.getArgs] returns an IMMUTABLE
 * list — the request-body rewrite goes through `chain.proceed(newArgs)`,
 * unlike the old `param.args[4] = …` mutation.
 */
object MarketplaceAdsHook {

    private const val TAG = "FBAR.Marketplace"

    /**
     * Cache for the discovery results (the old "fbar_marketplace_net_cache"
     * properties file's successor — now covering the renderables and the
     * response emitters too, which the old code re-scanned every launch).
     */
    private const val CACHE_PREFS = "fbar_marketplace_cache"
    private const val KEY_HOST_VERSION = "version"
    private const val KEY_MODULE_VERSION = "moduleVersion"
    private const val KEY_CLASSES = "classes"

    /** MethodCache class-map key when persisting through the shared cache. */
    const val CACHE_KEY = "marketplace.classes"

    /** Role prefixes in the cached/returned class list. */
    private const val ROLE_NET = "net:"
    private const val ROLE_RENDER = "render:"
    private const val ROLE_EMIT = "emit:"

    /** Litho renderable anchors (old code: installMarketplaceAdRenderBlock). */
    private val RENDER_ANCHORS = listOf(
        "MarketplaceVideoAdQuery",
        "MarketplaceVideoAdsComponent",
        "MarketplaceVideoAdsGrootLayoutSpec",
    )

    /** The RN Networking module's stable request-context string. */
    private const val NETWORKING_ANCHOR = "FBNetworkingModule_React_Native"

    /** The RN response-emitter class's stable event string. */
    private const val EMITTER_ANCHOR = "didReceiveNetworkData"

    /** Organic feed queries whose variables get the ad-skip rewrite. */
    private val FEED_QUERY_NAMES = setOf(
        "MarketplaceHomeFeedQueryRendererQuery",
        "MarketplaceHomeFeedPaginationQuery",
    )

    /** Ad-only queries dropped outright. */
    private val ADS_QUERY_MARKERS = listOf(
        "MarketplaceHomeFeedAds",
        "MarketplaceHomeFeedBoostedListingAds",
        "MarketplaceHomeFeedThemedAds",
    )

    /** Server-honoured ad-skip flags in the feed query's variables JSON. */
    private val AD_SKIP_FLAGS = listOf(
        "shouldSkipAdRequest",
        "shouldSkipBoostedListingAdRequest",
    )

    /**
     * Dedup registries: the early cached install and the full DexKit pass
     * resolve the same Method objects — hooking twice would run the
     * rewrite/block logic twice per request.
     */
    private val hookedSendRequests: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedRenderables: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedEmitters: MutableSet<Method> = ConcurrentHashMap.newKeySet()

    /**
     * Master ad-block switch (same gate as AdFilterHook — the marketplace
     * guard rides the mod's one swHOME_ADS switch). Re-read per invocation
     * so flipping the toggle applies without a reinstall.
     */
    private fun enabled(): Boolean =
        Settings.getBoolean(Settings.ADS_MARKETPLACE, true)

    // ------------------------------------------------------------------
    // Full-scan entry (DexKit available)
    // ------------------------------------------------------------------

    /**
     * Runs the render block, query block and response filter. Returns the
     * discovered class names, role-prefixed ("net:X", "render:Y", "emit:Z"),
     * for the caller to persist.
     */
    fun install(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        context: Context,
    ): List<String> {
        val found = mutableListOf<String>()
        runCatching { installRenderBlock(module, bridge, classLoader, found) }
            .onFailure { L.w(TAG, "Marketplace render block failed", it) }
        runCatching { installQueryBlock(module, bridge, classLoader, found) }
            .onFailure { L.w(TAG, "Marketplace query block failed", it) }
        runCatching { installResponseFilter(module, bridge, classLoader, found) }
            .onFailure { L.w(TAG, "Marketplace response filter failed", it) }
        L.i(TAG, "full scan: ${found.size} cacheable class(es)")
        return found
    }

    /**
     * Render block — for each anchor, hook every renderable-shaped declared
     * method on every class referencing it (old installMarketplaceAdRenderBlock).
     */
    private fun installRenderBlock(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        found: MutableList<String>,
    ) {
        for (anchor in RENDER_ANCHORS) {
            runCatching {
                // Contains matching (the old code's default) — the anchors
                // are prefixes of longer log/QPL literals in the dex, so an
                // Equals match would miss them.
                val matches = bridge.findClass { matcher { usingStrings(anchor) } }
                for (candidate in matches) {
                    val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull()
                        ?: continue
                    if (hookRenderable(module, clazz)) {
                        found.add(ROLE_RENDER + clazz.name)
                    }
                }
                L.i(TAG, "renderables for $anchor: ${matches.size}")
            }.onFailure { L.w(TAG, "render anchor failed: $anchor", it) }
        }
    }

    /**
     * Hooks the render/layout entry points on [clazz]. The query-fetched
     * card is a plain renderable (render(SectionContext)); the other two are
     * Litho layout components whose method names are obfuscated but whose
     * shape is stable: exactly one non-primitive parameter (the Litho
     * component context) and a non-primitive return (the component tree).
     * Lifecycle hooks (onCreateLayout's 2-param variants, void attach/
     * detach) are excluded by that shape.
     *
     * @return true when at least one new method was hooked on this class.
     */
    private fun hookRenderable(module: XposedInterface, clazz: Class<*>): Boolean {
        val targets = clazz.declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) && !method.isSynthetic && (
                method.name == "render" ||
                    (
                        method.parameterCount == 1 &&
                            !method.parameterTypes[0].isPrimitive &&
                            method.returnType != Void.TYPE &&
                            !method.returnType.isPrimitive
                        )
                )
        }
        var hooked = 0
        for (method in targets) {
            if (!hookedRenderables.add(method)) continue
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(RenderNullHook)
                hooked++
            }.onFailure { L.w(TAG, "render hook failed: ${clazz.name}.${method.name}", it) }
        }
        if (hooked > 0) {
            L.i(TAG, "render block: ${clazz.name} methods=$hooked")
        }
        return hooked > 0
    }

    /**
     * Query block — hook the RN Networking module's sendRequest (the old
     * installMarketplaceAdsQueryBlock). sendRequest keeps its RN-native name
     * because JS invokes it reflectively by name.
     */
    private fun installQueryBlock(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        found: MutableList<String>,
    ) {
        val matches = bridge.findClass { matcher { usingStrings(NETWORKING_ANCHOR) } }
        var installed = 0
        for (candidate in matches) {
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
            val sendRequest = clazz.declaredMethods.firstOrNull { method ->
                method.name == "sendRequest" && method.parameterCount == 9
            } ?: continue
            if (hookSendRequest(module, sendRequest)) {
                installed++
                found.add(ROLE_NET + clazz.name)
            }
        }
        L.i(TAG, "query block installed on $installed Networking module(s)")
    }

    /** @return true when the method was newly hooked. */
    private fun hookSendRequest(module: XposedInterface, sendRequest: Method): Boolean {
        if (!hookedSendRequests.add(sendRequest)) return false
        return runCatching {
            sendRequest.isAccessible = true
            module.hook(sendRequest).intercept(SendRequestHook)
        }.isSuccess
    }

    /**
     * Response filter — hooks the RN networking emitters' incremental-data
     * path (old installMarketplaceFeedResponseFilter). The old code used
     * this purely as a debug probe (capturing sponsored-bearing chunks to
     * disk); the port logs the first few such chunks so a leaking payload is
     * visible in logcat, then stays silent. No response rewriting happens
     * here — the feed ads are removed server-side by the request rewrite.
     */
    private fun installResponseFilter(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
        found: MutableList<String>,
    ) {
        val matches = bridge.findClass { matcher { usingStrings(EMITTER_ANCHOR) } }
        var installed = 0
        for (candidate in matches) {
            val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: continue
            if (hookEmitters(module, clazz)) {
                installed++
                found.add(ROLE_EMIT + clazz.name)
            }
        }
        L.i(TAG, "response filter installed on $installed emitter class(es)")
    }

    /**
     * Hooks the static incremental emitter on [clazz]: void, 6 params,
     * (ctx, String, String, int, long, long) — the text/base64/incremental
     * paths all route through this shape.
     */
    private fun hookEmitters(module: XposedInterface, clazz: Class<*>): Boolean {
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (!Modifier.isStatic(method.modifiers)) continue
            if (method.returnType != Void.TYPE) continue
            val params = method.parameterTypes
            val incrementalEmitter = params.size == 6 &&
                params[1] == String::class.java &&
                params[2] == String::class.java &&
                params[3] == Int::class.javaPrimitiveType &&
                params[4] == Long::class.javaPrimitiveType &&
                params[5] == Long::class.javaPrimitiveType
            if (!incrementalEmitter) continue
            if (!hookedEmitters.add(method)) continue
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept(EmitterProbeHook)
                hooked++
            }.onFailure { L.w(TAG, "emitter hook failed: ${clazz.name}.${method.name}", it) }
        }
        return hooked > 0
    }

    // ------------------------------------------------------------------
    // Cache-hit entry (no DexKit)
    // ------------------------------------------------------------------

    /**
     * Re-hooks from previously discovered class names. Idempotent (the dedup
     * registries swallow the overlap with a concurrent full scan), fails
     * softly while the secondary dexes are not yet attached, so the caller
     * can retry on the same cadence as the stable-class probe.
     *
     * @param cached role-prefixed entries as returned by [install]
     * @return true when at least one method set was installed
     */
    fun installCached(
        module: XposedInterface,
        classLoader: ClassLoader,
        context: Context,
        cached: Set<String>,
    ): Boolean {
        if (cached.isEmpty()) return false
        var installed = 0
        for (entry in cached) {
            runCatching {
                when {
                    entry.startsWith(ROLE_NET) -> {
                        val clazz = Class.forName(entry.removePrefix(ROLE_NET), false, classLoader)
                        val sendRequest = clazz.declaredMethods.firstOrNull { method ->
                            method.name == "sendRequest" && method.parameterCount == 9
                        }
                        if (sendRequest != null && hookSendRequest(module, sendRequest)) installed++
                    }
                    entry.startsWith(ROLE_RENDER) -> {
                        val clazz = Class.forName(entry.removePrefix(ROLE_RENDER), false, classLoader)
                        if (hookRenderable(module, clazz)) installed++
                    }
                    entry.startsWith(ROLE_EMIT) -> {
                        val clazz = Class.forName(entry.removePrefix(ROLE_EMIT), false, classLoader)
                        if (hookEmitters(module, clazz)) installed++
                    }
                }
            }.onFailure { L.w(TAG, "cached install failed for $entry", it) }
        }
        L.i(TAG, "cached install: $installed method set(s) from ${cached.size} class(es)")
        return installed > 0
    }

    // ------------------------------------------------------------------
    // Cache persistence (own prefs file, host+module version keyed)
    // ------------------------------------------------------------------

    /**
     * Persists the discovered class list (old saveMarketplaceNetGuardCache).
     * Invalidate on host update (names are per-build obfuscated) or module
     * update (hook logic may change).
     */
    fun saveCache(context: Context, names: List<String>) {
        if (names.isEmpty()) return
        runCatching {
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HOST_VERSION, hostVersionCode(context))
                .putString(KEY_MODULE_VERSION, moduleVersionKey())
                .putStringSet(KEY_CLASSES, names.toSet())
                .apply()
            L.i(TAG, "cache saved: ${names.size} class(es)")
        }.onFailure { L.w(TAG, "cache save failed", it) }
    }

    /**
     * Reads the cache back, or null when absent/stale (old
     * installMarketplaceNetGuardFromCache's validation half — the caller
     * decides what to do with the names).
     */
    fun loadCache(context: Context): Set<String>? = runCatching {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_HOST_VERSION, -1) != hostVersionCode(context)) return null
        if (prefs.getString(KEY_MODULE_VERSION, "") != moduleVersionKey()) return null
        prefs.getStringSet(KEY_CLASSES, emptySet())?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ------------------------------------------------------------------
    // ModuleMain wiring (shared MethodCache class-map channel)
    // ------------------------------------------------------------------

    /**
     * True when the shared discovery cache already carries this hook's
     * class-map entry — a cache written before the marketplace port must
     * force a rescan (checked from ModuleMain.installFromCache).
     */
    fun cachePresent(context: Context): Boolean =
        runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull() != null

    /**
     * Cache-hit install through the shared class map: reads the stored
     * role-prefixed CSV and delegates to [installCached]. No-op on a miss;
     * the "absent" sentinel means the anchors found nothing on this build.
     */
    fun installFromCache(module: XposedInterface, classLoader: ClassLoader, context: Context) {
        val state = runCatching { MethodCache.loadClass(context, CACHE_KEY) }.getOrNull()
        when {
            state == null -> return
            state == "absent" -> L.i(TAG, "anchor not found on this build — skipping")
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

    private fun hostVersionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(-1)

    /** The old feedGuardCacheModuleKey format. */
    private fun moduleVersionKey(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    // ------------------------------------------------------------------
    // Hookers
    // ------------------------------------------------------------------

    /** Skip the render/layout call — Litho skips the whole sponsored unit. */
    private object RenderNullHook : Hooker {
        private var logged = 0

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            if (logged < 8) {
                logged++
                L.i(TAG, "render block fired: ${chain.executable.name}")
            }
            return null
        }
    }

    /**
     * The RN Networking module's sendRequest: drop ad-only queries, rewrite
     * the organic feed queries' variables to flip the server-honoured
     * ad-skip flags. sendRequest is void, so returning null without calling
     * proceed() drops the request entirely.
     */
    private object SendRequestHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled()) return chain.proceed()
            val args = chain.args
            val body = requestBodyOf(args.getOrNull(4)) ?: return chain.proceed()
            val name = queryNameRegex.find(body)?.groupValues?.get(1)
                ?: formQueryIdRegex.find(body)?.groupValues?.get(1)
                ?: "persisted"
            if (name in FEED_QUERY_NAMES) {
                val rewritten = rewriteFeedRequestVariables(body) ?: return chain.proceed()
                val replacement = readableMapWithString(args.getOrNull(4), rewritten)
                    ?: return chain.proceed()
                // libxposed: args are immutable — proceed with the new array.
                val newArgs = args.toTypedArray()
                newArgs[4] = replacement
                return chain.proceed(newArgs)
            }
            if (ADS_QUERY_MARKERS.none { body.contains(it) }) return chain.proceed()
            L.i(TAG, "dropping marketplace ads query: $name")
            return null
        }
    }

    /** Read-only sponsored-chunk probe (old debug capture, logcat edition). */
    private object EmitterProbeHook : Hooker {
        private var logged = 0

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (logged >= 8) return chain.proceed()
            val body = chain.args.getOrNull(2) as? String ?: return chain.proceed()
            if (!body.contains("ponsored")) return chain.proceed()
            logged++
            L.i(TAG, "sponsored chunk len=${body.length} via ${chain.executable.name}")
            return chain.proceed()
        }
    }

    // ------------------------------------------------------------------
    // Request body helpers (old Patches.kt, verbatim ports)
    // ------------------------------------------------------------------

    /**
     * The RN Networking module receives its POST body as a ReadableMap with
     * a "string" key. ReadableMap is a host interface, so read it
     * reflectively.
     */
    private fun requestBodyOf(data: Any?): String? {
        if (data == null) return null
        val hasKey = data.javaClass.methods.firstOrNull {
            it.name == "hasKey" && it.parameterCount == 1
        } ?: return null
        val getString = data.javaClass.methods.firstOrNull {
            it.name == "getString" && it.parameterCount == 1
        } ?: return null
        return runCatching {
            if (hasKey.invoke(data, "string") != true) {
                return@runCatching null
            }
            getString.invoke(data, "string") as? String
        }.getOrNull()
    }

    /**
     * The marketplace home feed request body is form-encoded. Its
     * "variables" parameter is URL-encoded JSON whose schema (from the
     * persisted query config asset) includes server-honoured ad-skip flags.
     * Flipping them to true makes the server omit sponsored tiles from the
     * response. Returns null when there is nothing to rewrite.
     */
    private fun rewriteFeedRequestVariables(body: String): String? {
        val marker = "variables="
        val markerIndex = body.indexOf(marker)
        if (markerIndex < 0) return null
        val valueStart = markerIndex + marker.length
        val valueEnd = body.indexOf('&', valueStart).let { if (it < 0) body.length else it }
        val encoded = body.substring(valueStart, valueEnd)
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        val variables = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        var changed = false
        for (flag in AD_SKIP_FLAGS) {
            if (variables.optBoolean(flag, false)) continue
            variables.put(flag, true)
            changed = true
        }
        if (!changed) return null
        val rewritten = URLEncoder.encode(variables.toString(), "UTF-8")
        return body.substring(0, valueStart) + rewritten + body.substring(valueEnd)
    }

    /**
     * Builds a replacement ReadableMap body for the RN Networking module.
     * The module and its same-origin delegate read the body only through
     * hasKey/getString/getType, so any ReadableMap implementation works; the
     * RN bridge's WritableNativeMap is public API with a no-arg constructor
     * and putString(String, String).
     */
    private fun readableMapWithString(original: Any?, body: String): Any? {
        if (original == null) return null
        return runCatching {
            // Resolve through the host classloader (the original body map's),
            // not the module's own.
            val mapClass = original.javaClass.classLoader
                .loadClass("com.facebook.react.bridge.WritableNativeMap")
            val instance = mapClass.getDeclaredConstructor().newInstance()
            val putString = mapClass.methods.firstOrNull {
                it.name == "putString" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            } ?: return@runCatching null
            putString.invoke(instance, "string", body)
            instance
        }.getOrNull()
    }

    // GraphQL query name extractors: raw documents carry
    // "query Name…"; persisted Relay requests are form-encoded and carry the
    // readable name in fb_api_req_friendly_name plus a numeric doc_id.
    private val queryNameRegex = Regex("query[\\s]+([A-Za-z0-9_]+)")
    private val formQueryIdRegex = Regex("fb_api_req_friendly_name=([A-Za-z0-9_]+)")
}
