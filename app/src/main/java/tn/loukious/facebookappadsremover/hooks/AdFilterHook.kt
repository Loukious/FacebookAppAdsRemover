package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import android.os.Build
import tn.loukious.facebookappadsremover.BuildConfig
import tn.loukious.facebookappadsremover.core.AdTargets
import tn.loukious.facebookappadsremover.core.AdSurface
import tn.loukious.facebookappadsremover.core.AdSurfacePolicy
import tn.loukious.facebookappadsremover.core.BannerClassCacheEntry
import tn.loukious.facebookappadsremover.core.HookAction
import tn.loukious.facebookappadsremover.core.HookTarget
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * M2 ad/filter hook installation — ports the three original-mod native
 * installers (AdjzonOq0OhXgiiuU6H feed, Rn0LbcxLisWuSI9YThk video-ads,
 * kEMr0xnlytyyfRWPypcd banner dex-scan) onto the libxposed API.
 *
 * Callback semantics (recovered from libnc.so):
 *  - BLOCK_NULL:     toggle on → skip original, return null/false-safe default
 *                    (mod: bsrncODWYJ5TNENFG95a preset-result)
 *  - BLOCK_FALSE:    toggle on → skip original, return false
 *                    (mod: banner-ads XC_MethodReplacement)
 *  - SPONSORED_NULL: toggle on → run the ad check on receiver + args; null the
 *                    call only for sponsored stories
 *                    (mod: FNjBxTKppvYVTRFkExAd + X.2Wa.A00 classifier).
 *
 * The mod's ad check ultimately asked GraphQLStory for its sponsored-data
 * subtree (A0l() != null in its build). Getter names drift per release, so we
 * use TreeJNI.hasFieldValue(FIELD_NAME_HASH_CODE_sponsored_data) — the hash
 * (-132939024) is identical in the mod's build and stock 576.0.0.42.73.
 */
object AdFilterHook {

    private const val TAG = "FBAR.Ads"

    /** Safety cap on the banner dex-scan (the mod's dexplore query was limited too). */
    private const val MAX_BANNER_CLASSES = 100

    /** Banner-scan class cache — internal state, kept in the FB process. */
    private const val BANNER_CACHE_NAME = "fbar_prefs_banner"
    private const val KEY_BANNER_CLASSES = "cached_banner_classes"
    private const val KEY_BANNER_SCHEMA = "cached_banner_schema"
    private const val BANNER_SCHEMA = 2 // Class + surface provenance (not bare names).

    /**
     * Version stamps for the banner class cache.
     *
     * The cached names are obfuscated members of the host build, so they are
     * only meaningful for the exact Facebook build that produced them — that is
     * [KEY_BANNER_HOST_VERSION]. The scan's own semantics change with the
     * module, which is precisely how a poisoned class set (the SoLoader entry —
     * see LOADER_INFRA_PREFIXES) reached a shipped cache and kept being reused:
     * the cache was written once and then only ever read, so nothing could
     * revise it — that is [KEY_BANNER_MODULE_VERSION].
     *
     * Version/schema changes trigger a new scan. Never reuse old bare class
     * names without their feed/story/Reels origin: that bypasses the user's
     * independent ad toggles even if the class happens to load successfully.
     */
    private const val KEY_BANNER_HOST_VERSION = "cached_banner_host_version"
    private const val KEY_BANNER_MODULE_VERSION = "cached_banner_module_version"

    /**
     * Classes and members that must never be swept, whatever an anchor
     * resolves to.
     *
     * `com.facebook.soloader.SoLoader` holds the merged-native-library dispatch
     * table, which lists every native library name in the app — including
     * ad-related ones such as `libmailboxinthreadadcontextbannerjni.so`. So
     * SoLoader *references* the `mailboxinthreadadcontextbannerjni` banner
     * anchor, and its `loadLibrary` / `loadLibraryUnsafe` overloads return
     * boolean, so the banner sweep below swept them. The false-return hook never
     * calls the original, so no merged library ever had its `JNI_OnLoad`
     * invoked: every `initHybrid` threw UnsatisfiedLinkError and Facebook could
     * not start at all (reported 2026-09-16; reproduced on 578.0.0.40.75 by
     * clearing app data, which rebuilt the banner cache).
     *
     * The class list is cached and only rewritten when empty, so a poisoned
     * cache would keep killing the app on every launch. This guard is what
     * makes such a cache harmless, and what stops a future anchor from
     * re-introducing the same failure.
     */
    private val LOADER_INFRA_PREFIXES = listOf("com.facebook.soloader.")
    private val LOADER_INFRA_METHODS = setOf("loadLibrary", "loadLibraryUnsafe")

