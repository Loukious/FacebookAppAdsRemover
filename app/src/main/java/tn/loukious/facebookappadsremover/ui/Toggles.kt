package tn.loukious.facebookappadsremover.ui

import tn.loukious.facebookappadsremover.core.Settings

/**
 * The full toggle surface, in display order. Keys and defaults must stay in
 * sync with core.Settings (which documents the original-mod pref each one
 * mirrors). Subtitles record the hook-level effect, so the cost of each
 * switch is visible where it's flipped.
 *
 * A section may also carry non-toggle controls, declared through [extra]. That
 * is a typed field rather than a match on [ToggleSection.title] because the
 * title is display text: the session buttons were dispatched on the literal
 * "Account", and when no such section existed the code silently never ran.
 */
data class ToggleSpec(
    val key: String,
    val title: String,
    val subtitle: String,
    val default: Boolean,
)

/**
 * Non-toggle controls rendered inside a section, below its switches. [NONE] is
 * the common case: a section that is only switches.
 */
enum class SectionExtra {
    NONE,

    /** Free-text keyword list for the feed filter. */
    KEYWORDS,

    /** Session export/import buttons. */
    SESSION,

    /**
     * Launcher-icon switch. Module-local rather than a hook toggle — its value
     * lives in PackageManager's component state, so it works without the
     * framework service.
     */
    LAUNCHER,
}

data class ToggleSection(
    val title: String,
    val toggles: List<ToggleSpec>,
    val extra: SectionExtra = SectionExtra.NONE,
)

