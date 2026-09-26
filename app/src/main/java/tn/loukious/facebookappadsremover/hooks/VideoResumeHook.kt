package tn.loukious.facebookappadsremover.hooks

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.ResumeStore
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Video resume — port of the original mod's `X.qV2f0EclUxE76gtr3YEb`
 * (FacebookVideoResumeHook, log tag `VideoResume.txt`; RE notes:
 * tools/extract/playback_trio_notes.md).
 *
 * The mod hooked `X.4Qc` = FbGrootPlayer with hardcoded member names. Those
 * short names rotate every FB release, so this port resolves every member
 * structurally instead (verified against 577.0.0.50.72):
 *
 *  - Player class: the only class using the "FbGrootPlayer.pause" quicklog
 *    literal (DexKit, cached class name).
 *  - Tracking pair: maybeTrackVideoStart/Stop — real analytics names that
 *    survive obfuscation; their single parameter is the reason enum.
 *  - Reason enum: the tracking pair's parameter type.
 *  - Seek: the unique (reason, int) → void method — the public
 *    seekTo(reason, positionMs) entry.
 *  - Params getter: the unique zero-arg method returning the stable class
 *    com.facebook.video.engine.api.VideoPlayerParams.
 *  - videoId: parsed from VideoPlayerParams.toString(), which is exactly
 *    "VideoId: <id>" — a stable shape, no field-name dependency.
 *  - Position getter: reflection cannot see call targets, so DexKit resolves
 *    it once per version (see [findPositionGetter]) and the name is cached:
 *    the player method that delegates to the same engine accessor the seek
 *    dispatcher's own no-op guard reads ("FbGrootPlayer.seekToWithSafeSeek").
 *
 * Mod hooks on the player class:
 *
 *   maybeTrackVideoStart(reason) — AFTER hook: extract videoId + position,
 *       track the player in a WeakReference, then ARM the restore ONCE (nMXy:
 *       saved pos ≥ 1001 ms, deadline now+3000 ms, postDelayed 900 ms) when
 *       a stored position exists.
 *   maybeTrackVideoStop(reason) — BEFORE hook: tracked == this → save the
 *       current position to the store, clear tracking.
 *   seek(reason, int) — BEFORE hook (prio 10000): cancel pending restore;
 *       AFTER successful FB seek: update the saved point, including clearing
 *       an old point when the user scrubs to the beginning.
 *       Does NOT rewrite args (correction from the session-10 decode) — it
 *       marks state so the restore doesn't fight the player.
 *   Activity.onPause/onStop → FgOJ lifecycle save (min position 1000 ms).
 *
 * Restore arm (fully decoded, session 10): start-hook arms per-player state
 * → wi6Jf1Qzw1hmohzCl98E runnable (posted +900 ms) re-validates — videoId
 * must match, target ≥ 1001 ms (else disarm), ≥ 1501 ms final gate,
 * 3000 ms deadline — then Rn0LbcxLisWuSI9YThk runs the seek
 * `seek.invoke(player, reason, positionMs)` wrapped in an in-flight flag
 * with try/catch/finally.
 *
 * Deliberate deviations (the undecoded operands — documented, not invented):
 *  - wi6Jf's final `delta <= …` gate lost its threshold in the decompile;
 *    the port bails when the player is already within 1000 ms of the target
 *    (same 1 s threshold family as the decoded 1001/1501 windows).
 *  - the mod's nMXy clamp source (Math min/max operands) was not decoded;
 *    the port clamps to non-negative.
 *  - the mod hooked Activity.onPause/onStop via hookAllMethods; the port
 *    registers ActivityLifecycleCallbacks on the FB application — same
 *    events, no framework-class hook needed.
 */
object VideoResumeHook {

    private const val TAG = "FBAR.VideoResume"

    /** Cache key — the player-controller class; doubles as the sentinel. */
    const val CACHE_KEY = "media.video.player"

    /** Cache key — the DexKit-resolved position-getter name on that class. */
    const val POSITION_CACHE_KEY = "media.video.position"

    /** DexKit anchor: the pause quicklog literal, unique to the player class. */
    private const val CLASS_ANCHOR = "FbGrootPlayer.pause"

