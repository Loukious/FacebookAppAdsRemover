package tn.loukious.facebookappadsremover

import android.app.Application
import android.content.Context
import tn.loukious.facebookappadsremover.core.AdTargets
import tn.loukious.facebookappadsremover.core.Discovery
import tn.loukious.facebookappadsremover.core.L
import tn.loukious.facebookappadsremover.core.MethodCache
import tn.loukious.facebookappadsremover.core.ProbeTargets
import tn.loukious.facebookappadsremover.core.Settings
import tn.loukious.facebookappadsremover.core.SessionBackup
import tn.loukious.facebookappadsremover.hooks.AccountHook
import tn.loukious.facebookappadsremover.hooks.ActivityListHook
import tn.loukious.facebookappadsremover.hooks.AdFilterHook
import tn.loukious.facebookappadsremover.hooks.AdsOptOutHook
import tn.loukious.facebookappadsremover.hooks.AmoledThemeHook
import tn.loukious.facebookappadsremover.hooks.AutoRefreshHook
import tn.loukious.facebookappadsremover.hooks.BackgroundPlaybackHook
import tn.loukious.facebookappadsremover.hooks.CleanUrlHook
import tn.loukious.facebookappadsremover.hooks.ContextualDownloadHook
import tn.loukious.facebookappadsremover.hooks.DownloadHook
import tn.loukious.facebookappadsremover.hooks.DarkModeHook
import tn.loukious.facebookappadsremover.hooks.FeedGuardHook
import tn.loukious.facebookappadsremover.hooks.GameAdsHook
import tn.loukious.facebookappadsremover.hooks.HideSeenStoryHook
import tn.loukious.facebookappadsremover.hooks.MarketplaceAdsHook
import tn.loukious.facebookappadsremover.hooks.NewsfeedFilterHook
import tn.loukious.facebookappadsremover.hooks.NavigationTabHook
import tn.loukious.facebookappadsremover.hooks.PrivacyHook
import tn.loukious.facebookappadsremover.hooks.StoryFeedViewerHook
import tn.loukious.facebookappadsremover.hooks.StoriesTrayHook
import tn.loukious.facebookappadsremover.hooks.VideoResumeHook
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method

/**
 * Modern (libxposed API) module entry point.
 *
 * Rewrite of the original mod as a standalone LSPosed module targeting stock
 * com.facebook.katana.
 *
 * Facebook ships nearly all of its code in secondary dex archives that are
 * superpack-compressed inside the APK and only extracted/loaded at runtime
 * (data/dex/z-*.zip → split classloaders). Consequences:
 *
 *  1. onPackageReady fires BEFORE the secondary dex classloaders exist, so
 *     nothing beyond the bootstrap dex is hookable there.
 *  2. The secondary dex classes keep their real com.facebook.* names (only
 *     the bootstrap dex is name-obfuscated), so once the app is created we
 *     can resolve stable classes directly.
 *
 * Therefore: hook FacebookApplication.onCreate (its concrete class name
 * survives in the bootstrap dex) and run discovery after it completes.
 */
class ModuleMain : XposedModule() {

