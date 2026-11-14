package tn.loukious.facebookappadsremover.ui

internal enum class SessionRequestKind {
    LEGACY, EXPORT, IMPORT, DIAGNOSTICS_SAVE, DIAGNOSTICS_SHARE;

    val isDiagnostics: Boolean
        get() = this == DIAGNOSTICS_SAVE || this == DIAGNOSTICS_SHARE

    fun requiresRestart(ok: Boolean): Boolean = this == IMPORT && ok
}

/** Outstanding requests retain their purpose across process recreation. */
internal class SessionRequestState(
    initial: Set<String> = emptySet(),
    kinds: Map<String, SessionRequestKind> = emptyMap(),
) {
    private val pending = initial.associateWith { kinds[it] ?: SessionRequestKind.LEGACY }.toMutableMap()

    fun register(nonce: String, kind: SessionRequestKind = SessionRequestKind.LEGACY) {
        require(nonce.isNotBlank())
        pending[nonce] = kind
    }

    fun kindOf(nonce: String?): SessionRequestKind? = pending[nonce]

    fun consume(nonce: String?): Boolean = nonce != null && pending.remove(nonce) != null

    fun snapshot(): Set<String> = pending.keys.toSet()

    fun kindsSnapshot(): Map<String, SessionRequestKind> = pending.toMap()
}
