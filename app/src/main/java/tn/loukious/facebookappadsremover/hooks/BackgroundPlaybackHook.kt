package tn.loukious.facebookappadsremover.hooks

import android.app.Application
import android.content.Context
import android.os.SystemClock
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background video playback — port of the original mod's
 * `X.nlYTzRDfkAYF5jojqBcx` (FacebookBackgroundPlaybackHook, log tag
 * `BackgroundPlayback.txt`; RE notes: tools/extract/playback_trio_notes.md
 * §3).
 *
 * Decoded mod behaviour (session 15 + notes):
 *
 *  - A1Z (pause) before-hook @1282778: always records the pausing player +
 *    timestamp, then — gated on ThreadLocal reentrancy, the process gate
 *    (XMACw.yhNq()), no floating player active, and nlYTz's armed flag —
 *    calls setResult(null) to BLOCK the pause, arms the stale-seek state
 *    (videoId + targetMs + freshness longs) and preps the floating overlay
 *    (kRbFm.z0bnz…).
 *  - Dz2 (seek) before-hook @1281626, the stale-seek guard: if the armed
 *    state is fresh (deadline ≥ now AND now − lastTouch ≤ 1600 ms), the
 *    stored videoId matches, and |requestedMs − targetMs| ≥ 1201 ms →
 *    args[1] = min(Int.MAX, targetMs); log RETURN_SEEK_STALE_BLOCKED.
 *
 * Deliberate deviations (documented, not invented):
 *  - The floating overlay (X.kRbFm / X.sAbU6 player) is deferred — see the
 *    feature matrix, row "Floating video / PiP". Without it, the feature is
 *    "audio keeps playing when you leave the app": the port blocks pauses
 *    issued while the app is backgrounded.
 *  - The mod's exact block trigger crossed the overlay machinery (args[0]
 *    enum compare + different-player/isPlaying branch, partially decoded).
 *    The port blocks a backgrounded pause of the tracked player unless the
 *    reason is BY_MANUAL_CLICK_PAUSE (enum constant A1o — the mod's cleanup
 *    branch for manual pauses); manual clicks cannot occur while
 *    backgrounded anyway, so the check only protects media-session pauses.
 *  - The process gate (XMACw.yhNq) is a started-activity counter
 *    (ProcessLifecycleOwner semantics) instead of the mod's static —
 *    extended with a pause-window heuristic: FB's exit pause arrives during
 *    the activity's onPause, before onStop can zero the counter, so a pause
 *    landing within EXIT_PAUSE_WINDOW_MS of the last activity pause also
 *    counts as backgrounding. In-app navigation produces the same pause, so
 *    a blocked pause is re-issued after REPAUSE_DELAY_MS when the process
 *    turns out to be foreground (RePauseRunnable).
 *  - libxposed args are immutable — the clamp rewrites via
 *    chain.proceed(newArgs), which forwards to the remaining interceptors
 *    (the resume observer sees the clamped value, as in the mod's priority
 *    ordering: default-priority clamp runs before the prio-10000 observer).
 */
object BackgroundPlaybackHook {

    private const val TAG = "FBAR.Background"

    /** Cache key — sentinel only ("cached"/"absent"); the class comes from
     *  VideoResumeHook's discovery of the same player controller. */
    const val CACHE_KEY = "media.video.background"

    /** Cache key — the DexKit-resolved pause-method names (comma-joined). */
    const val PAUSE_CACHE_KEY = "media.video.pause"

    /** BY_MANUAL_CLICK_PAUSE — the reason enum's stable constant NAME; the
     *  obfuscated field name rotates per release, Enum.name never does. */
    private const val MANUAL_PAUSE_ENUM_NAME = "BY_MANUAL_CLICK_PAUSE"

    /** Mod: |requestedMs − targetMs| ≥ 1201 → clamp (nlYTz$Hk8o). */
    private const val STALE_SEEK_DEVIATION_MS = 1201L

    /** Mod: now − lastTouch ≤ 1600 ms freshness window (nlYTz$Hk8o). */
    private const val ARM_FRESHNESS_MS = 1600L

    /**
     * FB pauses the video DURING the activity's onPause — before
     * onActivityStopped decrements the activity counter (verified on-device:
     * pause lands ~2 ms after activity-pause, counter reaches 0 ~90 ms
     * later). The block gate therefore accepts a pause that lands within
     * this window of the last activity pause as "backgrounding".
     */
    private const val EXIT_PAUSE_WINDOW_MS = 500L

