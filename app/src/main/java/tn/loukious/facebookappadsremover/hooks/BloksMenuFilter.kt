package tn.loukious.facebookappadsremover.hooks

/**
 * Edits the Bloks `bookmarks_menu` response text before Facebook parses it.
 *
 * The response is JSON: a table of scripts (`"<key>":"<script>"`), a table of
 * state variables and a tree of minified components. Three things are
 * recognised in it, each by a marker that is not minified:
 *
 * - **Rows.** A row's tap script carries Facebook's logging JSON
 *   `{"bmid":<bookmark id>,...}` and the row's subtree refers to that script by
 *   its key, so a row is an array element that refers to exactly one bookmark.
 * - **Sections** (Help, Settings, ...). Facebook can hide each section itself:
 *   the section's visibility script reads its key (`"help"`) from the state
 *   `PE_PLAZA_SECTIONS:is_hidden_map` and yields `"gone"` or `"visible"`.
 * - **The "See more" button.** Its label script names `see_more_label` and a
 *   boolean state that also shows or hides the extra rows.
 *
 * Everything is done on the text itself (cut a range, or flip one `false`), so
 * the rest stays byte-for-byte what the server sent. Whatever is not recognised
 * is left alone: the worst case is that an entry stays visible.
 */
object BloksMenuFilter {

    /** What to remove. */
    class Rules(
        val bookmark: (Long) -> Boolean,
        val section: (String) -> Boolean = { false },
        /** Show the rows behind "See more" from the start and drop the button. */
        val expand: Boolean = false,
    )

