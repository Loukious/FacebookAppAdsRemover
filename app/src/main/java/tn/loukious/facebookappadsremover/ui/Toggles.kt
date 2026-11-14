package tn.loukious.facebookappadsremover.ui

import tn.loukious.facebookappadsremover.R
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

/** Leading icon of each switch row, keyed by setting. */
val TOGGLE_ICONS: Map<String, Int> = mapOf(
    Settings.ADS_NEWS_FEED to R.drawable.ic_newspaper,
    Settings.ADS_STORIES to R.drawable.ic_stories,
    Settings.ADS_REELS to R.drawable.ic_movie,
    Settings.ADS_MARKETPLACE to R.drawable.ic_store,
    Settings.ADS_GAME_ADS to R.drawable.ic_gamepad,
    Settings.ADS_FEED_GUARD to R.drawable.ic_layers,
    Settings.FEED_THREADS to R.drawable.ic_at,
    Settings.FEED_REELS to R.drawable.ic_movie,
    Settings.FEED_SUGGESTIONS to R.drawable.ic_sparkle,
    Settings.FEED_PYMK to R.drawable.ic_group,
    Settings.FEED_STORIES to R.drawable.ic_stories,
    Settings.FEED_AI_CONTENT to R.drawable.ic_sparkle,
    Settings.FEED_KEYWORDS_ENABLED to R.drawable.ic_label,
    Settings.FEED_AUTO_REFRESH_BLOCK to R.drawable.ic_sync_disabled,
    Settings.STORIES_HIDE_SEEN to R.drawable.ic_visibility_off,
    Settings.STORIES_HIDE_TRAY to R.drawable.ic_feed,
    Settings.APPEARANCE_DARK to R.drawable.ic_dark_mode,
    Settings.APPEARANCE_AMOLED to R.drawable.ic_contrast,
    Settings.PRIVACY_ALLOW_CAPTURE to R.drawable.ic_screenshot,
    Settings.PRIVACY_BLOCK_DETECTION to R.drawable.ic_visibility_off,
    Settings.NAV_HIDE_REELS_TAB to R.drawable.ic_movie,
    Settings.NAV_HIDE_MARKETPLACE_TAB to R.drawable.ic_store,
    Settings.NAV_HIDE_GAMES_TAB to R.drawable.ic_gamepad,
    Settings.NAVIGATION_ACTIVITY_LIST to R.drawable.ic_bug,
    Settings.LINKS_EXTERNAL_BROWSER to R.drawable.ic_open_in_new,
    Settings.LINKS_CLEAN_URL to R.drawable.ic_link,
    Settings.DOWNLOAD_USE_BROWSER to R.drawable.ic_open_in_new,
    Settings.DOWNLOAD_SHOW_ICON to R.drawable.ic_download,
    Settings.DOWNLOAD_CLIPBOARD to R.drawable.ic_clipboard,
    Settings.VIDEO_RESUME to R.drawable.ic_history,
    Settings.VIDEO_BACKGROUND to R.drawable.ic_headphones,
)

/** Icon and one-paragraph explanation shown for each section page. */
data class SectionInfo(val icon: Int, val intro: String)

val SECTION_INFO: Map<String, SectionInfo> = mapOf(
    "Ads" to SectionInfo(R.drawable.ic_block,
        "Removes ads before Facebook shows them. Turn off a switch if a part of the app stops loading."),
    "Feed filters" to SectionInfo(R.drawable.ic_feed,
        "Hide kinds of posts you don't want in your News Feed. Hiding too much can make the feed load slowly."),
    "Stories" to SectionInfo(R.drawable.ic_stories, "How Stories behave and where they appear."),
    "Appearance" to SectionInfo(R.drawable.ic_dark_mode, "Facebook's theme."),
    "Privacy" to SectionInfo(R.drawable.ic_shield,
        "Facebook blocks screenshots in some places and can notice when you take one. These switches stop both."),
    "Navigation" to SectionInfo(R.drawable.ic_tabs,
        "Clean up the tab bar. Hidden tabs are still reachable from the menu."),
    "Links" to SectionInfo(R.drawable.ic_link, "Where web links open, and whether Facebook can track your clicks."),
    "Downloader" to SectionInfo(R.drawable.ic_download, "Save photos, videos, reels and stories to your phone."),
    "Video" to SectionInfo(R.drawable.ic_play, "Video playback extras."),
    "Account" to SectionInfo(R.drawable.ic_person,
        "Move your Facebook login to another phone or restore it after reinstalling, without signing in again. " +
            "The backup file gives full access to your account: keep it private."),
    "Module" to SectionInfo(R.drawable.ic_apps, "Settings for this app itself."),
)