    companion object {
        const val TAG = "FacebookAppAdsRemover"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"
        private const val APP_CLASS = "com.facebook.katana.app.FacebookApplication"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        L.module = this
        L.i(TAG, "Module loaded in process: ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        L.i(TAG, "Package loaded: ${param.packageName} (first=${param.isFirstPackage})")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        if (param.packageName != FACEBOOK_PACKAGE) return

        L.i(TAG, "Facebook ready — hooking app onCreate")

        val appClass = runCatching {
            Class.forName(APP_CLASS, false, param.classLoader)
        }.getOrNull()

        if (appClass == null) {
            L.e(TAG, "$APP_CLASS not found in bootstrap classloader — aborting")
            return
        }

        // onCreate is declared on the abstract parent (NonBlockingApp); the
        // concrete class inherits it. Walk up until we find the declaration.
        var cls: Class<*> = appClass
        var onCreate: Method? = null
        while (cls != Any::class.java) {
            onCreate = runCatching {
                cls.getDeclaredMethod("onCreate")
            }.getOrNull()
            if (onCreate != null) break
            cls = cls.superclass ?: break
        }

        if (onCreate == null) {
            L.e(TAG, "No onCreate declaration found on $APP_CLASS hierarchy — aborting")
            return
        }

        hook(onCreate).intercept(AppCreatedHooker)
        L.i(TAG, "Hooked ${onCreate.declaringClass.name}.onCreate")
    }

    /** Runs after FacebookApplication.onCreate: the secondary dexes are loaded. */
    object AppCreatedHooker : Hooker {
        override fun intercept(chain: XposedInterface.Chain): Any? {
            val result = chain.proceed()
            val module = L.module
            val app = chain.thisObject
            if (module != null && app is Context) {
                try {
                    module.onAppCreated(app)
                } catch (t: Throwable) {
                    L.e(TAG, "onAppCreated failed", t)
                }
            }
            return result
        }
    }

    internal fun onAppCreated(context: Context) {
        L.i(TAG, "App onCreate completed — running discovery")

        // Toggle source FIRST: the settings UI (module app) writes remote
        // preferences; every hook reads them through core.Settings.
        runCatching { Settings.init(this, context) }
            .onFailure { L.e(TAG, "Settings init failed — toggles fall back to defaults", it) }

        // Morphe-style AMOLED theme. Framework seams are available as soon as
        // the app process exists; Facebook's own colour resolvers arrive with
        // the secondary dexes and are attached by the retry probe below.
        runCatching { AmoledThemeHook.installFramework(this) }
            .onFailure { L.e(TAG, "AMOLED framework hooks failed", it) }

        try {
            System.loadLibrary("dexkit")
        } catch (t: Throwable) {
            L.e(TAG, "Failed to load dexkit native library", t)
        }

        probeStableClasses(context.classLoader)

        // M3: account capture needs no DexKit (ViewerContext is a stable
        // com.facebook.* class), but it only resolves once the secondary
        // dexes attach — so it rides the same retry cadence as the probe.
        AccountHook.init(context)

        // M4: media download — the quick-link overlay + activity tracker need
        // only the app context; the capture hook rides the discovery paths.
        // (Capture is throttled to ≤1 full pass/sec + params-class gating, so
        // the 46-method hook-all stays cheap on the UI thread.)
        DownloadHook.init(context)

        // Game ads: framework seams only (activity lifecycle closer, launch
        // blockers, JS-interface + script watchers) — they need no DexKit,
        // so they arm immediately and close the cold-start window before
        // discovery finds the bridge classes.
        runCatching { GameAdsHook.installEarly(this, context) }
            .onFailure { L.e(TAG, "Game-ads early arm failed", it) }

        // Privacy: FLAG_SECURE removal (per-activity lifecycle callbacks) and
        // the capture-detection block (boot-classloader hooks, no DexKit).
        // Both default OFF — see PrivacyHook for the mod evidence.
        if (context is Application) {
            runCatching { PrivacyHook.init(context) }
                .onFailure { L.e(TAG, "Privacy init failed", it) }
        }
        runCatching { PrivacyHook.install(this) }
            .onFailure { L.e(TAG, "Privacy hooks failed", it) }

        // Hide only selected top/bottom navigation icons, not the backing
        // navigation routes. Framework fallback is ready before FB inflates
        // the bar; stable TabBarContainerLayout callback is armed on the same
        // secondary-dex retry cadence as other Facebook-specific hooks.
        runCatching { NavigationTabHook.installFramework(this) }
            .onFailure { L.e(TAG, "Navigation tab framework hook failed", it) }

        // Activity list: framework-class observer on
        // Activity.startActivityForResult — no DexKit, installed at boot like
        // the mod's "Hook Start Activity" installer. Dump output is gated on
        // the navigation.activityList toggle inside the callback.
        runCatching { ActivityListHook.install(this) }
            .onFailure { L.e(TAG, "Activity-list hook failed", it) }

        // Clean URL: l.php unwrap on Activity.startActivity — same boot-time
        // framework-class family as the activity-list hook (mod:
        // O1EMPB7OWX4fymeZ5Qom, swFbclid gate). Toggle is read in-callback.
        runCatching { CleanUrlHook.install(this) }
            .onFailure { L.e(TAG, "Clean-URL hook failed", it) }

        // Session export/import (ShareAccounts port): the module app's buttons
        // drive this receiver; it copies FB's authentication/logged_in session
        // files through MediaStore Downloads.
        runCatching { SessionBackup.install(context) }
            .onFailure { L.e(TAG, "Session backup receiver failed", it) }

        // The secondary dexes are injected into the app classloader's
        // dexElements some time after onCreate (dextricks async init). Retry
        // the probe until all stable classes resolve, then run discovery.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val delays = longArrayOf(1000, 3000, 7000, 15000, 30000)
        for (d in delays) {
            main.postDelayed({ probeAndMaybeDiscover(context) }, d)
        }
        probeAndMaybeDiscover(context)
    }

    private var accountHookTried = false
    private var paramsHookTried = false
    private var clipboardHookTried = false
    private var darkActivityHookTried = false
    private var amoledResolversReady = false
    private var newsfeedUiFallbackReady = false
    private var navigationTabNativeReady = false
    private var discoveryRan = false

    private fun probeAndMaybeDiscover(context: Context) {
        val classLoader = context.classLoader ?: return
        if (!accountHookTried) {
            accountHookTried = AccountHook.install(this, classLoader)
            if (accountHookTried) L.i(TAG, "Account capture hook installed")
        }
        // Dark mode, stable-name arm: FbFragmentActivity.isDarkMode needs only
        // the secondary dexes to attach (same cadence as the account hook).
        if (!darkActivityHookTried) {
            darkActivityHookTried = DarkModeHook.installActivityHook(this, classLoader)
            if (darkActivityHookTried) L.i(TAG, "Dark-mode activity hook installed")
        }
        if (!amoledResolversReady) {
            amoledResolversReady = AmoledThemeHook.installFacebookResolvers(this, classLoader)
            if (amoledResolversReady) L.i(TAG, "AMOLED Facebook colour resolvers installed")
        }
        if (!newsfeedUiFallbackReady) {
            newsfeedUiFallbackReady = NewsfeedFilterHook.installUiFallback(this, classLoader)
        }
        if (!navigationTabNativeReady) {
            navigationTabNativeReady = NavigationTabHook.installNative(this, classLoader)
        }
        // M4: video params capture — stable class name, needs only the
        // secondary dexes to attach (same cadence as the account hook).
        if (!paramsHookTried) {
            paramsHookTried = DownloadHook.installVideoParamsHook(this, classLoader)
            if (paramsHookTried) L.i(TAG, "Video params capture hook installed")
        }
        // M4: the mod's clipboard trigger for the qualities fetch — copying
        // any http(s) link (FB's "Copy link" on a video) opens the picker.
        // Framework class, installable immediately.
        if (!clipboardHookTried) {
            clipboardHookTried = DownloadHook.installClipboardHook(this)
        }
        val found = probeStableClasses(classLoader) == ProbeTargets.stableClasses.size

        val current = Thread.currentThread().contextClassLoader
        if (current != null && current !== classLoader) {
            L.i(TAG, "Thread contextClassLoader: $current")
            probeStableClasses(current)
        }

        if (found && !discoveryRan) {
            discoveryRan = true
            // Fast path: cached discovery results (same FB version) install in
            // ~1s. The full DexKit scan runs only on cache miss — during the
            // minutes-long scan no hooks exist, which is exactly when ads slip
            // through on every cold start without the cache.
            if (installFromCache(classLoader, context)) return
            L.i(TAG, "No discovery cache — running full DexKit scan")
            runDiscovery(classLoader, context)
        }
    }

    /** Cache-hit path: resolve stored hook methods directly, no DexKit. */
    private fun installFromCache(classLoader: ClassLoader, context: Context): Boolean {
        val methods = try {
            MethodCache.loadMethods(context, classLoader)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache read failed", t); null
        } ?: return false
        val runnableClass = try {
            MethodCache.loadClass(context, "feed.processNewStories")
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (runnableClass == null) return false
        val adsOptOutClass = try {
            MethodCache.loadClass(context, AdsOptOutHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        // A cache written before M2.6 lacks the ads-opt-out class; treat that
        // as a miss so the full scan re-runs and re-caches with the new key.
        if (adsOptOutClass == null) {
            L.i(TAG, "Discovery cache predates ads-opt-out hook — rescanning")
            return false
        }
        val playerClass = try {
            MethodCache.loadClass(context, DownloadHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        // Same for a pre-M4 cache: the player-container class is needed for
        // the download capture hook.
        if (playerClass == null) {
            L.i(TAG, "Discovery cache predates download hook — rescanning")
            return false
        }
        val mediaSetupClass = try {
            MethodCache.loadClass(context, DownloadHook.MEDIA_CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        // Same for a pre-M4b cache: the media-setup controller class.
        if (mediaSetupClass == null) {
            L.i(TAG, "Discovery cache predates media-setup hook — rescanning")
            return false
        }
        val contextualStoryState = try {
            MethodCache.loadClass(context, ContextualDownloadHook.STORY_CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        val contextualReelState = try {
            MethodCache.loadClass(context, ContextualDownloadHook.REEL_CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (contextualStoryState == null || contextualReelState == null) {
            L.i(TAG, "Discovery cache predates contextual downloader — rescanning")
            return false
        }
        if (contextualStoryState != "absent" &&
            contextualStoryState != ContextualDownloadHook.STORY_CACHE_VERSION
        ) {
            L.i(TAG, "Contextual story cache predates native-menu hook — rescanning")
            return false
        }
        if (ContextualDownloadHook.reelCacheNeedsRescan(contextualReelState)) {
            L.i(TAG, "Contextual reel cache expired or predates native-sidebar hook — rescanning")
            return false
        }
        if (!ContextualDownloadHook.isReelAbsent(contextualReelState) &&
            methods[ContextualDownloadHook.REEL_CACHE_KEY]?.size != 4
        ) {
            L.i(TAG, "Contextual reel cache missing native-sidebar methods — rescanning")
            return false
        }
        // Same for a pre-M2.7 cache: the story-viewer render methods. The
        // class-map entry doubles as a discovery outcome sentinel — "absent"
        // means the anchor wasn't found on this FB build (no methods to
        // cache), which must NOT trigger a rescan on every launch.
        val storyViewerState = try {
            MethodCache.loadClass(context, StoryFeedViewerHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (storyViewerState == null) {
            L.i(TAG, "Discovery cache predates story-viewer hook — rescanning")
            return false
        }
        // Same for a pre-M2.8 cache: the hide-seen-story setup methods.
        val hideSeenState = try {
            MethodCache.loadClass(context, HideSeenStoryHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (hideSeenState == null) {
            L.i(TAG, "Discovery cache predates hide-seen-story hook — rescanning")
            return false
        }
        // Same for a pre-dark-mode cache: the theme getter methods.
        val darkState = try {
            MethodCache.loadClass(context, DarkModeHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (darkState == null) {
            L.i(TAG, "Discovery cache predates dark-mode hook — rescanning")
            return false
        }
        val amoledResolverState = try {
            MethodCache.loadClass(context, AmoledThemeHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (amoledResolverState == null) {
            L.i(TAG, "Discovery cache predates AMOLED view resolver — rescanning")
            return false
        }
        // Same for a pre-auto-refresh cache: the refresh blocker methods.
        val refreshState = try {
            MethodCache.loadClass(context, AutoRefreshHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (refreshState == null) {
            L.i(TAG, "Discovery cache predates auto-refresh hook — rescanning")
            return false
        }
        // Same for a pre-stories-tray cache: the addStoriesAdapter method.
        val trayState = try {
            MethodCache.loadClass(context, StoriesTrayHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (trayState == null) {
            L.i(TAG, "Discovery cache predates stories-tray hook — rescanning")
            return false
        }
        // Same for a pre-M5 cache: the player-controller class shared by the
        // video-resume and background-playback hooks.
        val videoPlayerState = try {
            MethodCache.loadClass(context, VideoResumeHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (videoPlayerState == null) {
            L.i(TAG, "Discovery cache predates video-playback hooks — rescanning")
            return false
        }
        val backgroundState = try {
            MethodCache.loadClass(context, BackgroundPlaybackHook.CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (backgroundState == null) {
            L.i(TAG, "Discovery cache predates background-playback hook — rescanning")
            return false
        }
        // Same for a pre-v1.10 cache: the DexKit-resolved player members (the
        // position getter and pause methods) must be cached too, or the
        // structural hooks silently degrade on the cache fast-path.
        val positionState = try {
            MethodCache.loadClass(context, VideoResumeHook.POSITION_CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (positionState == null) {
            L.i(TAG, "Discovery cache predates position-getter key — rescanning")
            return false
        }
        val pauseState = try {
            MethodCache.loadClass(context, BackgroundPlaybackHook.PAUSE_CACHE_KEY)
        } catch (t: Throwable) {
            L.w(TAG, "discovery cache class read failed", t); null
        }
        if (pauseState == null) {
            L.i(TAG, "Discovery cache predates pause-method key — rescanning")
            return false
        }
        // Same for the ported ad guards (marketplace, game ads, feed guard):
        // each caches its discovered classes in the
        // shared class map, so a cache written before a port lacks that
        // hook's entry.
        if (!MarketplaceAdsHook.cachePresent(context) ||
            !GameAdsHook.cachePresent(context) ||
            !FeedGuardHook.cachePresent(context)
        ) {
            L.i(TAG, "Discovery cache predates a ported ad guard — rescanning")
            return false
        }

        L.i(TAG, "Discovery cache HIT: ${methods.size} target(s), installing without DexKit")
        try {
            AdFilterHook.init(context)
            AdFilterHook.install(this, AdTargets.all, methods)
            runCatching {
                // The banner class set has its own prefs cache; bridge is only
                // needed when that cache is empty (first launch).
                AdFilterHook.installBannerScan(this, null, classLoader, context)
            }.onFailure { L.e(TAG, "Banner scan failed", it) }
            runCatching {
                NewsfeedFilterHook.init(context)
                val feedReady = NewsfeedFilterHook.installCached(this, classLoader, runnableClass)
                if (!feedReady) {
                    L.w(TAG, "Cached classic-feed hook invalid — rediscovering next FB start")
                    MethodCache.invalidateClass(context, "feed.processNewStories")
                }
            }.onFailure { L.e(TAG, "Newsfeed filter installation failed", it) }
            adsOptOutClass?.let {
                runCatching {
                    AdsOptOutHook.installCached(this, classLoader, it)
                }.onFailure { L.e(TAG, "Ads opt-out installation failed", it) }
            }
            MarketplaceAdsHook.installFromCache(this, classLoader, context)
            GameAdsHook.installFromCache(this, classLoader, context)
            FeedGuardHook.installFromCache(this, classLoader, context)
            if (storyViewerState != "absent") {
                runCatching {
                    StoryFeedViewerHook.installCached(this, methods[StoryFeedViewerHook.CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "Story viewer installation failed", it) }
            } else {
                L.i(TAG, "Story viewer anchor not found on this build — skipping")
            }
            if (hideSeenState != "absent") {
                runCatching {
                    HideSeenStoryHook.installCached(this, methods[HideSeenStoryHook.CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "Hide-seen-story installation failed", it) }
            } else {
                L.i(TAG, "Hide-seen-story anchor not found on this build — skipping")
            }
            if (darkState != "absent") {
                runCatching {
                    DarkModeHook.installCached(this, methods[DarkModeHook.CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "Dark-mode installation failed", it) }
            } else {
                L.i(TAG, "Dark-mode anchor not found on this build — skipping")
            }
            if (amoledResolverState != "absent") {
                runCatching {
                    AmoledThemeHook.installViewResolverCached(
                        this, methods[AmoledThemeHook.CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "AMOLED view resolver installation failed", it) }
            } else {
                L.i(TAG, "AMOLED view resolver not found on this build — skipping")
            }
            if (refreshState != "absent") {
                runCatching {
                    AutoRefreshHook.installCached(this, methods[AutoRefreshHook.CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "Auto-refresh installation failed", it) }
            } else {
                L.i(TAG, "Auto-refresh anchor not found on this build — skipping")
            }
            if (trayState != "absent") {
                runCatching {
                    StoriesTrayHook.installCached(this, methods[StoriesTrayHook.CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "Stories-tray installation failed", it) }
            } else {
                L.i(TAG, "Stories-tray anchor not found on this build — skipping")
            }
            playerClass?.let {
                runCatching {
                    DownloadHook.installCached(this, classLoader, it)
                }.onFailure { L.e(TAG, "Download hook installation failed", it) }
            }
            mediaSetupClass?.let {
                runCatching {
                    DownloadHook.installMediaSetupCached(this, classLoader, it)
                }.onFailure { L.e(TAG, "Media-setup hook installation failed", it) }
            }
            if (contextualStoryState != "absent") {
                runCatching {
                    ContextualDownloadHook.installStoryCached(
                        this, methods[ContextualDownloadHook.STORY_CACHE_KEY].orEmpty())
                }.onFailure { L.e(TAG, "Contextual story download installation failed", it) }
            }
            if (!ContextualDownloadHook.isReelAbsent(contextualReelState)) {
                val installed = runCatching {
                    ContextualDownloadHook.installReelsCached(
                        this,
                        classLoader,
                        methods[ContextualDownloadHook.REEL_CACHE_KEY].orEmpty(),
                        contextualReelState,
                    )
                }.onFailure { L.e(TAG, "Contextual reel download installation failed", it) }
                    .getOrDefault(false)
                if (!installed) {
                    // Other hooks are already active: rescanning everything
                    // right here could duplicate them. Invalidate just this
                    // sentinel so the next Facebook process does a clean
                    // full scan instead of silently skipping reels forever.
                    L.w(TAG, "Cached reel hook failed; rediscovering on next Facebook start")
                    MethodCache.invalidateClass(context, ContextualDownloadHook.REEL_CACHE_KEY)
                }
            }
            if (videoPlayerState != "absent") {
                runCatching {
                    VideoResumeHook.init(context)
                    // Background playback first, then resume: the stale-seek
                    // clamp must run before the resume's seek observer (the
                    // mod registered them at default priority vs 10000).
                    if (backgroundState != "absent") {
                        val cachedPauses = pauseState.takeIf { it != "absent" }
                            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                        BackgroundPlaybackHook.installCached(
                            this, classLoader, videoPlayerState, context, cachedPauses)
                    }
                    VideoResumeHook.installCached(
                        this, classLoader, videoPlayerState,
                        positionState.takeIf { it != "absent" })
                }.onFailure { L.e(TAG, "Video playback hooks installation failed", it) }
            } else {
                L.i(TAG, "Video player anchor not found on this build — skipping")
            }
            L.i(TAG, "Hooks installed from cache")
            return true
        } catch (t: Throwable) {
            L.e(TAG, "Cache install failed — falling back to full discovery", t)
            return false
        }
    }

    /** Sanity probe: stable (non-obfuscated) classes resolvable directly. */
    private fun probeStableClasses(classLoader: ClassLoader?): Int {
        if (classLoader == null) return 0
        var found = 0
        for (name in ProbeTargets.stableClasses) {
            val ok = runCatching { Class.forName(name, false, classLoader) }.isSuccess
            if (ok) found++
            L.i(TAG, "STABLE_CLASS $name -> ${if (ok) "FOUND" else "NOT_FOUND"}")
        }
        return found
    }

    /** M1+M2: run probe + ad-filter targets through discovery, then install hooks. */
    private fun runDiscovery(classLoader: ClassLoader, context: Context?) {
        val discovery = Discovery()

        // Scan sources in order until one yields resolvable targets. The
        // extracted secondary dex archive is preferred when fresh, but it is
        // only regenerated lazily after an app update — an archive older than
        // the installed APK holds the previous version's class names, whose
        // methods resolve against nothing. The installed APK is always current.
        val secondary = findSecondaryDexZip(context)
        val apk = context?.applicationInfo?.sourceDir
        val sources = listOfNotNull(secondary, apk).distinct()

        var bridge: DexKitBridge? = null
        for (src in sources) {
            val candidate = runCatching { DexKitBridge.create(src) }.getOrNull() ?: continue
            val reports = discovery.discover(candidate, classLoader, ProbeTargets.all)
            val ok = reports.count { it.status == Discovery.Status.FOUND }
            L.i(TAG, "DexKit scanning: $src -> $ok/${reports.size} probe targets resolvable")
            if (ok > 0) {
                bridge = candidate
                break
            }
            candidate.close()
        }

        if (bridge == null) {
            L.e(TAG, "No dex source produced resolvable targets (tried: $sources)")
            return
        }

        try {
            // M2: ad/feed filters (ported from the original mod's hook map).
            if (context != null) {
                installAdFilters(bridge, classLoader, context)
            }
        } catch (t: Throwable) {
            L.e(TAG, "Discovery failed", t)
        } finally {
            bridge.close()
        }
    }

    /** Discovers and installs the M2 ad-filter hooks (feed + video + banner). */
    private fun installAdFilters(bridge: DexKitBridge, classLoader: ClassLoader, context: Context) {
        try {
            AdFilterHook.init(context)

            val discovery = Discovery()
            val targets = AdTargets.all
            val reports = discovery.discover(bridge, classLoader, targets)
            val methods = reports
                .filter { it.status == Discovery.Status.FOUND }
                .associate { it.key to it.methods }
            AdFilterHook.install(this, targets, methods)

            runCatching {
                AdFilterHook.installBannerScan(this, bridge, classLoader, context)
            }.onFailure { L.e(TAG, "Banner scan failed", it) }

            // M2.5: newsfeed content filter (mod's content-filter installer,
            // category-based row removal on the processNewStories Runnable).
            var runnableClass: String? = null
            runCatching {
                NewsfeedFilterHook.init(context)
                runnableClass = NewsfeedFilterHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Newsfeed filter installation failed", it) }

            // M2.6: ads-opt-out spoof (mod's EQRQ hook #1, X.1wO.A03 →
            // Boolean.TRUE): makes FB itself believe an ad-free session is
            // active, so sponsored content — incl. story-tray ads — is never
            // served.
            var adsOptOutClass: String? = null
            runCatching {
                adsOptOutClass = AdsOptOutHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Ads opt-out installation failed", it) }

            // M2.7: stories-in-feed viewer render block (mod's N38Ias3
            // callback, X.91I.A17 → null), gated on the stories-in-feed
            // toggle (mod: Story24hInNewsFeed) — same pref as the feed
            // filter's MULTI_FB_STORIES_TRAY category.
            var storyViewerMethods: List<Method>? = null
            runCatching {
                storyViewerMethods = StoryFeedViewerHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Story viewer hook installation failed", it) }

            // M2.8: hide-seen-story block (mod's edEtjRgj callback, X.AI9.A0F
            // → null), gated on the mod's hideSeenStory toggle. X.AI9 =
            // StoryViewerFragment (real name survives via
            // __redex_internal_original_name); the anchor literal sits inside
            // the hooked setup method itself.
            var hideSeenMethods: List<Method>? = null
            runCatching {
                hideSeenMethods = HideSeenStoryHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Hide-seen-story installation failed", it) }

            // Dark mode, theme-manager arm (mod's hzCa installer on
            // X.1XO.A04 → ThemePreferences.isDarkMode forced true).
            var darkMethods: List<Method>? = null
            runCatching {
                darkMethods = DarkModeHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Dark-mode hook installation failed", it) }

            // Morphe AMOLED route 1: discover the obfuscated FDS resolver
            // reached by ordinary Android Views from stable FdsColorScheme.
            var amoledResolverMethods: List<Method>? = null
            runCatching {
                amoledResolverMethods =
                    AmoledThemeHook.discoverViewResolver(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "AMOLED view resolver discovery failed", it) }

            // M2.9: News Feed auto-refresh block (mod's AdjzonOq installer on
            // the four refresh trigger methods, gated on swNewsFeedAutoReload —
            // each blocker skips the original entirely).
            var autoRefreshMethods: List<Method>? = null
            runCatching {
                autoRefreshMethods = AutoRefreshHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Auto-refresh hook installation failed", it) }

            // M2.10: stories-tray hide (mod's JvXX installer nulling
            // X.2dd.addStoriesAdapter so the tray adapter never joins the
            // feed's adapter list), gated on the hideTagStory toggle.
            var trayMethods: List<Method>? = null
            runCatching {
                trayMethods = StoriesTrayHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Stories-tray hook installation failed", it) }

            // M4: media download capture hook (mod's oRfibdnyvv3dEUIoNSY0$Hk8o
            // installer on the RichVideoPlayer container).
            var playerClass: String? = null
            runCatching {
                playerClass = DownloadHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Download hook installation failed", it) }

            // M4b: 576-renamed capture site — the video-setup controller that
            // BUILDS the {GraphQLMedia=…, NeedCenteringKey=…} map the mod's
            // callback regexed (C90164w8 in 576.0.0.42.73).
            var mediaSetupClass: String? = null
            runCatching {
                mediaSetupClass = DownloadHook.installMediaSetup(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Media-setup hook installation failed", it) }

            // Morphe-style native download actions. Stories reuse Facebook's
            // Save row; reels append a real button to Facebook's own sidebar.
            var contextualDownloads: ContextualDownloadHook.Result? = null
            runCatching {
                contextualDownloads = ContextualDownloadHook.install(this, bridge, classLoader)
            }.onFailure { L.e(TAG, "Contextual download installation failed", it) }

            // M5: video playback trio, resume + background arms (mod:
            // qV2f0EclUxE76gtr3YEb + nlYTzRDfkAYF5jojqBcx). Both hook the
            // same player controller (X.4Qc = FbGrootPlayer, found via the
            // "FbGrootPlayer.pause" quicklog literal); the floating player
            // (kRbFm7USGDhCvFgJ8BvY) is deferred — see the feature matrix.
            // Background installs first so its Dz2 clamp runs before the
            // resume observer (mod priority: default vs 10000).
            var videoPlayerClass: String? = null
            var backgroundInstalled = false
            var positionName: String? = null
            var pauseNames: List<String> = emptyList()
            runCatching {
                VideoResumeHook.init(context)
                val ctl = VideoResumeHook.findPlayerClass(bridge)
                if (ctl != null) {
                    // DexKit-resolved player members (names rotate per release,
                    // so they're cached alongside the class name).
                    positionName = VideoResumeHook.findPositionGetter(bridge, ctl)
                    pauseNames = VideoResumeHook.findPauseMethods(bridge, ctl)
                    backgroundInstalled =
                        BackgroundPlaybackHook.install(this, classLoader, ctl, context, pauseNames)
                    if (VideoResumeHook.hookPlayerClass(this, classLoader, ctl, positionName)) {
                        videoPlayerClass = ctl
                    }
                }
            }.onFailure { L.e(TAG, "Video playback hooks installation failed", it) }

            // Ported ad guards from the pre-rewrite module: marketplace ads,
            // game ads/rewards, and the CSR-experiment feed filter. Each owns discovery +
            // install and returns its class-map entry for the shared
            // discovery cache.
            val marketplaceCacheEntry =
                MarketplaceAdsHook.installAndCache(this, bridge, classLoader, context)
            val gameAdsCacheEntry =
                GameAdsHook.installAndCache(this, bridge, classLoader, context)
            val feedGuardCacheEntry =
                FeedGuardHook.installAndCache(this, bridge, classLoader, context)

            // Cache everything so the next launch of this FB version skips the
            // minutes-long DexKit scan and installs hooks in ~1s.
            runCatching {
                MethodCache.store(
                    context,
                    methods + buildMap {
                        storyViewerMethods?.let { put(StoryFeedViewerHook.CACHE_KEY, it) }
                        hideSeenMethods?.let { put(HideSeenStoryHook.CACHE_KEY, it) }
                        darkMethods?.let { put(DarkModeHook.CACHE_KEY, it) }
                        amoledResolverMethods?.let { put(AmoledThemeHook.CACHE_KEY, it) }
                        autoRefreshMethods?.let { put(AutoRefreshHook.CACHE_KEY, it) }
                        trayMethods?.let { put(StoriesTrayHook.CACHE_KEY, it) }
                        contextualDownloads?.storyMethods?.let {
                            put(ContextualDownloadHook.STORY_CACHE_KEY, it)
                        }
                        contextualDownloads?.reelMethods?.let {
                            put(ContextualDownloadHook.REEL_CACHE_KEY, it)
                        }
                    },
                    buildMap {
                        runnableClass?.let { put("feed.processNewStories", it) }
                        adsOptOutClass?.let { put(AdsOptOutHook.CACHE_KEY, it) }
                        playerClass?.let { put(DownloadHook.CACHE_KEY, it) }
                        mediaSetupClass?.let { put(DownloadHook.MEDIA_CACHE_KEY, it) }
                        put(
                            ContextualDownloadHook.STORY_CACHE_KEY,
                            if (contextualDownloads?.storyMethods != null)
                                ContextualDownloadHook.STORY_CACHE_VERSION
                            else "absent",
                        )
                        put(
                            ContextualDownloadHook.REEL_CACHE_KEY,
                            contextualDownloads?.reelMetadata
                                ?: ContextualDownloadHook.reelDiscoveryFailedEntry(),
                        )
                        videoPlayerClass?.let { put(VideoResumeHook.CACHE_KEY, it) }
                        // "absent" records a NOT_FOUND anchor so later launches
                        // skip the hook instead of rescanning forever.
                        put(StoryFeedViewerHook.CACHE_KEY, if (storyViewerMethods != null) "cached" else "absent")
                        put(HideSeenStoryHook.CACHE_KEY, if (hideSeenMethods != null) "cached" else "absent")
                        put(DarkModeHook.CACHE_KEY, if (darkMethods != null) "cached" else "absent")
                        put(AmoledThemeHook.CACHE_KEY,
                            if (amoledResolverMethods != null) "cached" else "absent")
                        put(AutoRefreshHook.CACHE_KEY, if (autoRefreshMethods != null) "cached" else "absent")
                        put(StoriesTrayHook.CACHE_KEY, if (trayMethods != null) "cached" else "absent")
                        // The resume key holds the class NAME (like the
                        // download keys above); "absent" doubles as sentinel.
                        if (videoPlayerClass == null) put(VideoResumeHook.CACHE_KEY, "absent")
                        put(BackgroundPlaybackHook.CACHE_KEY, if (backgroundInstalled) "cached" else "absent")
                        // DexKit-resolved player members; "absent" keeps a
                        // failed resolution from forcing a rescan every launch.
                        put(VideoResumeHook.POSITION_CACHE_KEY, positionName ?: "absent")
                        put(BackgroundPlaybackHook.PAUSE_CACHE_KEY,
                            pauseNames.joinToString(",").ifEmpty { "absent" })
                        // Ported ad guards: role-prefixed class CSVs (or the
                        // "absent" sentinel — see each hook's installAndCache).
                        put(MarketplaceAdsHook.CACHE_KEY, marketplaceCacheEntry)
                        put(GameAdsHook.CACHE_KEY, gameAdsCacheEntry)
                        put(FeedGuardHook.CACHE_KEY, feedGuardCacheEntry)
                    },
                )
                L.i(TAG, "Discovery results cached for this FB version")
            }.onFailure { L.w(TAG, "Failed to cache discovery results", it) }
        } catch (t: Throwable) {
            L.e(TAG, "Ad filter installation failed", t)
        }
    }

    /**
     * Locates FB's extracted secondary dex archive (data/dex/z-*.zip).
     * Archives older than the installed APK belong to a previous app version
     * (the archive is regenerated lazily, sometimes never) and are ignored —
     * their class names no longer resolve in the current classloader.
     */
    private fun findSecondaryDexZip(context: Context?): String? {
        if (context == null) return null
        return runCatching {
            val installedAt = context.applicationInfo?.sourceDir
                ?.let(::File)?.takeIf { it.exists() }?.lastModified() ?: 0L
            File(context.dataDir, "dex").listFiles { f ->
                f.name.startsWith("z-") && f.name.endsWith(".zip") &&
                        (installedAt == 0L || f.lastModified() >= installedAt)
            }?.maxByOrNull { it.length() }?.absolutePath
        }.getOrNull()
    }
}