    private val BMID = Regex("""bmid\W{1,6}(\d{5,20})""")
    private val QUOTED_WORD = Regex("""\\"([a-z][a-z0-9_]*)\\"""")
    private val QUOTED_ID = Regex("""\\"([0-9a-z]{4,})\\"""")
    private const val SECTIONS_STATE = "\"key\":\"PE_PLAZA_SECTIONS:is_hidden_map\""
    private const val MORE_MARKER = "see_more_label"

    /** Menu responses are ~250k chars; anything huge is not worth scanning on the parser's thread. */
    private const val MAX_CHARS = 4_000_000

    fun filter(text: String, hidden: (Long) -> Boolean): String = filter(text, Rules(hidden))

    /** Returns the edited text, or [text] itself when nothing changes. */
    fun filter(text: String, rules: Rules): String {
        if (text.length > MAX_CHARS || !text.contains("bmid")) return text
        val scripts = scriptIds(text)
        val all = scripts.values.toSet()
        // A menu with one entry has no "rows" to tell apart from their container.
        val rows = all.size >= 2 && all.any(rules.bookmark)
        val sectionsState = stateIdBefore(text, text.indexOf(SECTIONS_STATE))
        val expandEdit = if (rules.expand) expandEdit(text) else null
        if (!rows && sectionsState == null && expandEdit == null) return text

        val edits = ArrayList<Edit>()
        edits += scan(
            text, scripts,
            bookmark = if (rows) rules.bookmark else { _ -> false },
            sectionsState = sectionsState, section = rules.section,
            // The button goes only together with the state flip: never hide rows for good.
            cutMoreButton = expandEdit != null,
        )
        if (expandEdit != null) edits += expandEdit
        if (edits.isEmpty()) return text
        edits.sortWith(compareBy({ it.from }, { -it.to }))
        val out = StringBuilder(text.length)
        var pos = 0
        for (e in edits) {
            if (e.from < pos) continue // nested inside a range that is already cut
            out.append(text, pos, e.from).append(e.replacement)
            pos = e.to
        }
        out.append(text, pos, text.length)
        return out.toString()
    }

    /**
     * True when [text] names a hidden bookmark at all. If it does and [filter]
     * still cut nothing, the response format has changed.
     */
    fun namesHidden(text: String, hidden: (Long) -> Boolean): Boolean =
        text.length <= MAX_CHARS && text.contains("bmid") && scriptIds(text).values.any(hidden)

    private class Edit(val from: Int, val to: Int, val replacement: String = "")

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

    /** The id of the state entry (`{"id":"..","type":"..","data":{..}}`) whose body is at [at]. */
    private fun stateIdBefore(text: String, at: Int): String? {
        if (at < 0) return null
        val prefix = "{\"id\":\""
        val open = text.lastIndexOf(prefix, at)
        if (open < 0 || at - open > 200) return null
        val start = open + prefix.length
        val end = text.indexOf('"', start)
        return if (end in (start + 1) until at) text.substring(start, end) else null
    }

    /** Flips the "See more" state to expanded, or null when the response does not look as expected. */
    private fun expandEdit(text: String): Edit? {
        val marker = text.indexOf(MORE_MARKER)
        if (marker < 0) return null
        val open = text.lastIndexOf("\"\\t", marker) // a script: a string that starts with a tab
        if (open < 0 || marker - open > 2000) return null
        val close = stringEnd(text, open)
        if (close < marker) return null
        for (m in QUOTED_ID.findAll(text.substring(open, close))) {
            val entry = "{\"id\":\"${m.groupValues[1]}\",\"type\":\"ls\",\"data\":{\"initial\":false}}"
            val at = text.indexOf(entry)
            if (at >= 0) {
                val from = at + entry.length - "false}}".length
                return Edit(from, from + "false".length, "true")
            }
        }
        return null
    }

    private class Frame(val array: Boolean, var elementStart: Int) {
        /** Bookmark ids seen in the current element (array) or in the whole object. */
        val ids = HashSet<Long>()
        /** Bookmark ids of the array's finished elements. */
        val done = HashSet<Long>()
        /** Section key whose visibility script is in the current element / this object. */
        var section: String? = null
        /** Section key found in a bare string element: belongs to whatever holds this array. */
        var sectionUp: String? = null
        /** "See more" marker seen in the current element / in finished elements. */
        var more = false
        var moreDone = false
        val elements = ArrayList<IntArray>() // start, end, cut, "See more" candidate, has bookmarks
    }

    private fun scan(
        text: String,
        scripts: Map<String, Long>,
        bookmark: (Long) -> Boolean,
        sectionsState: String?,
        section: (String) -> Boolean,
        cutMoreButton: Boolean,
    ): List<Edit> {
        val out = ArrayList<Edit>()
        val stack = ArrayList<Frame>()
        val n = text.length
        var i = 0
        fun endElement(f: Frame, end: Int) {
            if (end > f.elementStart) {
                // Rows and sections are components, i.e. objects: never cut a bare string or number.
                val isObject = text.substring(f.elementStart, end).trim().let { it.startsWith('{') && it.endsWith('}') }
                var cut = isObject && f.ids.size == 1 && bookmark(f.ids.first())
                if (isObject) {
                    if (f.section?.let(section) == true) cut = true
                } else if (f.section != null) {
                    f.sectionUp = f.section
                }
                val moreCandidate = isObject && f.more && f.ids.isEmpty()
                f.elements.add(intArrayOf(f.elementStart, end, if (cut) 1 else 0,
                    if (moreCandidate) 1 else 0, if (f.ids.isEmpty()) 0 else 1))
            }
            f.done.addAll(f.ids); f.ids.clear()
            f.moreDone = f.moreDone || f.more; f.more = false
            f.section = null
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
                    val top = stack.lastOrNull()
                    if (top != null && j - i > 20) {
                        if (sectionsState != null && text.indexOf(sectionsState, i).let { it in 0 until j }) {
                            sectionKey(text.substring(i, j), sectionsState)?.let { top.section = it }
                        }
                        if (cutMoreButton && text.indexOf(MORE_MARKER, i).let { it in 0 until j }) top.more = true
                    }
                    i = j + 1
                }
                '[', '{' -> { stack.add(Frame(text[i] == '[', i + 1)); i++ }
                ',' -> {
                    val f = stack.lastOrNull()
                    if (f != null && f.array) {
                        endElement(f, i)
                        f.elementStart = i + 1
                    }
                    i++
                }
                ']', '}' -> {
                    val f = stack.removeLastOrNull() ?: return emptyList() // not the JSON we know
                    if (f.array != (text[i] == ']')) return emptyList()
                    val up: String?
                    if (f.array) {
                        endElement(f, i)
                        // The button sits next to the rows it reveals; a nested part of it does not.
                        if (f.elements.any { it[4] == 1 }) f.elements.forEach { if (it[3] == 1) it[2] = 1 }
                        addCuts(f.elements, out)
                        up = f.sectionUp
                    } else {
                        up = f.section
                    }
                    // The parent sees everything this container referred to.
                    stack.lastOrNull()?.let { p ->
                        p.ids.addAll(f.ids); p.ids.addAll(f.done)
                        p.more = p.more || f.more || f.moreDone
                        if (p.section == null) p.section = up
                    }
                    i++
                }
                else -> i++
            }
        }
        if (stack.isNotEmpty()) return emptyList() // truncated text: leave it alone
        return out
    }

    /**
     * The section key in a visibility script: it tests the hidden-sections state
     * for a quoted name and yields "gone" or "visible".
     */
    private fun sectionKey(script: String, stateId: String): String? {
        if (!script.contains("gone") || !script.contains("visible")) return null
        return QUOTED_WORD.findAll(script).map { it.groupValues[1] }
            .firstOrNull { it != "gone" && it != "visible" && it != stateId }
    }

    /** Turns cut elements into text ranges, keeping the array's commas valid. */
    private fun addCuts(elements: List<IntArray>, out: MutableList<Edit>) {
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
            out.add(Edit(from, to))
            i = j + 1
        }
    }
}
