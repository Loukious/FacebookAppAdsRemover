package tn.loukious.facebookappadsremover.ui

import android.content.Context
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * GitHub contributors, from the snapshot that scripts/update_contributors.py
 * writes into app/contributors (shipped as assets: no network at runtime).
 */
data class Contributor(
    val login: String,
    val added: Int,
    val removed: Int,
    val url: String,
    val avatar: String,
    val owner: Boolean,
)

fun loadContributors(context: Context): List<Contributor> = runCatching {
    val json = context.assets.open("contributors.json").bufferedReader().use { it.readText() }
    val array = JSONArray(json)
    List(array.length()) { i ->
        val o = array.getJSONObject(i)
        Contributor(
            login = o.getString("login"),
            added = o.getInt("added"),
            removed = o.getInt("removed"),
            url = o.getString("url"),
            avatar = o.getString("avatar"),
            owner = o.optBoolean("owner", false),
        )
    }
}.getOrDefault(emptyList())

/** GitHub-style five-block diff bar: share of added vs removed lines. */
internal fun diffBlocks(added: Int, removed: Int, blocks: Int = 5): Pair<Int, Int> {
    val total = added.toLong() + removed
    if (total == 0L) return 0 to 0
    var plus = (blocks * added.toDouble() / total).roundToInt()
    var minus = blocks - plus
    // Any non-zero side gets at least one block.
    if (added > 0 && plus == 0) { plus = 1; minus = blocks - 1 }
    if (removed > 0 && minus == 0) { minus = 1; plus = blocks - 1 }
    return plus to minus
}

