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
    /** Sub-heading the switch is listed under on its page; switches of one group stay together. */
    val group: String? = null,
)

/**
 * Non-toggle controls rendered inside a section, below its switches. [NONE] is
 * the common case: a section that is only switches.
 */
enum class SectionExtra {
    NONE,

    /** Free-text keyword list for the feed filter, shown under its switch while that is on. */
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
    Settings.MENU_HIDE_PREFIX + "1606854132932955" to R.drawable.ic_store,
    Settings.MENU_HIDE_PREFIX + "1120393419403920" to R.drawable.ic_sparkle,
    Settings.MENU_HIDE_PREFIX + "113779435978566" to R.drawable.ic_person,
    Settings.MENU_HIDE_PREFIX + "204127677323356" to R.drawable.ic_tag,
    Settings.MENU_HIDE_PREFIX + "2392950137" to R.drawable.ic_movie,
    Settings.MENU_HIDE_PREFIX + "513746992167374" to R.drawable.ic_gamepad,
    Settings.MENU_HIDE_PREFIX + "183217215062060" to R.drawable.ic_at,
    Settings.MENU_HIDE_PREFIX + "1450761518367453" to R.drawable.ic_star,
    Settings.MENU_HIDE_PREFIX + "303257506544370" to R.drawable.ic_history,
    Settings.MENU_HIDE_PREFIX + "586254444758776" to R.drawable.ic_label,
    Settings.MENU_HIDE_PREFIX + "2361831622" to R.drawable.ic_group,
    Settings.MENU_HIDE_PREFIX + "608920319153834" to R.drawable.ic_feed,
    Settings.MENU_HIDE_PREFIX + "2344061033" to R.drawable.ic_tag,
    Settings.MENU_HIDE_PREFIX + "250100865708545" to R.drawable.ic_layers,
    Settings.MENU_HIDE_PREFIX + "102784766491910" to R.drawable.ic_person,
    Settings.MENU_HIDE_PREFIX + "295535957817803" to R.drawable.ic_shield,
    Settings.MENU_HIDE_PREFIX + "413854060266105" to R.drawable.ic_shield,
    Settings.MENU_HIDE_PREFIX + "528216224033913" to R.drawable.ic_apps,
    Settings.MENU_HIDE_PREFIX + "280033845760645" to R.drawable.ic_block,
    Settings.MENU_HIDE_PREFIX + "2805739562851096" to R.drawable.ic_store,
    Settings.MENU_HIDE_PREFIX + "938806990723633" to R.drawable.ic_history,
    Settings.MENU_HIDE_PREFIX + "1188298679798386" to R.drawable.ic_sparkle,
    Settings.MENU_HIDE_PREFIX + "847392651283475" to R.drawable.ic_shield,
    Settings.MENU_HIDE_PREFIX + "580614790498088" to R.drawable.ic_info,
    Settings.MENU_HIDE_PREFIX + "528235453869350" to R.drawable.ic_bug,
    Settings.MENU_HIDE_PREFIX + "943638756156161" to R.drawable.ic_shield,
    Settings.MENU_HIDE_PREFIX + "1407107379423635" to R.drawable.ic_newspaper,
    Settings.MENU_HIDE_PREFIX + "342556368906156" to R.drawable.ic_movie,
    Settings.MENU_HIDE_PREFIX + "1546306509211461" to R.drawable.ic_at,
    Settings.MENU_HIDE_PREFIX + "124024574287414" to R.drawable.ic_stories,
    Settings.MENU_HIDE_PREFIX + "217974574879787" to R.drawable.ic_at,
    Settings.MENU_HIDE_PREFIX + "603734381593708" to R.drawable.ic_at,
    Settings.MENU_EXPAND to R.drawable.ic_layers,
    Settings.MENU_SECTION_PREFIX + "shortcut" to R.drawable.ic_link,
    Settings.MENU_SECTION_PREFIX + "help" to R.drawable.ic_info,
    Settings.MENU_SECTION_PREFIX + "settings" to R.drawable.ic_shield,
    Settings.MENU_SECTION_PREFIX + "upsell" to R.drawable.ic_sparkle,
    Settings.MENU_SECTION_PREFIX + "apps_from_facebook" to R.drawable.ic_apps,
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
    "Menu" to SectionInfo(R.drawable.ic_visibility_off,
        "Hides entries from the ≡ menu. The rest move up to fill the gap."),
    "Module" to SectionInfo(R.drawable.ic_apps, "Settings for this app itself."),
)

/** Developer switches, shown on the Diagnostics page rather than with features. */
val DEBUG_TOGGLES: List<ToggleSpec> = listOf(
    ToggleSpec(
        Settings.NAVIGATION_ACTIVITY_LIST,
        "Log opened screens",
        "Writes each internal Facebook screen to the log, so it ends up in the report",
        false,
    ),
)