    /** Name-only form — scan results are filtered before the classes load. */
    private fun isLoaderInfra(name: String): Boolean =
        LOADER_INFRA_PREFIXES.any { name.startsWith(it) }

    /**
     * Host (Facebook) versionCode — the identity of the build whose obfuscated
     * member names the banner cache holds. Deliberately the same key and the
     * same -1 sentinel as MethodCache, so the banner cache and the discovery
     * cache invalidate on the same Facebook update.
     */
    private fun hostVersionCode(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(-1)

    /** The Facebook app classloader — module code can't see secondary-dex classes otherwise. */
    @Volatile
    internal var appClassLoader: ClassLoader? = null

    /** Read per-surface prefs for every invocation (live settings changes). */
    private fun enabled(surfaces: Set<AdSurface>): Boolean = Settings.blockAdsOn(surfaces)

    fun init(context: Context) {
        appClassLoader = context.classLoader
        L.i(TAG, "Ad filters: newsFeed=${Settings.blockAdsOn(setOf(AdSurface.NEWS_FEED))} " +
            "stories=${Settings.blockAdsOn(setOf(AdSurface.STORIES))} " +
            "reels=${Settings.blockAdsOn(setOf(AdSurface.REELS))}")
    }

    /** Installs every resolved method target from the discovery report. */
    fun install(module: XposedInterface, targets: List<HookTarget>, methods: Map<String, List<Method>>) {
        // An obfuscated method can match multiple semantic anchors. Intercept
        // it only once and require ALL affected surfaces to be enabled so an
        // off toggle is never bypassed by another anchor on the same method.
        val resolved = LinkedHashMap<Method, Pair<HookAction, Set<AdSurface>>>()
        val ambiguous = HashSet<Method>()
        for (target in targets) {
            val action = target.action ?: continue
            val surfaces = AdSurfacePolicy.targetSurfaces(target.key)
            if (surfaces.isNullOrEmpty()) {
                L.w(TAG, "Unclassified ad target: ${target.key}; skipping")
                continue
            }
            for (m in methods[target.key].orEmpty()) {
                val existing = resolved[m]
                if (existing == null) resolved[m] = action to surfaces
                else if (existing.first != action) {
                    ambiguous.add(m)
                    L.w(TAG, "Conflicting ad actions for ${m.declaringClass.name}.${m.name}; refusing")
                } else resolved[m] = action to (existing.second + surfaces)
            }
        }
        var installed = 0
        for ((m, binding) in resolved) {
                if (m in ambiguous) continue
                val (action, surfaces) = binding
                // R8 centralizes string constants into dispatch tables —
                // `static String xxx(int)` with a giant switch. Anchor strings
                // then resolve to the TABLE, not the real method, and nulling
                // its return corrupts every caller (execSQL(null), non-null
                // contract NPEs, null event names in logging). Never hook them.
                if (isStringDispatchTable(m)) {
                    L.w(TAG, "skipping string-table method: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
                    continue
                }
                if (isLoaderInfra(m.declaringClass.name) || m.name in LOADER_INFRA_METHODS) {
                    L.w(TAG, "skipping loader infra: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
                    continue
                }
                try {
                    module.hook(m).intercept(hookerFor(action, surfaces,
                        "${m.declaringClass.name}.${m.name}/${m.parameterCount}"))
                    installed++
                    L.i(TAG, "hooked ads=$surfaces: ${m.declaringClass.name}.${m.name}/${m.parameterCount}")
                } catch (t: Throwable) {
                    L.w(TAG, "hook failed ads=$surfaces: ${m.declaringClass.name}.${m.name}", t)
                }
        }
        L.i(TAG, "Ad filter: $installed method hook(s) installed")
    }

    private fun hookerFor(action: HookAction, surfaces: Set<AdSurface>, label: String): Hooker = when (action) {
        HookAction.BLOCK_NULL -> LoggingBlockHook(label, surfaces)
        HookAction.BLOCK_FALSE -> SurfaceFalseHook(surfaces)
        HookAction.SPONSORED_NULL -> SponsoredNullHook(surfaces)
        HookAction.RECEIVER_SPONSORED_NULL -> ReceiverSponsoredNullHook(surfaces)
    }

    /**
     * Logs the first blocked invocation of each
     * hooked method so the log right before any crash identifies the hook
     * that fired (hot paths — one log per method, then silent).
     */
    private class LoggingBlockHook(
        private val label: String,
        private val surfaces: Set<AdSurface>,
    ) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled(surfaces)) return chain.proceed()
            if (!logged) {
                logged = true
                L.i(TAG, "block-null fired ($surfaces): $label")
            }
            return nullResult(chain.executable as? Method)
        }
        private var logged = false
    }

