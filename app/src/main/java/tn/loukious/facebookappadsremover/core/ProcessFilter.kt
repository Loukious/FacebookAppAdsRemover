package tn.loukious.facebookappadsremover.core

/**
 * Decides which Facebook processes get the hook set.
 *
 * Facebook runs a dozen child processes. The ones listed here are background
 * or service processes that never show feed, video, game or ad UI, so hooking
 * them only costs memory: the class probe, AMOLED resolvers and cached hooks
 * add ~75 MB to each, and on a discovery-cache miss each of them would run its
 * own full DexKit scan of the secondary dexes.
 *
 * UI-capable children (e.g. :quicksilver for Instant Games, :adnw for
 * Audience Network) are deliberately not listed.
 */
object ProcessFilter {
    private const val FACEBOOK_PACKAGE = "com.facebook.katana"

    private val SKIPPED_SUFFIXES = listOf(
        ":fdidsync",
        ":fbns",
        ":notification",
        ":zygote_warmer",
        ":sandboxed_process",
        ":he_sandboxed_process",
        ":bsod",
        ":pretosproc",
    )

    fun isSkipped(processName: String?): Boolean {
        if (processName == null || !processName.startsWith("$FACEBOOK_PACKAGE:")) return false
        val suffix = processName.substring(FACEBOOK_PACKAGE.length)
        return SKIPPED_SUFFIXES.any { suffix.startsWith(it) }
    }
}