private const val MENU_MAIN = "Menu entries"
private const val MENU_SHORTCUTS = "Your shortcuts"
private const val MENU_SETTINGS = "Settings & privacy"
private const val MENU_HELP = "Help & support"
private const val MENU_APPS = "Other Meta products"
private const val MENU_UPDATES = "Updates"

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
            "Hide posts that contain any word or phrase from your list, separated by commas",
            false,
        ),
        ToggleSpec(
            Settings.FEED_AUTO_REFRESH_BLOCK,
            "Block auto-refresh",
            "Stop the feed from jumping to the top and reloading when you return to the app",
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
    )),
    ToggleSection("Menu", listOf(
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "1606854132932955", "Hide Marketplace", "Remove Marketplace from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "1120393419403920", "Hide Meta AI", "Remove Meta AI from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "113779435978566", "Hide Avatars", "Remove Avatars from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "204127677323356", "Hide Your finds", "Remove Your finds (shopping picks) from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "2392950137", "Hide Reels", "Remove Reels from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "513746992167374", "Hide Games", "Remove Games from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "183217215062060", "Hide Pokes", "Remove Pokes from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "1450761518367453", "Hide Birthdays", "Remove Birthdays from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "303257506544370", "Hide Memories", "Remove Memories from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "586254444758776", "Hide Saved", "Remove Saved from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "2361831622", "Hide Groups", "Remove Groups from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "608920319153834", "Hide Feeds", "Remove Feeds from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "2344061033", "Hide Events", "Remove Events from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "250100865708545", "Hide Pages", "Remove Pages from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "102784766491910", "Hide Find friends", "Remove Find friends from the menu", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_EXPAND, "Show all entries",
            "Open the menu fully expanded and remove the See more button. List-style menu only", false, MENU_MAIN),
        ToggleSpec(Settings.MENU_SECTION_PREFIX + "shortcut", "Hide Your shortcuts",
            "Remove the whole Your shortcuts block", false, MENU_SHORTCUTS),
        ToggleSpec(Settings.MENU_SECTION_PREFIX + "settings", "Hide the whole section",
            "Remove Settings & privacy with everything in it. Settings stay reachable from your profile", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "295535957817803", "Hide Settings", "Remove Settings from Settings & privacy", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "413854060266105", "Hide Privacy Centre", "Remove Privacy Centre from Settings & privacy", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "528216224033913", "Hide Device requests", "Remove Device requests from Settings & privacy", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "280033845760645", "Hide Your ad activity", "Remove Your ad activity from Settings & privacy", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "2805739562851096", "Hide Orders and payments", "Remove Orders and payments from Settings & privacy", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "938806990723633", "Hide Link history", "Remove Link history from Settings & privacy", false, MENU_SETTINGS),
        ToggleSpec(Settings.MENU_SECTION_PREFIX + "help", "Hide the whole section",
            "Remove Help & support with everything in it", false, MENU_HELP),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "1188298679798386", "Hide Meta AI support assistant", "Remove Meta AI support assistant from Help & support", false, MENU_HELP),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "847392651283475", "Hide Scam protection centre", "Remove Scam protection centre from Help & support", false, MENU_HELP),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "580614790498088", "Hide Support", "Remove Support from Help & support", false, MENU_HELP),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "528235453869350", "Hide Report a problem", "Remove Report a problem from Help & support", false, MENU_HELP),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "943638756156161", "Hide Safety", "Remove Safety from Help & support", false, MENU_HELP),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "1407107379423635", "Hide Terms & policies", "Remove Terms & policies from Help & support", false, MENU_HELP),
        ToggleSpec(Settings.MENU_SECTION_PREFIX + "apps_from_facebook", "Hide the whole section",
            "Remove Other Meta products with everything in it", false, MENU_APPS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "342556368906156", "Hide Edits", "Remove Edits from Other Meta products", false, MENU_APPS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "1546306509211461", "Hide Threads", "Remove Threads from Other Meta products", false, MENU_APPS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "124024574287414", "Hide Instagram", "Remove Instagram from Other Meta products", false, MENU_APPS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "217974574879787", "Hide Messenger", "Remove Messenger from Other Meta products", false, MENU_APPS),
        ToggleSpec(Settings.MENU_HIDE_PREFIX + "603734381593708", "Hide WhatsApp", "Remove WhatsApp from Other Meta products", false, MENU_APPS),
        ToggleSpec(Settings.MENU_SECTION_PREFIX + "upsell", "Hide Updates",
            "Remove the Updates section with promo cards", false, MENU_UPDATES),
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