    /** DexKit anchor: the seek dispatcher's quicklog literal (same family). */
    private const val SEEK_DISPATCH_ANCHOR = "FbGrootPlayer.seekToWithSafeSeek"

    /** Stable analytics names on the player class (never obfuscated). */
    private const val M_START = "maybeTrackVideoStart"
    private const val M_STOP = "maybeTrackVideoStop"

    /** Stable FB class; its toString() is exactly "VideoId: <id>". */
    private const val PARAMS_CLASS = "com.facebook.video.engine.api.VideoPlayerParams"
    private const val VIDEO_ID_MARKER = "VideoId: "

    /** Mod: nMXy posts wi6Jf with postDelayed(…, 900). */
    private const val RESTORE_DELAY_MS = 900L

    /** Very short clips can pass their saved point before a 900-ms callback. */
    private const val SHORT_RESTORE_LIMIT_MS = 10_000L
    private const val SHORT_RESTORE_DELAY_MS = 200L
    private const val SHORT_MIN_ADVANCE_MS = 250L

    /** A no-op startup seek from Facebook must not look like user scrubbing. */
    private const val STARTUP_SEEK_WINDOW_MS = 1_250L

    /** Mod: restore deadline = elapsedRealtime() + 3000 (nMXy). */
    private const val RESTORE_DEADLINE_MS = 3000L

    /** A seek followed by FB's tracking stop/start is not a new playback. */
    private const val SCRUB_RESTART_COOLDOWN_MS = 5000L

    private val main = Handler(Looper.getMainLooper())

    /** The hooked methods (mod keeps one callback across all hooked methods). */
    private val hookHandles = ArrayList<XposedInterface.HookHandle>()
    private var lifecycleRegistered = false

    // Resolved-once members of the player class (all structural — see KDoc).
    @Volatile private var startMethod: Method? = null
    @Volatile private var stopMethod: Method? = null
    @Volatile private var paramsMethod: Method? = null
    @Volatile private var positionMethod: Method? = null

    /** Mod: qV2f.s9sEFa2Cpw9OaL6Yf67 — WeakReference to the tracked player. */
    @Volatile private var trackedRef: WeakReference<Any?>? = null

    val trackedPlayer: Any?
        get() = trackedRef?.get()

    /** Mod: x0g6F4hC2D0POfsNAQAh — per-player restore state, attached via
     *  setAdditionalInstanceField; the port keys a WeakHashMap by player. */
    private class RestoreState(val videoId: String) {
        val gate = VideoRestoreGate()
    }

    private val restoreStates = java.util.Collections.synchronizedMap(WeakHashMap<Any, RestoreState>())

    /** One restore per actual video visit, not per stop/start of a pooled Reel. */
    private val restoreVisits = VideoRestoreVisitGate()

    /** Read by BackgroundPlaybackHook so the stale-seek clamp never rewrites
     *  the restore arm's own seek (mod: the kRbFm hook marks nlYTz state).
     *  A counter, not a boolean — restores run on the main handler, but the
     *  read happens on whatever thread fires Dz2. */
    private val restoreInFlightCount = java.util.concurrent.atomic.AtomicInteger(0)

    val restoreInFlight: Boolean
        get() = restoreInFlightCount.get() > 0

    // ------------------------------------------------------------- install

    /**
     * DexKit path: find FbGrootPlayer via the "FbGrootPlayer.pause" quicklog
     * literal, resolve the position getter, and hook the tracking methods.
     *
     * @return the class name when found, for the discovery cache.
     */
    fun install(module: XposedInterface, bridge: DexKitBridge, classLoader: ClassLoader): String? {
        val className = findPlayerClass(bridge) ?: return null
        val positionName = findPositionGetter(bridge, className)
        return if (hookPlayerClass(module, classLoader, className, positionName)) className else null
    }

