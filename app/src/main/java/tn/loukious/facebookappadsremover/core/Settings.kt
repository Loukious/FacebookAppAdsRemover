package tn.loukious.facebookappadsremover.core

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * Single source of truth for feature toggles.
 *
 * Storage = the framework's remote-preferences group "fbar_settings"
 * (Vector/LSPosed daemon, SQLite configs table). The settings UI in the
 * module app writes through the libxposed service library
 * (ui.MainActivity); the hooks run inside the Facebook process, where the
 * libxposed API exposes the same group read-only through
 * [XposedInterface.getRemotePreferences]. Plain SharedPreferences files in
 * either app's storage are NOT part of this channel — verified the hard way
 * (2026-09-06): the daemon never reads them, so writes must go over the
 * service binder.
 *
 * The remote group may also push live updates to hooked processes, but
 * several hooks read their toggle only once at install — treat changes as
 * apply-on-next-Facebook-restart (the settings UI says the same).
 *
 * Until the settings app has written at least one toggle (group empty), we
 * fall back to the legacy FB-process-local "fbar_prefs" file that earlier
 * builds read — preserving state set via root during on-device verification.
 */
object Settings {

    private const val TAG = "FBAR.Settings"

    /** Prefs file name — must match the group name the settings UI writes. */
    const val NAME = "fbar_settings"

    /** Legacy FB-process-local file from earlier builds (root-editable). */
    const val LEGACY_NAME = "fbar_prefs"

    // Toggle keys + defaults, mirroring the original mod's switches:
    //   mod pref                              module key                      default
    //   app.telegram.bemai3012_swHOME_ADS   → ads.enabled (legacy, migrated
    //                                        to newsFeed/stories/reels)       TRUE
    //   swHOME_THREADS                       → feed.threads                    FALSE
    //   swHOME_REELS                         → feed.reels                      FALSE
    //   swHOME_GOIY                          → feed.suggestions                FALSE
    //   swNhungNguoiBanCoTheBiet             → feed.pymk                       FALSE
    //   Story24hInNewsFeed                   → feed.stories                    FALSE
    //   hideSeenStory                        → stories.hideSeen                FALSE
    //   privacy.disable_flag_secure          → privacy.allowCapture            FALSE
    //   privacy.capture_detection            → privacy.blockCaptureDetection   FALSE
    //   (download "use browser" setting)     → download.useBrowser             TRUE
    //   swVIDEO_RESUME                       → media.video.resume              FALSE
    //   swBACKGROUND_PLAYBACK                → media.video.background          FALSE

    /** Three independent ad surfaces. The old ads.enabled master is retained
     *  only as a read/migration fallback, not a live gate for any hook. */
    const val ADS_NEWS_FEED = "ads.newsFeed"
    const val ADS_STORIES = "ads.stories"
    const val ADS_REELS = "ads.reels"
    internal const val LEGACY_ADS_ENABLED = "ads.enabled"

    /** Marketplace and in-app games remain independently switchable. */
    const val ADS_MARKETPLACE = "ads.marketplace"
    const val ADS_GAME_ADS = "ads.gameAds"
    /** Advanced option: sponsored filter on CSR and late-cache feed paths.
     * Does not disable non-ad categories, AI or keyword rules. */
    const val ADS_FEED_GUARD = "ads.feedGuard"

    const val FEED_THREADS = "feed.threads"
    const val FEED_REELS = "feed.reels"
    const val FEED_SUGGESTIONS = "feed.suggestions"
    const val FEED_PYMK = "feed.pymk"
    const val FEED_STORIES = "feed.stories"

    /** AI-content filter — drops stories whose GenAI transparency model marks
     *  them as either self-disclosed or Meta-detected AI content. The matcher
     *  uses stable GraphQL field hashes rather than obfuscated Java names. */
    const val FEED_AI_CONTENT = "feed.aiContent"

    /** Keyword filter master switch; the list itself is [FEED_KEYWORDS]. */
    const val FEED_KEYWORDS_ENABLED = "feed.keywords.enabled"

    /** Comma/semicolon/newline-separated keyword list — a story whose TreeJNI
     *  dump contains any entry (case-insensitive) is dropped from the feed. */
    const val FEED_KEYWORDS = "feed.keywords"
    const val STORIES_HIDE_SEEN = "stories.hideSeen"

    /** Stories tray hide — the "Stories" card at the top of the News Feed
     *  (mod: hideTagStory, X.2dd.addStoriesAdapter null-out). */
    const val STORIES_HIDE_TRAY = "stories.hideTray"
    const val PRIVACY_ALLOW_CAPTURE = "privacy.allowCapture"
    const val PRIVACY_BLOCK_DETECTION = "privacy.blockCaptureDetection"
    const val DOWNLOAD_USE_BROWSER = "download.useBrowser"

    /** Show contextual reel/story download actions. The downloader itself
     *  stays armed when this is off; copied-link downloads still work. */
    const val DOWNLOAD_SHOW_ICON = "download.showIcon"

    /** Quick download via copied link — the clipboard trigger arm
     *  (mod: taiNhanh, `l6gJTzHczDDiahs77aMq` on setPrimaryClip, encrypted
     *  pref `app.telegram.bemai3012_taiNhanh`). Default FALSE: the mod's
     *  encrypted-pref read also defaulted FALSE. */
    const val DOWNLOAD_CLIPBOARD = "download.clipboardTrigger"

