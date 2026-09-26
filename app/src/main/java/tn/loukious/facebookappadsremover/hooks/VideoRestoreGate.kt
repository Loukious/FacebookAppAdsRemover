package tn.loukious.facebookappadsremover.hooks

import kotlin.math.abs

/**
 * One-shot restoration for one player/video playback session. Facebook can
 * emit maybeTrackVideoStart multiple times while scrubbing, buffering or
 * resuming playback. Those events must NOT arm another jump to an old point.
 *
 * No Android dependency: the critical seek/restore race is unit tested on
 * the JVM with a caller-supplied monotonic clock.
 */
internal class VideoRestoreGate {
    private var startEvaluated = false
    private var stopped = false
    private var manualSeekAt = -1L
    private var appliedSeekAt = -1L
    private var appliedSeekMs = -1
    private var targetMs = 0L
    private var deadlineAt = 0L
    private var restoring = false
    private var startedAt = -1L
    private var automaticDeparture = false
    private var resetPositionMs = -1
    private var resetAt = -1L

    @Synchronized
    fun armOnce(savedPositionMs: Long?, nowMs: Long, restoreWindowMs: Long): Boolean {
        if (startEvaluated || stopped) return false
        // Even an absent/short saved position consumes this session's start:
        // a later repeated start must not acquire a newly saved position.
        startEvaluated = true
        startedAt = nowMs
        val saved = savedPositionMs ?: return false
        if (saved < 1001L) return false
        targetMs = saved.coerceAtMost(Int.MAX_VALUE.toLong())
        deadlineAt = nowMs + restoreWindowMs
        return true
    }

    /** A manual/internal seek cancels the restoration, including its runnable. */
    @Synchronized
    fun cancelForSeek(nowMs: Long): Boolean {
        if (restoring) return false // The hook observed our own restore seek.
        manualSeekAt = nowMs
        // A later explicit user seek supersedes any previously observed
        // automatic Reel departure/reset; it must not enable a fresh arm.
        automaticDeparture = false
        resetPositionMs = -1
        resetAt = -1L
        targetMs = 0L
        deadlineAt = 0L
        startEvaluated = true
        return true
    }

    /** Called only after the original Facebook seek returned successfully. */
    @Synchronized
    fun recordAppliedSeek(requestedMs: Int, nowMs: Long) {
        appliedSeekMs = requestedMs.coerceAtLeast(0)
        appliedSeekAt = nowMs
    }

    /** Avoid immediately overwriting a backward seek with a stale getter on stop. */
    @Synchronized
    fun positionToSave(actualMs: Int, nowMs: Long): Int = when {
        appliedSeekAt >= 0 && nowMs - appliedSeekAt in 0..2500L &&
            abs(actualMs.toLong() - appliedSeekMs.toLong()) > 1500L -> appliedSeekMs
        resetAt >= 0 && resetAt >= appliedSeekAt &&
            nowMs - resetAt in 0..2500L && resetPositionMs >= 1000 &&
            actualMs < resetPositionMs - 1500 -> resetPositionMs
        else -> actualMs
    }

    /** Called before Facebook automatically rewinds a hidden Reel to zero. */
    @Synchronized
    fun recordAutomaticReset(positionBeforeResetMs: Int, nowMs: Long) {
        automaticDeparture = true
        if (positionBeforeResetMs >= 1000) {
            resetPositionMs = positionBeforeResetMs
            resetAt = nowMs
        }
    }

    /** Return a target at most once. A fresh start cannot re-arm this gate. */
    @Synchronized
    fun consumeIfValid(nowMs: Long, currentPositionMs: Int, minAdvanceMs: Long = 1000L): Long? {
        val target = targetMs
        // Our restore never moves a user backwards or repositions a video
        // whose natural playback position is already near/past the target.
        if (stopped || restoring || target < 1001L || currentPositionMs < 0 ||
            deadlineAt <= 0 || nowMs > deadlineAt ||
            target - currentPositionMs <= minAdvanceMs) {
            targetMs = 0L
            deadlineAt = 0L
            return null
        }
        targetMs = 0L
        deadlineAt = 0L
        restoring = true
        return target
    }

    @Synchronized
    fun finishRestore() { restoring = false }

    @Synchronized
    fun isRestoring(): Boolean = restoring

    @Synchronized
    fun wasJustStarted(nowMs: Long, startupWindowMs: Long): Boolean =
        startedAt >= 0 && nowMs - startedAt in 0..startupWindowMs

    @Synchronized
    fun markStopped() {
        stopped = true
        targetMs = 0L
        deadlineAt = 0L
    }

    /**
     * A real stop/reopen may start a new session. A stop/start immediately
     * following a seek is frequently Facebook's own scrub/rebuffer sequence:
     * do not replay the stored position in that case.
     */
    @Synchronized
    fun mayBeginNewSession(nowMs: Long, seekCooldownMs: Long): Boolean =
        stopped && (automaticDeparture || manualSeekAt < 0 || nowMs - manualSeekAt >= seekCooldownMs)

    @Synchronized
    fun wasRecentlySought(nowMs: Long, windowMs: Long): Boolean =
        manualSeekAt >= 0 && nowMs - manualSeekAt in 0..windowMs
}