val TOGGLE_SECTIONS: List<ToggleSection> = listOf(
    ToggleSection("Ads", listOf(
        ToggleSpec(
            Settings.ADS_NEWS_FEED,
            "Block News Feed ads",
            "Sponsored feed posts, multi-ad units and ad-channel requests; cached-feed ad blocking also requires the CSR guard below",
            true,
        ),
        ToggleSpec(
            Settings.ADS_STORIES,
            "Block Story ads",
            "Sponsored story buckets and story-player ad breaks",
            true,
        ),
        ToggleSpec(
            Settings.ADS_REELS,
            "Block Reels ads",
            "Dedicated Reels/Shorts ads and banners; shared video/ad-break hooks also require Block Story ads",
            true,
        ),
        ToggleSpec(
            Settings.ADS_MARKETPLACE,
            "Block marketplace ads",
            "Marketplace sponsored tiles, boosted listings and video ads — drops the ad queries and flips the server's ad-skip flags",
            true,
        ),
        ToggleSpec(
            Settings.ADS_GAME_ADS,
            "Block game ads",
            "In-app game ad requests (interstitial/rewarded/banner) are rejected; rewarded ads resolve as success so rewards are still granted",
            true,
        ),
        ToggleSpec(
            Settings.ADS_FEED_GUARD,
            "Enable CSR feed ad guard",
            "Additional News Feed cache/render protection; follows Block News Feed ads and does not disable AI/keyword filtering",
            true,
        ),
    )),
    ToggleSection("Feed filters", listOf(
        ToggleSpec(Settings.FEED_THREADS, "Hide Threads posts", "PROMOTION stories", false),
        ToggleSpec(Settings.FEED_REELS, "Hide Reels", "FB_SHORTS stories", false),
        ToggleSpec(
            Settings.FEED_SUGGESTIONS,
            "Hide suggestions",
            "ENGAGEMENT stories — can starve the feed pager when whole batches are suggestions",
            false,
        ),
        ToggleSpec(Settings.FEED_PYMK, "Hide People You May Know", "ENGAGEMENT_QP stories", false),
        ToggleSpec(Settings.FEED_STORIES, "Hide Stories in feed", "Stories tray + story viewer render", false),
        ToggleSpec(
            Settings.FEED_AI_CONTENT,
            "Hide AI-generated content",
            "Drops stories carrying Facebook's AI transparency label — self-disclosed or Meta-detected AI content",
            false,
        ),
        ToggleSpec(
            Settings.FEED_KEYWORDS_ENABLED,
            "Keyword filter",
            "Hides feed stories containing any keyword from the list below (case-insensitive, comma-separated)",
            false,
        ),
        ToggleSpec(
            Settings.FEED_AUTO_REFRESH_BLOCK,
            "Block auto-refresh",
            "Kills revisit/warm-start/foreground/force refresh on the News Feed (Beta)",
            false,
        ),
    ), SectionExtra.KEYWORDS),
    ToggleSection("Stories", listOf(
        ToggleSpec(Settings.STORIES_HIDE_SEEN, "View stories without marking seen", "Blocks the seen-reporting controller so the server never learns you watched", false),
        ToggleSpec(
            Settings.STORIES_HIDE_TRAY,
            "Hide Stories tray",
            "Keeps the Stories card out of the feed's adapter list",
            false,
        ),
    )),
    ToggleSection("Appearance", listOf(
        ToggleSpec(Settings.APPEARANCE_DARK, "Force dark mode", "ThemePreferences + FbFragmentActivity isDarkMode → true", false),
        ToggleSpec(
            Settings.APPEARANCE_AMOLED,
            "AMOLED black mode",
            "Morphe-style true-black backgrounds for Facebook dark mode; enable Facebook dark mode or Force dark mode too",
            false,
        ),
    )),
    ToggleSection("Privacy", listOf(
        ToggleSpec(Settings.PRIVACY_ALLOW_CAPTURE, "Allow screenshots & recording", "Clears FLAG_SECURE on every activity", false),
        ToggleSpec(Settings.PRIVACY_BLOCK_DETECTION, "Block capture detection", "FB's screenshot/recording watchers", false),
    )),
    ToggleSection("Navigation", listOf(
        ToggleSpec(
            Settings.NAV_HIDE_REELS_TAB,
            "Hide Reels tab",
            "Remove only the Reels button from Facebook's top/bottom tab bar; Reels still opens from links and the menu",
            false,
        ),
        ToggleSpec(
            Settings.NAV_HIDE_MARKETPLACE_TAB,
            "Hide Marketplace tab",
            "Remove only the Marketplace tab-bar button; Marketplace remains available elsewhere",
            false,
        ),
        ToggleSpec(
            Settings.NAV_HIDE_GAMES_TAB,
            "Hide Games tab",
            "Remove only the Games/Gaming tab-bar button; games remain accessible from the menu and links",
            false,
        ),
        ToggleSpec(
            Settings.NAVIGATION_ACTIVITY_LIST,
            "Activity list (Intent dump)",
            "Logs every hidden activity FB starts — action, URI, extras",
            false,
        ),
    )),
    ToggleSection("Links", listOf(
        ToggleSpec(
            Settings.LINKS_CLEAN_URL,
            "Unwrap l.php redirect links",
            "Rewrites facebook.com/l.php?u=…&fbclid=… URLs to the real destination before the browser opens them",
            false,
        ),
    )),
    ToggleSection("Downloader", listOf(
        ToggleSpec(Settings.DOWNLOAD_USE_BROWSER, "Download via browser", "Hand media URLs to the browser instead of the in-module downloader", true),
        ToggleSpec(
            Settings.DOWNLOAD_SHOW_ICON,
            "Show contextual download actions",
            "Shows Download on the active reel and enables Save for the story currently open; each action is bound to that media item",
            true,
        ),
        ToggleSpec(
            Settings.DOWNLOAD_CLIPBOARD,
            "Quick download from copied link",
            "Copying an http(s) link fires the qualities fetch + quick-download dialog",
            false,
        ),
    )),
    ToggleSection("Video", listOf(
        ToggleSpec(Settings.VIDEO_RESUME, "Resume video position", "Optional one-time resume when a video reopens; manual seeking always takes priority", false),
        ToggleSpec(Settings.VIDEO_BACKGROUND, "Background playback", "Keep the tracked video playing while the app is backgrounded (no floating window)", false),
    )),
    // No toggles of its own: the export/import buttons are the whole section.
    ToggleSection("Account", emptyList(), SectionExtra.SESSION),
    // Module-local rather than a hook toggle — see SectionExtra.LAUNCHER.
    ToggleSection("Module", emptyList(), SectionExtra.LAUNCHER),
)
