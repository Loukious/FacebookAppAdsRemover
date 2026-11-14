package tn.loukious.facebookappadsremover.ui

/** Compare a restart with the revision it actually applied. */
internal data class PendingChangesState(val revision: Long = 0L, val pending: Boolean = false) {
    fun changed(): PendingChangesState = PendingChangesState(revision + 1L, true)

    fun applied(expectedRevision: Long): PendingChangesState =
        if (expectedRevision == revision) copy(pending = false) else this
}
