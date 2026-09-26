package tn.loukious.facebookappadsremover.hooks

/**
 * FbGrootPlayer uses the same seek entry for user scrubbing and automatic
 * positioning during a Reel's startup/transition. Only the former may cancel
 * a pending resume or replace the saved position.
 *
 * These are stable names from Facebook's player-reason enum (the obfuscated
 * fields containing the enum constants change between app versions).
 */
internal object VideoSeekPolicy {
    private val INTERNAL_POSITIONING = setOf(
        // Facebook's pooled Reel player dispatches both its automatic
        // start-position seek and its reset-to-zero with BY_AUTOPLAY. v1.19
        // interpreted those as manual seeks and repeatedly suppressed resume.
        "BY_AUTOPLAY",
        // Facebook 580 FbShortsViewerVideoPlaybackController calls EeY(0)
        // with this reason whenever the viewer hides a short Reel.
        "BY_SHORT_FORM_VIDEO_INVISIBLE",
        "BY_SHORT_FORM_VIDEO_ONPAUSE",
        "BY_SHORT_FORM_VIDEO_ONRESUME",
        "BY_SHORT_FORM_VIDEO_FULLY_VISIBLE",
        "BY_INITIAL_FRAME_PERFECT_SEEK",
        "BY_VIDEO_CLIP_START_TIME",
        "BY_ABSOLUTE_SEEK_BY_TRANSITION",
        "BY_ABSOLUTE_SEEK_BY_TRANSITION_WITH_PREVIEW",
    )

    private val EXPLICIT_USER_SEEK = setOf(
        "BY_SEEKBAR_CONTROLLER",
        "BY_USER",
        "BY_USER_SWIPE",
        "BY_USER_GESTURE",
    )

    fun isReelDeparture(reasonName: String?): Boolean =
        reasonName == "BY_SHORT_FORM_VIDEO_INVISIBLE" ||
            reasonName == "BY_SHORT_FORM_VIDEO_ONPAUSE"

    fun isAutomaticReset(reasonName: String?, requestedMs: Int, currentMs: Int?): Boolean =
        requestedMs == 0 && currentMs != null && currentMs > 0 &&
            (reasonName == "BY_AUTOPLAY" || isReelDeparture(reasonName))

    fun shouldObserveAsUserSeek(
        reasonName: String?,
        requestedMs: Int,
        currentMs: Int?,
        recentlyStarted: Boolean,
    ): Boolean {
        if (reasonName in INTERNAL_POSITIONING) return false
        if (reasonName in EXPLICIT_USER_SEEK) return true
        // Reels frequently position a newly acquired player at 0 through a
        // generic seek. Do not mistake that no-op for a manual rewind. A real
        // rewind from a later point, even to zero, remains observable.
        if (recentlyStarted && requestedMs in 0..1000 &&
            currentMs != null && currentMs in 0..1000) return false
        return true // Unknown reasons fail safe: user's seek has precedence.
    }
}
