package tn.loukious.facebookapp

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log as AndroidLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val TAG = "FacebookAppAdsRemover"

private const val HOST_PACKAGE = "com.facebook.katana"
private const val BEFORE_SIZE_EXTRA = "facebook_ads_before_size"
private const val BUILD_MARKER = "feed_story_feedcsr_banner_v21_2026_04_16"
private const val GRAPHQL_FEED_UNIT_EDGE_CLASS = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
private const val GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS = "com.facebook.graphql.model.GraphQLFBMultiAdsFeedUnit"
private const val GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS =
    "com.facebook.graphql.model.GraphQLQuickPromotionNativeTemplateFeedUnit"
private const val AUDIENCE_NETWORK_ACTIVITY_CLASS = "com.facebook.ads.AudienceNetworkActivity"
private const val AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"
private const val NEKO_PLAYABLE_ACTIVITY_CLASS = "com.facebook.neko.playables.activity.NekoPlayableAdActivity"
private const val GAME_AD_REJECTION_MESSAGE = "Game ad request blocked"
private const val GAME_AD_REJECTION_CODE = "CLIENT_UNSUPPORTED_OPERATION"
private const val GAME_AD_SUCCESS_INSTANCE_PREFIX = "facebook_app_ads_remover_noop_ad"
private const val HOOK_HIT_LOG_EVERY = 25

private val GAME_AD_MESSAGE_TYPES = setOf(
    "getinterstitialadasync",
    "getrewardedvideoasync",
    "getrewardedinterstitialasync",
    "loadadasync",
    "showadasync",
    "loadbanneradasync"
)

private val GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    AUDIENCE_NETWORK_ACTIVITY_CLASS,
    AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS,
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

private val HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES = setOf(
    NEKO_PLAYABLE_ACTIVITY_CLASS
)

private val gameAdInstanceIds = ConcurrentHashMap<String, String>()
private val hookHitCounters = ConcurrentHashMap<String, AtomicInteger>()

private val GAME_AD_METHOD_TAGS = listOf(
    "Invalid JSON content received by onGetInterstitialAdAsync: ",
    "Invalid JSON content received by onGetRewardedInterstitialAsync: ",
    "Invalid JSON content received by onRewardedVideoAsync: ",
    "Invalid JSON content received by onLoadAdAsync: ",
    "Invalid JSON content received by onShowAdAsync: "
)

private val FEED_AD_CATEGORY_VALUES = setOf(
    "SPONSORED",
    "PROMOTION",
    "AD",
    "ADVERTISEMENT",
    "BANNER"
)

private val FEED_AD_SIGNAL_TOKENS = listOf(
    "sponsored",
    "promotion",
    "multiads",
    "quickpromotion",
    "reels_banner_ad",
    "reelsbannerads",
    "reels_post_loop_deferred_card",
    "deferred_card",
    "adbreakdeferredcta",
    "instreamadidlewithbannerstate",
    "instream_legacy_banner_ad",
    "unified_player_banner_ad",
    "banner_ad_",
    "floatingcta"
)

private object Log {
    fun i(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.i(tag, msg) else 0

    fun w(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.w(tag, msg) else 0

    fun w(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.w(tag, msg, throwable) else 0

    fun e(tag: String, msg: String): Int = if (BuildConfig.DEBUG) AndroidLog.e(tag, msg) else 0

    fun e(tag: String, msg: String, throwable: Throwable): Int =
        if (BuildConfig.DEBUG) AndroidLog.e(tag, msg, throwable) else 0
}

private data class FeedListSanitizerHook(
    val method: Method,
    val listArgIndex: Int
)

private data class StoryAdProviderHooks(
    val providerClass: Class<*>,
    val mergeMethod: Method?,
    val fetchMoreAdsMethod: Method?,
    val deferredUpdateMethod: Method?,
    val insertionTriggerMethod: Method?
)

private data class ResolvedHooks(
    val adKindEnumClass: Class<*>,
    val listBuilderAppendMethod: Method,
    val listBuilderFactoryMethod: Method?,
    val pluginPackBuildMethod: Method?,
    val instreamBannerEligibilityMethod: Method?,
    val indicatorPillAdEligibilityMethod: Method?,
    val feedCsrFilterMethods: List<Method>,
    val lateFeedListHooks: List<FeedListSanitizerHook>,
    val storyPoolAddMethods: List<Method>,
    val sponsoredPoolClass: Class<*>?,
    val sponsoredPoolAddMethod: Method?,
    val sponsoredStoryNextMethod: Method?,
    val storyAdProviders: List<StoryAdProviderHooks>,
    val gameAdRequestMethods: List<Method>,
    val gameAdBridgePostMessageMethod: Method?,
    val playableAdActivityOnCreate: Method?,
    val gameAdUiActivityMethods: List<Method>
)

private class AdStoryInspector(
    private val adKindEnumClass: Class<*>
) {
    private val enumMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val fieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun containsAdStory(
        value: Any?,
        depth: Int = 0,
        seen: IdentityHashMap<Any, Boolean> = IdentityHashMap()
    ): Boolean {
        if (value == null || depth > 4) return false
        if (isAdKind(value)) return true

        val type = value.javaClass
        if (type.isPrimitive || value is String || value is Number || value is Boolean || value is CharSequence) {
            return false
        }
        if (seen.put(value, true) != null) return false

        if (value is Iterable<*>) {
            var checked = 0
            for (item in value) {
                if (containsAdStory(item, depth + 1, seen)) return true
                checked++
                if (checked >= 8) break
            }
        }

        if (type.isArray) {
            val array = value as? Array<*>
            if (array != null) {
                var checked = 0
                for (item in array) {
                    if (containsAdStory(item, depth + 1, seen)) return true
                    checked++
                    if (checked >= 8) break
                }
            }
        }

        for (method in enumMethodsFor(type)) {
            val marker = runCatching { method.invoke(value) }.getOrNull()
            if (isAdKind(marker)) return true
        }

        for (field in fieldsFor(type)) {
            val fieldValue = runCatching { field.get(value) }.getOrNull()
            if (containsAdStory(fieldValue, depth + 1, seen)) return true
        }

        return false
    }

    private fun isAdKind(value: Any?): Boolean {
        return value != null && value.javaClass == adKindEnumClass && value.toString() == "AD"
    }

    private fun enumMethodsFor(type: Class<*>): List<Method> {
        return enumMethodCache.getOrPut(type) {
            val methods = LinkedHashMap<String, Method>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                current.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers) &&
                        method.parameterCount == 0 &&
                        method.returnType == adKindEnumClass
                    ) {
                        method.isAccessible = true
                        methods.putIfAbsent("${current.name}#${method.name}", method)
                    }
                }
                current = current.superclass
            }
            methods.values.toList()
        }
    }

    private fun fieldsFor(type: Class<*>): List<Field> {
        return fieldCache.getOrPut(type) {
            val fields = ArrayList<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java && fields.size < 24) {
                current.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers) && fields.size < 24) {
                        field.isAccessible = true
                        fields.add(field)
                    }
                }
                current = current.superclass
            }
            fields
        }
    }
}

