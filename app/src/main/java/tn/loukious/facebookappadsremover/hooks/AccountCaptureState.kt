package tn.loukious.facebookappadsremover.hooks

/**
 * Thread-safe account capture that follows the signed-in account for the
 * whole process lifetime; cookies and username are best-effort.
 *
 * Credentials are only ever stored as a pair: a token is accepted together
 * with the user ID it was observed with, so a token can never end up
 * attributed to another account.
 *  - A new user ID (account switch) replaces the whole record; values of the
 *    previous account that the new snapshot does not provide are removed.
 *  - The same user ID with a different token (refresh/replacement) updates
 *    the token.
 *  - [clear] drops everything (logout).
 * Before the first complete pair is seen, partial values are stored as they
 * arrive, as before.
 *
 * [persist] receives only changed keys; a null value means "remove".
 */
internal class AccountCaptureState(
    private val persist: (Map<String, String?>) -> Unit,
    private val log: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val decodeCookies: (String) -> String?,
) {
    companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_COOKIE_JSON = "cookie_json"
        const val KEY_COOKIE_HTTP = "cookie_http"
        private val ALL_KEYS = listOf(KEY_USER_ID, KEY_USERNAME, KEY_AUTH_TOKEN, KEY_COOKIE_JSON, KEY_COOKIE_HTTP)
    }

    data class Snapshot(
        val token: String? = null,
        val userId: String? = null,
        val username: String? = null,
        val cookies: String? = null,
    )

    /** True while a complete token + user ID pair is known. */
    @Volatile var completed = false
        private set
    @Volatile var authToken: String? = null
        private set

    private val values = HashMap<String, String>()
    private var warnedMissingCookies = false

    @Synchronized
    fun capture(snapshot: Snapshot) {
        val token = snapshot.token?.takeIf { it.isNotBlank() }
        val userId = snapshot.userId?.takeIf { it.isNotBlank() }
        val pair = token != null && userId != null
        val switching = pair && completed && userId != values[KEY_USER_ID]

        // Once a pair is known, a snapshot can only change the account data
        // when it carries a full pair: a lone token or ID can't be tied to
        // an account, and username/cookies only belong to the pair's user.
        if (completed && !pair) return

        val changed = LinkedHashMap<String, String?>()
        // On a switch every value is new, even one that happens to match the old account's.
        fun old(key: String) = if (switching) null else values[key]
        fun update(key: String, value: String?) {
            if (!value.isNullOrBlank() && value != old(key)) changed[key] = value
        }

        val cookies = snapshot.cookies?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\t', ' ')
        if (cookies.isNullOrBlank()) {
            if (!warnedMissingCookies) {
                warnedMissingCookies = true
                warn("mSessionCookiesString empty — cookies are optional; capture uses token/user ID")
            }
        } else if (cookies != old(KEY_COOKIE_JSON)) {
            update(KEY_COOKIE_JSON, cookies)
            val http = decodeCookies(cookies)
            if (!http.isNullOrBlank()) update(KEY_COOKIE_HTTP, http)
            else warn("ViewerContext cookies unavailable as HTTP header; continuing with token/user ID")
        }
        update(KEY_AUTH_TOKEN, token)
        update(KEY_USERNAME, snapshot.username)
        update(KEY_USER_ID, userId)

        if (switching) {
            // Nothing of the previous account may survive the switch.
            for (key in ALL_KEYS) if (key !in changed && values.containsKey(key)) changed[key] = null
        }

        if (changed.isNotEmpty()) {
            // Remember observations before persistence so a storage failure
            // cannot turn the same equals() calls into a write/log retry loop.
            for ((key, value) in changed) if (value == null) values.remove(key) else values[key] = value
            authToken = values[KEY_AUTH_TOKEN]
            persist(changed)
            if (switching) log("ViewerContext account changed")
            for ((key, value) in changed) if (value != null) log("ViewerContext $key updated")
        }

        if (!completed && !authToken.isNullOrBlank() && !values[KEY_USER_ID].isNullOrBlank()) {
            completed = true
            log("account capture complete")
        }
    }

    /** Logout: forget and remove every captured value. */
    @Synchronized
    fun clear() {
        if (values.isEmpty() && !completed) return
        val removed = values.keys.associateWith<String, String?> { null }
        values.clear()
        authToken = null
        completed = false
        if (removed.isNotEmpty()) persist(removed)
        log("account credentials cleared")
    }
}
