package tn.loukious.facebookappadsremover.hooks

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/** Native Facebook download entry points modelled after Morphe's patches. */
object ContextualDownloadHook {

    private const val TAG = "FBAR.ContextDownload"

    const val STORY_CACHE_KEY = "download.context.story"
    const val REEL_CACHE_KEY = "download.context.reel"
    const val STORY_CACHE_VERSION = "native-v3"
    // This schema is independent of the module's versionCode: old negatives
    // must not silently suppress a revised discovery algorithm on an update.
    private const val REEL_ABSENT_PREFIX = "absent-native-v6|"
    private const val REEL_META_PREFIX = "native-v6|"
    private const val REEL_NEGATIVE_RETRY_MS = 72L * 60 * 60 * 1000

    private const val STORY_MORE_MENU =
        "com.facebook.stories.viewer.ui.buckets.regular.topbar.menu.StoryViewerMoreButtonCallback"
    private const val STORY_CARD = "com.facebook.stories.model.StoryCard"
    private const val VIDEO_PLAYER_PARAMS = "com.facebook.video.engine.api.VideoPlayerParams"
    private const val FB_USER_SESSION = "com.facebook.auth.usersession.FbUserSession"
    private const val SAVE_STORY_ATTEMPTED = "save_story_attempted"
    private const val REEL_SIDEBAR = "UDDSideBarComponent"
    private const val DOWNLOAD_ROW = "fds_control_download_video"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"
    private const val LABEL = "Download"
    private const val REPOST_LABEL = "Repost"
    private const val TEST_ID = "download_button"
    private const val REPOST_TEST_ID = "repost_button"
    private const val CAPTION = ""
    private const val TAP_SLOT = 1
    private const val REEL_REPOST_ICON_NAME = "RESHARE"
    private const val STORY_REPOST_ICON_NAME = "RESHARE"

    data class Result(
        val storyMethods: List<Method>?,
        val reelMethods: List<Method>?,
        val reelMetadata: String?,
    )

    private data class ReelMetadata(
        val iconOwner: String,
        val iconField: String,
        val iconWrapper: String,
        val playerField: String,
        val contextField: String,
    ) {
        fun encode(): String = REEL_META_PREFIX + listOf(
            iconOwner,
            iconField,
            iconWrapper,
            playerField,
            contextField,
        ).joinToString("|")

        companion object {
            fun decode(raw: String?): ReelMetadata? {
                if (raw == null || !raw.startsWith(REEL_META_PREFIX)) return null
                val parts = raw.removePrefix(REEL_META_PREFIX).split('|')
                if (parts.size != 5 || parts.any { it.isBlank() }) return null
                return ReelMetadata(
                    iconOwner = parts[0],
                    iconField = parts[1],
                    iconWrapper = parts[2],
                    playerField = parts[3],
                    contextField = parts[4],
                )
            }
        }
    }

    private data class ReelRuntime(
        val builder: Method,
        val factory: Method,
        val assembly: Method,
        val marker: Method,
        val iconField: Field,
        val iconWrapper: Class<*>,
        val playerField: Field,
        val contextField: Field,
    )

    private data class StoryRuntime(
        val builder: Method,
        val contextField: Field,
        val storyField: Field,
        val menuItemCtor: Constructor<*>,
        val handlerType: Class<*>,
        val iconType: Class<*>,
    )

    private enum class ReelAction { DOWNLOAD, REPOST }

    private data class ReelCall(
        val owner: Any,
        val scoped: Any?,
        var factoryTemplate: Array<Any?>? = null,
        var injected: Boolean = false,
    )

    private val hookedStoryBuilders: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedStoryCapabilities: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedStoryHandlers: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedReelBuilders: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedReelFactories: MutableSet<Method> = ConcurrentHashMap.newKeySet()
    private val hookedReelAssemblies: MutableSet<Method> = ConcurrentHashMap.newKeySet()

    private val storyMenuDepth = ThreadLocal.withInitial { 0 }
    private val reelCall = ThreadLocal<ReelCall?>()