private class FeedItemInspector(
    itemContractTypes: Collection<Class<*>>
) {
    private val itemModelAccessor = resolveItemModelAccessor(itemContractTypes)
    private val itemEdgeAccessor = resolveItemEdgeAccessor(itemContractTypes)
    private val itemNetworkAccessor = resolveItemNetworkAccessor(itemContractTypes)
    private val categoryMethodCache = ConcurrentHashMap<Class<*>, Method?>()
    private val edgeAccessorCache = ConcurrentHashMap<Class<*>, Method?>()
    private val feedUnitAccessorCache = ConcurrentHashMap<Class<*>, Method?>()
    private val typeNameMethodCache = ConcurrentHashMap<Class<*>, Method?>()
    private val stringAccessorCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val stringFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun isSponsoredFeedItem(value: Any?): Boolean {
        if (value == null) return false

        val model = invokeNoThrow(itemModelAccessor, value)
        if (isSponsoredFeedCategory(readCategory(model))) {
            return true
        }

        val edge = edgeFrom(value)
        if (isSponsoredFeedCategory(readCategory(edge))) {
            return true
        }

        val feedUnit = feedUnitFrom(edge)
        val unitClassName = feedUnit?.javaClass?.name
        if (unitClassName == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS || unitClassName == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS) {
            return true
        }

        val typeName = readTypeName(feedUnit)
        if (isLikelyAdTypeName(typeName) || isAdSignalText(unitClassName)) {
            return true
        }

        if (containsKnownAdSignals(value)) return true
        if (containsKnownAdSignals(model)) return true
        if (containsKnownAdSignals(edge)) return true
        if (containsKnownAdSignals(feedUnit)) return true

        return false
    }

    fun describe(item: Any?): String {
        if (item == null) return "null"

        val edge = edgeFrom(item)
        val feedUnit = feedUnitFrom(edge)
        val category = readCategory(invokeNoThrow(itemModelAccessor, item))
            ?: readCategory(edge)
            ?: "unknown"
        val network = invokeNoThrow(itemNetworkAccessor, item)?.toString() ?: "unknown"
        val unitClass = feedUnit?.javaClass?.name ?: "null"
        val typeName = readTypeName(feedUnit) ?: "unknown"

        return "cat=$category isAd=${isSponsoredFeedItem(item)} network=$network wrapper=${item.javaClass.name} unit=$unitClass type=$typeName"
    }

    private fun edgeFrom(value: Any?): Any? {
        if (value == null) return null
        if (value.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) return value

        invokeNoThrow(itemEdgeAccessor, value)?.let { directEdge ->
            if (directEdge.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS) {
                return directEdge
            }
        }

        val fallback = edgeAccessorCache.getOrPut(value.javaClass) {
            resolveChildAccessor(value) { candidateValue ->
                candidateValue != null && candidateValue.javaClass.name == GRAPHQL_FEED_UNIT_EDGE_CLASS
            }
        }
        return invokeNoThrow(fallback, value)
    }

    private fun feedUnitFrom(edge: Any?): Any? {
        if (edge == null) return null

        val accessor = feedUnitAccessorCache.getOrPut(edge.javaClass) {
            resolveChildAccessor(edge) { candidateValue ->
                val className = candidateValue?.javaClass?.name
                className == GRAPHQL_MULTI_ADS_FEED_UNIT_CLASS ||
                    className == GRAPHQL_QUICK_PROMO_FEED_UNIT_CLASS ||
                    readTypeName(candidateValue)?.let { it != "FeedUnitEdge" } == true
            }
        }
        return invokeNoThrow(accessor, edge)
    }

    private fun readCategory(value: Any?): String? {
        if (value == null) return null

        if (value.javaClass.isEnum) {
            return value.toString()
        }

        val accessor = categoryMethodCache.getOrPut(value.javaClass) {
            allInstanceMethods(value.javaClass).firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.returnType.isEnum &&
                    candidate.returnType.enumConstants?.any {
                        val name = it.toString()
                        name == "SPONSORED" || name == "PROMOTION"
                    } == true
            }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value)?.toString()
    }

    private fun readTypeName(value: Any?): String? {
        if (value == null) return null

        val accessor = typeNameMethodCache.getOrPut(value.javaClass) {
            allInstanceMethods(value.javaClass).firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.returnType == String::class.java &&
                    candidate.name == "getTypeName"
            }?.apply { isAccessible = true }
        }
        return invokeNoThrow(accessor, value) as? String
    }

    private fun resolveItemModelAccessor(itemContractTypes: Collection<Class<*>>): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    !candidate.returnType.isPrimitive &&
                    candidate.returnType != Any::class.java &&
                    candidate.returnType != String::class.java &&
                    !candidate.returnType.isEnum
            }?.apply { isAccessible = true }
    }

    private fun resolveItemEdgeAccessor(itemContractTypes: Collection<Class<*>>): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    (candidate.returnType == Any::class.java || candidate.returnType.name == GRAPHQL_FEED_UNIT_EDGE_CLASS)
            }?.apply { isAccessible = true }
    }

    private fun resolveItemNetworkAccessor(itemContractTypes: Collection<Class<*>>): Method? {
        return itemContractTypes
            .asSequence()
            .flatMap { type -> allInstanceMethods(type).asSequence() }
            .firstOrNull { candidate ->
                candidate.parameterCount == 0 &&
                    candidate.returnType == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
    }

    private fun resolveChildAccessor(target: Any, acceptsValue: (Any?) -> Boolean): Method? {
        return allInstanceMethods(target.javaClass)
            .asSequence()
            .filter { candidate ->
                candidate.parameterCount == 0 &&
                    !candidate.returnType.isPrimitive &&
                    candidate.returnType != Void.TYPE &&
                    candidate.returnType != String::class.java &&
                    !candidate.returnType.isEnum &&
                    candidate.declaringClass != Any::class.java
            }
            .sortedByDescending { candidate -> scoreChildAccessor(candidate.returnType) }
            .firstOrNull { candidate ->
                acceptsValue(invokeNoThrow(candidate.apply { isAccessible = true }, target))
            }
    }

    private fun scoreChildAccessor(type: Class<*>): Int {
        return when {
            type.name == GRAPHQL_FEED_UNIT_EDGE_CLASS -> 4
            type.name.startsWith("com.facebook.graphql.model.") -> 3
            type.name.startsWith("com.facebook.") -> 2
            !type.name.startsWith("java.") &&
                !type.name.startsWith("javax.") &&
                !type.name.startsWith("android.") &&
                !type.name.startsWith("kotlin.") -> 1
            else -> 0
        }
    }

    private fun isSponsoredFeedCategory(value: String?): Boolean {
        return value != null && value in FEED_AD_CATEGORY_VALUES
    }

    private fun isLikelyAdTypeName(value: String?): Boolean {
        if (value == null) return false
        if (value.contains("QuickPromotion", ignoreCase = true)) return true
        return isAdSignalText(value)
    }

    private fun containsKnownAdSignals(value: Any?): Boolean {
        if (value == null) return false

        if (value is CharSequence) {
            return isAdSignalText(value.toString())
        }

        val type = value.javaClass
        if (isAdSignalText(type.name)) return true

        if (type.isEnum) {
            return isAdSignalText(value.toString())
        }

        if (type.isPrimitive || value is Number || value is Boolean) {
            return false
        }

        if (isAdSignalText(runCatching { value.toString() }.getOrNull())) return true

        for (method in stringAccessorsFor(type)) {
            val marker = invokeNoThrow(method, value) as? String
            if (isAdSignalText(marker)) return true
        }

        for (field in stringFieldsFor(type)) {
            val marker = runCatching { field.get(value) as? String }.getOrNull()
            if (isAdSignalText(marker)) return true
        }

        return false
    }

    private fun stringAccessorsFor(type: Class<*>): List<Method> {
        return stringAccessorCache.getOrPut(type) {
            allInstanceMethods(type)
                .asSequence()
                .filter { method ->
                    method.parameterCount == 0 &&
                        method.returnType == String::class.java &&
                        method.declaringClass != Any::class.java &&
                        method.name != "toString"
                }
                .take(12)
                .onEach { method -> method.isAccessible = true }
                .toList()
        }
    }

    private fun stringFieldsFor(type: Class<*>): List<Field> {
        return stringFieldCache.getOrPut(type) {
            val fields = ArrayList<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java && fields.size < 12) {
                current.declaredFields.forEach { field ->
                    if (!Modifier.isStatic(field.modifiers) && field.type == String::class.java && fields.size < 12) {
                        field.isAccessible = true
                        fields.add(field)
                    }
                }
                current = current.superclass
            }
            fields
        }
    }

    private fun isAdSignalText(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.lowercase()
        return FEED_AD_SIGNAL_TOKENS.any { token -> normalized.contains(token) }
    }

    private fun allInstanceMethods(type: Class<*>): List<Method> {
        val methods = LinkedHashMap<String, Method>()
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredMethods.forEach { method ->
                if (!Modifier.isStatic(method.modifiers)) {
                    method.isAccessible = true
                    methods.putIfAbsent("${current.name}#${method.name}/${method.parameterCount}", method)
                }
            }
            current.interfaces.forEach { iface ->
                iface.declaredMethods.forEach { method ->
                    if (!Modifier.isStatic(method.modifiers)) {
                        method.isAccessible = true
                        methods.putIfAbsent("${iface.name}#${method.name}/${method.parameterCount}", method)
                    }
                }
            }
            current = current.superclass
        }
        return methods.values.toList()
    }

    private fun invokeNoThrow(method: Method?, target: Any?): Any? {
        if (method == null || target == null) return null
        return runCatching { method.invoke(target) }.getOrNull()
    }
}