    /** DexKit lookup only, so ModuleMain can order hook installation. */
    fun findPlayerClass(bridge: DexKitBridge): String? {
        val hits = runCatching {
            bridge.findClass {
                matcher {
                    addUsingString(CLASS_ANCHOR, org.luckypray.dexkit.query.enums.StringMatchType.Equals)
                }
            }
        }.getOrElse {
            L.w(TAG, "DexKit query failed for player controller class", it)
            return null
        }
        val className = hits.firstOrNull()?.name
            ?: run {
                L.w(TAG, "NOT_FOUND player controller class (anchor: $CLASS_ANCHOR)")
                return null
            }
        L.i(TAG, "FOUND player controller: $className (${hits.size} hit(s))")
        return className
    }

    /** Cache-hit path: hook the previously discovered class directly. */
    fun installCached(module: XposedInterface, classLoader: ClassLoader, className: String,
                      positionName: String?): Boolean =
        hookPlayerClass(module, classLoader, className, positionName)

    /**
     * DexKit: the player methods that log the pause quicklog literal — the
     * pause entry point(s). FB 577 splits it into a public one-arg overload
     * and its impl (both log the literal); 576 had a single two-arg method.
     * Hooking every literal user covers both layouts (the mod's
     * multi-candidate pattern).
     */
    fun findPauseMethods(bridge: DexKitBridge, className: String): List<String> = runCatching {
        bridge.findMethod {
            matcher {
                declaredClass(className)
                usingStrings(CLASS_ANCHOR)
            }
        }.map { it.methodName }.distinct()
    }.getOrElse {
        L.w(TAG, "pause-method query failed for $className", it)
        emptyList()
    }

    /**
     * DexKit: the current-position getter on the player class. The seek
     * dispatcher's no-op guard compares the requested position against the
     * engine's live position accessor(s); the player's getter delegates to
     * the same accessor. So: take the ()I engine accessors the dispatcher
     * invokes, then find the player's ()I method that invokes one of them.
     * Ties (several wrapped accessors) break by caller count — the live
     * position is by far the most-read int on the player.
     */
    fun findPositionGetter(bridge: DexKitBridge, className: String): String? = runCatching {
        val dispatcher = bridge.findMethod {
            matcher { usingStrings(SEEK_DISPATCH_ANCHOR) }
        }.firstOrNull() ?: run {
            L.w(TAG, "NOT_FOUND seek dispatcher (anchor: $SEEK_DISPATCH_ANCHOR)")
            return null
        }
        val engineAccessors = dispatcher.invokes
            .filter { it.paramCount == 0 && it.returnTypeName == "int" }
        val candidates = engineAccessors.flatMap { accessor ->
            bridge.findMethod {
                matcher {
                    declaredClass(className)
                    paramCount(0)
                    returnType(java.lang.Integer.TYPE)
                    addInvoke(accessor.descriptor)
                }
            }
        }.distinct()
        when {
            candidates.size == 1 -> candidates.first().methodName
            candidates.isNotEmpty() ->
                candidates.maxByOrNull { it.callers.size }?.methodName
            else -> {
                L.w(TAG, "NOT_FOUND position getter: no player ()I wraps the dispatcher's accessors")
                null
            }
        }?.also { L.i(TAG, "position getter: $className.$it") }
    }.getOrNull()

