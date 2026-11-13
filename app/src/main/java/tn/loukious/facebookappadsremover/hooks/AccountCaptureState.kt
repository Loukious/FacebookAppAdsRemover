package tn.loukious.facebookappadsremover.hooks

/**
 * Follows authoritative session state for the process lifetime. The caller
 * reads that state inside its capture lock, immediately before [capture].
 * Authentication snapshots can replace optional fields only when both credentials
 * match the current pair; a retained context cannot change the account or restore
 * an old token. A matching canonical snapshot's missing optional values remove
 * earlier values, including when the pair is unchanged. Unknown authority performs
 * no writes; logout clears.
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

    sealed interface Session {
        data class SignedIn(val token: String, val userId: String) : Session {
            init { require(token.isNotBlank() && userId.isNotBlank()) }
        }
        data object LoggedOut : Session
        data object Unknown : Session
    }

    /** True while a complete token + user ID pair is known. */
    @Volatile var completed = false
        private set
    @Volatile var authToken: String? = null
        private set

    private val values = HashMap<String, String>()
    private var warnedMissingCookies = false
    private var cleared = false

    @Synchronized
    fun capture(snapshot: Snapshot, session: Session) {
        val current = when (session) {
            Session.Unknown -> return
            Session.LoggedOut -> { clear(); return }
            is Session.SignedIn -> session
        }
        val pairChanged = current.token != values[KEY_AUTH_TOKEN] || current.userId != values[KEY_USER_ID]
        val accountChanged = completed && current.userId != values[KEY_USER_ID]
        val matchingSample = snapshot.token == current.token && snapshot.userId == current.userId

        val changed = LinkedHashMap<String, String?>()
        fun old(key: String) = if (pairChanged) null else values[key]
        fun update(key: String, value: String?) {
            if (!value.isNullOrBlank() && value != old(key)) changed[key] = value
        }
        fun optional(key: String, value: String?) {
            if (value.isNullOrBlank()) {
                if (values.containsKey(key)) changed[key] = null
            } else update(key, value)
        }

        // The authority supplies credentials even if the candidate context is stale.
        update(KEY_AUTH_TOKEN, current.token)
        update(KEY_USER_ID, current.userId)
        if (matchingSample) {
            optional(KEY_USERNAME, snapshot.username)
            val cookies = snapshot.cookies?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\t', ' ')
            if (cookies.isNullOrBlank()) {
                optional(KEY_COOKIE_JSON, null)
                optional(KEY_COOKIE_HTTP, null)
                if (!warnedMissingCookies) {
                    warnedMissingCookies = true
                    warn("authentication cookies empty — capture uses token/user ID")
                }
            } else if (cookies != old(KEY_COOKIE_JSON)) {
                update(KEY_COOKIE_JSON, cookies)
                val http = runCatching { decodeCookies(cookies) }.getOrNull()
                if (!http.isNullOrBlank()) update(KEY_COOKIE_HTTP, http)
                else {
                    changed[KEY_COOKIE_HTTP] = null
                    warn("authentication cookies unavailable as HTTP header; continuing with token/user ID")
                }
            }
        }

        if (pairChanged) {
            // Include keys unknown to this process so older disk preferences are cleared too.
            for (key in ALL_KEYS) if (key !in changed) changed[key] = null
        }

        if (changed.isNotEmpty()) {
            // Remember observations before persistence so a storage failure
            // cannot turn the same equals() calls into a write/log retry loop.
            for ((key, value) in changed) if (value == null) values.remove(key) else values[key] = value
            authToken = values[KEY_AUTH_TOKEN]
            cleared = false
            val firstPair = !completed
            completed = true
            persist(changed)
            if (accountChanged) log("authentication account changed")
            for ((key, value) in changed) if (value != null) log("authentication session $key updated")
            if (firstPair) log("authentication session captured")
        }
    }

    /** Logout: forget and remove every captured value. */
    @Synchronized
    fun clear() {
        if (cleared) return
        val removed = ALL_KEYS.associateWith<String, String?> { null }
        values.clear()
        authToken = null
        completed = false
        cleared = true
        persist(removed)
        log("account credentials cleared")
    }
}