private fun Collection<MethodData>.firstMethodInstanceOrNull(classLoader: ClassLoader): Method? {
    return asSequence()
        .mapNotNull { methodData ->
            runCatching { methodData.getMethodInstance(classLoader) }.getOrNull()
        }
        .firstOrNull { method ->
            method.name != "<init>" && method.name != "<clinit>"
        }?.apply { isAccessible = true }
}

private fun findClassesByZeroArgStringTags(
    bridge: DexKitBridge,
    tags: Collection<String>
): List<ClassData> {
    val candidates = LinkedHashMap<String, ClassData>()
    tags.forEach { tag ->
        bridge.findClass {
            matcher {
                methods {
                    matchType = MatchType.Contains
                    add {
                        returnType = "java.lang.String"
                        paramCount = 0
                        usingStrings(tag)
                    }
                }
            }
        }.forEach { candidate ->
            candidates.putIfAbsent(candidate.name, candidate)
        }
    }
    return candidates.values.toList()
}

fun installFacebookAdRemover(classLoader: ClassLoader, bridge: DexKitBridge) {
    try {
        Log.i(TAG, "Starting hook install: $BUILD_MARKER")
        val hooks = resolveHooks(classLoader, bridge)
        val inspector = AdStoryInspector(hooks.adKindEnumClass)
        val feedItemInspector = FeedItemInspector(hooks.storyPoolAddMethods.map { it.parameterTypes[0] })

        hookListBuilderAppend(hooks.listBuilderAppendMethod, inspector)
        hooks.listBuilderFactoryMethod?.let { hookListResultFilter(it, "list factory", inspector) }
        hooks.pluginPackBuildMethod?.let { hookPluginPackFallback(it, inspector) }
        hooks.instreamBannerEligibilityMethod?.let { hookInstreamBannerEligibility(it) }
        hooks.indicatorPillAdEligibilityMethod?.let { hookIndicatorPillAdEligibility(it) }
        hooks.feedCsrFilterMethods.forEach { method ->
            runCatching { hookFeedCsrFilterInput(method, feedItemInspector) }
                .onFailure { Log.e(TAG, "Failed to hook feed CSR filter ${method.declaringClass.name}.${method.name}", it) }
        }
        hooks.lateFeedListHooks.forEach { hook ->
            runCatching { hookLateFeedListSanitizer(hook, feedItemInspector) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Failed to hook late feed list ${hook.method.declaringClass.name}.${hook.method.name}",
                        it
                    )
                }
        }
        hooks.storyPoolAddMethods.forEach { method ->
            runCatching { hookStoryPoolAdd(method, feedItemInspector) }
                .onFailure { Log.e(TAG, "Failed to hook story pool add ${method.declaringClass.name}.${method.name}", it) }
        }
        hooks.sponsoredPoolAddMethod?.let { hookSponsoredPoolAdd(it) }
        hooks.sponsoredStoryNextMethod?.let { hookSponsoredStoryNext(it) }
        hooks.storyAdProviders.forEach { hookStoryAdProvider(it) }
        hooks.sponsoredPoolClass?.let {
            hookSponsoredPoolListMethods(it)
            hookSponsoredPoolResultMethods(it)
        }
        hooks.gameAdRequestMethods.forEach { method ->
            runCatching { hookGameAdRequest(method) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Failed to hook game ad request ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdBridgePostMessageMethod?.let { method ->
            runCatching { hookGameAdBridge(method) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Failed to hook game ad bridge ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.playableAdActivityOnCreate?.let { method ->
            runCatching { hookPlayableAdActivity(method) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Failed to hook playable ad activity ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        hooks.gameAdUiActivityMethods.forEach { method ->
            runCatching { hookPlayableAdActivity(method) }
                .onFailure {
                    Log.e(
                        TAG,
                        "Failed to hook game ad activity ${method.declaringClass.name}.${method.name}",
                        it
                    )
                }
        }
        runCatching { hookGlobalGameAdActivityLifecycleFallback() }
            .onFailure { Log.e(TAG, "Failed to hook global game ad activity lifecycle fallback", it) }
        runCatching { hookGameAdActivityLaunchFallbacks() }
            .onFailure { Log.e(TAG, "Failed to hook game ad launch fallbacks", it) }

        Log.i(
            TAG,
            "Installed hooks: append=${hooks.listBuilderAppendMethod.declaringClass.name}.${hooks.listBuilderAppendMethod.name}" +
                ", factory=${hooks.listBuilderFactoryMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", plugin=${hooks.pluginPackBuildMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", bannerState=${hooks.instreamBannerEligibilityMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", indicatorPill=${hooks.indicatorPillAdEligibilityMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", feedFilters=${hooks.feedCsrFilterMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}" +
                ", lateFeed=${hooks.lateFeedListHooks.joinToString { "${it.method.declaringClass.name}.${it.method.name}[${it.listArgIndex}]" }}" +
                ", poolAdd=${hooks.storyPoolAddMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}" +
                ", feedPoolAdd=${hooks.sponsoredPoolAddMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", feedNext=${hooks.sponsoredStoryNextMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", storyProviders=${hooks.storyAdProviders.joinToString { it.providerClass.name }}" +
                ", gameAds=${hooks.gameAdRequestMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}" +
                ", gameBridge=${hooks.gameAdBridgePostMessageMethod?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", playableAd=${hooks.playableAdActivityOnCreate?.let { "${it.declaringClass.name}.${it.name}" } ?: "none"}" +
                ", gameAdUi=${hooks.gameAdUiActivityMethods.joinToString { "${it.declaringClass.name}.${it.name}" }}"
        )
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to install Facebook ad remover hooks", t)
    }
}

private fun resolveHooks(classLoader: ClassLoader, bridge: DexKitBridge): ResolvedHooks {
    val classGroups = bridge.batchFindClassUsingStrings {
        groups(
            mapOf(
                "listBuilderByString" to listOf("Non ads story fall into ads rendering logic, StoryType=%s, StoryId=%s"),
                "pluginPack" to listOf("FbShortsViewerPluginPack"),
                "adKindEnum" to listOf("AD", "UGC", "PARADE", "MIDCARD"),
                "feedCsrFilters" to listOf("FeedCSRCacheFilter", "FeedCSRCacheFilter2025H1", "FeedCSRCacheFilter2026H1"),
                "sponsoredPool" to listOf("SponsoredPoolContainerAdapter", "Edge type mismatch; not added", "Sponsored Pool"),
                "sponsoredStoryManager" to listOf("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder"),
                "storyAdsDataSource" to listOf("AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds", "StoryViewerAdsPaginatingDataManager.fetchMoreAds"),
                "storyAdsInDisc" to listOf("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion")
            ),
            StringMatchType.Equals
        )
    }

    Log.i(
        TAG,
        "DexKit groups: reels=${classGroups["listBuilderByString"]?.size ?: 0}, " +
            "plugin=${classGroups["pluginPack"]?.size ?: 0}, " +
            "adKind=${classGroups["adKindEnum"]?.size ?: 0}, " +
            "feedCsr=${classGroups["feedCsrFilters"]?.size ?: 0}, " +
            "feedPool=${classGroups["sponsoredPool"]?.size ?: 0}, " +
            "feedMgr=${classGroups["sponsoredStoryManager"]?.size ?: 0}, " +
            "storyAds=${classGroups["storyAdsDataSource"]?.size ?: 0}, " +
            "storyAdsInDisc=${classGroups["storyAdsInDisc"]?.size ?: 0}"
    )

    val adKindEnumClass = resolveAdKindEnumClass(classLoader, classGroups["adKindEnum"].orEmpty(), bridge)
    val listBuilderClass = resolveListBuilderClass(classGroups["listBuilderByString"].orEmpty(), bridge)
    val pluginPackClass = resolvePluginPackClass(classGroups["pluginPack"].orEmpty(), bridge)
    val sponsoredPoolClass = resolveSponsoredPoolClass(classGroups["sponsoredPool"].orEmpty(), bridge)
    val sponsoredStoryManagerClass =
        resolveSponsoredStoryManagerClass(classGroups["sponsoredStoryManager"].orEmpty(), bridge)
    val storyAdsDataSourceClass =
        resolveStoryAdsDataSourceClass(classGroups["storyAdsDataSource"].orEmpty(), bridge)
    val storyAdsInDiscClass =
        resolveStoryAdsInDiscClass(classGroups["storyAdsInDisc"].orEmpty(), bridge)

    val appendMethod = resolveAppendMethod(classLoader, listBuilderClass)
    val factoryMethod = resolveFactoryMethod(classLoader, listBuilderClass)
    val pluginMethod = pluginPackClass?.let { resolvePluginPackMethod(classLoader, it) }
    val instreamBannerEligibilityMethod = resolveInstreamBannerEligibilityMethod(classLoader, bridge)
    val indicatorPillAdEligibilityMethod = resolveIndicatorPillAdEligibilityMethod(classLoader, bridge)
    val feedCsrFilterMethods =
        resolveFeedCsrFilterMethods(classLoader, classGroups["feedCsrFilters"].orEmpty(), bridge)
    val lateFeedListHooks = resolveLateFeedListHooks(classLoader, bridge)
    val storyPoolAddMethods = resolveStoryPoolAddMethods(classLoader, bridge)
    val poolClassInstance = sponsoredPoolClass?.getInstance(classLoader)
    val poolAddMethod = sponsoredPoolClass?.let { resolveSponsoredPoolAddMethod(classLoader, it) }
    val sponsoredStoryNextMethod =
        sponsoredStoryManagerClass?.let { resolveSponsoredStoryNextMethod(classLoader, it) }
    val storyAdProviders = listOfNotNull(
        storyAdsDataSourceClass?.let { resolveStoryAdProviderHooks(classLoader, it, false) },
        storyAdsInDiscClass?.let { resolveStoryAdProviderHooks(classLoader, it, true) }
    ).distinct()
    val gameAdRequestMethods = resolveGameAdRequestMethods(classLoader, bridge)
    val gameAdBridgePostMessageMethod = resolveGameAdBridgePostMessageMethod(gameAdRequestMethods)
    val playableAdActivityOnCreate = resolvePlayableAdActivityOnCreate(classLoader)
    val gameAdUiActivityMethods = resolveGameAdUiActivityMethods(classLoader)

    Log.i(TAG, "Resolved reels list builder=${listBuilderClass.name}")
    Log.i(TAG, "Resolved plugin pack=${pluginPackClass?.name ?: "none"}")
    Log.i(TAG, "Resolved banner state eligibility=${instreamBannerEligibilityMethod?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "Resolved indicator pill eligibility=${indicatorPillAdEligibilityMethod?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "Resolved feed CSR filters=${feedCsrFilterMethods.joinToString { it.declaringClass.name }}")
    Log.i(TAG, "Resolved late feed list hooks=${lateFeedListHooks.joinToString { it.method.declaringClass.name }}")
    Log.i(TAG, "Resolved story pool add hooks=${storyPoolAddMethods.joinToString { it.declaringClass.name }}")
    Log.i(TAG, "Resolved feed sponsored pool=${sponsoredPoolClass?.name ?: "none"}")
    Log.i(TAG, "Resolved feed sponsored manager=${sponsoredStoryManagerClass?.name ?: "none"}")
    Log.i(TAG, "Resolved feed add method=${poolAddMethod?.name ?: "none"}")
    Log.i(TAG, "Resolved feed next method=${sponsoredStoryNextMethod?.name ?: "none"}")
    Log.i(TAG, "Resolved story ads data source=${storyAdsDataSourceClass?.name ?: "none"}")
    Log.i(TAG, "Resolved story ads in-disc source=${storyAdsInDiscClass?.name ?: "none"}")
    Log.i(TAG, "Resolved story ad providers=${storyAdProviders.joinToString { it.providerClass.name }}")
    Log.i(TAG, "Resolved game ad requests=${gameAdRequestMethods.joinToString { it.declaringClass.name }}")
    Log.i(TAG, "Resolved game ad bridge=${gameAdBridgePostMessageMethod?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "Resolved playable ad activity=${playableAdActivityOnCreate?.declaringClass?.name ?: "none"}")
    Log.i(TAG, "Resolved game ad UI activities=${gameAdUiActivityMethods.joinToString { it.declaringClass.name }}")

    return ResolvedHooks(
        adKindEnumClass = adKindEnumClass,
        listBuilderAppendMethod = appendMethod,
        listBuilderFactoryMethod = factoryMethod,
        pluginPackBuildMethod = pluginMethod,
        instreamBannerEligibilityMethod = instreamBannerEligibilityMethod,
        indicatorPillAdEligibilityMethod = indicatorPillAdEligibilityMethod,
        feedCsrFilterMethods = feedCsrFilterMethods,
        lateFeedListHooks = lateFeedListHooks,
        storyPoolAddMethods = storyPoolAddMethods,
        sponsoredPoolClass = poolClassInstance,
        sponsoredPoolAddMethod = poolAddMethod,
        sponsoredStoryNextMethod = sponsoredStoryNextMethod,
        storyAdProviders = storyAdProviders,
        gameAdRequestMethods = gameAdRequestMethods,
        gameAdBridgePostMessageMethod = gameAdBridgePostMessageMethod,
        playableAdActivityOnCreate = playableAdActivityOnCreate,
        gameAdUiActivityMethods = gameAdUiActivityMethods
    )
}

private fun resolveAdKindEnumClass(
    classLoader: ClassLoader,
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): Class<*> {
    val directCandidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("AD", "UGC", "PARADE", "MIDCARD")
            }
        }
    }

    directCandidates.forEach { candidate ->
        val clazz = runCatching { candidate.getInstance(classLoader) }.getOrNull() ?: return@forEach
        val constants = clazz.enumConstants?.map { it.toString() }.orEmpty()
        if (clazz.isEnum && "AD" in constants && "UGC" in constants) {
            return clazz
        }
    }

    error("Unable to resolve the Facebook ad-kind enum")
}

private fun resolveListBuilderClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData {
    val structuralCandidates = bridge.findClass {
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    modifiers = Modifier.STATIC
                    returnType = "void"
                    paramTypes = listOf(null, null, null, null, null, "java.util.List")
                }
                add {
                    modifiers = Modifier.STATIC
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, null, "boolean")
                }
                add {
                    returnType = "java.util.ArrayList"
                    paramTypes = listOf(null, null, null, "java.lang.Iterable")
                }
                add {
                    returnType = "java.util.List"
                    paramTypes = listOf(null, null, null, "boolean")
                }
            }
        }
    }

    return structuralCandidates.singleOrNull()
        ?: batchCandidates.firstOrNull()
        ?: error("Unable to resolve the upstream Facebook reels list-builder class")
}

private fun resolvePluginPackClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    if (batchCandidates.isNotEmpty()) {
        return batchCandidates.first()
    }

    return bridge.findClass {
        findFirst = true
        matcher {
            methods {
                matchType = MatchType.Contains
                add {
                    returnType = "java.lang.String"
                    paramCount = 0
                    usingStrings("FbShortsViewerPluginPack")
                }
                add {
                    returnType = "java.util.List"
                    paramCount = 0
                }
            }
        }
    }.firstOrNull()
}

private fun resolveSponsoredPoolClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val candidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("SponsoredPoolContainerAdapter", "Edge type mismatch; not added")
            }
        }
    }

    return candidates.firstOrNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "boolean"
                paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
            }
        }.isNotEmpty()
    }
}

private fun resolveSponsoredStoryManagerClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val candidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("FeedSponsoredStoryHolder.onPositionReset", "freshFeedStoryHolder")
            }
        }
    }

    return candidates.firstOrNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
                paramCount = 0
            }
        }.isNotEmpty()
    }
}

private fun resolveStoryAdsDataSourceClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val candidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings(
                    "AdPaginatingBucketStaticInsertionDataSource.fetchMoreAds",
                    "StoryViewerAdsPaginatingDataManager.fetchMoreAds"
                )
            }
        }
    }

    return candidates.firstOrNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes = listOf("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty() && candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf(null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty()
    }
}

private fun resolveStoryAdsInDiscClass(
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): ClassData? {
    val candidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates
    } else {
        bridge.findClass {
            matcher {
                usingEqStrings("FbStoryAdInDiscStoreImpl", "ads_insertion", "ads_deletion")
            }
        }
    }

    return candidates.firstOrNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "com.google.common.collect.ImmutableList"
                paramTypes = listOf("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty() && candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf(null, "com.google.common.collect.ImmutableList")
            }
        }.isNotEmpty()
    }
}

private fun resolveStoryAdProviderHooks(
    classLoader: ClassLoader,
    providerClassData: ClassData,
    includeInsertionTrigger: Boolean
): StoryAdProviderHooks {
    val providerClass = providerClassData.getInstance(classLoader)
    val mergeMethod = providerClassData.findMethod {
        findFirst = true
        matcher {
            returnType = "com.google.common.collect.ImmutableList"
            paramTypes = listOf("com.facebook.auth.usersession.FbUserSession", null, "com.google.common.collect.ImmutableList")
        }
    }.firstMethodInstanceOrNull(classLoader)
    val fetchMoreAdsMethod = providerClassData.findMethod {
        findFirst = true
        matcher {
            returnType = "void"
            paramTypes = listOf("com.google.common.collect.ImmutableList", "int")
        }
    }.firstMethodInstanceOrNull(classLoader)
    val deferredUpdateMethod = providerClassData.findMethod {
        findFirst = true
        matcher {
            returnType = "void"
            paramTypes = listOf(null, "com.google.common.collect.ImmutableList")
        }
    }.firstMethodInstanceOrNull(classLoader)
    val insertionTriggerMethod = if (!includeInsertionTrigger) {
        null
    } else {
        providerClassData.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramCount = 0
                usingStrings("ads_insertion")
            }
        }.firstMethodInstanceOrNull(classLoader)
    }

    return StoryAdProviderHooks(
        providerClass = providerClass,
        mergeMethod = mergeMethod,
        fetchMoreAdsMethod = fetchMoreAdsMethod,
        deferredUpdateMethod = deferredUpdateMethod,
        insertionTriggerMethod = insertionTriggerMethod
    )
}