    @Volatile private var reelRuntime: ReelRuntime? = null
    @Volatile private var storyRuntime: StoryRuntime? = null

    /** Retry unsuccessful discovery periodically even if FB's version is unchanged. */
    fun reelCacheNeedsRescan(raw: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (ReelMetadata.decode(raw) != null) return false
        val failedAt = raw?.takeIf { it.startsWith(REEL_ABSENT_PREFIX) }
            ?.removePrefix(REEL_ABSENT_PREFIX)?.toLongOrNull() ?: return true
        return failedAt <= 0L || failedAt > now || now - failedAt >= REEL_NEGATIVE_RETRY_MS
    }

    fun isReelAbsent(raw: String?): Boolean = raw?.startsWith(REEL_ABSENT_PREFIX) == true

    fun reelDiscoveryFailedEntry(): String = REEL_ABSENT_PREFIX + System.currentTimeMillis()

    fun install(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): Result {
        val story = discoverStory(module, bridge, classLoader)
        val reel = discoverReel(module, bridge, classLoader)
        return Result(story, reel?.first, reel?.second)
    }

    fun installStoryCached(module: XposedInterface, methods: List<Method>): Boolean =
        hookStoryMethods(module, methods)

    fun installReelsCached(
        module: XposedInterface,
        classLoader: ClassLoader,
        methods: List<Method>,
        metadata: String,
    ): Boolean {
        val meta = ReelMetadata.decode(metadata) ?: return false
        return installReelRuntime(module, classLoader, methods, meta)
    }

    // --------------------------------------------------------------- stories

    private fun discoverStory(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): List<Method>? = runCatching {
        // Morphe fingerprints the analytics-bearing method only to learn the
        // action class. The actual tap handler is then selected from that class.
        val actionHits = bridge.findMethod {
            matcher { usingStrings(SAVE_STORY_ATTEMPTED) }
        }.filter { it.returnTypeName == "void" && it.paramCount == 1 }

        check(actionHits.size == 1) {
            "Expected 1 save-story action fingerprint, found ${actionHits.size}"
        }
        val actionClass = actionHits.single().declaredClass
            ?: error("Save-story action class could not be resolved")
        val actionClassName = actionClass.name

        val builders = bridge.findMethod {
            matcher { declaredClass(STORY_MORE_MENU) }
        }.filter { method ->
            method.invokes.any { it.isConstructor && it.declaredClassName == actionClassName }
        }
        check(builders.size == 1) {
            "Expected 1 story menu builder creating $actionClassName, found ${builders.size}"
        }
        val builderData = builders.single()

        // Same shape Morphe patches: a non-framework virtual ()Z call.
        val capabilities = builderData.invokes.filter { called ->
            called.paramCount == 0 &&
                called.returnTypeName == "boolean" &&
                !Modifier.isStatic(called.modifiers) &&
                !called.declaredClassName.startsWith("java.") &&
                !called.declaredClassName.startsWith("android.")
        }.distinctBy(MethodData::descriptor)
        check(capabilities.size == 1) {
            "Expected 1 story save capability, found ${capabilities.size}"
        }

        val handlers = actionClass.methods.filter {
            it.methodName != "<init>" && it.returnTypeName == "void" && it.paramCount == 1
        }
        check(handlers.size == 1) {
            "Expected 1 story tap handler on $actionClassName, found ${handlers.size}"
        }

        val methods = listOf(
            builderData.getMethodInstance(classLoader),
            capabilities.single().getMethodInstance(classLoader),
            handlers.single().getMethodInstance(classLoader),
        )
        check(hookStoryMethods(module, methods)) { "No story methods were hooked" }
        L.i(TAG, "native story Save hook installed: $actionClassName")
        L.i(TAG, "native story Repost menu injection ready")
        methods
    }.onFailure { L.w(TAG, "story contextual discovery failed", it) }.getOrNull()