    const val APPEARANCE_DARK = "appearance.dark"

    /** Morphe-style AMOLED theme: keep Facebook's dark-mode semantics, but
     * turn its dark neutral background palette into true black. */
    const val APPEARANCE_AMOLED = "appearance.amoled"

    /** Clean URL — unwrap facebook.com/l.php?u=…&fbclid=… redirect links to
     *  the real destination when FB hands them to the browser (mod:
     *  swFbclid, X.O1EMPB7OWX4fymeZ5Qom). Default FALSE: the mod's
     *  encrypted-pref read also defaulted FALSE. */
    const val LINKS_CLEAN_URL = "links.cleanUrl"

    /** News Feed auto-refresh block (mod: swNewsFeedAutoReload). */
    const val FEED_AUTO_REFRESH_BLOCK = "feed.autoRefreshBlock"

    /** Activity list — Intent dump on every startActivityForResult
     *  (mod: navigation.activity_list.*). */
    const val NAVIGATION_ACTIVITY_LIST = "navigation.activityList"

    /** Hide only the Home navigation bar destinations (top or bottom).
     * Marketplace, Reels and Games remain accessible through menu, search,
     * notifications, deep links and their original activities/routes. */
    const val NAV_HIDE_REELS_TAB = "navigation.hideReelsTab"
    const val NAV_HIDE_MARKETPLACE_TAB = "navigation.hideMarketplaceTab"
    const val NAV_HIDE_GAMES_TAB = "navigation.hideGamesTab"

    /** Opt-in video resume — restore a saved point ONCE when a video opens,
     *  never force an already-playing video back after the user seeks. The
     *  original mod defaulted this on; the port defaults it OFF for safety.
     *  Explicit existing user preferences are not overwritten on upgrade. */
    const val VIDEO_RESUME = "media.video.resume"

    /** Background video playback — keep the tracked video playing while the
     *  app is backgrounded (mod: swBACKGROUND_PLAYBACK). Default FALSE:
     *  the mod's default for this switch was not decoded, and the port's
     *  no-overlay variant changes audible behaviour, so it ships off. */
    const val VIDEO_BACKGROUND = "media.video.background"

    private var prefs: SharedPreferences? = null

    /**
     * Picks the prefs source for the Facebook process. Call once from
     * ModuleMain.onAppCreated, before any hook reads a toggle.
     */
    fun init(module: XposedInterface?, context: Context) {
        val remote = module?.let {
            runCatching { it.getRemotePreferences(NAME) }
                .onFailure { L.w(TAG, "getRemotePreferences($NAME) threw", it) }
                .getOrNull()
        }
        val legacy = runCatching {
            context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
        // Empty remote file = the settings app was never opened → keep
        // honoring whatever the legacy file holds until the UI takes over.
        val source = if (remote != null && remote.all.isNotEmpty()) remote else legacy
        prefs = source
        L.i(TAG, "toggle source: " + when {
            source === remote -> "remote preferences ($NAME)"
            source === legacy -> "legacy local prefs ($LEGACY_NAME)"
            else -> "none (defaults)"
        })
    }

    /** Reads a toggle; safe on hot paths and before [init]. */
    fun getBoolean(key: String, default: Boolean): Boolean = prefs?.let { source ->
        runCatching {
            if (key in setOf(ADS_MARKETPLACE, ADS_GAME_ADS) &&
                !source.getBoolean(AdSettingsMigration.MARKER, false) &&
                source.contains(LEGACY_ADS_ENABLED) &&
                !source.getBoolean(LEGACY_ADS_ENABLED, true)) {
                // Before the module settings UI runs its one-time migration,
                // the old master still overrides even an explicitly checked
                // Marketplace/Game sub-switch. Preserve the old behavior.
                false
            } else if (source.contains(key)) source.getBoolean(key, default)
            else if (key in LEGACY_AD_SURFACE_KEYS && source.contains(LEGACY_ADS_ENABLED)) {
                // On first launch after the split, the UI may not have run its
                // migration yet. Preserve a disabled old master until the
                // corresponding explicit new preference is written.
                source.getBoolean(LEGACY_ADS_ENABLED, default)
            } else default
        }.getOrDefault(default)
    } ?: default

    private val LEGACY_AD_SURFACE_KEYS = setOf(
        ADS_NEWS_FEED, ADS_STORIES, ADS_REELS, ADS_MARKETPLACE, ADS_GAME_ADS,
    )

    /** Shared ad fetchers cannot be assigned safely to one surface; blocking
     * them only when *every* affected surface is enabled preserves off toggles.
     */
    fun blockAdsOn(surfaces: Set<AdSurface>): Boolean =
        AdSurfacePolicy.shouldBlock(surfaces) { surface ->
            getBoolean(when (surface) {
                AdSurface.NEWS_FEED -> ADS_NEWS_FEED
                AdSurface.STORIES -> ADS_STORIES
                AdSurface.REELS -> ADS_REELS
            }, true)
        }

    /** Reads a string setting (e.g. the keyword list); safe before [init]. */
    fun getString(key: String, default: String): String =
        prefs?.let { runCatching { it.getString(key, default) }.getOrDefault(default) } ?: default
}