private fun resolveAppendMethod(classLoader: ClassLoader, listBuilderClass: ClassData): Method {
    val method = listBuilderClass.findMethod {
        findFirst = true
        matcher {
            modifiers = Modifier.STATIC
            returnType = "void"
            paramTypes = listOf(null, listBuilderClass.name, null, null, null, "java.util.List")
        }
    }.firstOrNull() ?: error("Unable to resolve the list append method")

    return listOf(method).firstMethodInstanceOrNull(classLoader)
        ?: error("Unable to resolve the list append method")
}

private fun resolveFactoryMethod(classLoader: ClassLoader, listBuilderClass: ClassData): Method? {
    val method = listBuilderClass.findMethod {
        findFirst = true
        matcher {
            modifiers = Modifier.STATIC
            returnType = "java.util.ArrayList"
            paramTypes = listOf(listBuilderClass.name, null, null, null, "boolean")
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

private fun resolvePluginPackMethod(classLoader: ClassLoader, pluginPackClass: ClassData): Method? {
    val method = pluginPackClass.findMethod {
        findFirst = true
        matcher {
            returnType = "java.util.List"
            paramCount = 0
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

private fun resolveFeedCsrFilterMethods(
    classLoader: ClassLoader,
    batchCandidates: Collection<ClassData>,
    bridge: DexKitBridge
): List<Method> {
    val namedCandidates = if (batchCandidates.isNotEmpty()) {
        batchCandidates.toList()
    } else {
        findClassesByZeroArgStringTags(
            bridge,
            listOf("FeedCSRCacheFilter", "FeedCSRCacheFilter2025H1", "FeedCSRCacheFilter2026H1")
        )
    }

    val candidates = LinkedHashMap<String, ClassData>()
    namedCandidates.forEach { candidates.putIfAbsent(it.name, it) }

    return candidates.values.mapNotNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                paramTypes = listOf(
                    "com.facebook.auth.usersession.FbUserSession",
                    "com.google.common.collect.ImmutableList",
                    "int"
                )
            }
        }.firstMethodInstanceOrNull(classLoader)
    }.filter { method ->
        !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface &&
            !Modifier.isAbstract(method.declaringClass.modifiers)
    }.distinctBy { "${it.declaringClass.name}.${it.name}" }
}

private fun resolveLateFeedListHooks(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<FeedListSanitizerHook> {
    val hooks = LinkedHashMap<String, FeedListSanitizerHook>()

    bridge.findClass {
        matcher {
            usingStrings("handleStorageStories", "Empty Storage List")
        }
    }.forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf(null, "com.google.common.collect.ImmutableList", "int")
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            hooks.putIfAbsent(
                "${method.declaringClass.name}.${method.name}:1",
                FeedListSanitizerHook(method, 1)
            )
        }
    }

    bridge.findClass {
        matcher {
            usingStrings("cancelVendingTimerAndAddToPool_")
        }
    }.forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf("com.google.common.collect.ImmutableList", "java.lang.String")
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            hooks.putIfAbsent(
                "${method.declaringClass.name}.${method.name}:0",
                FeedListSanitizerHook(method, 0)
            )
        }
    }

    findClassesByZeroArgStringTags(
        bridge,
        listOf(
            "CSRNoOpStorageLifecycleImpl",
            "FeedCSRStorageLifecycle",
            "FriendlyFeedCSRStorageLifecycle",
            "FbShortsCSRStorageLifecycle"
        )
    ).forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "void"
                paramTypes = listOf(
                    "com.facebook.auth.usersession.FbUserSession",
                    null,
                    "com.google.common.collect.ImmutableList"
                )
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            hooks.putIfAbsent(
                "${method.declaringClass.name}.${method.name}:2",
                FeedListSanitizerHook(method, 2)
            )
        }
    }

    return hooks.values.filter { hook ->
        !Modifier.isAbstract(hook.method.modifiers) &&
            !hook.method.declaringClass.isInterface &&
            !Modifier.isAbstract(hook.method.declaringClass.modifiers)
    }.toList()
}

private fun resolveStoryPoolAddMethods(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<Method> {
    val methods = LinkedHashMap<String, Method>()

    findClassesByZeroArgStringTags(
        bridge,
        listOf("CSRStoryPoolCoordinator", "FeedStoryPoolCoordinator")
    ).forEach { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "boolean"
                paramTypes = listOf(null)
            }
        }.firstMethodInstanceOrNull(classLoader)?.let { method ->
            methods.putIfAbsent("${method.declaringClass.name}.${method.name}", method)
        }
    }

    return methods.values.filter { method ->
        !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface &&
            !Modifier.isAbstract(method.declaringClass.modifiers)
    }.toList()
}

private fun resolveInstreamBannerEligibilityMethod(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): Method? {
    val candidates = findClassesByZeroArgStringTags(
        bridge,
        listOf("InstreamAdIdleWithBannerState")
    )

    return candidates.asSequence().mapNotNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                returnType = "boolean"
                paramCount = 0
            }
        }.firstMethodInstanceOrNull(classLoader)
    }.firstOrNull { method ->
        !Modifier.isStatic(method.modifiers)
    }?.apply { isAccessible = true }
}

