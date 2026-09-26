package tn.loukious.facebookappadsremover.core

/** Ad surfaces that are independently configurable in the settings screen. */
enum class AdSurface { NEWS_FEED, STORIES, REELS }

/**
 * Explicit ownership of the original mod's ad targets. Obfuscated Facebook
 * methods may move between versions, but our semantic target keys do not.
 *
 * A hook used by more than one surface is enabled ONLY when every affected
 * surface is enabled. That intentionally favors a missed shared ad over
 * blocking content on a surface whose user-facing toggle is off.
 */
object AdSurfacePolicy {
    private val feed = setOf(AdSurface.NEWS_FEED)
    private val story = setOf(AdSurface.STORIES)
    private val reel = setOf(AdSurface.REELS)
    private val feedReel = feed + reel
    private val storyReel = story + reel
    private val allSurfaces = feed + storyReel

    private val targets = mapOf(
        // Feed-sponsored fetch/pool/render.
        "ads.adChannelFetch" to feed,
        "ads.adChannelRequest" to feed,
        "ads.sponsoredVend" to feed,
        "ads.sponsoredPoolAdd" to feedReel, // Also video-home sponsored pool.
        "ads.sponsoredPaSelect" to feed,
        "ads.multiAdRender" to feed,
        "ads.sponsoredAttachmentRender" to feed,
        "ads.sponsoredRender" to feed,

        // Generic in-stream/video playback paths can also be reached from
        // Stories. Reels-only fetch/UI hooks remain usable independently.
        "ads.videoAdFetch" to storyReel,
        "ads.extendedBreaksFetch" to storyReel,
        "ads.adBreakController" to storyReel,
        "ads.tapToFullscreenAdFetch" to storyReel,
        "ads.inContentVideoRender" to storyReel,
        "ads.videoHomeAdVend" to reel,
        "ads.reelsVideoAdQuery" to reel,
        "ads.reelsBannerRender" to reel,
        "ads.reelsAdParams" to reel,
        "ads.reelsPostLoopBanner" to reel,
        "ads.storyAdBucketParse" to story,
        "ads.adBreakStorySet" to story, // Story-player ad-break setter.
        "ads.shortsMidCardAd" to feedReel, // Shorts unit also appears in Home feed.
    )

    fun targetSurfaces(key: String): Set<AdSurface>? = targets[key]

    /** Pure, testable gate used by direct and banner hooks alike. */
    fun shouldBlock(
        surfaces: Set<AdSurface>,
        isEnabled: (AdSurface) -> Boolean,
    ): Boolean = surfaces.isNotEmpty() && surfaces.all(isEnabled)

    /**
     * Banner class discovery is by strings, not a feed-row context. A class
     * with anchors from multiple surfaces is treated as shared and blocked
     * only when all associated switches are enabled.
     */
    fun bannerSurfaces(anchor: String): Set<AdSurface> {
        val normalized = anchor.lowercase()
        return when {
            // Messenger JNI and mobile-plan upgrade banners are not a News
            // Feed/Story/Reels ad and must not be swept with these switches.
            "mailboxinthreadadcontext" in normalized ||
                "banner_upgrade_mobile_plan" in normalized -> emptySet()
            "reel" in normalized || "affiliate_link" in normalized -> reel
            "instream" in normalized || "adbreak" in normalized -> storyReel
            // Generic banner classes can run on any feed/viewer. Without
            // runtime surface provenance, block only when ALL are selected.
            else -> allSurfaces
        }
    }
}
