package tn.loukious.facebookappadsremover.hooks

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Recognizes outbound links from the four normal Facebook browser activities. */
internal object ExternalBrowserPolicy {
    private const val FACEBOOK_PACKAGE = "com.facebook.katana"
    private val browserActivities = setOf(
        "com.facebook.browser.lite.BrowserLiteActivity",
        "com.facebook.browser.lite.BrowserLiteInMainProcessBottomSheetActivity",
        "com.facebook.browser.litev2.lite.BrowserLiteDIActivity",
        "com.facebook.browser.litev2.lite.BrowserLiteDITransparentActivity",
    )
    private val internalDomains = setOf("facebook.com", "fb.com", "messenger.com", "meta.com")

    fun destination(packageName: String?, className: String?, url: String?): String? {
        if (packageName != FACEBOOK_PACKAGE || className !in browserActivities) return null
        var target = webUri(url) ?: return null
        // Decode only known link shims, never a random Facebook page's query.
        repeat(4) {
            if (internalHost(target.host) &&
                (target.path == "/l.php" || target.path == "/flx/warn/")) {
                target = webUri(queryParameter(target, "u")) ?: return null
            }
        }
        return target.toString().takeUnless { internalHost(target.host) }
    }

    private fun webUri(url: String?): URI? = runCatching {
        val uri = URI(url ?: return null)
        uri.takeIf {
            it.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !it.host.isNullOrBlank() && it.rawUserInfo == null
        }
    }.getOrNull()

    private fun internalHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        return internalDomains.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun queryParameter(uri: URI, key: String): String? = runCatching {
        uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8") == key)
                URLDecoder.decode(pair[1], "UTF-8") else null
        }
    }.getOrNull()
}
