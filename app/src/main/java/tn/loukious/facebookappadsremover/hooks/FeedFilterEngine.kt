package tn.loukious.facebookappadsremover.hooks

/**
 * A feed filter decision does not depend on how Facebook delivered the item.
 * The classic-feed, CSR cache, late-list and Litho hookers only adapt Facebook's
 * input/output types; all of them evaluate these same rules.
 *
 * Unknown/null items are retained (fail open). A rule must positively identify
 * an item before a pipeline is allowed to drop it.
 */
internal interface FeedItemSignals {
    fun category(item: Any): String?
    fun sponsored(item: Any): Boolean
    fun aiContent(item: Any): Boolean
    fun searchableText(item: Any): String?
}

internal class FeedItemFacts(val item: Any, private val signals: FeedItemSignals) {
    val category: String? by lazy(LazyThreadSafetyMode.NONE) { signals.category(item) }
    val sponsored: Boolean by lazy(LazyThreadSafetyMode.NONE) { signals.sponsored(item) }
    val aiContent: Boolean by lazy(LazyThreadSafetyMode.NONE) { signals.aiContent(item) }
    val searchableText: String? by lazy(LazyThreadSafetyMode.NONE) { signals.searchableText(item) }
}

internal interface FeedFilterRule {
    val id: String
    fun matches(item: FeedItemFacts): Boolean
}

internal class FeedFilterEngine(
    private val signals: FeedItemSignals,
    private val rules: List<FeedFilterRule>,
) {
    data class Decision(val ruleId: String?) {
        val remove: Boolean get() = ruleId != null
    }

    data class Partition(
        val kept: List<Any?>,
        val removedByRule: Map<String, Int>,
        val evaluatedByRule: Map<String, Int>,
        val inspected: Int,
    ) {
        val removed: Int get() = removedByRule.values.sum()
    }

    val active: Boolean get() = rules.isNotEmpty()

    fun decide(item: Any?): Decision = decide(item, null)

    private fun decide(item: Any?, evaluations: MutableMap<String, Int>?): Decision {
        if (item == null) return Decision(null)
        val facts = FeedItemFacts(item, signals)
        // First-match precedence is explicit and shared across every pipeline.
        for (rule in rules) {
            if (evaluations != null) {
                evaluations[rule.id] = (evaluations[rule.id] ?: 0) + 1
            }
            if (runCatching { rule.matches(facts) }.getOrDefault(false)) {
                return Decision(rule.id)
            }
        }
        return Decision(null)
    }

    fun partition(items: Iterable<*>): Partition {
        val kept = ArrayList<Any?>()
        val counts = LinkedHashMap<String, Int>()
        val evaluated = LinkedHashMap<String, Int>()
        var inspected = 0
        for (item in items) {
            inspected++
            val decision = decide(item, evaluated)
            if (decision.remove) {
                val id = decision.ruleId!!
                counts[id] = (counts[id] ?: 0) + 1
            } else {
                kept.add(item)
            }
        }
        return Partition(kept, counts, evaluated, inspected)
    }
}