    /**
     * Banner-ads dex-scan — port of the mod's kEMr0xnlytyyfRWPypcd installer:
     * find every class referencing any of the ~40 banner QPL/log anchors (the
     * dexplore reference filter was an OR over the anchor set, so we run one
     * DexKit class query per anchor and union the results), then hook each
     * declared method that returns boolean and takes at least one param,
     * replacing it with false. Found class names and semantic surface sets
     * are cached in prefs
     * ('cached_banner_classes' in the mod) so later launches skip the scan.
     *
     * Unlike the mod's cache, this one is stamped with the host and module
     * versions it was built from and is rebuilt when either moves — see
     * KEY_BANNER_HOST_VERSION. Without that, a class set discovered on an older
     * Facebook build is reused verbatim forever: Facebook's obfuscated names
     * drift every release, so the stale names stop resolving and the banner
     * filter silently degrades to doing nothing, with no way to notice or
     * recover short of clearing app data.
     */
    fun installBannerScan(module: XposedInterface, bridge: DexKitBridge?, classLoader: ClassLoader, context: Context) {
        val cache = context.getSharedPreferences(BANNER_CACHE_NAME, Context.MODE_PRIVATE)
        val cached: Set<String> = cache.getStringSet(KEY_BANNER_CLASSES, emptySet()).orEmpty()
        val hostVersion = hostVersionCode(context)
        val moduleVersion = BuildConfig.VERSION_CODE
        val fresh = hostVersion > 0 && cached.isNotEmpty() &&
            cache.getInt(KEY_BANNER_HOST_VERSION, -1) == hostVersion &&
            cache.getInt(KEY_BANNER_MODULE_VERSION, -1) == moduleVersion &&
            cache.getInt(KEY_BANNER_SCHEMA, -1) == BANNER_SCHEMA

        val classSurfaces: Map<String, Set<AdSurface>> = if (fresh) {
            L.i(TAG, "Banner scan: using ${cached.size} cached class(es) (fb=$hostVersion module=$moduleVersion)")
            val parsed = cached.mapNotNull { spec ->
                BannerClassCacheEntry.parse(spec)
                    ?: run { L.w(TAG, "Skipping unclassified banner cache entry"); null }
            }
            parsed.groupBy { it.className }
                .mapValues { (_, records) -> records.flatMap { it.surfaces }.toSet() }
        } else if (bridge == null) {
            // No bridge is available on MethodCache fast path. Old unscoped
            // banner records cannot safely be assigned to a new surface.
            L.w(TAG, "Banner cache missing/stale with no DexKit; skipping until fresh discovery")
            return
        } else {
            val found = LinkedHashMap<String, MutableSet<AdSurface>>()
            for (anchor in AdTargets.bannerAnchors) {
                val origin = AdSurfacePolicy.bannerSurfaces(anchor)
                if (origin.isEmpty()) continue // Unrelated Messenger/plan banners.
                // Exact string-pool equality — the mod's dexplore reference
                // filter matched whole constants (its anchor list carries
                // 'banner_ad' AND 'banner_ads' separately). DexKit's default
                // is Contains, which over-matches ('bannerPo' also hits
                // 'bannerPosition'…) and pulled in event-logging plumbing
                // that crashed the app when its boolean checks were nulled.
                val hits = try {
                    bridge.findClass { matcher { addUsingString(anchor, StringMatchType.Equals) } }
                } catch (t: Throwable) {
                    L.w(TAG, "banner anchor query failed: $anchor", t)
                    null
                } ?: continue
                for (c in hits) {
                    if (isLoaderInfra(c.name)) continue
                    if (c.name !in found && found.size >= MAX_BANNER_CLASSES) continue
                    found.getOrPut(c.name) { linkedSetOf() }.addAll(origin)
                }
            }
            L.i(TAG, "Banner scan: found ${found.size} class(es)")
            if (found.isNotEmpty()) {
                cache.edit()
                    .putStringSet(KEY_BANNER_CLASSES, found.map { (name, origin) ->
                        BannerClassCacheEntry(name, origin).encode()
                    }.toSet())
                    .putInt(KEY_BANNER_HOST_VERSION, hostVersion)
                    .putInt(KEY_BANNER_MODULE_VERSION, moduleVersion)
                    .putInt(KEY_BANNER_SCHEMA, BANNER_SCHEMA)
                    .apply()
            }
            found
        }

        var classesHooked = 0
        var methodsHooked = 0
        for ((name, surfaces) in classSurfaces) {
            // See LOADER_INFRA_PREFIXES: never sweep the native library loader,
            // even when it comes out of a cache written before this guard.
            if (isLoaderInfra(name)) {
                L.w(TAG, "banner scan: refusing to sweep loader infra $name")
                continue
            }
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            try {
                var hookedInClass = 0
                for (m in cls.declaredMethods) {
                    if (m.returnType != java.lang.Boolean.TYPE) continue
                    if (m.parameterCount < 1) continue
                    if (Modifier.isAbstract(m.modifiers)) continue
                    // Never intercept equals(): the mod's blind
                    // boolean-method sweep hooked overridden equals() in
                    // Quicksilver data classes, breaking HashMap lookups
                    // app-wide (manifested as NPEs deep in event logging).
                    if (m.name == "equals" && m.parameterCount == 1) continue
                    if (m.isSynthetic || m.isBridge) continue
                    // Belt and braces: whatever class an anchor lands on, the
                    // native loader's entry points are never replaced.
                    if (m.name in LOADER_INFRA_METHODS) continue
                    try {
                        module.hook(m).intercept(SurfaceFalseHook(surfaces))
                        methodsHooked++; hookedInClass++
                        L.i(TAG, "banner hook surfaces=$surfaces: $name.${m.name}/${m.parameterCount}")
                    } catch (t: Throwable) {
                        L.w(TAG, "banner hook failed: $name.${m.name}", t)
                    }
                }
                if (hookedInClass > 0) classesHooked++
            } catch (t: Throwable) {
                L.w(TAG, "banner class scan failed: $name", t)
            }
        }
        L.i(TAG, "Banner filter: $methodsHooked boolean method(s) in $classesHooked class(es)")
    }