    /**
     * Hooks the tracking pair (after/before), the seek entry (before) — the
     * mod's yhNqDcnFVKtPAnsZdXH registration — plus the lifecycle save.
     * Public so ModuleMain can install BackgroundPlaybackHook's hooks
     * FIRST (mod: default priority vs the resume hook's 10000).
     *
     * @param positionName the DexKit-resolved position-getter name (null on
     *   the discovery path when resolution failed — resume then degrades to
     *   tracking-only, never to wrong positions).
     */
    fun hookPlayerClass(module: XposedInterface, classLoader: ClassLoader, className: String,
                        positionName: String? = null): Boolean {
        if (hookHandles.isNotEmpty()) return true
        val cls = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrNull() ?: run {
            L.w(TAG, "class resolve failed: $className")
            return false
        }

        // Tracking pair: stable analytics names; the single parameter is the
        // reason enum shared by start/stop/seek/pause.
        val start = cls.declaredMethods.firstOrNull {
            it.name == M_START && it.parameterCount == 1 &&
                !Modifier.isAbstract(it.modifiers)
        }
        val stop = cls.declaredMethods.firstOrNull {
            it.name == M_STOP && it.parameterCount == 1 &&
                !Modifier.isAbstract(it.modifiers)
        }
        val reasonType = start?.parameterTypes?.getOrNull(0)
        if (start == null || stop == null || reasonType == null) {
            L.w(TAG, "tracking pair not found on $className " +
                    "(start=${start != null}, stop=${stop != null})")
        }

        // Seek: the unique (reason, int) → void method — seekTo(reason, ms).
        val seekCandidates = cls.declaredMethods.filter {
            it.parameterCount == 2 && reasonType == it.parameterTypes[0] &&
                it.parameterTypes[1] == java.lang.Integer.TYPE &&
                it.returnType == java.lang.Void.TYPE &&
                !Modifier.isAbstract(it.modifiers)
        }
        if (seekCandidates.size > 1) {
            L.w(TAG, "seek shape ambiguous on $className: " +
                    seekCandidates.joinToString { it.name })
        }
        val seek = seekCandidates.firstOrNull()

        // Params getter: the unique zero-arg method returning the stable
        // params class.
        val paramsCls = runCatching {
            Class.forName(PARAMS_CLASS, false, classLoader)
        }.getOrNull()
        val paramsCandidates = paramsCls?.let { pc ->
            cls.declaredMethods.filter {
                it.parameterCount == 0 && it.returnType == pc &&
                    !Modifier.isAbstract(it.modifiers)
            }
        }.orEmpty()
        if (paramsCandidates.size > 1) {
            L.w(TAG, "params getter ambiguous on $className: " +
                    paramsCandidates.joinToString { it.name })
        }
        val params = paramsCandidates.firstOrNull()?.also { it.isAccessible = true }

        // Position getter: reflection cannot see call targets, so its name
        // comes from DexKit (cached per version).
        val position = positionName?.let { resolveMethod(cls, it) }

        startMethod = start
        stopMethod = stop
        paramsMethod = params
        positionMethod = position
        seekMethod = seek

        var installed = 0
        for (m in listOfNotNull(start, stop, seek)) {
            runCatching {
                m.isAccessible = true
                hookHandles.add(module.hook(m).intercept(TrackHooker))
                installed++
            }.onFailure { L.w(TAG, "hook failed on ${cls.name}.${m.name}", it) }
        }
        if (installed < 3 || params == null || position == null) {
            L.w(TAG, "player member resolution incomplete on $className " +
                    "(start=${start != null}, stop=${stop != null}, seek=${seek != null}, " +
                    "params=${params != null}, position=${position != null})")
        }

        L.i(TAG, "video-resume hooks installed on ${cls.name}: $installed method(s) " +
                "(mod log: '[FacebookVideoResumeHook] - [VideoResume.txt] INIT_OK')")
        return installed > 0
    }

