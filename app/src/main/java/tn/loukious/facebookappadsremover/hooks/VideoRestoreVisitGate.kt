package tn.loukious.facebookappadsremover.hooks

/**
 * A Groot player may emit stop/start on every natural loop. Its per-player
 * VideoRestoreGate can consequently be replaced even though the user has not
 * actually left the Reel. Limit restoring across those transient sessions.
 *
 * This is deliberately conservative: after one restore a video must have a
 * different video start AND sufficient time away before it can restore again.
 * Safety (no seek feedback loop) takes priority over restoring a rapid revisit.
 */
internal class VideoRestoreVisitGate {
    private data class Restored(val atMs: Long, val visit: Long, var awayAtMs: Long = -1L)

    private var currentVideoId: String? = null
    private var currentVisit = 0L
    private val restored = LinkedHashMap<String, Restored>()

    @Synchronized
    fun observeStart(videoId: String, nowMs: Long) {
        if (currentVideoId == videoId) return
        val previous = currentVideoId
        if (previous != null) {
            restored[previous]?.takeIf { it.awayAtMs < 0L }?.awayAtMs = nowMs
        }
        currentVideoId = videoId
        currentVisit++
    }

    @Synchronized
    fun mayRestore(videoId: String, nowMs: Long): Boolean {
        if (currentVideoId != videoId) return false
        val previous = restored[videoId] ?: return true
        if (previous.visit == currentVisit) return false
        if (previous.awayAtMs <= previous.atMs) return false
        if (nowMs - previous.awayAtMs < 1_500L) return false
        if (nowMs - previous.atMs < 5_000L) return false
        return true
    }

    /** Called BEFORE invoking Facebook's seek, which can recurse into hooks. */
    @Synchronized
    fun markRestore(videoId: String, nowMs: Long): Boolean {
        if (!mayRestore(videoId, nowMs)) return false
        restored[videoId] = Restored(nowMs, currentVisit)
        // Bounded even if a user watches thousands of different videos.
        while (restored.size > 256) restored.remove(restored.keys.first())
        return true
    }
}
