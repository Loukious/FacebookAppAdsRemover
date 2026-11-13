package tn.loukious.facebookappadsremover.ui

/** Outstanding session requests; consuming one reply leaves every other request pending. */
internal class SessionRequestState(initial: Set<String> = emptySet()) {
    private val pending = initial.toMutableSet()

    fun register(nonce: String) {
        require(nonce.isNotBlank())
        pending.add(nonce)
    }

    fun consume(nonce: String?): Boolean = nonce != null && pending.remove(nonce)

    fun snapshot(): Set<String> = pending.toSet()
}