    /**
     * Registers the lifecycle save (mod: hookAllMethods(Activity, "onPause"/
     * "onStop") → dEdavZ8qV5oQ4Stxy4cL → qV2f.FgOJykHvmRjW8PKecX8).
     * Call once from ModuleMain with the FB application context.
     */
    fun init(context: Context) {
        ResumeStore.init(context)
        if (lifecycleRegistered) return
        val app = context as? Application ?: context.applicationContext as? Application
        if (app == null) {
            L.w(TAG, "no Application context — lifecycle save disabled")
            return
        }
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityPaused(activity: android.app.Activity) =
                    onLifecycleSave("ON_PAUSE")

                override fun onActivityStopped(activity: android.app.Activity) =
                    onLifecycleSave("ON_STOP")

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityStarted(activity: android.app.Activity) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
            lifecycleRegistered = true
            L.i(TAG, "lifecycle save registered (mod: ON_PAUSE/ON_STOP → FgOJ)")
        }.onFailure { L.w(TAG, "lifecycle registration failed", it) }
    }

    // ------------------------------------------------------------ extractor

    /** One observation of the tracked player (mod: UVFD3BoJI5sq4SwfZZu0). */
    class VideoInfo(val videoId: String, val paramInt: Int, val positionMs: Int)

    /**
     * Reads only the player's media id. The contextual downloader needs this
     * even when resume-position is disabled, so it must not depend on the
     * position getter or the resume preference.
     */
    fun extractVideoId(player: Any?): String? {
        val pm = paramsMethod ?: return null
        if (player == null) return null
        return runCatching {
            val params = pm.invoke(player) ?: return null
            params.toString().removePrefix(VIDEO_ID_MARKER)
                .takeIf { it.isNotEmpty() && it != "null" }
        }.getOrNull()
    }

    /**
     * JAvH7JcvC2m10XLQTVY port: params getter → toString() → videoId,
     * position getter → current position. Returns null when any member is
     * missing or the videoId is empty — callers treat that as "not a tracked
     * video". The videoId comes from VideoPlayerParams.toString() ("VideoId:
     * <id>") — the String field itself rotates per build.
     */
    fun extractInfo(player: Any?): VideoInfo? {
        val pos = positionMethod ?: return null
        val videoId = extractVideoId(player) ?: return null
        return runCatching {
            VideoInfo(videoId, 0, (pos.invoke(player) as Number).toInt())
        }.getOrNull()
    }

    // ---------------------------------------------------------------- hooks

    /**
     * One Hooker, three roles (the mod used three callback classes; the
     * libxposed port dispatches on the resolved method — names are dynamic):
     *  - maybeTrackVideoStart: proceed, then track + arm the restore;
     *  - maybeTrackVideoStop: save, then proceed (before-hook semantics);
     *  - seek: observe FB's own seek, then proceed.
     */
    private object TrackHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val method = chain.executable as? Method
            return when (method) {
                startMethod -> {
                    val result = chain.proceed()
                    runCatching { onVideoStart(chain.thisObject, chain.args.getOrNull(0)) }
                        .onFailure { L.w(TAG, "start tracking failed", it) }
                    result
                }
                stopMethod -> {
                    runCatching { onVideoStop(chain.thisObject) }
                        .onFailure { L.w(TAG, "stop tracking failed", it) }
                    chain.proceed()
                }
                seekMethod -> {
                    // Cancel the pending jump BEFORE Facebook processes the
                    // user's seek: seeking can synchronously issue another
                    // maybeTrackVideoStart and must not re-arm restoration.
                    val observation = runCatching { onPlayerSeek(
                        chain.thisObject, chain.args.getOrNull(0), chain.args.getOrNull(1)
                    ) }
                        .onFailure { L.w(TAG, "seek observation failed", it) }
                        .getOrNull()
                    val result = chain.proceed()
                    // Don't record a seek as saved progress if FB rejected it.
                    observation?.let { (videoId, requestedMs) ->
                        restoreStates[chain.thisObject]?.takeIf { it.videoId == videoId }
                            ?.gate?.recordAppliedSeek(requestedMs, SystemClock.elapsedRealtime())
                        if (requestedMs >= 1000) ResumeStore.put(videoId, requestedMs.toLong())
                        else ResumeStore.remove(videoId)
                    }
                    result
                }
                else -> chain.proceed()
            }
        }
    }

    /** bsrnc after-hook (maybeTrackVideoStart) + nMXy/QnHm arms. */
    private fun onVideoStart(player: Any?, reason: Any?) {
        if (player == null) return
        DownloadHook.onPlaybackStarted(extractVideoId(player))
        // BackgroundPlaybackHook and the contextual downloader need current
        // player tracking even when resume itself defaults OFF.
        trackedRef = WeakReference(player)
        if (!Settings.getBoolean(Settings.VIDEO_RESUME, false)) return
        val info = extractInfo(player) ?: return
        val now = SystemClock.elapsedRealtime()
        restoreVisits.observeStart(info.videoId, now)
        val (state, newSession) = synchronized(restoreStates) {
            val previous = restoreStates[player]
            if (previous != null && previous.videoId == info.videoId &&
                !previous.gate.mayBeginNewSession(now, SCRUB_RESTART_COOLDOWN_MS)) {
                previous to false
            } else {
                RestoreState(info.videoId).also { restoreStates[player] = it } to true
            }
        }
        if (!newSession) {
            L.i(TAG, "start reused state for ${info.videoId} " +
                    "(reason=$reason savedMs=${ResumeStore.get(info.videoId)?.positionMs} " +
                    "currentMs=${info.positionMs})")
            return // Rebuffer/repeated start, not a new video.
        }

        // QnHm store-refresh: the same video restarted within 5 s keeps its
        // entry (re-save with a fresh timestamp) instead of resetting it.
        if (ResumeStore.isRecent(info.videoId)) {
            ResumeStore.touch(info.videoId)
        }

        // nMXy arm: saved position ≥ 1001 ms → arm target + deadline, post
        // the restore decision runnable after 900 ms.
        val target = ResumeStore.get(info.videoId)?.positionMs
        if (!state.gate.armOnce(target, now, RESTORE_DEADLINE_MS)) {
            L.i(TAG, "start has no saved point for ${info.videoId} (reason=$reason)")
            return
        }
        val isShort = target != null && target <= SHORT_RESTORE_LIMIT_MS
        val delayMs = if (isShort) SHORT_RESTORE_DELAY_MS else RESTORE_DELAY_MS
        main.postDelayed(RestoreRunnable(player, state, reason, info.videoId, isShort), delayMs)
        L.i(TAG, "armed restore for ${info.videoId} at ${target}ms " +
                "(delay=${delayMs}ms, deadline +${RESTORE_DEADLINE_MS}ms)")
    }

    /** Hk8o before-hook (maybeTrackVideoStop): tracked == this → save. */
    private fun onVideoStop(player: Any?) {
        DownloadHook.onPlaybackStopped(extractVideoId(player))
        // Pooled Reels may stop a player after tracking moved to another one.
        // Its pending restore still must be invalidated, even if we must not
        // save the off-screen player as the current video.
        player?.let { restoreStates[it] }?.gate?.markStopped()
        if (trackedRef?.get() !== player) return
        // Always stop tracking, even with resume OFF, to avoid keeping stale
        // BackgroundPlaybackHook state when the user switches videos.
        trackedRef = null
        if (!Settings.getBoolean(Settings.VIDEO_RESUME, false)) return
        val state = player?.let { restoreStates[it] }
        val info = extractInfo(player) ?: return
        val savedMs = state?.gate?.positionToSave(
            info.positionMs, SystemClock.elapsedRealtime()) ?: info.positionMs
        // qsJG/EQRQ clamp: never save a position under 1000 ms.
        if (savedMs >= 1000) {
            ResumeStore.put(info.videoId, savedMs.toLong())
            L.i(TAG, "saved ${info.videoId} at ${savedMs}ms (mod log: stop-hook)")
        }
    }

    /**
     * kRbFm before-hook (Dz2, prio 10000): FB issued its own seek. The mod
     * marked the background-playback state (SetBoolean + cleared two longs)
     * so the two features don't fight; the port disarms the restore state
     * and records the sought position so the next save uses it.
     */
    private fun onPlayerSeek(player: Any?, reason: Any?, requested: Any?): Pair<String, Int>? {
        if (!Settings.getBoolean(Settings.VIDEO_RESUME, false)) return null
        val state = player?.let { restoreStates[it] } ?: return null
        val requestedMs = (requested as? Number)?.toInt() ?: return null
        val now = SystemClock.elapsedRealtime()
        val reasonName = (reason as? Enum<*>)?.name ?: reason?.toString()
        val currentMs = runCatching { (positionMethod?.invoke(player) as? Number)?.toInt() }.getOrNull()
        if (!VideoSeekPolicy.shouldObserveAsUserSeek(
                reasonName, requestedMs, currentMs,
                state.gate.wasJustStarted(now, STARTUP_SEEK_WINDOW_MS))) {
            // FB580's Shorts viewer pauses an offscreen Reel and issues
            // EeY(BY_SHORT_FORM_VIDEO_INVISIBLE, 0). Its position must be
            // recorded *before* that internal reset. Never confuse it with
            // the user's deliberate rewind-to-zero, which clears the store.
            if (VideoSeekPolicy.isAutomaticReset(reasonName, requestedMs, currentMs) &&
                extractVideoId(player) == state.videoId && currentMs != null) {
                val savedMs = state.gate.positionToSave(currentMs, now)
                if (savedMs >= 1000) ResumeStore.put(state.videoId, savedMs.toLong())
                state.gate.recordAutomaticReset(savedMs, now)
            }
            L.i(TAG, "ignored FB internal seek for ${state.videoId}: reason=$reasonName " +
                    "requestedMs=$requestedMs currentMs=$currentMs")
            return null
        }
        if (!state.gate.cancelForSeek(now)) return null
        L.i(TAG, "user/FB seek canceled pending restore for ${state.videoId}: " +
                "reason=$reasonName requestedMs=$requestedMs currentMs=$currentMs")
        return state.videoId to requestedMs
    }

    /** FgOJykHvmRjW8PKecX8 port: lifecycle save with the 1000 ms clamp. */
    private fun onLifecycleSave(event: String) {
        if (!Settings.getBoolean(Settings.VIDEO_RESUME, false)) return
        val player = trackedRef?.get() ?: return
        runCatching {
            val info = extractInfo(player) ?: return
            val savedMs = restoreStates[player]?.gate?.positionToSave(
                info.positionMs, SystemClock.elapsedRealtime()) ?: info.positionMs
            if (savedMs >= 1000) {
                ResumeStore.put(info.videoId, savedMs.toLong())
                L.i(TAG, "SAVE_$event ${info.videoId} at ${savedMs}ms")
            }
        }.onFailure { L.w(TAG, "SAVE_${event}_FAILED", it) }
    }

    // -------------------------------------------------------- restore arm

    /**
     * wi6Jf1Qzw1hmohzCl98E port — the restore decision runnable posted by
     * nMXy. Validates (videoId match, target windows, deadline) then runs
     * the Rn0 seek executor.
     */
    private class RestoreRunnable(
        private val player: Any,
        private val state: RestoreState,
        private val reason: Any?,
        private val videoId: String,
        private val shortPosition: Boolean,
    ) : Runnable {
        override fun run() {
            try {
                if (!Settings.getBoolean(Settings.VIDEO_RESUME, false)) return
                if (restoreStates[player] !== state || trackedRef?.get() !== player) {
                    L.i(TAG, "restore skipped $videoId: player/state no longer current")
                    return
                }
                val info = extractInfo(player) ?: run {
                    L.i(TAG, "restore skipped $videoId: no video info")
                    return
                }
                // Step 3: the video on screen must still be the armed one.
                if (info.videoId != videoId) {
                    L.i(TAG, "restore skipped $videoId: player now has ${info.videoId}")
                    return
                }
                val now = SystemClock.elapsedRealtime()
                if (!restoreVisits.mayRestore(videoId, now)) {
                    L.i(TAG, "restore skipped $videoId: already restored this visit")
                    return
                }
                val target = state.gate.consumeIfValid(
                    now, info.positionMs,
                    if (shortPosition) SHORT_MIN_ADVANCE_MS else 1000L) ?: run {
                    L.i(TAG, "restore skipped $videoId: gate rejected at ${info.positionMs}ms")
                    return
                }

                // Rn0LbcxLisWuSI9YThk: the seek executor.
                restoreInFlightCount.incrementAndGet()
                try {
                    val seek = seekMethodFor(player) ?: return
                    if (!restoreVisits.markRestore(videoId, SystemClock.elapsedRealtime())) {
                        L.i(TAG, "restore skipped $videoId: visit already consumed")
                        return
                    }
                    seek.invoke(player, reason, target.toInt())
                    L.i(TAG, "restored $videoId to ${target}ms (one-shot)")
                } catch (t: Throwable) {
                    L.w(TAG, "restore seek failed for $videoId", t)
                } finally {
                    restoreInFlightCount.decrementAndGet()
                    state.gate.finishRestore()
                }
            } catch (t: Throwable) {
                L.w(TAG, "restore decision failed for $videoId", t)
            }
        }
    }

    @Volatile private var seekMethod: Method? = null

    /** The resolved Dz2(C2EZ, int) — set by hookPlayerClass. */
    fun seekMethodFor(@Suppress("UNUSED_PARAMETER") player: Any): Method? = seekMethod

    // ---------------------------------------------------------------- utils

    private fun resolveMethod(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val cur = c
            cur.declaredMethods.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it
            }
            c = cur.superclass
        }
        return null
    }
}