    private fun hookStoryMethods(module: XposedInterface, methods: List<Method>): Boolean {
        if (methods.size != 3) return false
        val builder = methods[0]
        val capability = methods[1]
        val handler = methods[2]
        val runtime = runCatching { resolveStoryRuntime(builder, handler) }
            .onFailure { L.w(TAG, "story native-menu runtime resolve failed", it) }
            .getOrNull() ?: return false
        storyRuntime = runtime
        var installed = 0

        if (hookedStoryBuilders.add(builder)) {
            runCatching {
                builder.isAccessible = true
                module.hook(builder).intercept(StoryMenuBuilderHook)
                installed++
            }.onFailure { L.w(TAG, "story menu-builder hook failed", it) }
        }
        if (hookedStoryCapabilities.add(capability)) {
            runCatching {
                capability.isAccessible = true
                module.hook(capability).intercept(StoryCapabilityHook)
                installed++
            }.onFailure { L.w(TAG, "story capability hook failed", it) }
        }
        if (hookedStoryHandlers.add(handler)) {
            runCatching {
                handler.isAccessible = true
                module.hook(handler).intercept(StoryActionHook)
                installed++
            }.onFailure { L.w(TAG, "story action hook failed", it) }
        }

        return installed > 0 ||
            (hookedStoryBuilders.contains(builder) &&
                hookedStoryCapabilities.contains(capability) &&
                hookedStoryHandlers.contains(handler))
    }