private fun resolveIndicatorPillAdEligibilityMethod(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): Method? {
    val classCandidates = bridge.findClass {
        matcher {
            usingStrings(
                "IndicatorPillComponent.render",
                "com.facebook.feedback.comments.plugins.indicatorpill.reelsadsfloatingcta.ReelsAdsFloatingCtaPlugin"
            )
        }
    }

    return classCandidates.asSequence().mapNotNull { candidate ->
        candidate.findMethod {
            findFirst = true
            matcher {
                modifiers = Modifier.STATIC
                returnType = "boolean"
                paramCount = 3
            }
        }.firstMethodInstanceOrNull(classLoader)
    }.firstOrNull()?.apply { isAccessible = true }
}

private fun resolveSponsoredPoolAddMethod(classLoader: ClassLoader, sponsoredPoolClass: ClassData): Method? {
    val method = sponsoredPoolClass.findMethod {
        findFirst = true
        matcher {
            returnType = "boolean"
            paramTypes = listOf("com.facebook.graphql.model.GraphQLFeedUnitEdge")
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

private fun resolveSponsoredStoryNextMethod(
    classLoader: ClassLoader,
    sponsoredStoryManagerClass: ClassData
): Method? {
    val method = sponsoredStoryManagerClass.findMethod {
        findFirst = true
        matcher {
            returnType = "com.facebook.graphql.model.GraphQLFeedUnitEdge"
            paramCount = 0
        }
    }.firstOrNull() ?: return null

    return listOf(method).firstMethodInstanceOrNull(classLoader)
}

private fun resolveGameAdRequestMethods(
    classLoader: ClassLoader,
    bridge: DexKitBridge
): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    GAME_AD_METHOD_TAGS.forEach { tag ->
        bridge.findMethod {
            matcher {
                returnType = "void"
                paramTypes = listOf("org.json.JSONObject")
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
    }
    return methods.values.toList()
}

private fun resolveGameAdBridgePostMessageMethod(gameAdRequestMethods: Collection<Method>): Method? {
    val bridgeClass = gameAdRequestMethods.firstOrNull()?.declaringClass ?: return null
    return bridgeClass.declaredMethods.firstOrNull { method ->
        method.name == "postMessage" &&
            method.parameterCount == 2 &&
            method.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

private fun resolvePlayableAdActivityOnCreate(classLoader: ClassLoader): Method? {
    val activityClass = runCatching { classLoader.loadClass(NEKO_PLAYABLE_ACTIVITY_CLASS) }.getOrNull() ?: return null
    return activityClass.declaredMethods
        .firstOrNull { method ->
            method.name == "onResume" &&
                method.parameterCount == 0
        }?.apply { isAccessible = true }
}

private fun resolveGameAdUiActivityMethods(classLoader: ClassLoader): List<Method> {
    val methods = LinkedHashMap<String, Method>()
    listOf(
        AUDIENCE_NETWORK_ACTIVITY_CLASS,
        AUDIENCE_NETWORK_REMOTE_ACTIVITY_CLASS
    ).forEach { className ->
        val activityClass = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
        (activityClass.declaredMethods + activityClass.methods)
            .firstOrNull { method ->
                (method.name == "onResume" && method.parameterCount == 0) ||
                    (method.name == "onStart" && method.parameterCount == 0) ||
                    (method.name == "onCreate" && method.parameterCount == 1 && method.parameterTypes[0] == Bundle::class.java)
            }?.apply {
                isAccessible = true
                methods.putIfAbsent("${declaringClass.name}.${name}", this)
            }
    }
    return methods.values.toList()
}

private fun hookListBuilderAppend(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val list = param.args.getOrNull(5) as? List<*>
            param.setObjectExtra(BEFORE_SIZE_EXTRA, list?.size ?: -1)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val beforeSize = param.getObjectExtra(BEFORE_SIZE_EXTRA) as? Int ?: return
            val list = param.args.getOrNull(5) as? MutableList<Any?> ?: return
            if (beforeSize < 0 || beforeSize > list.size) return

            var removed = 0
            for (index in list.lastIndex downTo beforeSize) {
                if (inspector.containsAdStory(list[index])) {
                    list.removeAt(index)
                    removed++
                }
            }

            if (removed > 0) {
                Log.i(TAG, "Removed $removed ad item(s) from upstream list append")
            }
        }
    })
}

private fun hookListResultFilter(method: Method, source: String, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) {
                Log.i(TAG, "Removed $removed ad item(s) from $source")
            }
        }
    })
}

private fun hookPluginPackFallback(method: Method, inspector: AdStoryInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (inspector.containsAdStory(param.thisObject)) {
                Log.i(TAG, "Returning an empty plugin pack for an ad-backed story")
                param.result = arrayListOf<Any?>()
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val result = param.result as? MutableList<Any?> ?: return
            val removed = filterAdItems(result, inspector)
            if (removed > 0) {
                Log.i(TAG, "Removed $removed ad plugin item(s)")
            }
        }
    })
}

private fun hookFeedCsrFilterInput(method: Method, feedItemInspector: FeedItemInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val filterName = method.declaringClass.name
            val originalList = param.args.getOrNull(1) as? Iterable<*>
            if (originalList == null) return
            logFeedItems("$filterName IN", originalList, feedItemInspector)
            val keptItems = ArrayList<Any?>()
            var removed = 0

            for (item in originalList) {
                if (feedItemInspector.isSponsoredFeedItem(item)) {
                    removed++
                } else {
                    keptItems.add(item)
                }
            }

            if (removed <= 0) return

            val rebuilt = buildImmutableListLike(param.args.getOrNull(1), keptItems) ?: return
            param.args[1] = rebuilt
            Log.i(TAG, "Removed $removed sponsored feed item(s) before ${method.declaringClass.name}.${method.name}")
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val filterName = method.declaringClass.name
            val resultItems = extractFeedItemsFromResult(param.result)
            if (resultItems != null) {
                logFeedItems("$filterName OUT", resultItems, feedItemInspector)
                val keptItems = ArrayList<Any?>()
                var removed = 0
                for (item in resultItems) {
                    if (feedItemInspector.isSponsoredFeedItem(item)) {
                        removed++
                    } else {
                        keptItems.add(item)
                    }
                }
                if (removed > 0 && replaceFeedItemsInResult(param, keptItems)) {
                    Log.i(TAG, "Removed $removed sponsored feed item(s) from result of ${method.declaringClass.name}.${method.name}")
                }
            }
        }
    })
}

private fun hookLateFeedListSanitizer(hook: FeedListSanitizerHook, feedItemInspector: FeedItemInspector) {
    XposedBridge.hookMethod(hook.method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalList = param.args.getOrNull(hook.listArgIndex) as? Iterable<*> ?: return
            val keptItems = ArrayList<Any?>()
            var removed = 0

            for (item in originalList) {
                if (feedItemInspector.isSponsoredFeedItem(item)) {
                    removed++
                } else {
                    keptItems.add(item)
                }
            }

            if (removed <= 0) return

            val rebuilt = buildImmutableListLike(param.args.getOrNull(hook.listArgIndex), keptItems) ?: return
            param.args[hook.listArgIndex] = rebuilt
            Log.i(
                TAG,
                "Late-stage removed $removed sponsored feed item(s) before ${hook.method.declaringClass.name}.${hook.method.name}"
            )
        }
    })
}

private fun hookStoryPoolAdd(method: Method, feedItemInspector: FeedItemInspector) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val item = param.args.getOrNull(0)
            if (!feedItemInspector.isSponsoredFeedItem(item)) return
            param.result = false
            Log.i(TAG, "Blocked sponsored feed item from story pool in ${method.declaringClass.name}.${method.name}")
        }
    })
}

private fun logHookHitThrottled(hookName: String, method: Method, detail: String? = null) {
    val hits = hookHitCounters.computeIfAbsent(hookName) { AtomicInteger(0) }.incrementAndGet()
    if (hits <= 3 || hits % HOOK_HIT_LOG_EVERY == 0) {
        val extra = detail?.let { " $it" } ?: ""
        Log.i(TAG, "Hook hit $hookName count=$hits at ${method.declaringClass.name}.${method.name}$extra")
    }
}

