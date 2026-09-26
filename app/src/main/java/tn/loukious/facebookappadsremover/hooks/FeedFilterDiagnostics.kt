package tn.loukious.facebookappadsremover.hooks

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import tn.loukious.facebookappadsremover.core.L

/**
 * Hook installation, actual invocation, item inspection, and removal are
 * separate states. An installed hook is NOT evidence its feed pipeline ran.
 */
internal object FeedFilterDiagnostics {
    private const val TAG = "FBAR.Filter"
    private class PipelineHealth {
        val installed = AtomicInteger()
        val invoked = AtomicInteger()
        val batches = AtomicInteger()
        val inspected = AtomicLong()
        val aiEvaluated = AtomicLong()
        val removed = AtomicLong()
        val aiRemoved = AtomicLong()
        val lastInvoke = AtomicLong(-1L)
    }

    private val health = FeedPipeline.values().associateWith { PipelineHealth() }
    private val scheduled = AtomicBoolean(false)

    fun installed(pipeline: FeedPipeline) {
        health.getValue(pipeline).installed.incrementAndGet()
    }

    fun installedCount(pipeline: FeedPipeline): Int = health.getValue(pipeline).installed.get()

    fun invoked(pipeline: FeedPipeline) {
        val stats = health.getValue(pipeline)
        stats.invoked.incrementAndGet()
        stats.lastInvoke.set(SystemClock.uptimeMillis())
    }

    fun batch(pipeline: FeedPipeline, result: FeedFilterEngine.Partition) {
        val stats = health.getValue(pipeline)
        val count = stats.batches.incrementAndGet()
        stats.inspected.addAndGet(result.inspected.toLong())
        stats.aiEvaluated.addAndGet((result.evaluatedByRule["AI_CONTENT"] ?: 0).toLong())
        stats.removed.addAndGet(result.removed.toLong())
        stats.aiRemoved.addAndGet((result.removedByRule["AI_CONTENT"] ?: 0).toLong())
        if (result.removed > 0 || count <= 3 || count % 50 == 0) {
            L.i(TAG, "pipeline=$pipeline batch=$count inspected=${result.inspected} " +
                "aiEvaluated=${result.evaluatedByRule["AI_CONTENT"] ?: 0} " +
                "removed=${result.removed} rules=${result.removedByRule}")
        }
    }

    fun blocked(pipeline: FeedPipeline, decision: FeedFilterEngine.Decision) {
        if (decision.remove) {
            val stats = health.getValue(pipeline)
            val matches = stats.removed.incrementAndGet()
            if (decision.ruleId == "AI_CONTENT") stats.aiRemoved.incrementAndGet()
            if (matches <= 5 || matches % 25L == 0L) {
                L.i(TAG, "pipeline=$pipeline removed=1 rule=${decision.ruleId} " +
                    "totalRemoved=$matches")
            }
        }
    }

    /** Litho evaluates one edge at a time, rather than an Iterable batch. */
    fun renderInspected(decision: FeedFilterEngine.Decision, aiEnabled: Boolean) {
        val stats = health.getValue(FeedPipeline.LITHO_RENDER)
        stats.inspected.incrementAndGet()
        // FeedContentRules evaluates sponsored/category rules BEFORE AI.
        // A decision on a prior category must not inflate AI evaluations.
        if (aiEnabled && (decision.ruleId == null || decision.ruleId == "AI_CONTENT" ||
                decision.ruleId == "KEYWORD")) {
            stats.aiEvaluated.incrementAndGet()
        }
    }

    /** Once per FB process, capture late-starting/cache-only pipelines too. */
    fun scheduleHealth() {
        if (!scheduled.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ logHealth("20s") }, 20_000L)
        handler.postDelayed({ logHealth("90s") }, 90_000L)
    }

    fun logHealth(stage: String) {
        val now = SystemClock.uptimeMillis()
        FeedPipeline.values().forEach { pipeline ->
            val stats = health.getValue(pipeline)
            val installed = stats.installed.get()
            val calls = stats.invoked.get()
            val evaluated = stats.aiEvaluated.get()
            val last = stats.lastInvoke.get()
            val state = when {
                installed == 0 -> "NO_HOOK"
                calls == 0 -> "HOOK_INSTALLED_NOT_INVOKED"
                stats.inspected.get() == 0L && pipeline != FeedPipeline.LITHO_RENDER ->
                    "INVOKED_NO_ITEMS"
                stats.removed.get() == 0L -> "INVOKED_NO_REMOVALS"
                else -> "REMOVING"
            }
            L.i(TAG, "health stage=$stage pipeline=$pipeline state=$state " +
                "installed=$installed invoked=$calls inspected=${stats.inspected.get()} " +
                "aiEvaluated=$evaluated removed=${stats.removed.get()} " +
                "aiRemoved=${stats.aiRemoved.get()} " +
                "idleMs=${if (last < 0) -1 else now - last}")
        }
    }
}