    // ------------------------------------------------------------------
    // Hookers
    // ------------------------------------------------------------------

    /**
     * R8 string-constant dispatch table: `static String m(int)` — hundreds of
     * interned literals behind a switch. Anchored discovery resolves to these
     * whenever the anchor literal was centralized, so they must be excluded.
     */
    internal fun isStringDispatchTable(m: Method): Boolean {
        return Modifier.isStatic(m.modifiers) &&
            m.returnType == String::class.java &&
            m.parameterCount == 1 &&
            m.parameterTypes[0] == Int::class.javaPrimitiveType
    }

    /** Skip original, return false — gated per banner/ad-method surface. */
    private class SurfaceFalseHook(private val surfaces: Set<AdSurface>) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled(surfaces)) return chain.proceed()
            return java.lang.Boolean.FALSE
        }
    }

    /** Ad-check receiver + args, null only sponsored — mod FNjBxTKppvYVTRFkExAd. */
    private class SponsoredNullHook(private val surfaces: Set<AdSurface>) : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled(surfaces)) return chain.proceed()
            return if (SponsoredCheck.isSponsored(chain.thisObject) ||
                chain.args.any { SponsoredCheck.isSponsored(it) }
            ) {
                L.i(TAG, "blocked sponsored render: ${chain.executable.name}")
                nullResult(chain.executable as? Method)
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * Null only when the receiver stringifies with "SPONSORED" — mod
     * UVFD3BoJI5sq4SwfZZu0 on X.4qr.A00 (the FBShortsMidCardFeedUnit TreeJNI
     * type-node getter): String.valueOf(thisObject).contains("SPONSORED")
     * decides, so the getter nulls only sponsored mid-card units.
     */
    private class ReceiverSponsoredNullHook(private val surfaces: Set<AdSurface>) : Hooker {
        private var logged = false

        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!enabled(surfaces)) return chain.proceed()
            val sponsored = runCatching {
                chain.thisObject?.toString()?.contains("SPONSORED") == true
            }.getOrDefault(false)
            if (!sponsored) return chain.proceed()
            if (!logged) {
                logged = true
                L.i(TAG, "receiver-sponsored null fired: ${chain.executable.name}")
            }
            return nullResult(chain.executable as? Method)
        }
    }

    /** Returning null from a primitive-returning method crashes; substitute defaults. */
    private fun nullResult(m: Method?): Any? {
        return when (m?.returnType) {
            java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            java.lang.Float.TYPE -> 0f
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Ad check (mod: X.2Wa.A00 -> C2Rc.A0H -> GraphQLStory.A0l() != null)
    // ------------------------------------------------------------------

    private object SponsoredCheck {
        private const val PARTIAL_STORY = "com.facebook.graphql.model.GraphQLPartialStory"

        @Volatile private var hashField: Field? = null
        @Volatile private var resolvedHash = false
        // Optional.empty = "class checked, no hasFieldValue method" — a plain
        // null value would NPE on ConcurrentHashMap.put.
        private val hasFieldValueCache = ConcurrentHashMap<Class<*>, java.util.Optional<Method>>()

        /**
         * True if the object is a sponsored story or wraps one. Mirrors the
         * mod's classifier: any GraphQLStory in the wrapper chain with a
         * non-null sponsored_data subtree.
         */
        fun isSponsored(obj: Any?): Boolean {
            if (obj == null) return false
            if (isSponsoredTree(obj)) return true
            if (isSkippable(obj)) return false
            return try {
                // Wrapper types (FeedProps and friends): test every object
                // field one level deep — the mod walked the same parent chain.
                var cls: Class<*> = obj.javaClass
                while (cls != null && cls != Any::class.java) {
                    for (f in cls.declaredFields) {
                        if (f.isSynthetic || Modifier.isStatic(f.modifiers)) continue
                        val t = f.type
                        if (t.isPrimitive || t == String::class.java || t.isArray) continue
                        f.isAccessible = true
                        val v = runCatching { f.get(obj) }.getOrNull() ?: continue
                        if (v !== obj && !isSkippable(v) && isSponsoredTree(v)) return true
                    }
                    cls = cls.superclass
                }
                false
            } catch (t: Throwable) {
                L.w(TAG, "isSponsored walk failed on ${obj.javaClass.name}", t)
                false
            }
        }

        /** Direct test: TreeJNI-backed object whose sponsored_data field is set. */
        private fun isSponsoredTree(obj: Any): Boolean {
            val hash = sponsoredHash() ?: return false
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                val c = cls
                val cached = hasFieldValueCache[c]
                val m = if (cached != null) cached.orElse(null) else run {
                    val found = runCatching {
                        val mm = c.getDeclaredMethod("hasFieldValue", Int::class.javaPrimitiveType)
                        mm.isAccessible = true; mm
                    }.getOrNull()
                    hasFieldValueCache[c] = java.util.Optional.ofNullable(found)
                    found
                }
                if (m != null) {
                    return runCatching { m.invoke(obj, hash) as Boolean }.getOrDefault(false)
                }
                cls = c.superclass
            }
            return false
        }

        /** Cheap value types we never want to reflect into. */
        private fun isSkippable(obj: Any): Boolean {
            val n = obj.javaClass.name
            return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("kotlin.")
        }

        /** Resolves FIELD_NAME_HASH_CODE_sponsored_data from GraphQLPartialStory (stable name+constant). */
        private fun sponsoredHash(): Int? {
            if (resolvedHash) return hashField?.get(null) as? Int
            resolvedHash = true
            return runCatching {
                val c = Class.forName(PARTIAL_STORY, false, appClassLoader)
                val f = c.getDeclaredField("FIELD_NAME_HASH_CODE_sponsored_data")
                f.isAccessible = true
                hashField = f
                f.get(null) as Int
            }.getOrElse {
                L.w(TAG, "sponsored_data hash constant not found — ad check degraded", it)
                null
            }
        }
    }
}