    /**
     * A block can be a false positive: FB also pauses during in-app
     * navigation (old activity pauses before the new one starts). Wait this
     * long; if an activity is started again (in-app nav or a quick return),
     * re-issue the pause that was blocked.
     */
    private const val REPAUSE_DELAY_MS = 300L

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val hookHandles = ArrayList<XposedInterface.HookHandle>()

    /** nlYTz.JAvH — WeakReference to the last player that hit A1Z. */
    @Volatile private var lastPauseRef: WeakReference<Any?>? = null

    /** nlYTz.Rn0 — when that pause happened (elapsedRealtime). */
    @Volatile private var lastPauseAt = 0L

    /** nlYTz.W3sHf — the armed stale-seek state set when a pause is blocked. */
    @Volatile private var armedVideoId: String? = null
    @Volatile private var armedTargetMs = 0L
    @Volatile private var armedAt = 0L

    /** XMACw.yhNq port — process-foreground gate via a started-activity
     *  counter (0 started activities = backgrounded), plus the timestamp of
     *  the last activity pause (see EXIT_PAUSE_WINDOW_MS — FB's exit pause
     *  arrives before the counter can reach 0). */
    private val startedActivities = AtomicInteger(0)
    @Volatile private var lastActivityPausedAt = 0L

    @Volatile private var manualPauseConstant: Any? = null
    @Volatile private var manualPauseResolved = false

    /** The hooked pause + seek methods (identity-dispatched — names rotate). */
    @Volatile private var pauseMethods: List<Method> = emptyList()
    @Volatile private var seekMethod: Method? = null

    // ------------------------------------------------------------- install

    /**
     * Hooks the pause entry point(s) and the seek entry on the player
     * controller found by VideoResumeHook, plus the foreground tracker.
     * Install BEFORE VideoResumeHook.hookPlayerClass so the clamp runs
     * before the resume observer (mod: default priority vs 10000).
     *
     * All members resolve structurally (see VideoResumeHook's KDoc):
     *  - pause: the DexKit-resolved names of the methods logging the
     *    "FbGrootPlayer.pause" literal — 577 splits it into a public overload
     *    and its impl; hooking every literal user covers both layouts (the
     *    mod's multi-candidate pattern);
     *  - seek: the unique (reason, int) → void method — same shape rule as
     *    the resume hook's seek;
     *  - manual-pause constant: the reason enum constant whose stable
     *    Enum.name is BY_MANUAL_CLICK_PAUSE.
     *
     * @param pauseNames DexKit-resolved pause-method names (empty when that
     *   query failed — the feature then degrades to seek-clamp only).
     * @return true when at least one hook installed.
     */
    fun install(module: XposedInterface, classLoader: ClassLoader,
                className: String, context: Context,
                pauseNames: List<String> = emptyList()): Boolean {
        if (hookHandles.isNotEmpty()) return true
        registerForegroundTracker(context)
        val cls = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrNull() ?: run {
            L.w(TAG, "class resolve failed: $className")
            return false
        }

        // Pause entry points — every resolved literal user, non-abstract.
        val pauses = cls.declaredMethods.filter {
            it.name in pauseNames && !Modifier.isAbstract(it.modifiers)
        }
        if (pauses.isEmpty()) {
            L.w(TAG, "pause methods not found on $className (resolved names: $pauseNames)")
        }
        // The reason enum is the pauses' first parameter (and the seek's).
        val reasonType = pauses.firstOrNull()?.parameterTypes?.getOrNull(0)

        // seekTo(reason, positionMs) — the unique (reason, int) → void shape.
        val seek = reasonType?.let { rt ->
            val seekCandidates = cls.declaredMethods.filter {
                it.parameterCount == 2 && it.parameterTypes[0] == rt &&
                    it.parameterTypes[1] == java.lang.Integer.TYPE &&
                    it.returnType == java.lang.Void.TYPE &&
                    !Modifier.isAbstract(it.modifiers)
            }
            if (seekCandidates.size > 1) {
                L.w(TAG, "seek shape ambiguous on $className: " +
                        seekCandidates.joinToString { it.name })
            }
            seekCandidates.firstOrNull()
        }

        // BY_MANUAL_CLICK_PAUSE constant, for the manual-pause cleanup branch.
        reasonType?.let { enumClass ->
            runCatching {
                manualPauseConstant = enumClass.enumConstants
                    .firstOrNull { (it as? Enum<*>)?.name == MANUAL_PAUSE_ENUM_NAME }
            }.onFailure { L.w(TAG, "manual-pause constant resolve failed", it) }
            manualPauseResolved = true
        }

        pauseMethods = pauses
        seekMethod = seek

        var installed = 0
        for (m in pauses + listOfNotNull(seek)) {
            runCatching {
                m.isAccessible = true
                hookHandles.add(module.hook(m).intercept(PlaybackHooker))
                installed++
            }.onFailure { L.w(TAG, "hook failed on ${cls.name}.${m.name}", it) }
        }
        if (pauses.isEmpty() || seek == null || manualPauseConstant == null) {
            L.w(TAG, "member resolution incomplete on $className " +
                    "(pause=${pauses.size}, seek=${seek != null}, " +
                    "manualPause=${manualPauseConstant != null})")
        }
        L.i(TAG, "background-playback hooks installed on ${cls.name}: $installed method(s) " +
                "(mod log: '[FacebookBackgroundPlaybackHook] - [BackgroundPlayback.txt] INIT_OK')")
        return installed > 0
    }

