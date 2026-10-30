package tn.loukious.facebookappadsremover.hooks

/** One-shot, thread-safe account capture; cookies and username are best-effort. */
internal class AccountCaptureState(
    private val persist: (Map<String, String>) -> Unit,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val decodeCookies: (String) -> String?,
    private val onComplete: () -> Unit,
) {
    companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_COOKIE_JSON = "cookie_json"
        const val KEY_COOKIE_HTTP = "cookie_http"
    }

    data class Snapshot(
        val token: String? = null,
        val userId: String? = null,
        val username: String? = null,
        val cookies: String? = null,
    )

    @Volatile var completed = false
        private set
    @Volatile var authToken: String? = null
        private set

    private val values = HashMap<String, String>()
    private var warnedMissingCookies = false

    @Synchronized
    fun capture(snapshot: Snapshot) {
        // Unhooking does not cancel callbacks already in flight. They must
        // stay inert, including when removing a framework hook fails.
        if (completed) return
        val changed = LinkedHashMap<String, String>()
        fun update(key: String, value: String?) {
            if (!value.isNullOrBlank() && value != values[key]) changed[key] = value
        }

        val cookies = snapshot.cookies?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\t', ' ')
        if (cookies.isNullOrBlank()) {
            if (!warnedMissingCookies) {
                warnedMissingCookies = true
                warn("mSessionCookiesString empty — cookies are optional; capture uses token/user ID")
            }
        } else if (cookies != values[KEY_COOKIE_JSON]) {
            update(KEY_COOKIE_JSON, cookies)
            val http = decodeCookies(cookies)
            if (!http.isNullOrBlank()) update(KEY_COOKIE_HTTP, http)
            else warn("ViewerContext cookies unavailable as HTTP header; continuing with token/user ID")
        }
        update(KEY_AUTH_TOKEN, snapshot.token)
        update(KEY_USERNAME, snapshot.username)
        update(KEY_USER_ID, snapshot.userId)

        if (changed.isNotEmpty()) {
            // Remember observations before persistence so a storage failure
            // cannot turn the same equals() calls into a write/log retry loop.
            values.putAll(changed)
            authToken = values[KEY_AUTH_TOKEN]
            persist(changed)
            for (key in changed.keys) log("ViewerContext $key updated")
        }

        if (!authToken.isNullOrBlank() && !values[KEY_USER_ID].isNullOrBlank()) {
            completed = true
            log("account capture complete — unhooking equals()")
            onComplete()
        }
    }
}