    private object StoryMenuBuilderHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val previous = storyMenuDepth.get() ?: 0
            storyMenuDepth.set(previous + 1)
            return try {
                val result = chain.proceed()
                if (Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) {
                    val owner = chain.thisObject
                    if (owner != null) {
                        runCatching { appendStoryRepost(chain, owner) }
                            .onFailure { L.w(TAG, "story Repost menu injection failed", it) }
                    }
                }
                result
            } finally {
                storyMenuDepth.set(previous)
            }
        }
    }

    private fun resolveStoryRuntime(builder: Method, handler: Method): StoryRuntime {
        val menuItemClass = handler.parameterTypes.singleOrNull()
            ?: error("Story action no longer takes one menu item")
        val ctor = menuItemClass.declaredConstructors.singleOrNull { constructor ->
            val p = constructor.parameterTypes
            p.size == 10 &&
                p[0].isInterface &&
                p[1].isEnum &&
                CharSequence::class.java.isAssignableFrom(p[2]) &&
                p[3] == Integer::class.java && p[4] == Integer::class.java &&
                p[5] == Integer::class.java && p[6] == String::class.java &&
                p[7] == String::class.java && p[8] == Int::class.javaPrimitiveType &&
                p[9] == Boolean::class.javaPrimitiveType
        } ?: error("Story menu item constructor changed: ${menuItemClass.name}")
        ctor.isAccessible = true

        val owner = builder.declaringClass
        val contextField = owner.declaredFields.singleOrNull {
            Context::class.java.isAssignableFrom(it.type)
        } ?: error("Story menu owner no longer has one Context field")
        val storyField = owner.declaredFields.singleOrNull { it.type.name == STORY_CARD }
            ?: error("Story menu owner no longer has one StoryCard field")
        contextField.isAccessible = true
        storyField.isAccessible = true

        return StoryRuntime(
            builder = builder,
            contextField = contextField,
            storyField = storyField,
            menuItemCtor = ctor,
            handlerType = ctor.parameterTypes[0],
            iconType = ctor.parameterTypes[1],
        )
    }

    private fun appendStoryRepost(chain: XposedInterface.Chain, owner: Any) {
        val runtime = storyRuntime ?: return
        val builderIndex = runtime.builder.parameterTypes.indexOfFirst {
            it.name == "com.google.common.collect.ImmutableList\$Builder"
        }
        if (builderIndex < 0) return
        val listBuilder = chain.args.getOrNull(builderIndex) ?: return
        val context = runtime.contextField.get(owner) as? Context ?: return
        val storyCard = runtime.storyField.get(owner) ?: return
        val icon = enumConstant(runtime.iconType, STORY_REPOST_ICON_NAME) ?: run {
            L.w(TAG, "story Repost icon $STORY_REPOST_ICON_NAME not present")
            return
        }

        val loader = runtime.handlerType.classLoader ?: ContextualDownloadHook::class.java.classLoader
        val clickHandler = Proxy.newProxyInstance(loader, arrayOf(runtime.handlerType)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "FBARStoryRepostHandler"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> {
                    if (method.parameterCount == 1) {
                        L.i(TAG, "native story Repost tapped")
                        DownloadHook.openStoryRepost(context, storyCard)
                    }
                    null
                }
            }
        }
        val item = runtime.menuItemCtor.newInstance(
            clickHandler,
            icon,
            null,
            null,
            null,
            null,
            // Match Facebook's native Save-story layout: its visible label
            // lives in A06 (resource id). For a custom string, Wkb.A08 is the
            // direct-string fallback for that same *primary* label slot. Our
            // previous A03 placement was the secondary text slot, hence gray.
            REPOST_LABEL,
            null,
            // Save story uses menu item id 2 and enabled=false. Mirroring
            // those style flags keeps Repost on the same native row path.
            2,
            false,
        )
        val add = listBuilder.javaClass.methods.firstOrNull {
            it.name == "add" && it.parameterCount == 1
        } ?: error("ImmutableList.Builder.add(Object) not found")
        add.invoke(listBuilder, item)
        L.i(TAG, "native Repost item appended to story menu")
    }

    private object StoryCapabilityHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return chain.proceed()
            return if ((storyMenuDepth.get() ?: 0) > 0) true else chain.proceed()
        }
    }

    private object StoryActionHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return chain.proceed()
            val owner = chain.thisObject ?: return chain.proceed()
            var context: Context? = null
            var storyCard: Any? = null

            var type: Class<*>? = owner.javaClass
            while (type != null && type != Any::class.java) {
                val current = type
                for (field in runCatching { current.declaredFields }.getOrElse { emptyArray() }) {
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(owner)
                    }.getOrNull() ?: continue
                    if (context == null && field.type == Context::class.java) context = value as Context
                    if (storyCard == null && field.type.name == STORY_CARD) storyCard = value
                }
                type = current.superclass
            }

            return if (storyCard != null && DownloadHook.openStoryDownload(context, storyCard)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    // ----------------------------------------------------------------- reels

    private fun discoverReel(
        module: XposedInterface,
        bridge: DexKitBridge,
        classLoader: ClassLoader,
    ): Pair<List<Method>, String>? = runCatching {
        val anchorHits = bridge.findMethod {
            matcher { usingStrings(REEL_SIDEBAR) }
        }
        L.i(TAG, "reel discovery: ${anchorHits.size} UDDSideBarComponent string hits")
        val sidebarCandidates = anchorHits.filter { method ->
            method.invokes.any { called ->
                called.paramTypeNames.count { it == FUNCTION1 } == 4 &&
                    called.paramTypeNames.firstOrNull() == FB_USER_SESSION
            }
        }
        L.i(TAG, "reel discovery: ${sidebarCandidates.size} matching sidebar builders")
        check(sidebarCandidates.size == 1) {
            "Expected 1 reel sidebar builder, found ${sidebarCandidates.size}"
        }
        val sidebar = sidebarCandidates.single()
        check(sidebar.paramCount == 1) { "Reel sidebar builder now takes ${sidebar.paramCount} params" }

        val factories = sidebar.invokes.filter { called ->
            called.paramTypeNames.count { it == FUNCTION1 } == 4 &&
                called.paramTypeNames.firstOrNull() == FB_USER_SESSION
        }.distinctBy(MethodData::descriptor)
        check(factories.size == 1) { "Expected 1 reel button factory, found ${factories.size}" }
        val factory = factories.single()
        // 578 has 19 arguments, 580 has 20. Validate the semantic prefix and
        // allow *any number* of subsequent boolean feature flags: copy the
        // current reel's values rather than guessing defaults for new flags.
        check(ReelFactorySignature.accepts(factory.paramTypeNames)) {
            "Reel button factory has incompatible signature: ${factory.descriptor}"
        }

        val parameters = factory.paramTypeNames
        val iconBaseType = parameters[5]

        val wrapperCtors = sidebar.invokes.filter { called ->
            called.isConstructor &&
                called.paramCount == 1 &&
                called.declaredClass?.superClass?.name == iconBaseType
        }.distinctBy { it.declaredClassName }
        check(wrapperCtors.size == 1) { "Expected 1 reel icon wrapper, found ${wrapperCtors.size}" }
        val iconWrapperCtor = wrapperCtors.single()
        val iconEnumType = iconWrapperCtor.paramTypeNames.single()

        val assemblies = sidebar.invokes.filter { called ->
            called.paramTypeNames.count { it == "java.util.ArrayList" } == 2 &&
                called.paramTypeNames.any { it == "java.util.List" }
        }.distinctBy(MethodData::descriptor)
        check(assemblies.size == 1) { "Expected 1 reel sidebar assembly call, found ${assemblies.size}" }
        val assembly = assemblies.single()

        val markers = sidebar.invokes.filter { called ->
            called.paramCount == 1 &&
                called.paramTypeNames.single() == iconEnumType &&
                called.returnTypeName != "void"
        }.distinctBy(MethodData::descriptor)
        check(markers.size == 1) { "Expected 1 reel icon marker, found ${markers.size}" }
        val marker = markers.single()

        val icon = bridge.findMethod {
            matcher { usingStrings(DOWNLOAD_ROW) }
        }.asSequence()
            .flatMap { it.usingFields.asSequence() }
            .map { it.field }
            .firstOrNull { field ->
                field.typeName == iconEnumType && Modifier.isStatic(field.modifiers)
            }
        check(icon != null) { "Facebook download row exposes no icon of $iconEnumType" }

        val component = sidebar.declaredClass
            ?: error("Reel sidebar class could not be resolved")
        val playerFields = component.fields.filter { field ->
            field.type?.fields?.any { it.typeName == VIDEO_PLAYER_PARAMS } == true
        }
        check(playerFields.size == 1) { "Expected 1 reel player field, found ${playerFields.size}" }

        val scopedType = sidebar.paramTypes.single()
            ?: error("Reel sidebar scoped parameter type could not be resolved")
        val contextFields = scopedType.fields.filter { it.typeName == Context::class.java.name }
        check(contextFields.size == 1) { "Expected 1 context field on ${scopedType.name}, found ${contextFields.size}" }

        val meta = ReelMetadata(
            iconOwner = icon.declaredClassName,
            iconField = icon.fieldName,
            iconWrapper = iconWrapperCtor.declaredClassName,
            playerField = playerFields.single().fieldName,
            contextField = contextFields.single().fieldName,
        )

        val methods = listOf(
            sidebar.getMethodInstance(classLoader),
            factory.getMethodInstance(classLoader),
            assembly.getMethodInstance(classLoader),
            marker.getMethodInstance(classLoader),
        )
        check(installReelRuntime(module, classLoader, methods, meta)) { "Native reel hooks were not installed" }
        L.i(TAG, "native reel Download button installed via ${sidebar.declaredClassName}.${sidebar.methodName}")
        methods to meta.encode()
    }.onFailure { L.w(TAG, "reel native-button discovery failed", it) }.getOrNull()

    @Synchronized
    private fun installReelRuntime(
        module: XposedInterface,
        classLoader: ClassLoader,
        methods: List<Method>,
        meta: ReelMetadata,
    ): Boolean {
        if (methods.size != 4) return false
        val builder = methods[0]
        val factory = methods[1]
        val assembly = methods[2]
        val marker = methods[3]
        if (!Modifier.isStatic(factory.modifiers) || !Modifier.isStatic(marker.modifiers)) {
            L.w(TAG, "reel factory/marker is no longer static")
            return false
        }
        if (!ReelFactorySignature.accepts(factory.parameterTypes.map { it.name })) {
            L.w(TAG, "cached reel factory signature changed: $factory")
            return false
        }

        val runtime = runCatching {
            val iconOwner = Class.forName(meta.iconOwner, false, classLoader)
            val iconWrapper = Class.forName(meta.iconWrapper, false, classLoader)
            val iconField = iconOwner.getDeclaredField(meta.iconField).apply { isAccessible = true }
            val playerField = builder.declaringClass.getDeclaredField(meta.playerField).apply { isAccessible = true }
            val scopedType = builder.parameterTypes.single()
            val contextField = scopedType.getDeclaredField(meta.contextField).apply { isAccessible = true }
            ReelRuntime(
                builder = builder,
                factory = factory.apply { isAccessible = true },
                assembly = assembly.apply { isAccessible = true },
                marker = marker.apply { isAccessible = true },
                iconField = iconField,
                iconWrapper = iconWrapper,
                playerField = playerField,
                contextField = contextField,
            )
        }.onFailure { L.w(TAG, "reel runtime metadata resolve failed", it) }.getOrNull() ?: return false

        reelRuntime = runtime
        if (!hookedReelBuilders.contains(builder)) {
            runCatching {
                module.hook(builder).intercept(ReelBuilderHook)
                hookedReelBuilders.add(builder)
            }.onFailure { L.w(TAG, "reel builder hook failed", it) }
        }
        if (!hookedReelFactories.contains(factory)) {
            runCatching {
                module.hook(factory).intercept(ReelFactoryHook)
                hookedReelFactories.add(factory)
            }.onFailure { L.w(TAG, "reel factory hook failed", it) }
        }
        if (!hookedReelAssemblies.contains(assembly)) {
            runCatching {
                module.hook(assembly).intercept(ReelAssemblyHook)
                hookedReelAssemblies.add(assembly)
            }.onFailure { L.w(TAG, "reel assembly hook failed", it) }
        }
        return hookedReelBuilders.contains(builder) &&
            hookedReelFactories.contains(factory) &&
            hookedReelAssemblies.contains(assembly)
    }

    private object ReelBuilderHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val owner = chain.thisObject ?: return chain.proceed()
            val previous = reelCall.get()
            reelCall.set(ReelCall(owner, chain.args.getOrNull(0)))
            return try {
                chain.proceed()
            } finally {
                reelCall.set(previous)
            }
        }
    }

    private object ReelAssemblyHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            if (!Settings.getBoolean(Settings.DOWNLOAD_SHOW_ICON, true)) return chain.proceed()
            val call = reelCall.get() ?: return chain.proceed()
            if (!call.injected) {
                runCatching { injectNativeReelButtons(chain, call) }
                    .onFailure { L.w(TAG, "native reel button injection failed", it) }
            }
            return chain.proceed()
        }
    }

    /** Capture one real Facebook sidebar-button invocation as the template. */
    private object ReelFactoryHook : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val call = reelCall.get()
            if (call != null && call.factoryTemplate == null) {
                call.factoryTemplate = chain.args.toTypedArray()
            }
            return chain.proceed()
        }
    }

    private fun injectNativeReelButtons(chain: XposedInterface.Chain, call: ReelCall) {
        val runtime = reelRuntime ?: return
        val assemblyTypes = runtime.assembly.parameterTypes
        val buttonListIndex = assemblyTypes.indexOfFirst { it.name == "java.util.List" }
        val markerListIndex = assemblyTypes.indexOfFirst { it.name == "java.util.ArrayList" }
        if (buttonListIndex < 0 || markerListIndex < 0) return

        @Suppress("UNCHECKED_CAST")
        val buttons = chain.args.getOrNull(buttonListIndex) as? MutableCollection<Any?> ?: return
        @Suppress("UNCHECKED_CAST")
        val markers = chain.args.getOrNull(markerListIndex) as? MutableCollection<Any?> ?: return

        val player = runtime.playerField.get(call.owner) ?: return
        val context = call.scoped?.let { runtime.contextField.get(it) as? Context } ?: return
        val template = call.factoryTemplate ?: run {
            L.w(TAG, "reel sidebar reached assembly before any button factory call")
            return
        }
        val downloadIcon = runtime.iconField.get(null) ?: return
        val downloadButton = buildNativeButton(
            runtime, template, context, player, ReelAction.DOWNLOAD, downloadIcon) ?: return
        val downloadMarker = runtime.marker.invoke(null, downloadIcon) ?: return
        buttons.add(downloadButton)
        markers.add(downloadMarker)
        L.i(TAG, "native Download button appended to reel sidebar")

        val repostIcon = enumConstant(runtime.iconField.type, REEL_REPOST_ICON_NAME)
        if (repostIcon != null) {
            val repostButton = buildNativeButton(
                runtime, template, context, player, ReelAction.REPOST, repostIcon)
            val repostMarker = runtime.marker.invoke(null, repostIcon)
            if (repostButton != null && repostMarker != null) {
                buttons.add(repostButton)
                markers.add(repostMarker)
                L.i(TAG, "native Repost button appended to reel sidebar")
            }
        } else {
            L.w(TAG, "reel Repost icon $REEL_REPOST_ICON_NAME not present")
        }
        call.injected = true
    }

    private fun buildNativeButton(
        runtime: ReelRuntime,
        template: Array<Any?>,
        context: Context,
        player: Any,
        action: ReelAction,
        icon: Any,
    ): Any? {
        val p = runtime.factory.parameterTypes
        if (template.size != p.size ||
            !ReelFactorySignature.accepts(p.map { it.name })
        ) {
            L.w(TAG, "reel button factory layout no longer matches the captured template")
            return null
        }
        val functionType = p.firstOrNull { it.name == FUNCTION1 } ?: return null
        val handlers = (0..6).map { slot ->
            functionProxy(functionType, slot, context, player, action)
        }

        val primaryCtor = p[2].getDeclaredConstructor(String::class.java, functionType).apply { isAccessible = true }
        val labelCtor = p[3].getDeclaredConstructor(String::class.java, functionType).apply { isAccessible = true }
        val iconCtor = runtime.iconWrapper.getDeclaredConstructor(runtime.iconField.type).apply {
            isAccessible = true
        }

        val label = if (action == ReelAction.DOWNLOAD) LABEL else REPOST_LABEL
        val testId = if (action == ReelAction.DOWNLOAD) TEST_ID else REPOST_TEST_ID

        // Keep Facebook's real session/mode and any opaque state from a button
        // it just built for this exact reel, replacing only the values Morphe
        // explicitly supplies for its Download button.
        val args = template.copyOf()
        args[2] = primaryCtor.newInstance(label, handlers[0])
        args[3] = labelCtor.newInstance(label, handlers[1])
        args[4] = labelCtor.newInstance(label, handlers[2])
        args[5] = iconCtor.newInstance(icon)
        args[6] = true
        args[7] = false
        args[8] = testId
        args[9] = CAPTION
        // Factory parameter 10 is String. Morphe's `const/16 v50, 0x0`
        // passes a null object reference here, not integer zero.
        args[10] = null
        args[11] = handlers[3]
        args[12] = handlers[4]
        args[13] = handlers[5]
        args[14] = handlers[6]
        args[15] = 0x11
        args[16] = false
        args[17] = true
        args[18] = false
        // Any additional flags are deliberately left as Facebook supplied
        // them in the original button for this reel.
        return runtime.factory.invoke(null, *args)
    }

    private fun functionProxy(
        functionType: Class<*>,
        slot: Int,
        context: Context,
        player: Any,
        action: ReelAction,
    ): Any {
        val loader = functionType.classLoader ?: ContextualDownloadHook::class.java.classLoader
        val unit = runCatching {
            Class.forName("kotlin.Unit", true, loader).getField("INSTANCE").get(null)
        }.getOrNull()
        return Proxy.newProxyInstance(loader, arrayOf(functionType)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    if (slot == TAP_SLOT) {
                        when (action) {
                            ReelAction.DOWNLOAD -> {
                                L.i(TAG, "native reel Download tapped")
                                DownloadHook.openReelDownload(context, player)
                            }
                            ReelAction.REPOST -> {
                                L.i(TAG, "native reel Repost tapped")
                                DownloadHook.openReelRepost(context, player)
                            }
                        }
                    }
                    unit
                }
                "toString" -> "FBARReel${action.name}Handler($slot)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun enumConstant(type: Class<*>, name: String): Any? =
        type.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == name }
}