    /** Cache-hit path — same install, class name + pause names from cache. */
    fun installCached(module: XposedInterface, classLoader: ClassLoader,
                      className: String, context: Context,
                      pauseNames: List<String> = emptyList()): Boolean =
        install(module, classLoader, className, context, pauseNames)

    private fun registerForegroundTracker(context: Context) {
        val app = context as? Application ?: context.applicationContext as? Application
        if (app == null) {
            L.w(TAG, "no Application context — foreground tracker disabled")
            return
        }
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    // A starting activity means in-app navigation or a return
                    // to the app — any pending pause window is over.
                    lastActivityPausedAt = 0L
                    startedActivities.incrementAndGet()
                }

                override fun onActivityPaused(activity: android.app.Activity) {
                    lastActivityPausedAt = SystemClock.elapsedRealtime()
                    L.i(TAG, "activity paused — startedActivities=${startedActivities.get()}")
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    if (startedActivities.decrementAndGet() < 0) startedActivities.set(0)
                    L.i(TAG, "activity stopped — startedActivities=${startedActivities.get()}")
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            })
        }.onFailure { L.w(TAG, "foreground tracker registration failed", it) }
    }

    // ---------------------------------------------------------------- hooks

    /**
     * Both hooks in one Hooker, dispatched on method identity (names rotate
     * per release):
     *  - pause: the pause block (nlYTz$bsrnc);
     *  - seek: the stale-seek clamp (nlYTz$Hk8o).
     */
    private object PlaybackHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val method = chain.executable as? Method
            return when {
                pauseMethods.any { it === method } -> onPause(chain)
                method === seekMethod -> onSeek(chain)
                else -> chain.proceed()
            }
        }
    }

    /** nlYTz$bsrnc port — pause block. */
    private fun onPause(chain: XposedInterface.Chain): Any? {
        // Always record the pause target + timestamp (mod does this
        // unconditionally before the guards).
        val player = chain.thisObject
        lastPauseRef = WeakReference(player)
        lastPauseAt = SystemClock.elapsedRealtime()

        if (Settings.getBoolean(Settings.VIDEO_BACKGROUND, false)) {
            // Gate-state trace: pause timing vs the activity-stop counter is
            // the fragile half of this feature (FB pauses during the
            // activity's onPause, potentially before onActivityStopped).
            L.i(TAG, "pause observed: reason=${chain.args.getOrNull(0)} " +
                    "tracked=${VideoResumeHook.trackedPlayer === player} " +
                    "startedActivities=${startedActivities.get()} " +
                    "manual=${isManualPause(chain.args.getOrNull(0))}")
        }

        // "Backgrounded" = no started activities, or a pause landing inside
        // the activity-pause window (FB's exit pause arrives before onStop —
        // see EXIT_PAUSE_WINDOW_MS).
        val now = SystemClock.elapsedRealtime()
        val backgrounded = startedActivities.get() == 0 ||
            (lastActivityPausedAt != 0L && now - lastActivityPausedAt <= EXIT_PAUSE_WINDOW_MS)

        if (Settings.getBoolean(Settings.VIDEO_BACKGROUND, false) &&
            backgrounded &&
            player != null &&
            VideoResumeHook.trackedPlayer === player &&  // the tracked video
            !isManualPause(chain.args.getOrNull(0))
        ) {
            // Arm the stale-seek state from the current position, then block
            // the pause (mod: setResult(null) + overlay prep — overlay
            // deferred; see class KDoc).
            val info = VideoResumeHook.extractInfo(player)
            if (info != null) {
                armedVideoId = info.videoId
                armedTargetMs = info.positionMs.toLong()
                armedAt = SystemClock.elapsedRealtime()
                // False-positive correction: if an activity starts again
                // (in-app navigation), re-issue this pause after the window.
                (chain.executable as? Method)?.let { m ->
                    main.postDelayed(
                        RePauseRunnable(m, player, chain.args.getOrNull(0)), REPAUSE_DELAY_MS)
                }
                L.i(TAG, "BACKGROUND_PAUSE_BLOCKED\nreason=${chain.args.getOrNull(0)} " +
                        "videoId=${info.videoId} positionMs=${info.positionMs}")
                return null  // the pause entries are void — setResult(null) equivalent
            }
        }
        return chain.proceed()
    }

    /**
     * The blocked pause may have been an in-app navigation (old activity
     * pauses before the new one starts, identical to an exit at pause time).
     * If the process is foreground again when this runs, the block was a
     * false positive — invoke the original pause. The hook re-evaluates the
     * gate on that invocation (foreground → proceeds), so no reentrancy
     * guard is needed.
     */
    private class RePauseRunnable(
        private val method: Method,
        private val player: Any,
        private val reason: Any?,
    ) : Runnable {
        override fun run() {
            if (startedActivities.get() == 0) return  // real exit — keep playing
            runCatching { method.invoke(player, reason) }
                .onFailure { L.w(TAG, "re-pause failed", it) }
                .onSuccess { L.i(TAG, "re-paused after in-app navigation (reason=$reason)") }
        }
    }

    /** nlYTz$Hk8o port — stale-seek clamp. */
    private fun onSeek(chain: XposedInterface.Chain): Any? {
        val requestedMs = (chain.args.getOrNull(1) as? Number)?.toInt() ?: return chain.proceed()
        if (!Settings.getBoolean(Settings.VIDEO_BACKGROUND, false)) return chain.proceed()
        // The stale-position clamp was intended for the background transition,
        // not for user scrubbing after Facebook becomes interactive again.
        // An old arm within its 1600-ms window otherwise rewrites a backward
        // seek and visibly snaps the user toward the previous video position.
        if (startedActivities.get() > 0) {
            armedVideoId = null
            armedAt = 0L
            return chain.proceed()
        }
        // Never rewrite the restore arm's own seek (mod: the kRbFm hook marks
        // nlYTz state so the clamp stands down).
        if (VideoResumeHook.restoreInFlight) return chain.proceed()

        val videoId = armedVideoId ?: return chain.proceed()
        val now = SystemClock.elapsedRealtime()
        if (now - armedAt > ARM_FRESHNESS_MS) return chain.proceed()

        val info = VideoResumeHook.extractInfo(chain.thisObject) ?: return chain.proceed()
        if (info.videoId != videoId) return chain.proceed()

        val deviation = kotlin.math.abs(requestedMs.toLong() - armedTargetMs)
        if (deviation >= STALE_SEEK_DEVIATION_MS) {
            val clamped = kotlin.math.min(Int.MAX_VALUE.toLong(), armedTargetMs).toInt()
            L.i(TAG, "RETURN_SEEK_STALE_BLOCKED requestedMs=$requestedMs " +
                    "targetMs=$clamped videoId=$videoId")
            // libxposed args are immutable — rewrite via proceed(newArgs).
            val reason = chain.args.getOrNull(0)
            return chain.proceed(arrayOf(reason, clamped))
        }
        return chain.proceed()
    }

    /**
     * The mod's cleanup branch: BY_MANUAL_CLICK_PAUSE pauses are never
     * blocked. When the constant can't be resolved the check fails open
     * (block) — manual clicks can't arrive while the app is backgrounded,
     * which the outer gate already requires.
     */
    private fun isManualPause(reason: Any?): Boolean {
        if (!manualPauseResolved || manualPauseConstant == null) return false
        return reason === manualPauseConstant
    }
}
