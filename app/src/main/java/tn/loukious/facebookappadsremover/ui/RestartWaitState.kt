package tn.loukious.facebookappadsremover.ui

/** Coordinates a restart without treating broadcast delivery as process termination. */
internal class RestartWaitState(private val deadline: Long) {
    enum class Completion { CONFIRMED, OPEN_ONLY, FAILED }

    private var endpoints: Set<String>? = null
    private val dead = HashSet<String>()
    private var armed = false
    private var finished = false

    val pending: Boolean
        @Synchronized get() = !finished

    /** Preparation is side-effect free; duplicate or late replies cannot start another restart. */
    @Synchronized
    fun prepared(ids: Set<String>, now: Long): Completion? {
        expire(now)?.let { return it }
        if (finished || endpoints != null) return null
        endpoints = ids.toSet()
        return if (ids.isEmpty()) complete(Completion.OPEN_ONLY) else null
    }

    /** All death watches must exist before the caller requests any process to stop. */
    @Synchronized
    fun arm(now: Long): Completion? {
        expire(now)?.let { return it }
        if (finished || endpoints == null) return null
        armed = true
        return confirmIfReady()
    }

    @Synchronized
    fun died(id: String, now: Long): Completion? {
        expire(now)?.let { return it }
        if (finished || endpoints?.contains(id) != true) return null
        dead.add(id)
        return confirmIfReady()
    }

    @Synchronized
    fun timeout(now: Long): Completion? = expire(now)

    @Synchronized
    fun fail(): Completion? = if (finished) null else complete(Completion.FAILED)

    @Synchronized
    fun cancel() {
        finished = true
    }

    private fun confirmIfReady(): Completion? =
        if (armed && endpoints?.all { it in dead } == true) complete(Completion.CONFIRMED) else null

    private fun expire(now: Long): Completion? =
        if (!finished && now >= deadline) complete(Completion.FAILED) else null

    private fun complete(completion: Completion): Completion {
        finished = true
        return completion
    }
}