private fun hookInstreamBannerEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            logHookHitThrottled("bannerState", method)
            param.result = false
        }
    })
}

private fun hookIndicatorPillAdEligibility(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val pluginSlot = param.args.getOrNull(2)?.toString() ?: "unknown"
            logHookHitThrottled("indicatorPill", method, "slot=$pluginSlot")
            param.result = false
        }
    })
}

private fun hookGameAdRequest(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val payload = param.args.getOrNull(0) ?: return
            if (resolveGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Log.i(
                    TAG,
                    "Resolved game ad request as success in ${method.declaringClass.name}.${method.name}"
                )
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Log.i(
                    TAG,
                    "Rejected game ad request in ${method.declaringClass.name}.${method.name}"
                )
            } else {
                Log.w(
                    TAG,
                    "Unable to resolve or reject game ad request ${method.declaringClass.name}.${method.name}"
                )
            }
        }
    })
}

private fun hookGameAdBridge(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val rawMessage = param.args.getOrNull(0) as? String ?: return
            val payload = runCatching { JSONObject(rawMessage) }.getOrNull() ?: return
            val messageType = payload.optString("type")
            if (messageType !in GAME_AD_MESSAGE_TYPES) return

            if (resolveGameAdPayload(param.thisObject, payload, messageType)) {
                param.result = null
                Log.i(
                    TAG,
                    "Resolved game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            } else if (rejectGameAdPayload(param.thisObject, payload)) {
                param.result = null
                Log.i(
                    TAG,
                    "Rejected game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            } else {
                Log.w(
                    TAG,
                    "Unable to resolve or reject game ad bridge message type=$messageType in ${method.declaringClass.name}.${method.name}"
                )
            }
        }
    })
}

private fun hookPlayableAdActivity(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name != method.declaringClass.name) return
            finishGameAdActivity(activity, "direct hook ${method.declaringClass.name}.${method.name}")
        }
    })
}

private fun hookGlobalGameAdActivityLifecycleFallback() {
    val onResume = (Activity::class.java.declaredMethods + Activity::class.java.methods).firstOrNull { method ->
        method.name == "onResume" && method.parameterCount == 0
    }?.apply { isAccessible = true } ?: return

    XposedBridge.hookMethod(onResume, object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name !in GAME_AD_ACTIVITY_CLASS_NAMES) return
            finishGameAdActivity(activity, "global lifecycle fallback")
        }
    })

    Log.i(TAG, "Hooked global game ad activity lifecycle fallback")
}

private fun hookGameAdActivityLaunchFallbacks() {
    val methods = LinkedHashMap<String, Method>()
    listOf(Instrumentation::class.java, Activity::class.java, ContextWrapper::class.java).forEach { type ->
        (type.declaredMethods + type.methods)
            .filter { method ->
                method.name in setOf("execStartActivity", "startActivity", "startActivityForResult", "startActivityIfNeeded") &&
                    method.parameterTypes.any { it == Intent::class.java }
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
            hookGameAdActivityLaunchMethod(method)
            hooked++
        }.onFailure {
            Log.w(TAG, "Failed to hook game ad launch fallback ${method.declaringClass.name}.${method.name}", it)
        }
    }
    Log.i(TAG, "Hooked $hooked game ad activity launch fallback method(s)")
}

private fun hookGameAdActivityLaunchMethod(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val intent = param.args.firstOrNull { it is Intent } as? Intent ?: return
            val blockedClassName = resolveBlockedGameAdActivity(intent) ?: return
            if (!shouldBlockGameAdActivityLaunch(blockedClassName)) return
            if (method.returnType == Boolean::class.javaPrimitiveType) {
                param.result = false
            } else {
                param.result = null
            }
            Log.i(
                TAG,
                "Blocked game ad activity launch to $blockedClassName via ${method.declaringClass.name}.${method.name}"
            )
        }
    })
}

private fun shouldBlockGameAdActivityLaunch(className: String): Boolean {
    return className in HARD_BLOCKED_GAME_AD_ACTIVITY_CLASS_NAMES
}

private fun resolveBlockedGameAdActivity(intent: Intent): String? {
    val explicitTarget = intent.component?.className
    if (explicitTarget != null && explicitTarget in GAME_AD_ACTIVITY_CLASS_NAMES) {
        return explicitTarget
    }
    return null
}

private fun finishGameAdActivity(activity: Activity, source: String) {
    if (activity.isFinishing) return
    activity.setResult(Activity.RESULT_CANCELED)
    activity.finish()
    Log.i(TAG, "Closed game ad activity ${activity.javaClass.name} via $source")
}

private fun resolveGameAdPayload(target: Any?, payload: Any?, messageType: String? = null): Boolean {
    if (target == null || payload == null) return false

    val promiseId = extractPromiseId(payload)
    if (promiseId == null) {
        Log.w(TAG, "Unable to extract promiseID for resolved game ad payload")
        return false
    }

    val resolveMethod = resolveGameAdResolveMethod(target.javaClass)
    if (resolveMethod == null) {
        Log.w(TAG, "Unable to resolve success helper for resolved game ad payload")
        return false
    }

    val successPayload = buildGameAdSuccessPayload(payload, messageType)
    return runCatching {
        resolveMethod.invoke(target, promiseId, successPayload)
        true
    }.getOrElse {
        Log.e(TAG, "Failed to resolve game ad payload", it)
        false
    }
}

private fun rejectGameAdPayload(target: Any?, payload: Any?): Boolean {
    if (target == null || payload == null) return false

    val bridgeRejectMethod = resolveGameAdBridgeRejectMethod(target.javaClass)
    if (bridgeRejectMethod != null) {
        val success = runCatching {
            bridgeRejectMethod.invoke(target, GAME_AD_REJECTION_MESSAGE, GAME_AD_REJECTION_CODE, payload)
            true
        }.getOrElse {
            Log.e(TAG, "Failed to reject game ad payload via bridge reject helper", it)
            false
        }
        if (success) {
            return true
        }
    }

    val promiseId = extractPromiseId(payload)
    if (promiseId == null) {
        Log.w(TAG, "Unable to extract promiseID for rejected game ad payload")
        return false
    }
    val rejectMethod = resolveGameAdRejectMethod(target.javaClass)
    if (rejectMethod == null) {
        Log.w(TAG, "Unable to resolve reject helper for rejected game ad payload")
        return false
    }
    return runCatching {
        rejectMethod.invoke(
            target,
            promiseId,
            GAME_AD_REJECTION_MESSAGE,
            GAME_AD_REJECTION_CODE
        )
        true
    }.getOrElse {
        Log.e(TAG, "Failed to reject game ad payload", it)
        false
    }
}

private fun resolveGameAdResolveMethod(type: Class<*>?): Method? {
    if (type == null) return null

    val candidates = (type.declaredMethods + type.methods).filter { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 2 &&
            method.parameterTypes[0] == String::class.java &&
            !method.parameterTypes[1].isPrimitive
    }

    return (candidates.firstOrNull { it.parameterTypes[1] == Any::class.java }
        ?: candidates.firstOrNull { JSONObject::class.java.isAssignableFrom(it.parameterTypes[1]) }
        ?: candidates.firstOrNull()
        )?.apply { isAccessible = true }
}

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

private fun resolveGameAdRejectMethod(type: Class<*>?): Method? {
    if (type == null) return null
    return (type.declaredMethods + type.methods).firstOrNull { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterCount == 3 &&
            method.parameterTypes.all { it == String::class.java }
    }?.apply { isAccessible = true }
}

private fun extractGameAdContent(payload: Any?): JSONObject? {
    val json = payload as? JSONObject ?: return null
    return json.optJSONObject("content")
}

