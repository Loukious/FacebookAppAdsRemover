package tn.loukious.facebookappadsremover.core

import java.net.URI

/**
 * Public reports contain discovery/configuration diagnostics, not a replay of
 * the user's activity. Unknown messages and multiline payload continuations
 * are omitted rather than trying to recognize every possible private value.
 */
internal object DiagnosticsReportSanitizer {
    // `logcat -v time`: parse the tag, never accept a line merely because its
    // message happens to mention FBAR (including logs from other applications).
    private val logLine = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+[VDIWEAF]/([^\s(]+)\s*\(\s*\d+\): ?)(.*)$""",
    )
    private val hookTags = setOf(
        "FBAR.Account", "FBAR.Activity", "FBAR.Ads", "FBAR.AdsOptOut",
        "FBAR.Amoled", "FBAR.Background", "FBAR.Browser", "FBAR.CleanUrl",
        "FBAR.ContextDownload", "FBAR.Download", "FBAR.Feed", "FBAR.FeedGuard",
        "FBAR.GameAds", "FBAR.Marketplace", "FBAR.NavTabs", "FBAR.Privacy",
        "FBAR.Refresh", "FBAR.Theme", "FBAR.Tray", "FBAR.VideoResume",
    )
    private val modulePrefixes = listOf(
        "Module loaded in process:", "Package loaded:", "Skipping background process ",
        "Facebook ready", "No onCreate declaration ", "onAppCreated failed",
        "App onCreate completed", "Settings init failed", "Failed to load dexkit ",
        "Thread contextClassLoader:", "No discovery cache", "Discovery ",
        "discovery cache ", "STABLE_CLASS ", "DexKit scanning:", "No dex source ",
        "Cache install failed", "Hooks installed from cache", "Ad filter installation failed",
        "AMOLED ", "Background-playback ", "Game-ads ", "Privacy ", "Navigation tab ",
        "Activity-list ", "Clean-URL ", "External-browser ", "Session backup ",
        "Account capture hook installed", "Dark-mode activity hook installed",
        "Video params capture hook installed", "Cached ", "Banner scan failed",
        "Newsfeed filter installation failed", "Ads opt-out installation failed",
        "Story viewer ", "Hide-seen-story ", "Dark-mode ", "Auto-refresh ",
        "Stories-tray ", "Download hook ", "Media-setup hook ", "Contextual ", "Video playback ",
    )
    private val discoveryResult = Regex("""^(?:FOUND|NOT_FOUND|NOT_UNIQUE|QUERY_FAILED|RESOLVE_FAILED)\b""")
    private val hookDiagnostic = Regex(
        """^(?:hooked|hook failed|class resolve failed|stable class resolve failed|DexKit query failed|field missing|anchor not found|skipping string-table method)\b""",
        RegexOption.IGNORE_CASE,
    )
    // These prefixes come from installation messages, not user-controlled
    // Intent/media payloads. In particular, an arbitrary message containing
    // "hooked" or "installed" must never qualify as a diagnostic.
    private val hookPrefixes = mapOf(
        "FBAR.Account" to listOf("no instance equals()"),
        "FBAR.Activity" to listOf(
            "activity-list dump armed on Activity.startActivityForResult",
            "activity-list hook failed", "startActivityForResult(Intent,int,Bundle) not found",
        ),
        "FBAR.Download" to listOf(
            "activity tracker registration failed", "params ctor hook failed",
            "no constructors hookable on ", "VideoPlayerParams ctor hook installed",
            "clipboard qualities trigger installed", "clipboard hook failed",
            "source field not unique on ", "expected 3 Uri fields on ",
            "media-setup payload fields not found on ", "media-setup capture hook installed on ",
            "capture hook installed on ",
        ),
        "FBAR.VideoResume" to listOf(
            "pause-method query failed for ", "position getter:", "tracking pair not found on ",
            "seek shape ambiguous on ", "params getter ambiguous on ",
            "player member resolution incomplete on ", "video-resume hooks installed on ",
            "no Application context", "lifecycle save registered", "lifecycle registration failed",
        ),
        "FBAR.Background" to listOf(
            "pause methods not found on ", "seek shape ambiguous on ",
            "manual-pause constant resolve failed", "member resolution incomplete on ",
            "background-playback hooks installed on ", "foreground tracker registration failed",
        ),
        "FBAR.ContextDownload" to listOf(
            "native story Save hook installed:", "native story Repost menu injection ready",
            "story contextual discovery failed", "reel discovery:",
            "native reel Download button installed via ", "reel native-button discovery failed",
            "cached reel factory signature changed:", "reel runtime metadata resolve failed",
        ),
    )
    private val exceptionType = Regex(
        """^(?:Caused by: )?((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:Exception|Error))(?::.*)?$""",
    )
    private val privateFields = Regex(
        """\b(?:access_?token|auth_?token|mAuthToken|FBTOKEN|FBFULLCOOKIE|cookies?|mSessionCookiesString|username|user_?id|video_?id|comment_?id|page_?id|nonce)\s*[:=]""",
        RegexOption.IGNORE_CASE,
    )
    private val urls = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s<>]+""")
    private val sessionFile = Regex("""FBAR-Session-[^\s/\\]+|(?:logged_in_|dbl_local_auth_)\d+""")
    private val longIdentifier = Regex("""(?<![\w$])\d{7,}(?:_\d+)*(?![\w$])""")
    private val privatePath = Regex("""/(?:data/(?:user(?:_de)?|data)|storage|sdcard)/[^\s,\]\)]+""")

    /** Return a shareable logcat line, or null for runtime/private payloads. */
    fun sanitizeLine(line: String): String? {
        val parsed = logLine.matchEntire(line) ?: return null
        val prefix = parsed.groupValues[1]
        val tag = parsed.groupValues[2]
        val message = parsed.groupValues[3]
        val knownTag = tag in hookTags || tag in setOf(
            "FacebookAppAdsRemover", "FBAR.Discovery", "FBAR.Settings", "FBAR.Filter", "FBAR.Session",
        )
        if (!knownTag) return null

        // Throwable messages may contain request bodies, credentials or local
        // filenames. Keep their type for diagnosis; omit all stack/payload
        // continuation lines, which do not match the diagnostic allowlist.
        exceptionType.matchEntire(message.trim())?.let {
            return prefix + it.groupValues[1] + ": <message omitted>"
        }
        if (message.firstOrNull()?.isWhitespace() == true || privateFields.containsMatchIn(message)) return null

        val diagnostic = when (tag) {
            "FacebookAppAdsRemover" -> modulePrefixes.any(message::startsWith) ||
                hookDiagnostic.containsMatchIn(message)
            "FBAR.Discovery" -> discoveryResult.containsMatchIn(message)
            "FBAR.Settings" -> message.startsWith("toggle source:") || message.startsWith("getRemotePreferences(")
            "FBAR.Filter" -> message.startsWith("pipeline=") || message.startsWith("health stage=")
            "FBAR.Session" -> message == "session backup receiver registered" ||
                message == "session backup receiver registration failed"
            "FBAR.Activity" -> hookPrefixes.getValue(tag).any(message::startsWith)
            else -> discoveryResult.containsMatchIn(message) || hookDiagnostic.containsMatchIn(message) ||
                hookPrefixes[tag].orEmpty().any(message::startsWith)
        }
        if (!diagnostic) return null

        // Discovery's RESOLVE_FAILED includes Throwable.toString() inline.
        // Other accepted diagnostics may also append an exception inline.
        val withoutExceptionMessage = message.replace(
            Regex("""((?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(?:Exception|Error)):.*"""),
            "$1: <message omitted>",
        )
        val sanitized = redactUrls(withoutExceptionMessage)
            .replace(privatePath, "<private path>")
            .replace(sessionFile, "<session file>")
            .replace(longIdentifier, "<id>")
        return prefix + sanitized
    }

    /** URI parsing keeps only a real network host, never userinfo or a file path. */
    internal fun redactUrls(message: String): String = urls.replace(message) { match ->
        val host = runCatching { URI(match.value).host }.getOrNull()
        if (host.isNullOrBlank()) "<link>" else "<link:$host>"
    }
}
