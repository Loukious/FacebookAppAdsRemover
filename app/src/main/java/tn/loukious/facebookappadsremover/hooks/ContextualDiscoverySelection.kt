package tn.loukious.facebookappadsremover.hooks

/** Discovery precedence shared by the native contextual hooks. */
internal object ContextualDiscoverySelection {
    fun <T> withFallback(primary: () -> T, fallback: () -> T): T {
        val first = runCatching(primary)
        if (first.isSuccess) return first.getOrThrow()
        return try {
            fallback()
        } catch (failure: Throwable) {
            val primaryFailure = first.exceptionOrNull()!!
            if (primaryFailure !== failure) failure.addSuppressed(primaryFailure)
            throw failure
        }
    }

    data class Calls<T>(val candidates: List<T>, val includeHelpers: Boolean)

    fun <T> preferDirect(
        candidates: List<T>,
        directMatch: (T) -> Boolean,
        helperMatch: (T) -> Boolean,
    ): Calls<T> {
        val direct = candidates.filter(directMatch)
        // Keep ambiguous direct matches for the caller's uniqueness check.
        // Helpers must not change a previously valid direct selection.
        return if (direct.isNotEmpty()) Calls(direct, false)
        else Calls(candidates.filter(helperMatch), true)
    }
}