private fun buildGameAdSuccessPayload(payload: Any?, messageType: String? = null): JSONObject {
    val effectiveMessageType = messageType
        ?: (payload as? JSONObject)?.optString("type").orEmpty()
    val content = extractGameAdContent(payload)
    val result = JSONObject()

    val placementId = content?.optString("placementID")?.takeIf { it.isNotBlank() }
    val requestedAdInstanceId = content?.optString("adInstanceID")?.takeIf { it.isNotBlank() }
    val bannerPosition = content?.optString("bannerPosition")?.takeIf { it.isNotBlank() }

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
    }

    return result
}

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
    val jsonObjectClass = payload?.javaClass ?: return null
    if (jsonObjectClass.name != "org.json.JSONObject") return null
    val getJSONObject = (jsonObjectClass.declaredMethods + jsonObjectClass.methods).firstOrNull { method ->
        method.name == "getJSONObject" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == String::class.java
    }?.apply { isAccessible = true } ?: return null
    val getString = (jsonObjectClass.declaredMethods + jsonObjectClass.methods).firstOrNull { method ->
        method.name == "getString" &&
            method.parameterCount == 1 &&
            method.parameterTypes[0] == String::class.java
    }?.apply { isAccessible = true } ?: return null

    val content = runCatching { getJSONObject.invoke(payload, "content") }.getOrNull() ?: return null
    return runCatching { getString.invoke(content, "promiseID") as? String }.getOrNull()
}

private fun hookSponsoredPoolAdd(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = false
            Log.i(TAG, "Blocked sponsored feed edge from entering the pool")
        }
    })
}

private fun hookSponsoredStoryNext(method: Method) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            Log.i(TAG, "Blocked sponsored story vending from feed manager")
        }
    })
}

private fun hookStoryAdsMerge(method: Method, source: String) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val originalBuckets = param.args.getOrNull(2)
            if (originalBuckets != null) {
                param.result = originalBuckets
                Log.i(TAG, "Blocked story ad bucket merge in $source")
            }
        }
    })
}

private fun hookStoryAdsNoOp(method: Method, reason: String, source: String) {
    XposedBridge.hookMethod(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
            Log.i(TAG, "Blocked $reason in $source")
        }
    })
}

private fun hookStoryAdProvider(provider: StoryAdProviderHooks) {
    val hooked = ArrayList<String>()

    provider.mergeMethod?.let { method ->
        hookStoryAdsMerge(method, provider.providerClass.name)
        hooked.add("merge")
    }
    provider.fetchMoreAdsMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad fetchMoreAds", provider.providerClass.name)
        hooked.add("fetchMoreAds")
    }
    provider.deferredUpdateMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad deferred update", provider.providerClass.name)
        hooked.add("deferredUpdate")
    }
    provider.insertionTriggerMethod?.let { method ->
        hookStoryAdsNoOp(method, "story ad insertion trigger", provider.providerClass.name)
        hooked.add("insertionTrigger")
    }

    if (hooked.isNotEmpty()) {
        Log.i(TAG, "Hooked story ad provider ${provider.providerClass.name}: ${hooked.joinToString()}")
    }
}

private fun hookSponsoredPoolListMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 0 &&
                List::class.java.isAssignableFrom(method.returnType)
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = arrayListOf<Any?>()
                }
            })
            hooked++
        }
    Log.i(TAG, "Hooked $hooked feed pool list method(s) on ${poolClass.name}")
}

private fun hookSponsoredPoolResultMethods(poolClass: Class<*>) {
    var hooked = 0
    poolClass.declaredMethods
        .filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                isSponsoredResultCarrier(method.returnType) &&
                (
                    method.parameterCount == 0 ||
                        (method.parameterCount == 1 && method.parameterTypes[0] == Boolean::class.javaPrimitiveType)
                    )
        }
        .forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    buildSponsoredEmptyResult(method.returnType)?.let { emptyResult ->
                        param.result = emptyResult
                    }
                }
            })
            hooked++
        }
    Log.i(TAG, "Hooked $hooked feed pool result method(s) on ${poolClass.name}")
}

private fun isSponsoredResultCarrier(type: Class<*>): Boolean {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return false
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return false
    return reasonType.enumConstants?.any { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" } == true
}

private fun buildSponsoredEmptyResult(type: Class<*>): Any? {
    val constructor = type.declaredConstructors.firstOrNull { it.parameterCount == 2 } ?: return null
    val reasonType = constructor.parameterTypes.getOrNull(1) ?: return null
    val emptyReason = reasonType.enumConstants?.firstOrNull { it.toString() == "SPONSORED_GET_NEXT_RETURN_NULL" }
        ?: reasonType.enumConstants?.firstOrNull { it.toString() == "FAIL" }
        ?: return null
    constructor.isAccessible = true
    return constructor.newInstance(null, emptyReason)
}

private fun filterAdItems(list: MutableList<Any?>, inspector: AdStoryInspector): Int {
    var removed = 0
    val iterator = list.iterator()
    while (iterator.hasNext()) {
        if (inspector.containsAdStory(iterator.next())) {
            iterator.remove()
            removed++
        }
    }
    return removed
}

private fun buildImmutableListLike(sample: Any?, items: List<Any?>): Any? {
    if (sample == null) return null
    return runCatching {
        val immutableListClass = Class.forName(
            "com.google.common.collect.ImmutableList",
            false,
            sample.javaClass.classLoader
        )
        val copyOf = immutableListClass.getDeclaredMethod("copyOf", Iterable::class.java)
        copyOf.invoke(null, items)
    }.getOrNull()
}

private fun replaceFeedItemsInResult(param: XC_MethodHook.MethodHookParam, items: List<Any?>): Boolean {
    val result = param.result ?: return false
    val rebuiltResult = rebuildFeedResult(result, items) ?: return false
    param.result = rebuiltResult
    return true
}

private fun rebuildFeedResult(result: Any, items: List<Any?>): Any? {
    val type = result.javaClass
    val fields = runCatching {
        type.declaredFields.onEach { it.isAccessible = true }
    }.getOrNull() ?: return null

    val listField = fields.firstOrNull { candidate ->
        !Modifier.isStatic(candidate.modifiers) &&
            Iterable::class.java.isAssignableFrom(candidate.type)
    } ?: return null

    val intArrayField = fields.firstOrNull { candidate ->
        !Modifier.isStatic(candidate.modifiers) && candidate.type == IntArray::class.java
    } ?: return null

    val intFields = fields.filter { candidate ->
        !Modifier.isStatic(candidate.modifiers) && candidate.type == Int::class.javaPrimitiveType
    }
    if (intFields.size < 3) return null

    val originalList = runCatching { listField.get(result) }.getOrNull()
    val rebuiltList = buildImmutableListLike(originalList, items) ?: return null
    val stats = runCatching { intArrayField.get(result) as? IntArray }.getOrNull()?.clone() ?: return null
    val ints = intFields.map { field -> runCatching { field.getInt(result) }.getOrNull() ?: return null }

    val constructor = type.declaredConstructors.firstOrNull { constructor ->
        constructor.parameterCount == 5 &&
            constructor.parameterTypes.getOrNull(0)?.name == "com.google.common.collect.ImmutableList" &&
            constructor.parameterTypes.getOrNull(1) == IntArray::class.java &&
            constructor.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
    } ?: return null

    constructor.isAccessible = true
    return runCatching {
        constructor.newInstance(rebuiltList, stats, ints[0], ints[1], ints[2])
    }.getOrNull()
}

private fun extractFeedItemsFromResult(result: Any?): Iterable<*>? {
    if (result == null) return null
    if (result is Iterable<*>) return result

    return runCatching {
        val field = result.javaClass.declaredFields.firstOrNull { candidate ->
            Iterable::class.java.isAssignableFrom(candidate.type)
        } ?: return null
        field.isAccessible = true
        field.get(result) as? Iterable<*>
    }.getOrNull()
}

private fun logFeedItems(source: String, items: Iterable<*>, feedItemInspector: FeedItemInspector) {
    var index = 0
    for (item in items) {
        Log.i(TAG, "FeedItem $source[$index] ${feedItemInspector.describe(item)}")
        index++
    }
    Log.i(TAG, "FeedItem $source count=$index")
}