val TOGGLE_SECTIONS: List<ToggleSection> = listOf(
    ToggleSection("Ads", listOf(
        ToggleSpec(
            Settings.ADS_NEWS_FEED,
            "Block News Feed ads",
            "Sponsored posts and ad carousels in your feed",
            true,
        ),
        ToggleSpec(
            Settings.ADS_STORIES,
            "Block Story ads",
            "Sponsored stories and ads between stories",
            true,
        ),
        ToggleSpec(
            Settings.ADS_REELS,
            "Block Reels ads",
            "Ads and banners in Reels. Video ad breaks also need Story ads blocked",
            true,
        ),
        ToggleSpec(
            Settings.ADS_MARKETPLACE,
            "Block marketplace ads",
            "Sponsored and boosted listings and video ads in Marketplace",
            true,
        ),
        ToggleSpec(
            Settings.ADS_GAME_ADS,
            "Block game ads",
            "Ads in Instant Games. You still get the rewards for skipped reward ads",
            true,
        ),
        ToggleSpec(
            Settings.ADS_FEED_GUARD,
            "Extra feed protection",
            "Second layer for feed ads: also removes ads that Facebook saved earlier and shows when you open the app offline or scroll back. Needs Block News Feed ads",
            true,
        ),
    )),
    ToggleSection("Feed filters", listOf(
        ToggleSpec(Settings.FEED_THREADS, "Hide Threads posts", "Posts from Threads suggested in your feed", false),
        ToggleSpec(Settings.FEED_REELS, "Hide Reels", "Reels inside the feed", false),
        ToggleSpec(
            Settings.FEED_SUGGESTIONS,
            "Hide suggestions",
            "Suggested posts and groups. The feed may load slower when there are many",
            false,
        ),
        ToggleSpec(Settings.FEED_PYMK, "Hide People You May Know", "Friend suggestions in the feed", false),
        ToggleSpec(Settings.FEED_STORIES, "Hide Stories in feed", "Stories blocks inside the feed", false),
        ToggleSpec(
            Settings.FEED_AI_CONTENT,
            "Hide AI-generated content",
            "Posts that Facebook labels as made with AI",
            false,
        ),
        ToggleSpec(
            Settings.FEED_KEYWORDS_ENABLED,
            "Keyword filter",
            "Hide posts containing any word from the list below, separated by commas",
            false,
        ),
        ToggleSpec(
            Settings.FEED_AUTO_REFRESH_BLOCK,
            "Block auto-refresh",
            "Stop the feed from jumping to the top and reloading when you return to the app (beta)",
            false,
        ),
    ), SectionExtra.KEYWORDS),
    ToggleSection("Stories", listOf(
        ToggleSpec(Settings.STORIES_HIDE_SEEN, "View stories without marking seen", "Watch stories without showing up in the viewers list", false),
        ToggleSpec(
            Settings.STORIES_HIDE_TRAY,
            "Hide Stories tray",
            "Remove the Stories row from the top of the feed",
            false,
        ),
    )),
    ToggleSection("Appearance", listOf(
        ToggleSpec(Settings.APPEARANCE_DARK, "Force dark mode", "Always use Facebook's dark theme", false),
        ToggleSpec(
            Settings.APPEARANCE_AMOLED,
            "AMOLED black mode",
            "Pure black backgrounds in dark theme. Needs dark theme on",
            false,
        ),
    )),
    ToggleSection("Privacy", listOf(
        ToggleSpec(Settings.PRIVACY_ALLOW_CAPTURE, "Allow screenshots & recording", "Allow screenshots and screen recording everywhere in the app", false),
        ToggleSpec(Settings.PRIVACY_BLOCK_DETECTION, "Block capture detection", "Facebook won't notice when you take a screenshot", false),
    )),
    ToggleSection("Navigation", listOf(
        ToggleSpec(
            Settings.NAV_HIDE_REELS_TAB,
            "Hide Reels tab",
            "Remove the Reels button from the tab bar. Reels still open from links and the menu",
            false,
        ),
        ToggleSpec(
            Settings.NAV_HIDE_MARKETPLACE_TAB,
            "Hide Marketplace tab",
            "Remove the Marketplace button from the tab bar. Still available from the menu",
            false,
        ),
        ToggleSpec(
            Settings.NAV_HIDE_GAMES_TAB,
            "Hide Games tab",
            "Remove the Gaming button from the tab bar. Still available from the menu",
            false,
        ),
        ToggleSpec(
            Settings.NAVIGATION_ACTIVITY_LIST,
            "Log opened screens (for developers)",
            "Writes each internal Facebook screen to the system log. Only needed when reporting a bug",
            false,
        ),
    )),
    ToggleSection("Links", listOf(
        ToggleSpec(
            Settings.LINKS_EXTERNAL_BROWSER,
            "Open links in external browser",
            "Open web links in your default browser. Facebook pages and login stay in the app",
            true,
        ),
        ToggleSpec(
            Settings.LINKS_CLEAN_URL,
            "Remove link tracking",
            "Open the real address instead of Facebook's tracking redirect",
            false,
        ),
    )),
    ToggleSection("Downloader", listOf(
        ToggleSpec(Settings.DOWNLOAD_USE_BROWSER, "Download via browser", "Hand downloads to your browser. Turn off to save files directly with this module", true),
        ToggleSpec(
            Settings.DOWNLOAD_SHOW_ICON,
            "Show download buttons",
            "Add a Download button to reels and a Save option to stories",
            true,
        ),
        ToggleSpec(
            Settings.DOWNLOAD_CLIPBOARD,
            "Quick download from copied link",
            "When you copy a link to a video or reel, a dialog appears to download it",
            false,
        ),
    )),
    ToggleSection("Video", listOf(
        ToggleSpec(Settings.VIDEO_RESUME, "Resume video position", "Continue a video from where you stopped", false),
        ToggleSpec(Settings.VIDEO_BACKGROUND, "Background playback", "The current video keeps playing with the screen off or in another app", false),
    )),
    // No toggles of its own: the export/import buttons are the whole section.
    ToggleSection("Account", emptyList(), SectionExtra.SESSION),
    // Module-local rather than a hook toggle — see SectionExtra.LAUNCHER.
    ToggleSection("Module", emptyList(), SectionExtra.LAUNCHER),
)
