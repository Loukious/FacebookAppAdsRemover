package tn.loukious.facebookappadsremover.hooks

/**
 * Cuts side-menu rows out of the Bloks `bookmarks_menu` response text.
 *
 * The response is JSON: a table of scripts (`"<key>":"<script>"`) and a tree of
 * minified components. A menu row's tap script carries Facebook's logging JSON
 * `{"bmid":<bookmark id>,...}`, and the row's subtree refers to that script by
 * its key. A row is therefore an array element whose subtree refers to exactly
 * one bookmark id. Hidden rows are removed from the text itself, so everything
 * else stays byte-for-byte what the server sent (no re-serialisation).
 */
object BloksMenuFilter {
    private val BMID = Regex("""bmid\W{1,6}(\d{5,20})""")

    /** Returns [text] without the rows of hidden bookmarks, or [text] itself when nothing changes. */
    fun filter(text: String, hidden: (Long) -> Boolean): String {
        if (!text.contains("bmid")) return text
        val scripts = scriptIds(text)
        if (scripts.isEmpty()) return text
        val all = scripts.values.toSet()
        // A menu with one entry has no "rows" to tell apart from their container.
        if (all.size < 2 || all.none(hidden)) return text
        val cuts = cuts(text, scripts, hidden)
        if (cuts.isEmpty()) return text
        val out = StringBuilder(text.length)
        var pos = 0
        for ((from, to) in cuts) {
            if (from < pos) continue // nested inside a row that is already cut
            out.append(text, pos, from)
            pos = to
        }
        out.append(text, pos, text.length)
        return out.toString()
    }

    /** Script key -> bookmark id, for every `"key":"script"` pair whose script names one bookmark. */
    private fun scriptIds(text: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] != '"') { i++; continue }
            val keyEnd = stringEnd(text, i)
            if (keyEnd + 2 < n && text[keyEnd + 1] == ':' && text[keyEnd + 2] == '"') {
                val valueEnd = stringEnd(text, keyEnd + 2)
                if (text.indexOf("bmid", keyEnd + 2).let { it in 0 until valueEnd }) {
                    val ids = BMID.findAll(text.substring(keyEnd + 2, valueEnd)).map { it.groupValues[1] }.toSet()
                    ids.singleOrNull()?.toLongOrNull()?.let { out[text.substring(i + 1, keyEnd)] = it }
                }
                i = valueEnd + 1
            } else {
                i = keyEnd + 1
            }
        }
        return out
    }

    /** Index of the closing quote of the string that opens at [open]. */
    private fun stringEnd(text: String, open: Int): Int {
        var i = open + 1
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '\\') i += 2 else if (c == '"') return i else i++
        }
        return n - 1
    }

    private class Frame(val array: Boolean, var elementStart: Int) {
        /** Bookmark ids seen in the current element (array) or in the whole object. */
        val ids = HashSet<Long>()
        /** Bookmark ids of the array's finished elements. */
        val done = HashSet<Long>()
        val elements = ArrayList<IntArray>() // start, end, 1 = cut
    }

    /** Text ranges to delete, sorted by start; a range nested in another follows it. */
    private fun cuts(text: String, scripts: Map<String, Long>, hidden: (Long) -> Boolean): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val stack = ArrayList<Frame>()
        val n = text.length
        var i = 0
        fun endElement(f: Frame, end: Int) {
            if (end > f.elementStart) {
                val cut = f.ids.size == 1 && hidden(f.ids.first())
                f.elements.add(intArrayOf(f.elementStart, end, if (cut) 1 else 0))
            }
        }
        while (i < n) {
            when (text[i]) {
                '"' -> {
                    // Words inside the string: references to script keys.
                    var j = i + 1
                    var word = -1
                    while (j < n) {
                        val c = text[j]
                        val isWord = c in 'a'..'z' || c in '0'..'9' || c in 'A'..'Z' || c == '_'
                        if (isWord) { if (word < 0) word = j } else {
                            if (word >= 0) {
                                scripts[text.substring(word, j)]?.let { id -> stack.lastOrNull()?.ids?.add(id) }
                                word = -1
                            }
                            if (c == '"') break
                            if (c == '\\') j++
                        }
                        j++
                    }
                    i = j + 1
                }
                '[', '{' -> { stack.add(Frame(text[i] == '[', i + 1)); i++ }
                ',' -> {
                    val f = stack.lastOrNull()
                    if (f != null && f.array) {
                        endElement(f, i)
                        f.done.addAll(f.ids)
                        f.ids.clear()
                        f.elementStart = i + 1
                    }
                    i++
                }
                ']', '}' -> {
                    val f = stack.removeLastOrNull()
                    if (f != null) {
                        if (f.array) {
                            endElement(f, i)
                            addCuts(f.elements, out)
                        }
                        // The parent sees everything this container referred to.
                        stack.lastOrNull()?.ids?.let { it.addAll(f.ids); it.addAll(f.done) }
                    }
                    i++
                }
                else -> i++
            }
        }
        out.sortWith(compareBy({ it.first }, { -it.second }))
        return out
    }

    /** Turns cut elements into text ranges, keeping the array's commas valid. */
    private fun addCuts(elements: List<IntArray>, out: MutableList<Pair<Int, Int>>) {
        var i = 0
        while (i < elements.size) {
            if (elements[i][2] == 0) { i++; continue }
            var j = i
            while (j + 1 < elements.size && elements[j + 1][2] == 1) j++
            val from: Int
            val to: Int
            if (j + 1 < elements.size) { from = elements[i][0]; to = elements[j + 1][0] } // run + its trailing comma
            else if (i > 0) { from = elements[i - 1][1]; to = elements[j][1] }            // leading comma + run
            else { from = elements[i][0]; to = elements[j][1] }                           // the whole array
            out.add(from to to)
            i = j + 1
        }
    }
}
