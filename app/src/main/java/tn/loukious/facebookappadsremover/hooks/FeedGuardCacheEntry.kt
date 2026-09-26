package tn.loukious.facebookappadsremover.hooks

/** Stable serializer for cached guard roles; persisted roles include the colon. */
internal data class FeedGuardCacheEntry(val role: String, val className: String) {
    companion object {
        fun parse(value: String): FeedGuardCacheEntry? {
            val separator = value.indexOf(':')
            if (separator <= 0 || separator == value.lastIndex) return null
            return FeedGuardCacheEntry(
                role = value.substring(0, separator + 1),
                className = value.substring(separator + 1),
            )
        }
    }
}
